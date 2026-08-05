package ru.example.itemhotkeys;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.text.Text;

import java.util.function.Predicate;

public final class AntiFlyHandler {

    /*
     * 20 тиков Minecraft ≈ 1 секунда.
     *
     * Полная последовательность занимает примерно
     * 26–30 тиков, то есть 1,3–1,5 секунды.
     */
    private static final int AFTER_OFFHAND_SWAP_DELAY = 4;
    private static final int BEFORE_SNEAK_DELAY = 2;
    private static final int SNEAK_HOLD_DELAY = 6;
    private static final int AFTER_SNEAK_RELEASE_DELAY = 2;
    private static final int BEFORE_RESTORE_DELAY = 3;
    private static final int AFTER_RESTORE_DELAY = 3;

    /*
     * Максимальное ожидание сообщения сервера.
     *
     * При отсутствии сообщения предмет всё равно
     * будет возвращён обратно.
     */
    private static final int CONFIRM_TIMEOUT_TICKS = 6;

    /*
     * Аварийный тайм-аут всего действия.
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

        /*
         * Если Анти-Флай уже находится во второй руке,
         * повторный обмен не выполняется.
         */
        if (itemMatcher.test(
                client.player.getOffHandStack()
        )) {
            ItemHotkeysClient.showMessage(
                    client,
                    "§eАнти-Флай уже находится во второй руке."
            );

            VisualSwapState.end();
            clearState();
            ActionController.finish(5);
            return;
        }

        matcher = itemMatcher;
        displayName = itemDisplayName;
        sourceInventoryIndex = foundIndex;

        VisualSwapState.begin();

        /*
         * Запоминаем состояние клавиш, чтобы после
         * завершения вернуть управление игроку.
         */
        attackWasPressed =
                client.options.attackKey.isPressed();

        useWasPressed =
                client.options.useKey.isPressed();

        sneakWasPressed =
                client.options.sneakKey.isPressed()
                        || client.player.isSneaking();

        /*
         * На время действия отключаем конфликтующие
         * нажатия ЛКМ и ПКМ.
         */
        client.options.attackKey.setPressed(false);
        client.options.useKey.setPressed(false);

        /*
         * Если игрок уже держал Shift, временно
         * отпускаем его перед новой активацией.
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
         * Перемещаем Анти-Флай из найденного слота
         * во вторую руку.
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
            VisualSwapState.end();
            clearState();
            ActionController.resetWithoutRecovery();
            return;
        }

        totalTicks++;

        if (totalTicks > ACTION_TIMEOUT_TICKS) {
            ActionController.cancel(
                    client,
                    "Анти-Флай отменён из-за тайм-аута."
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
                    "Ошибка состояния Анти-Флая."
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
                    "Неверный этап Анти-Флая."
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
         * Поддерживаем разные варианты написания
         * названия в сообщениях сервера.
         */
        boolean antiFly =
                text.contains("анти-флай")
                        || text.contains("анти флай")
                        || text.contains("анти-полет")
                        || text.contains("анти полет")
                        || text.contains("антиполет");

        /*
         * Примеры подходящих сообщений:
         *
         * Анти Полёт » Перезарядка 15 сек
         * Анти-Флай успешно активирован
         */
        boolean activated =
                text.contains("перезарядка")
                        || text.contains("успешно")
                        || text.contains("использован")
                        || text.contains("активирован");

        if (!antiFly || !activated) {
            return;
        }

        serverConfirmed = true;

