package ru.example.itemhotkeys;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;

public final class VisualSwapState {
    private static boolean active;

    private static ItemStack visualMainHand = ItemStack.EMPTY;
    private static ItemStack visualOffHand = ItemStack.EMPTY;

    private VisualSwapState() {
    }

    public static void begin() {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client.player == null) {
            clear();
            return;
        }

        visualMainHand =
                client.player.getMainHandStack().copy();

        visualOffHand =
                client.player.getOffHandStack().copy();

        active = true;
    }

    public static void end() {
        clear();
    }

    public static boolean isActive() {
        return active;
    }

    public static ItemStack getVisualMainHand() {
        return visualMainHand;
    }

    public static ItemStack getVisualOffHand() {
        return visualOffHand;
    }

    private static void clear() {
        active = false;
        visualMainHand = ItemStack.EMPTY;
        visualOffHand = ItemStack.EMPTY;
    }
}
