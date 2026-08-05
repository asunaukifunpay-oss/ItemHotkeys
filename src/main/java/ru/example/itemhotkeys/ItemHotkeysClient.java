package ru.example.itemhotkeys;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;
import java.util.function.Predicate;

public final class ItemHotkeysClient implements ClientModInitializer {
    private static final String CATEGORY =
            "category.itemhotkeys.binds";

    /*
     * Задержки для обычных предметов.
     * 20 тиков Minecraft — примерно одна секунда.
     */
    private static final int INVENTORY_SWAP_DELAY = 6;
    private static final int SLOT_SELECT_DELAY = 6;
    private static final int ITEM_USE_DELAY = 6;
    private static final int SLOT_RESTORE_DELAY = 7;
    private static final int INVENTORY_RESTORE_DELAY = 7;

    /*
     * Задержки Анти-Флая.
     */
    private static final int OFFHAND_MOVE_DELAY = 7;
    private static final int BEFORE_SNEAK_DELAY = 5;
    private static final int SNEAK_HOLD_DELAY = 7;
    private static final int AFTER_SNEAK_DELAY = 4;
    private static final int OFFHAND_RESTORE_DELAY = 8;

    /*
     * Максимум 40 тиков ожидания серверного сообщения:
     * около двух секунд.
     */
    private static final int SERVER_CONFIRM_TIMEOUT = 40;

    /*
     * Общая защита от спама после завершения.
     */
    private static final int FINAL_COOLDOWN = 20;

    /*
     * Максимальное время всей операции.
     */
    private static final int ACTION_TIMEOUT = 140;

    private static KeyBinding enderTrapKey;
    private static KeyBinding trapKey;
    private static KeyBinding livalkaKey;
    private static KeyBinding antiFlyKey;

    private static PendingAction pendingAction;
    private static int cooldownTicks;

    @Override
    public void onInitializeClient() {
        enderTrapKey = registerKey(
                "key.itemhotkeys.ender_trap",
                GLFW.GLFW_KEY_Z
        );

        trapKey = registerKey(
                "key.itemhotkeys.trap",
                GLFW.GLFW_KEY_X
        );

        livalkaKey = registerKey(
                "key.itemhotkeys.livalka",
                GLFW.GLFW_KEY_C
        );

        antiFlyKey = registerKey(
                "key.itemhotkeys.antifly",
                GLFW.GLFW_KEY_V
        );

        ClientTickEvents.END_CLIENT_TICK.register(
                ItemHotkeysClient::onEndClientTick
        );

        /*
         * Получаем системные сообщения сервера.
         * Сообщение продолжает нормально отображаться игроку.
         */
        ClientReceiveMessageEvents.GAME.register(
                (message, overlay) ->
                        onServerGameMessage(message)
        );
    }

    private static KeyBinding registerKey(
            String translationKey,
            int defaultKey
    ) {
        return KeyBindingHelper.registerKeyBinding(
                new KeyBinding(
                        translationKey,
                        InputUtil.Type.KEYSYM,
                        defaultKey,
                        CATEGORY
                )
        );
    }

    private static void onEndClientTick(
            MinecraftClient client
    ) {
        if (cooldownTicks > 0) {
            cooldownTicks--;
        }

        /*
         * Пока операция выполняется, новые нажатия
         * не принимаются и не сохраняются в очереди.
         */
        if (pendingAction != null) {
            tickPendingAction(client);
            clearQueuedKeyPresses();
            return;
        }

        if (cooldownTicks > 0 || !canStartAction(client)) {
            clearQueuedKeyPresses();
            return;
        }

        /*
         * За один тик можно запустить только одно действие.
         */
        if (enderTrapKey.wasPressed()) {
            clearQueuedKeyPresses();

            startRegularItemAction(
                    client,
                    itemNameContains("Эндер Ловушка"),
                    "Эндер Ловушка"
            );

            return;
        }

        if (trapKey.wasPressed()) {
            clearQueuedKeyPresses();

            startRegularItemAction(
                    client,
                    stack -> {
                        if (stack.isEmpty()) {
                            return false;
                        }

                        String name = normalize(
                                stack.getName().getString()
                        );

                        return name.contains(
                                normalize("Ловушка")
                        ) && !name.contains(
                                normalize("Эндер Ловушка")
                        );
                    },
                    "Ловушка"
            );

            return;
        }

        if (livalkaKey.wasPressed()) {
            clearQueuedKeyPresses();

            startRegularItemAction(
                    client,
                    itemNameContains("Ливалка"),
                    "Ливалка"
            );

            return;
        }

        if (antiFlyKey.wasPressed()) {
            clearQueuedKeyPresses();

            startAntiFlyAction(
                    client,
                    itemNameContains("Анти-Флай"),
                    "Анти-Флай"
            );
        }
    }