        /*
         * Если сейчас ожидается подтверждение,
         * сразу переходим к возврату предмета.
         */
        if (stage
                == ActionStage
                .ANTI_FLY_WAIT_FOR_SERVER_CONFIRMATION) {
            stage =
                    ActionStage
                            .ANTI_FLY_RESTORE_OFFHAND;

            delayTicks = BEFORE_RESTORE_DELAY;
        }
    }

    private static void verifyOffhand(
            MinecraftClient client
    ) {
        ItemStack offhandStack =
                client.player.getOffHandStack();

        if (matcher == null
                || !matcher.test(offhandStack)) {
            ActionController.cancel(
                    client,
                    "Анти-Флай не переместился "
                            + "во вторую руку."
            );
            return;
        }

        offhandPrepared = true;

        stage =
                ActionStage
                        .ANTI_FLY_WAIT_BEFORE_SNEAK;

        delayTicks = BEFORE_SNEAK_DELAY;
    }

    private static void prepareSneak() {
        stage =
                ActionStage
                        .ANTI_FLY_PRESS_SNEAK;

        delayTicks = 0;
    }

    private static void pressSneak(
            MinecraftClient client
    ) {
        /*
         * Перед нажатием Shift ещё раз убеждаемся,
         * что Анти-Флай находится во второй руке.
         */
        if (matcher == null
                || !matcher.test(
                client.player.getOffHandStack()
        )) {
            ActionController.cancel(
                    client,
                    "Анти-Флай исчез из второй руки."
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

        stage =
                ActionStage
                        .ANTI_FLY_HOLD_SNEAK;

        delayTicks = SNEAK_HOLD_DELAY;
    }

    private static void releaseSneak(
            MinecraftClient client
    ) {
        safelyReleaseSneak(client);

        stage =
                ActionStage
                        .ANTI_FLY_RELEASE_SNEAK;

        delayTicks = AFTER_SNEAK_RELEASE_DELAY;
    }

    private static void beginConfirmationWait() {
        confirmationTicks = 0;

        stage =
                ActionStage
                        .ANTI_FLY_WAIT_FOR_SERVER_CONFIRMATION;

        delayTicks = 0;
    }

    private static void waitForConfirmation() {
        if (serverConfirmed) {
            stage =
                    ActionStage
                            .ANTI_FLY_RESTORE_OFFHAND;

            delayTicks = BEFORE_RESTORE_DELAY;
            return;
        }

        confirmationTicks++;

        /*
         * Не задерживаем всё действие надолго.
         * Если сообщение сервера не было поймано,
         * через несколько тиков возвращаем предмет.
         */
        if (confirmationTicks
                >= CONFIRM_TIMEOUT_TICKS) {
            stage =
                    ActionStage
                            .ANTI_FLY_RESTORE_OFFHAND;

            delayTicks = BEFORE_RESTORE_DELAY;
            return;
        }

        delayTicks = 0;
    }

    private static void restoreOffhand(
            MinecraftClient client
    ) {
        safelyReleaseSneak(client);

        /*
         * Выполняем обратный обмен только один раз
         * и только если Анти-Флай всё ещё находится
         * во второй руке.
         */
        if (!restoreSent
                && offhandPrepared
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

        stage =
                ActionStage
                        .ANTI_FLY_WAIT_AFTER_RESTORE;

        delayTicks = AFTER_RESTORE_DELAY;
    }

    private static void prepareRestoreVerification() {
        stage =
                ActionStage
                        .ANTI_FLY_VERIFY_RESTORE;

        delayTicks = 0;
    }

    private static void verifyRestore(
            MinecraftClient client
    ) {
        ItemStack offhandStack =
                client.player.getOffHandStack();

        /*
         * Не отправляем второй автоматический обмен,
         * чтобы не получить двойной SWAP при задержке
         * ответа сервера.
         */
        if (matcher != null
                && matcher.test(offhandStack)) {
            ItemHotkeysClient.showMessage(
                    client,
                    "§cНе удалось вернуть "
                            + displayName
                            + " из второй руки."
            );
        }

        stage = ActionStage.FINISH;
        delayTicks = 0;
    }

    private static void finish(
            MinecraftClient client
    ) {
        safelyReleaseSneak(client);
        restoreInputState(client);

        VisualSwapState.end();
        clearState();

        /*
         * Небольшой кулдаун против случайного
         * повторного нажатия бинда.
         */
        ActionController.finish(4);
    }

    public static void recover(
            MinecraftClient client
    ) {
        if (client == null
                || client.player == null) {
            VisualSwapState.end();
            clearState();
            return;
        }

        safelyReleaseSneak(client);

        /*
         * При аварийной отмене пытаемся один раз
         * вернуть предмет в исходный слот.
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
         * Восстанавливаем Shift, если игрок держал
         * его до запуска Анти-Флая.
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
        if (client == null
                || client.player == null
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
