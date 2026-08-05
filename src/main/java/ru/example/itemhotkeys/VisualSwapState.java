package ru.example.itemhotkeys;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;

public final class VisualSwapState {
    private static boolean active;

    private static ItemStack savedMainHand = ItemStack.EMPTY;
    private static ItemStack savedOffHand = ItemStack.EMPTY;

    private VisualSwapState() {
    }

    public static void begin() {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client.player == null) {
            end();
            return;
        }

        savedMainHand = client.player
                .getMainHandStack()
                .copy();

        savedOffHand = client.player
                .getOffHandStack()
                .copy();

        active = true;
    }

    public static void end() {
        active = false;
        savedMainHand = ItemStack.EMPTY;
        savedOffHand = ItemStack.EMPTY;
    }

    public static boolean isActive() {
        return active;
    }

    public static ItemStack getSavedMainHand() {
        return savedMainHand;
    }

    public static ItemStack getSavedOffHand() {
        return savedOffHand;
    }
}