    /*
     * Вызывается при получении системного сообщения сервера.
     */
    private static void onServerGameMessage(Text message) {
        if (pendingAction == null
                || pendingAction.type != ActionType.ANTI_FLY) {
            return;
        }

        String normalizedMessage = normalize(
                message.getString()
        );

        /*
         * Основной вариант сообщения с сервера.
         * Дополнительные варианты оставлены на случай,
         * если текст немного отличается.
         */
        boolean success =
                normalizedMessage.contains(
                        normalize("успешно использовано")
                )
                || normalizedMessage.contains(
                        normalize("успешно активировано")
                )
                || normalizedMessage.contains(
                        normalize("анти-флай успешно")
                )
                || normalizedMessage.contains(
                        normalize("антиполет успешно")
                );

        if (!success) {
            return;
        }

        /*
         * Не реагируем на старые сообщения, полученные
         * до фактического нажатия Shift.
         */
        if (pendingAction.stage
                == ActionStage.ANTI_FLY_HOLD_SNEAK
                || pendingAction.stage
                == ActionStage.ANTI_FLY_RELEASE_SNEAK
                || pendingAction.stage
                == ActionStage.ANTI_FLY_WAIT_CONFIRM) {
            pendingAction.serverConfirmed = true;
        }
    }

    private static boolean canStartAction(
            MinecraftClient client
    ) {
        return client.player != null
                && client.interactionManager != null
                && client.getNetworkHandler() != null
                && client.currentScreen == null;
    }

    private static boolean canContinueAction(
            MinecraftClient client
    ) {
        return client.player != null
                && client.interactionManager != null
                && client.getNetworkHandler() != null;
    }

    private static void clearQueuedKeyPresses() {
        clearKeyQueue(enderTrapKey);
        clearKeyQueue(trapKey);
        clearKeyQueue(livalkaKey);
        clearKeyQueue(antiFlyKey);
    }

    private static void clearKeyQueue(
            KeyBinding keyBinding
    ) {
        while (keyBinding.wasPressed()) {
            // Удаляем накопившиеся нажатия.
        }
    }

    private static Predicate<ItemStack> itemNameContains(
            String wantedName
    ) {
        String normalizedWantedName = normalize(wantedName);

        return stack -> {
            if (stack.isEmpty()) {
                return false;
            }

            String itemName = normalize(
                    stack.getName().getString()
            );

            return itemName.contains(normalizedWantedName);
        };
    }

    private static String normalize(String text) {
        return text
                .trim()
                .replace('ё', 'е')
                .replace('Ё', 'Е')
                .toLowerCase(Locale.ROOT);
    }

    /*
     * Обычные предметы:
     * ловушки и Ливалка временно проходят через хотбар.
     */
    private static void startRegularItemAction(
            MinecraftClient client,
            Predicate<ItemStack> matcher,
            String displayName
    ) {
        PlayerInventory inventory =
                client.player.getInventory();

        int sourceIndex = findItem(inventory, matcher);

        if (sourceIndex < 0) {
            showMessage(
                    client,
                    "§cНе найден предмет: " + displayName
            );

            cooldownTicks = 10;
            return;
        }

        int originalSelectedSlot = inventory.selectedSlot;

        int actionHotbarSlot;
        boolean movedFromInventory;

        if (sourceIndex >= 0 && sourceIndex < 9) {
            actionHotbarSlot = sourceIndex;
            movedFromInventory = false;
        } else {
            actionHotbarSlot = findTemporaryHotbarSlot(
                    inventory,
                    originalSelectedSlot
            );

            swapInventorySlotWithHotbar(
                    client,
                    sourceIndex,
                    actionHotbarSlot
            );

            movedFromInventory = true;
        }

        pendingAction = PendingAction.regularItem(
                matcher,
                displayName,
                sourceIndex,
                originalSelectedSlot,
                actionHotbarSlot,
                movedFromInventory,
                INVENTORY_SWAP_DELAY
        );
    }

