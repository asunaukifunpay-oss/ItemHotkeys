package ru.example.itemhotkeys;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.text.Text;

import java.util.function.Predicate;

public final class AntiFlyHandler {
    /*
     * Анти-Флай не проходит через хотбар и основную руку.
     * Он напрямую меняется местами со второй рукой.
     */
    private static final int OFFHAND_MOVE_DELAY = 7;
    private static final int BEFORE_SNEAK_DELAY = 5;
    private static final int SNEAK_HOLD_DELAY = 7;
    private static final int AFTER_SNEAK_DELAY = 4;
    private static final int OFFHAND_RESTORE_DELAY = 8;

    /*
     * Максимальное ожидание серверного сообщения:
     * 40 тиков ≈ 2 секунды.
     */
    private static final int CONFIRM_TIMEOUT_TICKS = 40;

    private static final int ACTION_TIMEOUT_TICKS = 120;

    private static Predicate<ItemStack> matcher;
    private static String displayName;

    private static ActionStage stage;

    private static int sourceInventoryIndex;

    private static boolean attackWasPressed;
    private static boolean useWasPressed;
    private static boolean sneakWasPressed;

    private static boolean sneakPressedByMod;
    private static boolean serverConfirmed;

    /*
     * Становится true только после подтверждения,
     * что Анти-Флай действительно оказался в offhand.
     */
    private static boolean offhandPrepared;

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
            ActionController.finish(10);
            return;
        }

        if (itemMatcher.test(
                client.player.getOffHandStack()
        )) {
            ItemHotkeysClient.showMessage(
                    client,
                    "§eАнти-Флай уже находится "
                            + "во второй руке."
            );

            clearState();
            ActionController.finish(10);
            return;
        }

        matcher = itemMatcher;
        displayName = itemDisplayName;
        sourceInventoryIndex = foundIndex;

        attackWasPressed =
                client.options.attackKey.isPressed();

        useWasPressed =
                client.options.useKey.isPressed();

        sneakWasPressed =
                client.options.sneakKey.isPressed()
                        || client.player.isSneaking();

        /*
         * Убираем конфликтующие действия на время
         * активации.
         */
        client.options.attackKey.setPressed(false);
        client.options.useKey.setPressed(false);

        /*
         * Если игрок уже сидел на Shift, сначала
         * приводим сервер в состояние RELEASE.
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
         * Прямой обмен:
         *
         * исходный слот Анти-Флая ↔ offhand.
         *
         * Основная рука и выбранный слот хотбара
         * вообще не меняются.
         */
        InventoryUtil.swapInventorySlotWithOffhand(
                client,
                sourceInventoryIndex
        );

        stage = ActionStage.ANTI_FLY_VERIFY_OFFHAND;

        delayTicks = OFFHAND_MOVE_DELAY;
        totalTicks = 0;
        confirmationTicks = 0;

        sneakPressedByMod = false;
        serverConfirmed = false;
        offhandPrepared = false;
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
                    waitForServerConfirmation(client);

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
                    "Получен неправильный этап "
                            + "Анти-Флая."
            );
        }
    }

    public static void onServerMessage(
            Text message
    ) {
        if (!ActionController.isActive(
                ActionType.ANTI_FLY
        ) || message == null) {
            return;
        }

        String normalizedMessage =
                ItemNames.normalize(
                        message.getString()
                );

        boolean success =
                normalizedMessage.contains(
                        ItemNames.normalize(
                                "успешно использовано"
                        )
                )
                || normalizedMessage.contains(
                        ItemNames.normalize(
                                "успешно активировано"
                        )
                )
                || normalizedMessage.contains(
                        ItemNames.normalize(
                                "анти-флай успешно"
                        )
                )
                || normalizedMessage.contains(
                        ItemNames.normalize(
                                "антиполет успешно"
                        )
                );

        if (!success) {
            return;
        }

        /*
         * Сообщение считается подтверждением только
         * после помещения предмета во вторую руку.
         */
        if (offhandPrepared) {
            serverConfirmed = true;
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
        delayTicks = 1;
    }

    private static void pressSneak(
            MinecraftClient client
    ) {
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

        delayTicks = AFTER_SNEAK_DELAY;
    }

    private static void afterSneakRelease() {
        if (serverConfirmed) {
            stage =
                    ActionStage.ANTI_FLY_RESTORE_OFFHAND;

            delayTicks = 2;
            return;
        }

        confirmationTicks = 0;

        stage =
                ActionStage
                        .ANTI_FLY_WAIT_FOR_SERVER_CONFIRMATION;

        delayTicks = 1;
    }

    private static void waitForServerConfirmation(
            MinecraftClient client
    ) {
        if (serverConfirmed) {
            stage =
                    ActionStage.ANTI_FLY_RESTORE_OFFHAND;

            delayTicks = 2;
            return;
        }

        confirmationTicks++;

        if (confirmationTicks
                >= CONFIRM_TIMEOUT_TICKS) {
            ItemHotkeysClient.showMessage(
                    client,
                    "§eСерверное подтверждение "
                            + "не получено. Возвращаю "
                            + displayName + "."
            );

            stage =
                    ActionStage.ANTI_FLY_RESTORE_OFFHAND;

            delayTicks = 1;
            return;
        }

        delayTicks = 1;
    }

    private static void restoreOffhand(
            MinecraftClient client
    ) {
        safelyReleaseSneak(client);

        /*
         * Выполняем обратный обмен даже если Анти-Флай
         * был израсходован.
         *
         * Это необходимо, чтобы предмет, который раньше
         * лежал во второй руке, вернулся из исходного
         * слота обратно в offhand.
         */
        if (offhandPrepared) {
            InventoryUtil.swapInventorySlotWithOffhand(
                    client,
                    sourceInventoryIndex
            );
        }

        stage =
                ActionStage.ANTI_FLY_WAIT_AFTER_RESTORE;

        delayTicks = OFFHAND_RESTORE_DELAY;
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

        /*
         * После возврата Анти-Флай не должен оставаться
         * во второй руке.
         */
        if (matcher.test(offhandStack)) {
            ItemHotkeysClient.showMessage(
                    client,
                    "§cНе удалось подтвердить возврат "
                            + "Анти-Флая."
            );
        }

        stage = ActionStage.FINISH;
        delayTicks = 2;
    }

    private static void finish(
            MinecraftClient client
    ) {
        safelyReleaseSneak(client);
        restoreInputState(client);

        clearState();
        ActionController.finish();
    }

    public static void recover(
            MinecraftClient client
    ) {
        if (client == null || client.player == null) {
            clearState();
            return;
        }

        safelyReleaseSneak(client);

        /*
         * Обратный SWAP выполняем только если ранее
         * было подтверждено успешное помещение
         * Анти-Флая во вторую руку.
         */
        if (offhandPrepared
                && InventoryUtil.isInventoryIndex(
                        sourceInventoryIndex
                )) {
            InventoryUtil.swapInventorySlotWithOffhand(
                    client,
                    sourceInventoryIndex
            );
        }

        restoreInputState(client);
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
         * Возвращаем исходное состояние Shift.
         */
        if (sneakWasPressed) {
            client.options.sneakKey.setPressed(true);

            if (client.player != null
                    && client.getNetworkHandler() != null) {
                sendSneakPacket(
                        client,
                        ClientCommandC2SPacket.Mode
                                .PRESS_SHIFT_KEY
                );
            }
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

        delayTicks = 0;
        totalTicks = 0;
        confirmationTicks = 0;
    }
}
