package ru.example.itemhotkeys;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.text.Text;

import java.util.function.Predicate;

public final class AntiFlyHandler {
    /*
     * Последовательность разделена на отдельные этапы.
     *
     * 20 тиков Minecraft = примерно 1 секунда.
     */
    private static final int AFTER_OFFHAND_SWAP_DELAY = 4;
    private static final int BEFORE_SNEAK_DELAY = 3;
    private static final int SNEAK_HOLD_DELAY = 4;
    private static final int AFTER_SNEAK_RELEASE_DELAY = 3;
    private static final int BEFORE_RESTORE_DELAY = 4;
    private static final int AFTER_RESTORE_DELAY = 5;

    /*
     * Если сообщение о перезарядке не пришло,
     * предмет всё равно возвращается через 20 тиков.
     */
    private static final int CONFIRM_TIMEOUT_TICKS = 20;

    /*
     * Общий аварийный тайм-аут — около четырёх секунд.
     */
    private static final int ACTION_TIMEOUT_TICKS = 80;

    private static Predicate<ItemStack> matcher;
    private static String displayName;

    private static ActionStage stage;

    private static int sourceInventoryIndex;

    private static boolean attackWasPressed;
    private static boolean useWasPressed;
    private static boolean sneakWasPressed;

    private static boolean sneakPressedByMod;
    private static boolean offhandPrepared;
    private static boolean serverConfirmed;
    private static boolean restoreSent;

    private static int delayTicks;
    private static int totalTicks;
    private static int confirmationTicks;

    private AntiFlyHandler() {
    }

    public static void start(
            MinecraftClient client,
            Predicate<ItemStack> itemMatcher,
            String itemDisplayName
    ) {
        if (!ActionController.begin(ActionType.ANTI_FLY)) {
            return;
        }

        if (!canWork(client)) {
            clearState();
            VisualSwapState.end();
            ActionController.finish(10);
            return;
        }

        PlayerInventory inventory =
                client.player.getInventory();

        int foundIndex = InventoryUtil.findItem(
                inventory,
                itemMatcher
        );

        if (foundIndex < 0) {
            ItemHotkeysClient.showMessage(
                    client,
                    "§cНе найден предмет: "
                            + itemDisplayName
            );

            clearState();
            VisualSwapState.end();
            ActionController.finish(10);
            return;
        }

        /*
         * Если Анти-Флай уже находится во второй руке,
         * не выполняем инвентарный обмен повторно.
         */
        if (itemMatcher.test(
                client.player.getOffHandStack()
        )) {
            ItemHotkeysClient.showMessage(
                    client,
                    "§eАнти-Флай уже находится "
                            + "во второй руке."
            );

            clearState();
            VisualSwapState.end();
            ActionController.finish(10);
            return;
        }

        matcher = itemMatcher;
        displayName = itemDisplayName;
        sourceInventoryIndex = foundIndex;

        /*
         * Запоминаем визуальное состояние рук.
         * Это влияет только на отрисовку клиента.
         */
        VisualSwapState.begin();

        attackWasPressed =
                client.options.attackKey.isPressed();

        useWasPressed =
                client.options.useKey.isPressed();

        sneakWasPressed =
                client.options.sneakKey.isPressed()
                        || client.player.isSneaking();

        /*
         * Во время последовательности не допускаем
         * одновременную атаку или использование.
         */
        client.options.attackKey.setPressed(false);
        client.options.useKey.setPressed(false);

        /*
         * Если игрок уже держит Shift, сначала корректно
         * отпускаем его. Иначе новое нажатие Shift
         * может не восприниматься как отдельное действие.
         */
        if (sneakWasPressed) {
            client.options.sneakKey.setPressed(false);

            sendSneakPacket(
                    client,
                    ClientCommandC2SPacket.Mode
                            .RELEASE_SHIFT_KEY
            );
        }

        /*
         * Единственный прямой обмен:
         *
         * исходный слот Анти-Флая ↔ offhand.
         *
         * Выбранный слот хотбара не меняется.
         */
        InventoryUtil.swapInventorySlotWithOffhand(
                client,
                sourceInventoryIndex
        );

        stage = ActionStage.ANTI_FLY_VERIFY_OFFHAND;

        delayTicks = AFTER_OFFHAND_SWAP_DELAY;
        totalTicks = 0;
        confirmationTicks = 0;

        sneakPressedByMod = false;
        offhandPrepared = false;
        serverConfirmed = false;
        restoreSent = false;
    }

