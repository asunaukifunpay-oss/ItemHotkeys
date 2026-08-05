package ru.example.itemhotkeys;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;

import java.util.function.Predicate;

public final class RegularItemHandler {
    /*
     * Быстрая схема для:
     *
     * - Эндер-ловушки;
     * - обычной Ловушки;
     * - Ливалки.
     *
     * 1 тик Minecraft приблизительно равен 0,05 секунды.
     */
    private static final int INVENTORY_SWAP_DELAY = 1;
    private static final int SLOT_SELECT_DELAY = 1;
    private static final int AFTER_USE_DELAY = 1;
    private static final int SLOT_RESTORE_DELAY = 1;
    private static final int INVENTORY_RESTORE_DELAY = 2;

    /*
     * Максимальное время выполнения операции.
     * 50 тиков — примерно 2,5 секунды.
     */
    private static final int ACTION_TIMEOUT_TICKS = 50;

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
        originalSelectedSlot = inventory.selectedSlot;

        /*
         * Запоминаем предметы, которые игрок видел
         * в основной и второстепенной руках.
         *
         * Пока операция выполняется, mixin продолжит
         * отображать именно эти предметы.
         */
        VisualSwapState.begin();

        /*
         * Запоминаем, были ли зажаты ЛКМ и ПКМ.
         */
        attackWasPressed =
                client.options.attackKey.isPressed();

        useWasPressed =
                client.options.useKey.isPressed();

        /*
         * На время автоматического использования
         * прекращаем конфликтующие действия.
         */
        client.options.attackKey.setPressed(false);
        client.options.useKey.setPressed(false);

        if (foundIndex >= 0 && foundIndex <= 8) {
            /*
             * Предмет уже находится в хотбаре.
             * Переставлять его из инвентаря не нужно.
             */
            actionHotbarSlot = foundIndex;
            movedFromInventory = false;
            delayTicks = 0;
        } else {
            /*
             * Предмет находится в основной части
             * инвентаря.
             *
             * Временно меняем его местами с отдельным
             * слотом хотбара.
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
            /*
             * Если игрок вышел с сервера или соединение
             * пропало, пакеты восстановления уже отправить
             * нельзя. Но визуальный режим необходимо
             * обязательно отключить.
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
                    "Неправильный этап обычного предмета."
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
         * Пока нужный предмет реально не появился
         * во временном слоте, ПКМ не отправляется.
         *
         * Это защищает от использования меча или первого
         * предмета хотбара при рассинхронизации.
         */
        if (!matcher.test(preparedStack)) {
            ActionController.cancel(
                    client,
                    "Не удалось подготовить: "
                            + displayName
            );
            return;
        }

        /*
         * Серверу отправляется настоящая смена слота,
         * но визуально игрок продолжает видеть старый
         * предмет благодаря VisualSwapState и mixin.
         */
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

        if (!matcher.test(mainHandStack)) {
            ActionController.cancel(
                    client,
                    "В основной руке оказался "
                            + "другой предмет."
            );
            return;
        }

        stage = ActionStage.REGULAR_USE_ITEM;
        delayTicks = 0;
    }

    private static void useItem(
            MinecraftClient client
    ) {
        ItemStack mainHandStack =
                client.player.getMainHandStack();

        /*
         * Повторная проверка выполняется непосредственно
         * перед использованием.
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

        delayTicks = 0;
    }

    private static void restoreSelectedSlot(
            MinecraftClient client
    ) {
        /*
         * Возвращаем настоящий выбранный слот.
         */
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
            delayTicks = 0;
            return;
        }

        stage =
                ActionStage.REGULAR_RESTORE_INVENTORY;

        delayTicks = 0;
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
         * Допустимые состояния временного слота:
         *
         * 1. В нём всё ещё находится нужный предмет.
         * 2. Он пустой, если предмет был одноразовым
         *    и полностью израсходовался.
         *
         * Если там появился посторонний предмет,
         * слепой обмен не выполняется.
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

        delayTicks = 0;
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
         * Если специальный предмет всё ещё остался
         * во временном хотбаре, выполняется только одна
         * дополнительная попытка возврата.
         */
        if (matcher.test(temporaryStack)) {
            InventoryUtil.swapInventorySlotWithHotbar(
                    client,
                    sourceInventoryIndex,
                    actionHotbarSlot
            );
        }

        stage = ActionStage.FINISH;
        delayTicks = 1;
    }

    private static void finish(
            MinecraftClient client
    ) {
        /*
         * Сначала возвращаем управление игроку.
         */
        restoreInputState(client);

        /*
         * Затем отключаем визуальную подмену.
         * К этому моменту настоящий предмет и выбранный
         * слот уже должны быть возвращены.
         */
        VisualSwapState.end();

        clearState();

        /*
         * Небольшой кулдаун защищает от спама биндами.
         */
        ActionController.finish(4);
    }

    public static void recover(
            MinecraftClient client
    ) {
        if (client == null || client.player == null) {
            VisualSwapState.end();
            clearState();
            return;
        }

        /*
         * Возвращаем исходный выбранный слот.
         */
        if (InventoryUtil.isHotbarSlot(
                originalSelectedSlot
        )) {
            InventoryUtil.selectHotbarSlot(
                    client,
                    originalSelectedSlot
            );
        }

        /*
         * При аварийном восстановлении выполняем обмен
         * только тогда, когда нужный предмет точно
         * находится во временном слоте.
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

        /*
         * Даже если восстановление не удалось,
         * визуальная подмена должна быть выключена,
         * иначе старый меч останется нарисован навсегда.
         */
        VisualSwapState.end();

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
