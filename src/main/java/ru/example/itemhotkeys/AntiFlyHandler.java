package ru.example.itemhotkeys;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.text.Text;

import java.util.function.Predicate;

public final class AntiFlyHandler {
    /*
     * Анти-Флай напрямую меняется местами с offhand.
     *
     * Выбранный слот хотбара и основная рука
     * вообще не меняются.
     */
    private static final int OFFHAND_MOVE_DELAY = 2;
    private static final int BEFORE_SNEAK_DELAY = 1;
    private static final int SNEAK_HOLD_DELAY = 2;
    private static final int AFTER_SNEAK_DELAY = 1;
    private static final int OFFHAND_RESTORE_DELAY = 3;

    /*
     * Если сообщение сервера не пришло, предмет всё равно
     * возвращается через 15 тиков — примерно 0,75 секунды.
     */
    private static final int CONFIRM_TIMEOUT_TICKS = 15;

    /*
     * Полный аварийный тайм-аут.
     */
    private static final int ACTION_TIMEOUT_TICKS = 60;

    private static Predicate<ItemStack> matcher;
    private static String displayName;

    private static ActionStage stage;

    private static int sourceInventoryIndex;

    private static boolean attackWasPressed;
    private static boolean useWasPressed;
    private static boolean sneakWasPressed;

    private static boolean sneakPressedByMod;
    private static boolean serverConfirmed;
    private static boolean offhandPrepared;
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
            VisualSwapState.end();
            clearState();
            ActionController.finish(5);
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

