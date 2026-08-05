package ru.example.itemhotkeys;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;

import java.util.function.Predicate;

public final class InventoryUtil {
    /*
     * В PlayerInventory:
     *
     * 0–8  — хотбар;
     * 9–35 — основная часть инвентаря.
     */
    public static final int FIRST_HOTBAR_SLOT = 0;
    public static final int LAST_HOTBAR_SLOT = 8;
    public static final int FIRST_MAIN_SLOT = 9;
    public static final int LAST_MAIN_SLOT = 35;

    /*
     * Для SlotActionType.SWAP кнопка 40 означает offhand.
     */
    public static final int OFFHAND_SWAP_BUTTON = 40;

    private InventoryUtil() {
    }

    public static int findItem(
            PlayerInventory inventory,
            Predicate<ItemStack> matcher
    ) {
        /*
         * Сначала проверяем хотбар.
         */
        for (
                int slot = FIRST_HOTBAR_SLOT;
                slot <= LAST_HOTBAR_SLOT;
                slot++
        ) {
            if (matcher.test(inventory.getStack(slot))) {
                return slot;
            }
        }

        /*
         * Затем основную часть инвентаря.
         */
        for (
                int slot = FIRST_MAIN_SLOT;
                slot <= LAST_MAIN_SLOT;
                slot++
        ) {
            if (matcher.test(inventory.getStack(slot))) {
                return slot;
            }
        }

        return -1;
    }

    public static int findTemporaryHotbarSlot(
            PlayerInventory inventory,
            int selectedSlot
    ) {
        /*
         * Предпочитаем пустой слот, не являющийся
         * текущей основной рукой.
         */
        for (
                int slot = FIRST_HOTBAR_SLOT;
                slot <= LAST_HOTBAR_SLOT;
                slot++
        ) {
            if (slot != selectedSlot
                    && inventory.getStack(slot).isEmpty()) {
                return slot;
            }
        }

        /*
         * Если пустого слота нет, используем любой другой.
         * Его содержимое позже вернётся обратно.
         */
        for (
                int slot = FIRST_HOTBAR_SLOT;
                slot <= LAST_HOTBAR_SLOT;
                slot++
        ) {
            if (slot != selectedSlot) {
                return slot;
            }
        }

        return selectedSlot;
    }

    public static void selectHotbarSlot(
            MinecraftClient client,
            int hotbarSlot
    ) {
        if (!isHotbarSlot(hotbarSlot)
                || client.player == null
                || client.getNetworkHandler() == null) {
            return;
        }

        /*
         * Не отправляем лишний пакет, если нужный слот
         * уже выбран.
         */
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

    public static void swapInventorySlotWithHotbar(
            MinecraftClient client,
            int inventoryIndex,
            int hotbarSlot
    ) {
        if (!canClickInventory(client)
                || !isInventoryIndex(inventoryIndex)
                || !isHotbarSlot(hotbarSlot)) {
            return;
        }

        client.interactionManager.clickSlot(
                client.player.currentScreenHandler.syncId,
                inventoryIndexToScreenSlot(inventoryIndex),
                hotbarSlot,
                SlotActionType.SWAP,
                client.player
        );
    }

    public static void swapInventorySlotWithOffhand(
            MinecraftClient client,
            int inventoryIndex
    ) {
        if (!canClickInventory(client)
                || !isInventoryIndex(inventoryIndex)) {
            return;
        }

        client.interactionManager.clickSlot(
                client.player.currentScreenHandler.syncId,
                inventoryIndexToScreenSlot(inventoryIndex),
                OFFHAND_SWAP_BUTTON,
                SlotActionType.SWAP,
                client.player
        );
    }

    public static ItemStack getInventoryStack(
            MinecraftClient client,
            int inventoryIndex
    ) {
        if (client.player == null
                || !isInventoryIndex(inventoryIndex)) {
            return ItemStack.EMPTY;
        }

        return client.player
                .getInventory()
                .getStack(inventoryIndex);
    }

    public static boolean isInventoryIndex(
            int inventoryIndex
    ) {
        return inventoryIndex >= FIRST_HOTBAR_SLOT
                && inventoryIndex <= LAST_MAIN_SLOT;
    }

    public static boolean isHotbarSlot(
            int hotbarSlot
    ) {
        return hotbarSlot >= FIRST_HOTBAR_SLOT
                && hotbarSlot <= LAST_HOTBAR_SLOT;
    }

    public static int inventoryIndexToScreenSlot(
            int inventoryIndex
    ) {
        /*
         * В PlayerScreenHandler хотбар расположен
         * в экранных слотах 36–44.
         */
        if (isHotbarSlot(inventoryIndex)) {
            return 36 + inventoryIndex;
        }

        return inventoryIndex;
    }

    private static boolean canClickInventory(
            MinecraftClient client
    ) {
        return client.player != null
                && client.interactionManager != null
                && client.player.currentScreenHandler != null;
    }
}