    /*
     * Анти-Флай:
     *
     * Хотбар и основная рука не используются.
     * Предмет напрямую меняется местами со второй рукой.
     */
    private static void startAntiFlyAction(
            MinecraftClient client,
            Predicate<ItemStack> matcher,
            String displayName
    ) {
        PlayerInventory inventory =
                client.player.getInventory();

        int sourceIndex = findItem(inventory, matcher);

        if (sourceIndex < 0) {
            showMessage(
                    client,
                    "§cНе найден предмет: " + displayName
            );

            cooldownTicks = 10;
            return;
        }

        /*
         * Не запускаем повторно, если Анти-Флай
         * уже находится во второй руке.
         */
        if (matcher.test(client.player.getOffHandStack())) {
            showMessage(
                    client,
                    "§eАнти-Флай уже находится "
                            + "во второстепенной руке."
            );

            cooldownTicks = 10;
            return;
        }

        boolean attackWasPressed =
                client.options.attackKey.isPressed();

        boolean useWasPressed =
                client.options.useKey.isPressed();

        boolean sneakWasPressed =
                client.options.sneakKey.isPressed()
                        || client.player.isSneaking();

        /*
         * На время короткой последовательности отключаем
         * конфликтующие действия.
         */
        client.options.attackKey.setPressed(false);
        client.options.useKey.setPressed(false);

        if (sneakWasPressed) {
            client.options.sneakKey.setPressed(false);

            sendSneakPacket(
                    client,
                    ClientCommandC2SPacket.Mode
                            .RELEASE_SHIFT_KEY
            );
        }

        /*
         * SWAP с кнопкой 40 означает обмен выбранного
         * экранного слота со второстепенной рукой.
         *
         * Предмет, который уже был во второй руке,
         * временно попадёт в исходный слот Анти-Флая.
         */
        swapInventorySlotWithOffhand(
                client,
                sourceIndex
        );

        pendingAction = PendingAction.antiFly(
                matcher,
                displayName,
                sourceIndex,
                attackWasPressed,
                useWasPressed,
                sneakWasPressed,
                OFFHAND_MOVE_DELAY
        );
    }

    private static int findItem(
            PlayerInventory inventory,
            Predicate<ItemStack> matcher
    ) {
        for (int index = 0; index < 9; index++) {
            if (matcher.test(inventory.getStack(index))) {
                return index;
            }
        }

        for (int index = 9; index < 36; index++) {
            if (matcher.test(inventory.getStack(index))) {
                return index;
            }
        }

        return -1;
    }

    private static int findTemporaryHotbarSlot(
            PlayerInventory inventory,
            int selectedSlot
    ) {
        /*
         * Предпочитаем пустой слот.
         */
        for (int slot = 0; slot < 9; slot++) {
            if (slot != selectedSlot
                    && inventory.getStack(slot).isEmpty()) {
                return slot;
            }
        }

        /*
         * Если пустого нет, берём любой другой слот.
         */
        for (int slot = 0; slot < 9; slot++) {
            if (slot != selectedSlot) {
                return slot;
            }
        }

        return selectedSlot;
    }

    private static void tickPendingAction(
            MinecraftClient client
    ) {
        if (pendingAction == null) {
            return;
        }

        if (!canContinueAction(client)) {
            pendingAction = null;
            cooldownTicks = FINAL_COOLDOWN;
            return;
        }

        pendingAction.totalTicks++;

        if (pendingAction.totalTicks > ACTION_TIMEOUT) {
            abortAction(
                    client,
                    "Действие отменено из-за тайм-аута."
            );
            return;
        }

        if (pendingAction.delayTicks > 0) {
            pendingAction.delayTicks--;
            return;
        }

        switch (pendingAction.stage) {
            /*
             * Обычные предметы.
             */
            case REGULAR_VERIFY_HOTBAR ->
                    regularVerifyHotbar(client);

            case REGULAR_WAIT_AFTER_SELECT ->
                    regularUseItem(client);

            case REGULAR_WAIT_AFTER_USE ->
                    regularRestoreSelectedSlot(client);

            case REGULAR_WAIT_AFTER_SLOT_RESTORE ->
                    regularRestoreInventory(client);

            case REGULAR_WAIT_AFTER_INVENTORY_RESTORE ->
                    regularVerifyRestore(client);

            /*
             * Анти-Флай.
             */
            case ANTI_FLY_VERIFY_OFFHAND ->
                    antiFlyVerifyOffhand(client);

            case ANTI_FLY_PRESS_SNEAK ->
                    antiFlyPressSneak(client);

            case ANTI_FLY_HOLD_SNEAK ->
                    antiFlyReleaseSneak(client);

            case ANTI_FLY_RELEASE_SNEAK ->
                    antiFlyAfterSneakRelease(client);

            case ANTI_FLY_WAIT_CONFIRM ->
                    antiFlyWaitForConfirmation(client);

            case ANTI_FLY_RESTORE_OFFHAND ->
                    antiFlyRestoreOffhand(client);

            case ANTI_FLY_VERIFY_RESTORE ->
                    antiFlyVerifyRestore(client);

            case FINISH ->
                    finishAction(client);
        }
    }