            VisualSwapState.end();
            clearState();
            ActionController.finish(5);
            return;
        }

        matcher = itemMatcher;
        displayName = itemDisplayName;
        sourceInventoryIndex = foundIndex;

        /*
         * Сохраняем визуальные предметы обеих рук.
         *
         * После настоящего переноса Анти-Флая во вторую
         * руку игрок продолжит визуально видеть прежний
         * предмет offhand.
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
         * На время последовательности останавливаем
         * конфликтующие ЛКМ и ПКМ.
         */
        client.options.attackKey.setPressed(false);
        client.options.useKey.setPressed(false);

        /*
         * Если игрок уже держит Shift, сначала отправляем
         * отпускание, чтобы последующее нажатие Shift
         * являлось отдельным событием.
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
         * Настоящий обмен:
         *
         * исходный слот Анти-Флая ↔ offhand.
         *
         * Основная рука и выбранный слот хотбара
         * при этом не меняются.
         */
        InventoryUtil.swapInventorySlotWithOffhand(
                client,
                sourceInventoryIndex
        );

        stage =
                ActionStage.ANTI_FLY_VERIFY_OFFHAND;

        delayTicks = OFFHAND_MOVE_DELAY;
        totalTicks = 0;
        confirmationTicks = 0;

        sneakPressedByMod = false;
        serverConfirmed = false;
        offhandPrepared = false;
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
            VisualSwapState.end();
            clearState();
            ActionController.resetWithoutRecovery();
            return;
        }

        totalTicks++;

        if (totalTicks > ACTION_TIMEOUT_TICKS) {
            /*
             * При общем тайм-ауте сначала пытаемся
             * вернуть предмет.
             */
            recover(client);

            ItemHotkeysClient.showMessage(
                    client,
                    "§cАнти-Флай отменён "
                            + "из-за тайм-аута."
            );

            ActionController.finish(8);
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
                    afterSneakRelease();

            case ANTI_FLY_WAIT_FOR_SERVER_CONFIRMATION ->
                    waitForServerConfirmation();

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
         * Распознаются, например:
         *
         * "Анти-Флай перезарядка 15 секунд"
         * "Анти-Флай успешно использован"
         * "Анти-Флай успешно активирован"
         */
        boolean containsAntiFly =
                text.contains("анти-флай")
                        || text.contains("анти флай")
                        || text.contains("антиполет");

        boolean containsSuccess =
                text.contains("перезарядка")
                        || text.contains("успешно")
                        || text.contains("использован")
                        || text.contains("активирован");

        if (!containsAntiFly || !containsSuccess) {
            return;
        }

        serverConfirmed = true;

        /*
         * Как только сервер прислал подтверждение,
         * прекращаем ожидание и сразу начинаем возврат.
         */
        if (stage
                == ActionStage.ANTI_FLY_HOLD_SNEAK
                || stage
                == ActionStage.ANTI_FLY_RELEASE_SNEAK
                || stage
                == ActionStage
                .ANTI_FLY_WAIT_FOR_SERVER_CONFIRMATION) {
            stage =
                    ActionStage.ANTI_FLY_RESTORE_OFFHAND;

            delayTicks = 1;
        }
    }

    private static void verifyOffhand(
            MinecraftClient client
    ) {
        ItemStack offhandStack =
                client.player.getOffHandStack();

        if (!matcher.test(offhandStack)) {
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
        delayTicks = 0;
    }

    private static void pressSneak(
            MinecraftClient client
    ) {
        /*
         * Перед Shift ещё раз проверяем offhand.
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

        /*
         * Если подтверждение уже пришло во время
         * удержания Shift, сразу возвращаем предмет.
         */
        if (serverConfirmed) {
            stage =
                    ActionStage.ANTI_FLY_RESTORE_OFFHAND;

            delayTicks = 1;
            return;
        }

        stage =
                ActionStage.ANTI_FLY_RELEASE_SNEAK;

        delayTicks = AFTER_SNEAK_DELAY;
    }

    private static void afterSneakRelease() {
        if (serverConfirmed) {
            stage =
                    ActionStage.ANTI_FLY_RESTORE_OFFHAND;

            delayTicks = 1;
            return;
        }

        confirmationTicks = 0;

        stage =
                ActionStage
                        .ANTI_FLY_WAIT_FOR_SERVER_CONFIRMATION;

        delayTicks = 0;
    }

    private static void waitForServerConfirmation() {
        if (serverConfirmed
                || confirmationTicks
                >= CONFIRM_TIMEOUT_TICKS) {
            stage =
                    ActionStage.ANTI_FLY_RESTORE_OFFHAND;

            delayTicks = 1;
            return;
        }

        confirmationTicks++;
        delayTicks = 0;
    }

    private static void restoreOffhand(
            MinecraftClient client
    ) {
        safelyReleaseSneak(client);

        /*
         * Один обратный обмен:
         *
         * offhand ↔ исходный слот.
         */
        if (!restoreSent && offhandPrepared) {
            InventoryUtil.swapInventorySlotWithOffhand(
                    client,
                    sourceInventoryIndex
            );

            restoreSent = true;
        }

        stage =
                ActionStage.ANTI_FLY_WAIT_AFTER_RESTORE;

        delayTicks = OFFHAND_RESTORE_DELAY;
    }

    private static void prepareRestoreVerification() {
        stage =
                ActionStage.ANTI_FLY_VERIFY_RESTORE;

        delayTicks = 0;
    }

    private static void verifyRestore(
            MinecraftClient client
    ) {
        ItemStack offhandStack =
                client.player.getOffHandStack();

        /*
         * После возврата Анти-Флай не должен оставаться
         * во второй руке.
         */
        if (matcher.test(offhandStack)) {
            /*
             * Выполняется одна дополнительная попытка.
             */
            InventoryUtil.swapInventorySlotWithOffhand(
                    client,
                    sourceInventoryIndex
            );

            stage = ActionStage.FINISH;
            delayTicks = OFFHAND_RESTORE_DELAY;
            return;
        }

        stage = ActionStage.FINISH;
        delayTicks = 1;
    }

    private static void finish(
            MinecraftClient client
    ) {
        safelyReleaseSneak(client);
        restoreInputState(client);

        /*
         * Отключаем визуальное скрытие только после
         * завершения настоящего возврата.
         */
        VisualSwapState.end();

        clearState();
        ActionController.finish(5);
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
         * При аварии возвращаем предмет только если
         * Анти-Флай точно находится в offhand.
         */
        if (matcher != null
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
        }

        restoreInputState(client);

        /*
         * Визуальный режим обязательно отключается
         * даже при ошибке.
         */
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
         * Возвращаем Shift в исходное состояние.
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
        serverConfirmed = false;
        offhandPrepared = false;
        restoreSent = false;

        delayTicks = 0;
        totalTicks = 0;
        confirmationTicks = 0;
    }
}
