package com.tinsl.discordrpc;

import com.tinsl.discordrpc.client.ClientHandler;
import com.tinsl.discordrpc.config.ModConfig;
import com.tinsl.discordrpc.core.LauncherConflict;
import com.tinsl.discordrpc.core.RPCManager;
import com.tinsl.discordrpc.gui.ConfigScreen;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
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

    public DiscordRPCMod() {
        instance = this;
        modConfig = new ModConfig(FMLPaths.CONFIGDIR.get());

        if (FMLEnvironment.dist.isClient()) {
            IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
            modBus.addListener(this::onClientSetup);
            modBus.addListener(ClientHandler::onRegisterKeyMappings);

            // Adds the "Config" button to the entry in the mod list.
            ModLoadingContext.get().registerExtensionPoint(
                    ConfigScreenHandler.ConfigScreenFactory.class,
                    () -> new ConfigScreenHandler.ConfigScreenFactory(
                            (minecraft, parentScreen) -> new ConfigScreen(parentScreen)
                    )
            );
        }
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        LOGGER.info("Initializing Discord Rich Presence...");
        modConfig.loadProfiles();

        rpcManager = new RPCManager(modConfig);
        clientHandler = new ClientHandler(rpcManager, modConfig);

        MinecraftForge.EVENT_BUS.register(clientHandler);

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