    public static void tick(
            MinecraftClient client
    ) {
        if (!ActionController.isActive(
                ActionType.ANTI_FLY
        )) {
            return;
        }

        if (!canWork(client)) {
            /*
             * После отключения от сервера восстановительные
             * пакеты отправлять уже нельзя.
             */
            VisualSwapState.end();
            clearState();
            ActionController.resetWithoutRecovery();
            return;
        }

        totalTicks++;

        if (totalTicks > ACTION_TIMEOUT_TICKS) {
            ActionController.cancel(
                    client,
                    "Анти-Флай отменён "
                            + "из-за тайм-аута."
            );
            return;
        }

        if (delayTicks > 0) {
            delayTicks--;
            return;
        }

        if (stage == null) {
            ActionController.cancel(
                    client,
                    "Неизвестное состояние Анти-Флая."
            );
            return;
        }

        switch (stage) {
            case ANTI_FLY_VERIFY_OFFHAND ->
                    verifyOffhand(client);

            case ANTI_FLY_WAIT_BEFORE_SNEAK ->
                    prepareSneak();

            case ANTI_FLY_PRESS_SNEAK ->
                    pressSneak(client);

            case ANTI_FLY_HOLD_SNEAK ->
                    releaseSneak(client);

            case ANTI_FLY_RELEASE_SNEAK ->
                    beginConfirmationWait();

            case ANTI_FLY_WAIT_FOR_SERVER_CONFIRMATION ->
                    waitForConfirmation();

            case ANTI_FLY_RESTORE_OFFHAND ->
                    restoreOffhand(client);

            case ANTI_FLY_WAIT_AFTER_RESTORE ->
                    prepareRestoreVerification();

            case ANTI_FLY_VERIFY_RESTORE ->
                    verifyRestore(client);

            case FINISH ->
                    finish(client);

            default -> ActionController.cancel(
                    client,
                    "Неправильный этап Анти-Флая."
            );
        }
    }

    public static void onServerMessage(
            Text message
    ) {
        if (!ActionController.isActive(
                ActionType.ANTI_FLY
        )
                || message == null
                || !offhandPrepared) {
            return;
        }

        String text = ItemNames.normalize(
                message.getString()
        );

        /*
         * Поддерживаются варианты:
         *
         * Анти-Флай » Перезарядка 15 сек
         * Анти Полёт » Перезарядка 15 сек
         * Анти-Флай успешно использован
         */
        boolean mentionsAntiFly =
                text.contains("анти-флай")
                        || text.contains("анти флай")
                        || text.contains("анти-полет")
                        || text.contains("анти полет")
                        || text.contains("антиполет");

        boolean confirmsActivation =
                text.contains("перезарядка")
                        || text.contains("успешно")
                        || text.contains("использован")
                        || text.contains("активирован");

        if (!mentionsAntiFly || !confirmsActivation) {
            return;
        }

        serverConfirmed = true;

        /*
         * Не делаем обратный SWAP прямо внутри события
         * получения сообщения. Возврат выполнится позднее
         * из обычного игрового тика.
         */
        if (stage
                == ActionStage
                .ANTI_FLY_WAIT_FOR_SERVER_CONFIRMATION) {
            stage = ActionStage.ANTI_FLY_RESTORE_OFFHAND;
            delayTicks = BEFORE_RESTORE_DELAY;
        }
    }

    private static void verifyOffhand(
            MinecraftClient client
    ) {
        ItemStack offhandStack =
                client.player.getOffHandStack();

        if (!matcher.test(offhandStack)) {
            /*
             * Не повторяем SWAP. Если первый обмен
             * не подтвердился, действие отменяется.
             */
            ActionController.cancel(
                    client,
                    "Анти-Флай не переместился "
                            + "во вторую руку."
            );
            return;
        }

        offhandPrepared = true;

        stage =
                ActionStage.ANTI_FLY_WAIT_BEFORE_SNEAK;

        delayTicks = BEFORE_SNEAK_DELAY;
    }

    private static void prepareSneak() {
        stage = ActionStage.ANTI_FLY_PRESS_SNEAK;
        delayTicks = 1;
    }

    private static void pressSneak(
            MinecraftClient client
    ) {
        /*
         * Последняя проверка перед нажатием Shift.
         */
        if (!matcher.test(
                client.player.getOffHandStack()
        )) {
            ActionController.cancel(
                    client,
                    "Анти-Флай исчез "
                            + "из второй руки."
            );
            return;
        }

        client.options.sneakKey.setPressed(true);

        sendSneakPacket(
                client,
                ClientCommandC2SPacket.Mode
                        .PRESS_SHIFT_KEY
        );

        sneakPressedByMod = true;

        stage = ActionStage.ANTI_FLY_HOLD_SNEAK;
        delayTicks = SNEAK_HOLD_DELAY;
    }

    private static void releaseSneak(
            MinecraftClient client
    ) {
        safelyReleaseSneak(client);

        stage =
                ActionStage.ANTI_FLY_RELEASE_SNEAK;

        delayTicks = AFTER_SNEAK_RELEASE_DELAY;
    }

    private static void beginConfirmationWait() {
        confirmationTicks = 0;

        stage =
                ActionStage
                        .ANTI_FLY_WAIT_FOR_SERVER_CONFIRMATION;

        delayTicks = 1;
    }

