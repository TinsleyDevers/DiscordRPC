package com.tinsl.discordrpc.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.tinsl.discordrpc.config.ModConfig;
import com.tinsl.discordrpc.core.RPCManager;
import com.tinsl.discordrpc.gui.ConfigScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/**
 * NeoForge-side glue: registers the keybind and forwards client ticks to the
 * loader-neutral {@link PresenceTicker}.
 */
public class ClientHandler {
    private static final KeyMapping OPEN_CONFIG_KEY = new KeyMapping(
            "discordrpc.key.open_config",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F6,
            "key.categories.discordrpc"
    );

    private final PresenceTicker ticker;

    public ClientHandler(RPCManager rpcManager, ModConfig config) {
        this.ticker = new PresenceTicker(rpcManager, config);
    }

    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_CONFIG_KEY);
    }

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (OPEN_CONFIG_KEY.consumeClick()) {
            while (OPEN_CONFIG_KEY.consumeClick()) {} // drain queued presses
            if (!(mc.screen instanceof ConfigScreen)) {
                mc.setScreen(new ConfigScreen(mc.screen));
            }
        }
        ticker.tick(mc);
    }

    public static KeyMapping getOpenConfigKey() {
        return OPEN_CONFIG_KEY;
    }
}
