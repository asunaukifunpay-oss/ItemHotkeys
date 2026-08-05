package ru.example.itemhotkeys;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.function.Predicate;

public final class AntiFlyHandler {

    private AntiFlyHandler() {
    }

    public static void start(
            MinecraftClient client,
            Predicate<ItemStack> matcher,
            String displayName
    ) {
        if (client != null) {
            ItemHotkeysClient.showMessage(
                    client,
                    "§eAntiFlyHandler временно отключён."
            );
        }
    }

    public static void tick(MinecraftClient client) {
        // Заглушка
    }

    public static void onServerMessage(Text message) {
        // Заглушка
    }

    public static void recover(MinecraftClient client) {
        // Заглушка
    }
}
