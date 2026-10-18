package com.tinsl.discordrpc;

import com.tinsl.discordrpc.client.ClientHandler;
import com.tinsl.discordrpc.config.ModConfig;
import com.tinsl.discordrpc.core.LauncherConflict;
import com.tinsl.discordrpc.core.RPCManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class DiscordRPCMod implements ClientModInitializer {
    public static final String MOD_ID = "discordrpc";
    public static final Logger LOGGER = LogManager.getLogger("DiscordPresence");

    private static DiscordRPCMod instance;
    private ModConfig modConfig;
    private RPCManager rpcManager;
    private ClientHandler clientHandler;

    @Override
    public void onInitializeClient() {
        instance = this;
        LOGGER.info("Initializing Discord Rich Presence...");

        modConfig = new ModConfig(FabricLoader.getInstance().getConfigDir());
        modConfig.loadProfiles();

        rpcManager = new RPCManager(modConfig);
        clientHandler = new ClientHandler(rpcManager, modConfig);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (rpcManager != null) rpcManager.shutdown();
        }, "DiscordPresence-Shutdown"));

        LauncherConflict.Launcher launcher = LauncherConflict.detect(FabricLoader.getInstance().getGameDir());
        if (launcher != LauncherConflict.Launcher.NONE) {
            LOGGER.warn("Detected {} - its built-in Discord Rich Presence will hide this mod's presence "
                    + "until it is disabled in the launcher settings.", launcher.displayName());
        }

        if (modConfig.isEnabled()) rpcManager.connect();
    }

    public static DiscordRPCMod getInstance() { return instance; }
    public ModConfig getModConfig() { return modConfig; }
    public RPCManager getRpcManager() { return rpcManager; }
    public ClientHandler getClientHandler() { return clientHandler; }
}
