package com.tinsl.discordrpc;

import com.tinsl.discordrpc.client.ClientHandler;
import com.tinsl.discordrpc.config.ModConfig;
import com.tinsl.discordrpc.core.LauncherConflict;
import com.tinsl.discordrpc.core.RPCManager;
import com.tinsl.discordrpc.gui.ConfigScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(DiscordRPCMod.MOD_ID)
public class DiscordRPCMod {
    public static final String MOD_ID = "discordrpc";
    public static final Logger LOGGER = LogManager.getLogger("DiscordPresence");

    private static DiscordRPCMod instance;
    private final ModConfig modConfig;
    private RPCManager rpcManager;
    private ClientHandler clientHandler;

    public DiscordRPCMod(IEventBus modBus, ModContainer modContainer) {
        instance = this;
        modConfig = new ModConfig(FMLPaths.CONFIGDIR.get());

        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            modBus.addListener(this::onClientSetup);
            modBus.addListener(ClientHandler::onRegisterKeyMappings);

            // Adds the "Config" button to the entry in the mod list.
            modContainer.registerExtensionPoint(IConfigScreenFactory.class,
                    (IConfigScreenFactory) (mc, parent) -> new ConfigScreen(parent));
        }
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        LOGGER.info("Initializing Discord Rich Presence...");
        modConfig.loadProfiles();

        rpcManager = new RPCManager(modConfig);
        clientHandler = new ClientHandler(rpcManager, modConfig);

        NeoForge.EVENT_BUS.register(clientHandler);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (rpcManager != null) rpcManager.shutdown();
        }, "DiscordPresence-Shutdown"));

        LauncherConflict.Launcher launcher = LauncherConflict.detect(FMLPaths.GAMEDIR.get());
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
