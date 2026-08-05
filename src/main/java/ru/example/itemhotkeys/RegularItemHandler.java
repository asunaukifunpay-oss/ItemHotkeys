package ru.example.itemhotkeys;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;

import java.util.function.Predicate;

public final class RegularItemHandler {
    /*
     * 20 игровых тиков = примерно 1 секунда.
     */
    private static final int INVENTORY_SWAP_DELAY = 6;
    private static final int SLOT_SELECT_DELAY = 6;
    private static final int AFTER_USE_DELAY = 6;
    private static final int SLOT_RESTORE_DELAY = 7;
    private static final int INVENTORY_RESTORE_DELAY = 7;

    private static final int ACTION_TIMEOUT_TICKS = 100;

    private static Predicate<ItemStack> matcher;
    private static String displayName;

    private static ActionStage stage;

    private static int sourceInventoryIndex;
    private static int originalSelectedSlot;
    private static int actionHotbarSlot;

    private static boolean movedFromInventory;

    private static boolean attackWasPressed;
    private static boolean useWasPressed;

    private static int delayTicks;
    private static int totalTicks;

    private RegularItemHandler() {
    }

    public static void start(
            MinecraftClient client,
            Predicate<ItemStack> itemMatcher,
            String itemDisplayName
    ) {
        if (!ActionController.begin(ActionType.REGULAR_ITEM)) {
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

        matcher = itemMatcher;
        displayName = itemDisplayName;

        sourceInventoryIndex = foundIndex;
        originalSelectedSlot = inventory.selectedSlot;

        /*
         * На время последовательности прекращаем
         * конфликтующие действия ЛКМ и ПКМ.
         */
        attackWasPressed =
                client.options.attackKey.isPressed();

        useWasPressed =
                client.options.useKey.isPressed();

        client.options.attackKey.setPressed(false);
        client.options.useKey.setPressed(false);

        if (foundIndex >= 0 && foundIndex <= 8) {
            /*
             * Предмет уже находится в хотбаре.
             */
            actionHotbarSlot = foundIndex;
            movedFromInventory = false;

            delayTicks = 2;
        } else {
            /*
             * Предмет находится в основной части
             * инвентаря. Временно переносим его
             * в отдельный слот хотбара.
             */
            actionHotbarSlot =
                    InventoryUtil.findTemporaryHotbarSlot(
                            inventory,
                            originalSelectedSlot
                    );

            InventoryUtil.swapInventorySlotWithHotbar(
                    client,
                    sourceInventoryIndex,
                    actionHotbarSlot
            );

            movedFromInventory = true;
            delayTicks = INVENTORY_SWAP_DELAY;
        }

        stage = ActionStage.REGULAR_VERIFY_HOTBAR;
        totalTicks = 0;
    }

    public static void startTrap(
            MinecraftClient client
    ) {
        start(
                client,
                ItemNames.TRAP,
                "Ловушка"
        );
    }

    public static void tick(
            MinecraftClient client
    ) {
        if (!ActionController.isActive(
                ActionType.REGULAR_ITEM
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
                    "Действие отменено из-за тайм-аута."
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
                    "Неизвестное состояние действия."
            );
            return;
        }

        switch (stage) {
            case REGULAR_VERIFY_HOTBAR ->
                    verifyHotbarItem(client);

            case REGULAR_WAIT_AFTER_SLOT_SELECT ->
                    prepareUse(client);

            case REGULAR_USE_ITEM ->
                    useItem(client);

            case REGULAR_WAIT_AFTER_USE ->
                    prepareSelectedSlotRestore();

            case REGULAR_RESTORE_SELECTED_SLOT ->
                    restoreSelectedSlot(client);

            case REGULAR_WAIT_AFTER_SLOT_RESTORE ->
                    prepareInventoryRestore();

            case REGULAR_RESTORE_INVENTORY ->
                    restoreInventory(client);

            case REGULAR_WAIT_AFTER_INVENTORY_RESTORE ->
                    prepareRestoreVerification();

            case REGULAR_VERIFY_RESTORE ->
                    verifyInventoryRestore(client);

            case FINISH ->
                    finish(client);

            default -> ActionController.cancel(
                    client,
                    "Получен неправильный этап "
                            + "обычного предмета."
            );
        }
    }

    private static void verifyHotbarItem(
            MinecraftClient client
    ) {
        ItemStack preparedStack =
                InventoryUtil.getInventoryStack(
                        client,
                        actionHotbarSlot
                );

        /*
         * Не используем предмет, пока точно
         * не увидим его в подготовленном слоте.
         */
        if (!matcher.test(preparedStack)) {
            ActionController.cancel(
                    client,
                    "Не удалось подготовить: "
                            + displayName
            );
            return;
        }

        InventoryUtil.selectHotbarSlot(
                client,
                actionHotbarSlot
        );

        stage =
                ActionStage.REGULAR_WAIT_AFTER_SLOT_SELECT;

        delayTicks = SLOT_SELECT_DELAY;
    }

    private static void prepareUse(
            MinecraftClient client
    ) {
        ItemStack mainHandStack =
                client.player.getMainHandStack();

        /*
         * Защита от использования первого или другого
         * предмета при рассинхронизации.
         */
        if (!matcher.test(mainHandStack)) {
            ActionController.cancel(
                    client,
                    "В основной руке оказался "
                            + "другой предмет."
            );
            return;
        }

        stage = ActionStage.REGULAR_USE_ITEM;
        delayTicks = 1;
    }

    private static void useItem(
            MinecraftClient client
    ) {
        ItemStack mainHandStack =
                client.player.getMainHandStack();

        /*
         * Повторная проверка непосредственно перед ПКМ.
         */
        if (!matcher.test(mainHandStack)) {
            ActionController.cancel(
                    client,
                    "Использование отменено: "
                            + displayName
                            + " отсутствует в руке."
            );
            return;
        }

        client.interactionManager.interactItem(
                client.player,
                Hand.MAIN_HAND
        );

        stage = ActionStage.REGULAR_WAIT_AFTER_USE;
        delayTicks = AFTER_USE_DELAY;
    }

    private static void prepareSelectedSlotRestore() {
        stage =
                ActionStage.REGULAR_RESTORE_SELECTED_SLOT;

        delayTicks = 1;
    }

    private static void restoreSelectedSlot(
            MinecraftClient client
    ) {
        InventoryUtil.selectHotbarSlot(
                client,
                originalSelectedSlot
        );

        stage =
                ActionStage.REGULAR_WAIT_AFTER_SLOT_RESTORE;

        delayTicks = SLOT_RESTORE_DELAY;
    }

    private static void prepareInventoryRestore() {
        if (!movedFromInventory) {
            stage = ActionStage.FINISH;
            delayTicks = 2;
            return;
        }

        stage =
                ActionStage.REGULAR_RESTORE_INVENTORY;

        delayTicks = 1;
    }

    private static void restoreInventory(
            MinecraftClient client
    ) {
        ItemStack temporaryStack =
                InventoryUtil.getInventoryStack(
                        client,
                        actionHotbarSlot
                );

        /*
         * Если предмет не израсходовался, он должен
         * находиться во временном слоте.
         *
         * Если предмет одноразовый и исчез, обмен всё
         * равно необходим, чтобы вернуть исходный предмет
         * временного слота обратно.
         */
        if (!temporaryStack.isEmpty()
                && !matcher.test(temporaryStack)) {
            ActionController.cancel(
                    client,
                    "Временный слот был изменён. "
                            + "Возврат остановлен."
            );
            return;
        }

        InventoryUtil.swapInventorySlotWithHotbar(
                client,
                sourceInventoryIndex,
                actionHotbarSlot
        );

        stage =
                ActionStage.REGULAR_WAIT_AFTER_INVENTORY_RESTORE;

        delayTicks = INVENTORY_RESTORE_DELAY;
    }

    private static void prepareRestoreVerification() {
        stage =
                ActionStage.REGULAR_VERIFY_RESTORE;

        delayTicks = 1;
    }

    private static void verifyInventoryRestore(
            MinecraftClient client
    ) {
        ItemStack temporaryStack =
                InventoryUtil.getInventoryStack(
                        client,
                        actionHotbarSlot
                );

        /*
         * Если специальный предмет по-прежнему находится
         * во временном хотбаре, сервер не подтвердил
         * обратный обмен.
         */
        if (matcher.test(temporaryStack)) {
            ItemHotkeysClient.showMessage(
                    client,
                    "§cНе удалось подтвердить возврат: "
                            + displayName
            );
        }

        stage = ActionStage.FINISH;
        delayTicks = 2;
    }

    private static void finish(
            MinecraftClient client
    ) {
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

        /*
         * Всегда возвращаем прежний выбранный слот.
         */
        InventoryUtil.selectHotbarSlot(
                client,
                originalSelectedSlot
        );

        /*
         * При аварии выполняем обратный обмен только
         * тогда, когда специальный предмет точно виден
         * во временном слоте.
         */
        if (movedFromInventory
                && matcher != null
                && InventoryUtil.isInventoryIndex(
                        sourceInventoryIndex
                )
                && InventoryUtil.isHotbarSlot(
                        actionHotbarSlot
                )) {
            ItemStack temporaryStack =
                    InventoryUtil.getInventoryStack(
                            client,
                            actionHotbarSlot
                    );

            if (matcher.test(temporaryStack)) {
                InventoryUtil.swapInventorySlotWithHotbar(
                        client,
                        sourceInventoryIndex,
                        actionHotbarSlot
                );
            }
        }

        restoreInputState(client);
        clearState();
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
        originalSelectedSlot = -1;
        actionHotbarSlot = -1;

        movedFromInventory = false;

        attackWasPressed = false;
        useWasPressed = false;

        delayTicks = 0;
        totalTicks = 0;
    }
}