    /*
     * -------------------------------------------------
     * Обычные предметы
     * -------------------------------------------------
     */

    private static void regularVerifyHotbar(
            MinecraftClient client
    ) {
        ItemStack stack =
                client.player.getInventory().getStack(
                        pendingAction.actionHotbarSlot
                );

        if (!pendingAction.matcher.test(stack)) {
            abortAction(
                    client,
                    "Не удалось подготовить: "
                            + pendingAction.displayName
            );
            return;
        }

        selectHotbarSlot(
                client,
                pendingAction.actionHotbarSlot
        );

        pendingAction.stage =
                ActionStage.REGULAR_WAIT_AFTER_SELECT;

        pendingAction.delayTicks = SLOT_SELECT_DELAY;
    }

    private static void regularUseItem(
            MinecraftClient client
    ) {
        if (!pendingAction.matcher.test(
                client.player.getMainHandStack()
        )) {
            abortAction(
                    client,
                    "В руке оказался другой предмет."
            );
            return;
        }

        client.interactionManager.interactItem(
                client.player,
                Hand.MAIN_HAND
        );

        pendingAction.stage =
                ActionStage.REGULAR_WAIT_AFTER_USE;

        pendingAction.delayTicks = ITEM_USE_DELAY;
    }

    private static void regularRestoreSelectedSlot(
            MinecraftClient client
    ) {
        selectHotbarSlot(
                client,
                pendingAction.originalSelectedSlot
        );

        pendingAction.stage =
                ActionStage.REGULAR_WAIT_AFTER_SLOT_RESTORE;

        pendingAction.delayTicks = SLOT_RESTORE_DELAY;
    }

    private static void regularRestoreInventory(
            MinecraftClient client
    ) {
        if (!pendingAction.movedFromInventory) {
            pendingAction.stage = ActionStage.FINISH;
            pendingAction.delayTicks = 2;
            return;
        }

        swapInventorySlotWithHotbar(
                client,
                pendingAction.sourceInventoryIndex,
                pendingAction.actionHotbarSlot
        );

        pendingAction.stage =
                ActionStage.REGULAR_WAIT_AFTER_INVENTORY_RESTORE;

        pendingAction.delayTicks = INVENTORY_RESTORE_DELAY;
    }

    private static void regularVerifyRestore(
            MinecraftClient client
    ) {
        ItemStack temporaryHotbarStack =
                client.player.getInventory().getStack(
                        pendingAction.actionHotbarSlot
                );

        /*
         * Если предмет полностью израсходовался,
         * совпадения уже не будет — это нормальная ситуация.
         */
        if (pendingAction.matcher.test(temporaryHotbarStack)) {
            showMessage(
                    client,
                    "§cСервер не подтвердил возврат: "
                            + pendingAction.displayName
            );
        }

        pendingAction.stage = ActionStage.FINISH;
        pendingAction.delayTicks = 2;
    }

    /*
     * -------------------------------------------------
     * Анти-Флай
     * -------------------------------------------------
     */

    private static void antiFlyVerifyOffhand(
            MinecraftClient client
    ) {
        if (!pendingAction.matcher.test(
                client.player.getOffHandStack()
        )) {
            abortAction(
                    client,
                    "Анти-Флай не переместился "
                            + "во второстепенную руку."
            );
            return;
                                                }
        
