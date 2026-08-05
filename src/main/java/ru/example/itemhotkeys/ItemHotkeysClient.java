package ru.example.itemhotkeys;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;
import java.util.function.Predicate;

public final class ItemHotkeysClient implements ClientModInitializer {
    private static final String CATEGORY = "category.itemhotkeys.binds";

    private static KeyBinding enderTrapKey;
    private static KeyBinding trapKey;
    private static KeyBinding livalkaKey;
    private static KeyBinding antiFlyKey;

    private static PendingAction pendingAction;

    @Override
    public void onInitializeClient() {
        enderTrapKey = register(
                "key.itemhotkeys.ender_trap",
                GLFW.GLFW_KEY_Z
        );

        trapKey = register(
                "key.itemhotkeys.trap",
                GLFW.GLFW_KEY_X
        );

        livalkaKey = register(
                "key.itemhotkeys.livalka",
                GLFW.GLFW_KEY_C
        );

        antiFlyKey = register(
                "key.itemhotkeys.antifly",
                GLFW.GLFW_KEY_V
        );

        ClientTickEvents.END_CLIENT_TICK.register(
                ItemHotkeysClient::onEndTick
        );
    }

    private static KeyBinding register(
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

    private static void onEndTick(MinecraftClient client) {
        tickPending(client);

        if (!canStartAction(client) || pendingAction != null) {
            drainKeys();
            return;
        }

        while (enderTrapKey.wasPressed()) {
            useNamedItem(
                    client,
                    exactName("Эндер Ловушка"),
                    "Эндер Ловушка",
                    false
            );
        }

        while (trapKey.wasPressed()) {
            useNamedItem(
                    client,
                    exactName("Ловушка"),
                    "Ловушка",
                    false
            );
        }

        while (livalkaKey.wasPressed()) {
            useNamedItem(
                    client,
                    exactName("Ливалка"),
                    "Ливалка",
                    false
            );
        }

        while (antiFlyKey.wasPressed()) {
            useNamedItem(
                    client,
                    exactName("Анти-Флай"),
                    "Анти-Флай",
                    true
            );
        }
    }

    private static void drainKeys() {
        while (enderTrapKey.wasPressed()) {
            // Очищаем очередь нажатий.
        }

        while (trapKey.wasPressed()) {
            // Очищаем очередь нажатий.
        }

        while (livalkaKey.wasPressed()) {
            // Очищаем очередь нажатий.
        }

        while (antiFlyKey.wasPressed()) {
            // Очищаем очередь нажатий.
        }
    }

    private static boolean canStartAction(MinecraftClient client) {
        return client.player != null
                && client.interactionManager != null
                && client.getNetworkHandler() != null
                && client.currentScreen == null;
    }

    private static Predicate<ItemStack> exactName(String wantedName) {
        String normalizedWantedName = normalize(wantedName);

        return stack -> !stack.isEmpty()
                && normalize(stack.getName().getString())
                .equals(normalizedWantedName);
    }

    private static String normalize(String text) {
        return text
                .trim()
                .replace('ё', 'е')
                .replace('Ё', 'Е')
                .toLowerCase(Locale.ROOT);
    }

    private static void useNamedItem(
            MinecraftClient client,
            Predicate<ItemStack> matcher,
            String displayName,
            boolean antiFly
    ) {
        PlayerInventory inventory = client.player.getInventory();

        int inventoryIndex = findItem(inventory, matcher);

        if (inventoryIndex < 0) {
            client.player.sendMessage(
                    Text.literal(
                            "§c[Item Hotkeys] Не найден предмет: "
                                    + displayName
                    ),
                    true
            );

            return;
        }

        int selectedHotbarSlot = inventory.selectedSlot;

        boolean itemWasMoved =
                inventoryIndex != selectedHotbarSlot;

        /*
         * Если предмет не находится в выбранном слоте хотбара,
         * временно меняем его местами с предметом в руке.
         */
        if (itemWasMoved) {
            int screenSlot =
                    inventoryIndexToScreenSlot(inventoryIndex);

            client.interactionManager.clickSlot(
                    client.player.currentScreenHandler.syncId,
                    screenSlot,
                    selectedHotbarSlot,
                    SlotActionType.SWAP,
                    client.player
            );
        }

        pendingAction = new PendingAction(
                antiFly
                        ? ActionType.ANTI_FLY
                        : ActionType.RIGHT_CLICK,
                itemWasMoved,
                inventoryIndex,
                selectedHotbarSlot,
                1,
                client.player.isSneaking()
        );
    }

    private static int findItem(
            PlayerInventory inventory,
            Predicate<ItemStack> matcher
    ) {
        /*
         * Сначала проверяется хотбар.
         */
        for (int index = 0; index < 9; index++) {
            ItemStack stack = inventory.getStack(index);

            if (matcher.test(stack)) {
                return index;
            }
        }

        /*
         * Затем проверяется основная часть инвентаря.
         */
        for (int index = 9; index < 36; index++) {
            ItemStack stack = inventory.getStack(index);

            if (matcher.test(stack)) {
                return index;
            }
        }

        return -1;
    }

    private static int inventoryIndexToScreenSlot(
            int inventoryIndex
    ) {
        /*
         * В PlayerScreenHandler:
         *
         * индексы инвентаря 9–35 соответствуют слотам 9–35;
         * индексы хотбара 0–8 соответствуют слотам 36–44.
         */
        if (inventoryIndex < 9) {
            return 36 + inventoryIndex;
        }

        return inventoryIndex;
    }

    private static void tickPending(MinecraftClient client) {
        if (pendingAction == null) {
            return;
        }

        if (!canContinueAction(client)) {
            pendingAction = null;
            return;
        }

        if (pendingAction.delayTicks > 0) {
            pendingAction.delayTicks--;
            return;
        }

        switch (pendingAction.type) {
            case RIGHT_CLICK -> performRightClick(client);

            case ANTI_FLY -> activateAntiFly(client);

            case RELEASE_SNEAK -> releaseSneak(client);

            case RESTORE -> {
                restoreOriginalItem(client, pendingAction);
                pendingAction = null;
            }
        }
    }

    private static void performRightClick(
            MinecraftClient client
    ) {
        client.interactionManager.interactItem(
                client.player,
                Hand.MAIN_HAND
        );

        pendingAction.delayTicks = 2;
        pendingAction.type = ActionType.RESTORE;
    }

    private static void activateAntiFly(
            MinecraftClient client
    ) {
        /*
         * Анти-Флай активируется нажатием Shift,
         * когда предмет находится в основной руке.
         */
        if (!pendingAction.wasSneaking) {
            client.options.sneakKey.setPressed(true);

            client.getNetworkHandler().sendPacket(
                    new ClientCommandC2SPacket(
                            client.player,
                            ClientCommandC2SPacket.Mode.PRESS_SHIFT_KEY
                    )
            );
        }

        pendingAction.delayTicks = 2;
        pendingAction.type = ActionType.RELEASE_SNEAK;
    }

    private static void releaseSneak(
            MinecraftClient client
    ) {
        /*
         * Если игрок до использования предмета уже держал Shift,
         * мод не должен самостоятельно отпускать его.
         */
        if (!pendingAction.wasSneaking) {
            client.options.sneakKey.setPressed(false);

            client.getNetworkHandler().sendPacket(
                    new ClientCommandC2SPacket(
                            client.player,
                            ClientCommandC2SPacket.Mode.RELEASE_SHIFT_KEY
                    )
            );
        }

        pendingAction.delayTicks = 1;
        pendingAction.type = ActionType.RESTORE;
    }

    private static boolean canContinueAction(
            MinecraftClient client
    ) {
        return client.player != null
                && client.interactionManager != null
                && client.getNetworkHandler() != null;
    }

    private static void restoreOriginalItem(
            MinecraftClient client,
            PendingAction action
    ) {
        if (!action.itemWasMoved) {
            return;
        }

        client.interactionManager.clickSlot(
                client.player.currentScreenHandler.syncId,
                inventoryIndexToScreenSlot(
                        action.sourceInventoryIndex
                ),
                action.selectedHotbarSlot,
                SlotActionType.SWAP,
                client.player
        );
    }

    private enum ActionType {
        RIGHT_CLICK,
        ANTI_FLY,
        RELEASE_SNEAK,
        RESTORE
    }

    private static final class PendingAction {
        private ActionType type;

        private final boolean itemWasMoved;
        private final int sourceInventoryIndex;
        private final int selectedHotbarSlot;
        private final boolean wasSneaking;

        private int delayTicks;

        private PendingAction(
                ActionType type,
                boolean itemWasMoved,
                int sourceInventoryIndex,
                int selectedHotbarSlot,
                int delayTicks,
                boolean wasSneaking
        ) {
            this.type = type;
            this.itemWasMoved = itemWasMoved;
            this.sourceInventoryIndex = sourceInventoryIndex;
            this.selectedHotbarSlot = selectedHotbarSlot;
            this.delayTicks = delayTicks;
            this.wasSneaking = wasSneaking;
        }
    }
        }
