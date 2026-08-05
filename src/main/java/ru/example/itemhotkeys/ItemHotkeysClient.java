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
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;
import java.util.function.Predicate;

public final class ItemHotkeysClient implements ClientModInitializer {
    private static final String CATEGORY =
            "category.itemhotkeys.binds";

    private static KeyBinding enderTrapKey;
    private static KeyBinding trapKey;
    private static KeyBinding livalkaKey;
    private static KeyBinding antiFlyKey;

    private static PendingAction pendingAction;

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
        tickPendingAction(client);

        if (!canStartAction(client) || pendingAction != null) {
            clearQueuedKeyPresses();
            return;
        }

        while (enderTrapKey.wasPressed()) {
            startItemAction(
                    client,
                    itemNameContains("Эндер Ловушка"),
                    "Эндер Ловушка",
                    ActivationType.RIGHT_CLICK
            );
        }

        while (trapKey.wasPressed()) {
            startItemAction(
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
                    "Ловушка",
                    ActivationType.RIGHT_CLICK
            );
        }

        while (livalkaKey.wasPressed()) {
            startItemAction(
                    client,
                    itemNameContains("Ливалка"),
                    "Ливалка",
                    ActivationType.RIGHT_CLICK
            );
        }

        while (antiFlyKey.wasPressed()) {
            startItemAction(
                    client,
                    itemNameContains("Анти-Флай"),
                    "Анти-Флай",
                    ActivationType.ANTI_FLY
            );
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
        while (enderTrapKey.wasPressed()) {
        }

        while (trapKey.wasPressed()) {
        }

        while (livalkaKey.wasPressed()) {
        }

        while (antiFlyKey.wasPressed()) {
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

    private static void startItemAction(
            MinecraftClient client,
            Predicate<ItemStack> matcher,
            String displayName,
            ActivationType activationType
    ) {
        PlayerInventory inventory =
                client.player.getInventory();

        int sourceIndex = findItem(inventory, matcher);

        if (sourceIndex < 0) {
            client.player.sendMessage(
                    Text.literal(
                            "§c[Item Hotkeys] Не найден предмет: "
                                    + displayName
                    ),
                    true
            );

            return;
        }

        int originalSelectedSlot = inventory.selectedSlot;

        int actionHotbarSlot;
        boolean itemMovedToHotbar;

        if (sourceIndex >= 0 && sourceIndex < 9) {
            actionHotbarSlot = sourceIndex;
            itemMovedToHotbar = false;
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

            itemMovedToHotbar = true;
        }

        selectHotbarSlot(client, actionHotbarSlot);

        boolean playerWasSneaking =
                client.player.isSneaking();

        pendingAction = new PendingAction(
                activationType == ActivationType.RIGHT_CLICK
                        ? ActionStage.USE_RIGHT_CLICK
                        : ActionStage.SWAP_TO_OFFHAND,
                sourceIndex,
                originalSelectedSlot,
                actionHotbarSlot,
                itemMovedToHotbar,
                playerWasSneaking,
                1
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
            int originalSelectedSlot
    ) {
        for (int slot = 0; slot < 9; slot++) {
            if (slot != originalSelectedSlot
                    && inventory.getStack(slot).isEmpty()) {
                return slot;
            }
        }

        for (int slot = 0; slot < 9; slot++) {
            if (slot != originalSelectedSlot) {
                return slot;
            }
        }

        return originalSelectedSlot;
    }

    private static void tickPendingAction(
            MinecraftClient client
    ) {
        if (pendingAction == null) {
            return;
        }

        if (!canContinueAction(client)) {
            safelyReleaseSneak(client);
            pendingAction = null;
            return;
        }

        if (pendingAction.delayTicks > 0) {
            pendingAction.delayTicks--;
            return;
        }

        switch (pendingAction.stage) {
            case USE_RIGHT_CLICK ->
                    useItemWithRightClick(client);

            case SWAP_TO_OFFHAND ->
                    swapAntiFlyToOffhand(client);

            case PRESS_SNEAK ->
                    pressSneakForAntiFly(client);

            case RELEASE_SNEAK ->
                    releaseSneakForAntiFly(client);

            case SWAP_BACK_FROM_OFFHAND ->
                    swapAntiFlyBackFromOffhand(client);

            case RESTORE_SELECTED_SLOT ->
                    restoreSelectedSlot(client);

            case RESTORE_INVENTORY -> {
                restoreInventory(client);
                pendingAction = null;
            }
        }
    }

    private static void useItemWithRightClick(
            MinecraftClient client
    ) {
        client.interactionManager.interactItem(
                client.player,
                Hand.MAIN_HAND
        );

        pendingAction.delayTicks = 1;
        pendingAction.stage =
                ActionStage.RESTORE_SELECTED_SLOT;
    }

    private static void swapAntiFlyToOffhand(
            MinecraftClient client
    ) {
        sendSwapHandsPacket(client);

        /*
         * Ждём, чтобы сервер успел увидеть предмет
         * во второстепенной руке.
         */
        pendingAction.delayTicks = 2;
        pendingAction.stage = ActionStage.PRESS_SNEAK;
    }

    private static void pressSneakForAntiFly(
            MinecraftClient client
    ) {
        /*
         * Если игрок уже держит Shift, повторно нажимать
         * и затем отпускать его нельзя.
         */
        if (!pendingAction.playerWasSneaking) {
            client.options.sneakKey.setPressed(true);

            client.getNetworkHandler().sendPacket(
                    new ClientCommandC2SPacket(
                            client.player,
                            ClientCommandC2SPacket.Mode
                                    .PRESS_SHIFT_KEY
                    )
            );

            pendingAction.sneakPressedByMod = true;
        }

        /*
         * Держим Shift несколько тиков, чтобы серверный
         * плагин успел обработать активацию Анти-Флая.
         */
        pendingAction.delayTicks = 4;
        pendingAction.stage = ActionStage.RELEASE_SNEAK;
    }

    private static void releaseSneakForAntiFly(
            MinecraftClient client
    ) {
        safelyReleaseSneak(client);

        /*
         * После отпускания Shift даём серверу ещё один тик.
         */
        pendingAction.delayTicks = 1;
        pendingAction.stage =
                ActionStage.SWAP_BACK_FROM_OFFHAND;
    }

    private static void safelyReleaseSneak(
            MinecraftClient client
    ) {
        if (pendingAction == null
                || !pendingAction.sneakPressedByMod
                || client.player == null
                || client.getNetworkHandler() == null) {
            return;
        }

        client.options.sneakKey.setPressed(false);

        client.getNetworkHandler().sendPacket(
                new ClientCommandC2SPacket(
                        client.player,
                        ClientCommandC2SPacket.Mode
                                .RELEASE_SHIFT_KEY
                )
        );

        pendingAction.sneakPressedByMod = false;
    }

    private static void swapAntiFlyBackFromOffhand(
            MinecraftClient client
    ) {
        sendSwapHandsPacket(client);

        pendingAction.delayTicks = 1;
        pendingAction.stage =
                ActionStage.RESTORE_SELECTED_SLOT;
    }

    private static void restoreSelectedSlot(
            MinecraftClient client
    ) {
        selectHotbarSlot(
                client,
                pendingAction.originalSelectedSlot
        );

        pendingAction.delayTicks = 1;
        pendingAction.stage =
                ActionStage.RESTORE_INVENTORY;
    }

    private static void restoreInventory(
            MinecraftClient client
    ) {
        if (!pendingAction.itemMovedToHotbar) {
            return;
        }

        swapInventorySlotWithHotbar(
                client,
                pendingAction.sourceInventoryIndex,
                pendingAction.actionHotbarSlot
        );
    }

    private static void selectHotbarSlot(
            MinecraftClient client,
            int hotbarSlot
    ) {
        if (client.player.getInventory().selectedSlot
                == hotbarSlot) {
            return;
        }

        client.player.getInventory().selectedSlot =
                hotbarSlot;

        client.getNetworkHandler().sendPacket(
                new UpdateSelectedSlotC2SPacket(hotbarSlot)
        );
    }

    private static void swapInventorySlotWithHotbar(
            MinecraftClient client,
            int inventoryIndex,
            int hotbarSlot
    ) {
        int screenSlot =
                inventoryIndexToScreenSlot(inventoryIndex);

        client.interactionManager.clickSlot(
                client.player.currentScreenHandler.syncId,
                screenSlot,
                hotbarSlot,
                SlotActionType.SWAP,
                client.player
        );
    }

    private static int inventoryIndexToScreenSlot(
            int inventoryIndex
    ) {
        if (inventoryIndex >= 0 && inventoryIndex < 9) {
            return 36 + inventoryIndex;
        }

        return inventoryIndex;
    }

    private static void sendSwapHandsPacket(
            MinecraftClient client
    ) {
        client.getNetworkHandler().sendPacket(
                new PlayerActionC2SPacket(
                        PlayerActionC2SPacket.Action
                                .SWAP_ITEM_WITH_OFFHAND,
                        BlockPos.ORIGIN,
                        Direction.DOWN
                )
        );
    }

    private enum ActivationType {
        RIGHT_CLICK,
        ANTI_FLY
    }

    private enum ActionStage {
        USE_RIGHT_CLICK,
        SWAP_TO_OFFHAND,
        PRESS_SNEAK,
        RELEASE_SNEAK,
        SWAP_BACK_FROM_OFFHAND,
        RESTORE_SELECTED_SLOT,
        RESTORE_INVENTORY
    }

    private static final class PendingAction {
        private ActionStage stage;

        private final int sourceInventoryIndex;
        private final int originalSelectedSlot;
        private final int actionHotbarSlot;
        private final boolean itemMovedToHotbar;
        private final boolean playerWasSneaking;

        private boolean sneakPressedByMod;
        private int delayTicks;

        private PendingAction(
                ActionStage stage,
                int sourceInventoryIndex,
                int originalSelectedSlot,
                int actionHotbarSlot,
                boolean itemMovedToHotbar,
                boolean playerWasSneaking,
                int delayTicks
        ) {
            this.stage = stage;
            this.sourceInventoryIndex = sourceInventoryIndex;
            this.originalSelectedSlot = originalSelectedSlot;
            this.actionHotbarSlot = actionHotbarSlot;
            this.itemMovedToHotbar = itemMovedToHotbar;
            this.playerWasSneaking = playerWasSneaking;
            this.delayTicks = delayTicks;
        }
    }
}
