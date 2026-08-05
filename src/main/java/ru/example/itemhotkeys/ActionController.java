package ru.example.itemhotkeys;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

public final class ActionController {
    /*
     * После завершения одного действия новые бинды
     * некоторое время игнорируются.
     *
     * 20 тиков Minecraft — примерно одна секунда.
     */
    private static final int DEFAULT_COOLDOWN_TICKS = 20;

    private static ActionType activeType = ActionType.NONE;
    private static int cooldownTicks;

    private ActionController() {
    }

    public static void tick(MinecraftClient client) {
        if (cooldownTicks > 0) {
            cooldownTicks--;
        }

        switch (activeType) {
            case REGULAR_ITEM ->
                    RegularItemHandler.tick(client);

            case ANTI_FLY ->
                    AntiFlyHandler.tick(client);

            case NONE -> {
                // Активного действия нет.
            }
        }
    }

    public static boolean canAcceptInput(
            MinecraftClient client
    ) {
        return activeType == ActionType.NONE
                && cooldownTicks <= 0
                && client.player != null
                && client.interactionManager != null
                && client.getNetworkHandler() != null
                && client.currentScreen == null;
    }

    public static boolean begin(
            ActionType requestedType
    ) {
        if (requestedType == null
                || requestedType == ActionType.NONE
                || activeType != ActionType.NONE
                || cooldownTicks > 0) {
            return false;
        }

        activeType = requestedType;
        return true;
    }

    public static boolean isActive(
            ActionType type
    ) {
        return activeType == type;
    }

    public static ActionType getActiveType() {
        return activeType;
    }

    public static void finish() {
        finish(DEFAULT_COOLDOWN_TICKS);
    }

    public static void finish(int cooldown) {
        activeType = ActionType.NONE;
        cooldownTicks = Math.max(0, cooldown);
    }

    public static void cancel(
            MinecraftClient client,
            String reason
    ) {
        /*
         * Сначала обработчик пытается вернуть предметы
         * и клавиши в исходное состояние.
         */
        switch (activeType) {
            case REGULAR_ITEM ->
                    RegularItemHandler.recover(client);

            case ANTI_FLY ->
                    AntiFlyHandler.recover(client);

            case NONE -> {
                // Восстанавливать нечего.
            }
        }

        if (reason != null && !reason.isBlank()) {
            ItemHotkeysClient.showMessage(
                    client,
                    "§c" + reason
            );
        }

        activeType = ActionType.NONE;
        cooldownTicks = DEFAULT_COOLDOWN_TICKS;
    }

    public static void onServerMessage(
            Text message
    ) {
        if (activeType != ActionType.ANTI_FLY
                || message == null) {
            return;
        }

        AntiFlyHandler.onServerMessage(message);
    }

    public static void resetWithoutRecovery() {
        /*
         * Используется при отключении от сервера или
         * исчезновении игрока, когда отправлять пакеты
         * восстановления уже невозможно.
         */
        activeType = ActionType.NONE;
        cooldownTicks = DEFAULT_COOLDOWN_TICKS;
    }

    public static int getCooldownTicks() {
        return cooldownTicks;
    }
}
