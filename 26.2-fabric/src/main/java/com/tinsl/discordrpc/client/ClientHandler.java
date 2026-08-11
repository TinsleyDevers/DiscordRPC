package com.tinsl.discordrpc.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.tinsl.discordrpc.DiscordRPCMod;
import com.tinsl.discordrpc.config.ModConfig;
import com.tinsl.discordrpc.core.RPCManager;
import com.tinsl.discordrpc.gui.ConfigScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Fabric-side glue: registers the keybind and forwards client ticks to the
 * loader-neutral {@link PresenceTicker}.
 */
public class ClientHandler {
    private static final KeyMapping.Category KEY_CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(DiscordRPCMod.MOD_ID, "general"));

    private static KeyMapping openConfigKey;

    private final PresenceTicker ticker;

    public ClientHandler(RPCManager rpcManager, ModConfig config) {
        this.ticker = new PresenceTicker(rpcManager, config);

        openConfigKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "discordrpc.key.open_config",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F6,
                KEY_CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (openConfigKey.consumeClick()) {
                mc.gui.setScreen(new ConfigScreen(mc.gui.screen()));
            }
            ticker.tick(mc);
        });
    }

    public static KeyMapping getOpenConfigKey() {
        return openConfigKey;
    }
}