    private static void waitForConfirmation() {
        if (serverConfirmed) {
            stage =
                    ActionStage.ANTI_FLY_RESTORE_OFFHAND;

            delayTicks = BEFORE_RESTORE_DELAY;
            return;
        }

        confirmationTicks++;

        if (confirmationTicks
                >= CONFIRM_TIMEOUT_TICKS) {
            /*
             * Подтверждение могло быть отправлено в другом
             * типе сообщения. Предмет всё равно возвращаем.
             */
            stage =
                    ActionStage.ANTI_FLY_RESTORE_OFFHAND;

            delayTicks = BEFORE_RESTORE_DELAY;
            return;
        }

        delayTicks = 1;
    }

    private static void restoreOffhand(
            MinecraftClient client
    ) {
        safelyReleaseSneak(client);

        if (!restoreSent
                && offhandPrepared
                && matcher.test(
                client.player.getOffHandStack()
        )) {
            /*
             * Единственный обратный обмен:
             *
             * offhand ↔ исходный слот.
             */
            InventoryUtil.swapInventorySlotWithOffhand(
                    client,
                    sourceInventoryIndex
            );

            restoreSent = true;
        }

        stage =
                ActionStage.ANTI_FLY_WAIT_AFTER_RESTORE;

        delayTicks = AFTER_RESTORE_DELAY;
    }

    private static void prepareRestoreVerification() {
        stage =
                ActionStage.ANTI_FLY_VERIFY_RESTORE;

        delayTicks = 1;
    }

    private static void verifyRestore(
            MinecraftClient client
    ) {
        ItemStack offhandStack =
                client.player.getOffHandStack();

        if (matcher.test(offhandStack)) {
            /*
             * Не отправляем второй SWAP автоматически,
             * чтобы не создавать повторные пакеты.
             */
            ItemHotkeysClient.showMessage(
                    client,
                    "§cСервер не подтвердил возврат "
                            + displayName + "."
            );
        }

        stage = ActionStage.FINISH;
        delayTicks = 1;
    }

    private static void finish(
            MinecraftClient client
    ) {
        safelyReleaseSneak(client);
        restoreInputState(client);

        VisualSwapState.end();

        clearState();
        ActionController.finish(10);
    }

    public static void recover(
            MinecraftClient client
    ) {
        if (client == null || client.player == null) {
            VisualSwapState.end();
            clearState();
            return;
        }

        safelyReleaseSneak(client);

        /*
         * При аварии выполняется максимум один возврат,
         * только если Анти-Флай точно находится в offhand
         * и возврат ещё не отправлялся.
         */
        if (!restoreSent
                && matcher != null
                && InventoryUtil.isInventoryIndex(
                        sourceInventoryIndex
                )
                && matcher.test(
                client.player.getOffHandStack()
        )) {
            InventoryUtil.swapInventorySlotWithOffhand(
                    client,
                    sourceInventoryIndex
            );

            restoreSent = true;
        }

        restoreInputState(client);
        VisualSwapState.end();
        clearState();
    }

    private static void safelyReleaseSneak(
            MinecraftClient client
    ) {
        if (!sneakPressedByMod) {
            return;
        }

        client.options.sneakKey.setPressed(false);

        sendSneakPacket(
                client,
                ClientCommandC2SPacket.Mode
                        .RELEASE_SHIFT_KEY
        );

        sneakPressedByMod = false;
    }

    private static void restoreInputState(
            MinecraftClient client
    ) {
        if (client == null) {
            return;
        }

        client.options.attackKey.setPressed(
                attackWasPressed
        );

        client.options.useKey.setPressed(
                useWasPressed
        );

        /*
         * Возвращаем состояние Shift, которое было
         * до запуска Анти-Флая.
         */
        if (sneakWasPressed
                && client.player != null
                && client.getNetworkHandler() != null) {
            client.options.sneakKey.setPressed(true);

            sendSneakPacket(
                    client,
                    ClientCommandC2SPacket.Mode
                            .PRESS_SHIFT_KEY
            );
        } else {
            client.options.sneakKey.setPressed(false);
        }
    }

    private static void sendSneakPacket(
            MinecraftClient client,
            ClientCommandC2SPacket.Mode mode
    ) {
        if (client.player == null
                || client.getNetworkHandler() == null) {
            return;
        }

        client.getNetworkHandler().sendPacket(
                new ClientCommandC2SPacket(
                        client.player,
                        mode
                )
        );
    }

    private static boolean canWork(
            MinecraftClient client
    ) {
        return client != null
                && client.player != null
                && client.interactionManager != null
                && client.getNetworkHandler() != null;
    }

    private static void clearState() {
        matcher = null;
        displayName = null;
        stage = null;

        sourceInventoryIndex = -1;

        attackWasPressed = false;
        useWasPressed = false;
        sneakWasPressed = false;

        sneakPressedByMod = false;
        offhandPrepared = false;
        serverConfirmed = false;
        restoreSent = false;

        delayTicks = 0;
        totalTicks = 0;
        confirmationTicks = 0;
    }
}
