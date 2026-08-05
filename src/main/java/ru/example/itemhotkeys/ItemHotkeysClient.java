package ru.example.itemhotkeys;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public final class ItemHotkeysClient implements ClientModInitializer {
    private static final String CATEGORY =
            "category.itemhotkeys.binds";

    private static KeyBinding enderTrapKey;
    private static KeyBinding trapKey;
    private static KeyBinding livalkaKey;
    private static KeyBinding antiFlyKey;

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
         * Слушаем серверные игровые сообщения.
         * Они всё равно продолжают отображаться в чате
         * или action bar.
         */
        ClientReceiveMessageEvents.GAME.register(
                (message, overlay) ->
                        ActionController.onServerMessage(message)
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
        ActionController.tick(client);

        if (!ActionController.canAcceptInput(client)) {
            clearAllKeyQueues();
            return;
        }

        /*
         * За один игровой тик запускается максимум
         * одно действие.
         */
        if (enderTrapKey.wasPressed()) {
            clearAllKeyQueues();

            RegularItemHandler.start(
                    client,
                    ItemNames.ENDER_TRAP,
                    "Эндер Ловушка"
            );

            return;
        }

        if (trapKey.wasPressed()) {
            clearAllKeyQueues();

            RegularItemHandler.startTrap(
                    client
            );

            return;
        }

        if (livalkaKey.wasPressed()) {
            clearAllKeyQueues();

            RegularItemHandler.start(
                    client,
                    ItemNames.LIVALKA,
                    "Ливалка"
            );

            return;
        }

        if (antiFlyKey.wasPressed()) {
            clearAllKeyQueues();

            AntiFlyHandler.start(
                    client,
                    ItemNames.ANTI_FLY,
                    "Анти-Флай"
            );
        }
    }

    private static void clearAllKeyQueues() {
        clearKeyQueue(enderTrapKey);
        clearKeyQueue(trapKey);
        clearKeyQueue(livalkaKey);
        clearKeyQueue(antiFlyKey);
    }

    private static void clearKeyQueue(
            KeyBinding keyBinding
    ) {
        while (keyBinding.wasPressed()) {
            // Удаляем повторные нажатия из очереди.
        }
    }

    public static void showMessage(
            MinecraftClient client,
            String message
    ) {
        if (client.player == null) {
            return;
        }

        client.player.sendMessage(
                Text.literal(
                        "§7[§dItem Hotkeys§7] " + message
                ),
                true
        );
    }
}
