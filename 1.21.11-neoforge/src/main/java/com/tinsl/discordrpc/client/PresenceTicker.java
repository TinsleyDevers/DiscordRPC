package com.tinsl.discordrpc.client;

import com.tinsl.discordrpc.DiscordRPCMod;
import com.tinsl.discordrpc.config.ModConfig;
import com.tinsl.discordrpc.config.RichPresenceProfile;
import com.tinsl.discordrpc.core.LauncherConflict;
import com.tinsl.discordrpc.core.RPCManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import java.lang.ref.WeakReference;

/**
 * Per-tick presence bookkeeping: tracks which screen/dimension/server the
 * player is in and feeds it to the {@link RPCManager}. Uses only vanilla
 * classes and plain polling (no loader events), so the same file runs on
 * Forge, NeoForge and Fabric - each loader just calls {@link #tick} once per
 * client tick.
 */
public class PresenceTicker {
    private final RPCManager rpc;
    private final ModConfig config;

    private int tickCounter = 0;
    private boolean wasInWorld = false;
    private String lastDimensionId = "";
    private String lastScreenKey = "";
    /** Weak so a closed screen (and the world it references) can be collected. */
    private WeakReference<Screen> lastScreen = new WeakReference<>(null);
    private boolean conflictNoticeSent = false;

    // Previous-frame input state, used as the AFK activity signal.
    private double lastMouseX, lastMouseY;
    private float lastYaw, lastPitch;
    private double lastPlayerX, lastPlayerZ;

    public PresenceTicker(RPCManager rpc, ModConfig config) {
        this.rpc = rpc;
        this.config = config;
    }

    /** Call once at the end of every client tick. */
    public void tick(Minecraft mc) {
        detectPlayerActivity(mc);
        trackScreen(mc);

        tickCounter++;
        if (tickCounter >= 20) {
            tickCounter = 0;
            updateContext(mc);
        }
    }

    /** Marks the player as active whenever the mouse or the player itself moved. */
    private void detectPlayerActivity(Minecraft mc) {
        double mouseX = mc.mouseHandler.xpos();
        double mouseY = mc.mouseHandler.ypos();
        boolean active = mouseX != lastMouseX || mouseY != lastMouseY;
        lastMouseX = mouseX;
        lastMouseY = mouseY;

        LocalPlayer player = mc.player;
        if (player != null) {
            if (player.getYRot() != lastYaw || player.getXRot() != lastPitch
                    || player.getX() != lastPlayerX || player.getZ() != lastPlayerZ) {
                active = true;
            }
            lastYaw = player.getYRot();
            lastPitch = player.getXRot();
            lastPlayerX = player.getX();
            lastPlayerZ = player.getZ();
        }

        if (active) {
            rpc.onPlayerActivity();
        }
    }

    /** Watches for screen changes to drive per-screen overrides in the main menu. */
    private void trackScreen(Minecraft mc) {
        Screen screen = mc.screen;
        if (screen == lastScreen.get()) return;
        lastScreen = new WeakReference<>(screen);
        rpc.onPlayerActivity();

        String key = mapScreenToKey(screen);
        if (!key.equals(lastScreenKey)) {
            lastScreenKey = key;
            rpc.setCurrentScreenKey(key);
            rpc.forceUpdate();
        }

        if (screen instanceof TitleScreen) {
            rpc.setContext(RichPresenceProfile.ContextType.MAIN_MENU);
            rpc.setCurrentServerIp("");
            rpc.setCurrentDimensionId("");
            wasInWorld = false;
            rpc.ensureConnected();
        }
    }

    private void updateContext(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            if (wasInWorld) {
                wasInWorld = false;
                rpc.setContext(RichPresenceProfile.ContextType.MAIN_MENU);
                rpc.setCurrentServerIp("");
                rpc.setCurrentDimensionId("");
                lastDimensionId = "";
            }
            return;
        }

        boolean justJoined = !wasInWorld;
        if (justJoined) {
            wasInWorld = true;
            rpc.resetSessionTime();

            if (mc.getSingleplayerServer() != null) {
                rpc.setCurrentServerIp("");
            } else {
                ServerData serverData = mc.getCurrentServer();
                if (serverData != null) rpc.setCurrentServerIp(serverData.ip);
            }
            rpc.setCurrentScreenKey("");
            lastScreenKey = "";
            rpc.ensureConnected();
            maybeWarnAboutLauncher(mc);
        }

        String dimId = mc.level.dimension().identifier().toString();
        if (!dimId.equals(lastDimensionId)) {
            lastDimensionId = dimId;
            rpc.setCurrentDimensionId(dimId);
            rpc.forceUpdate();
        }

        RichPresenceProfile.ContextType baseContext = mc.getSingleplayerServer() != null
                ? RichPresenceProfile.ContextType.SINGLEPLAYER
                : RichPresenceProfile.ContextType.MULTIPLAYER;
        rpc.setContext(baseContext);
        updatePartyInfo(mc, baseContext);
    }

    /**
     * Feeds current player counts to the manager. The real server capacity is
     * only known from the server-list ping; without it we report max 0 so the
     * party is omitted instead of rendering as a permanently full "(n of n)".
     */
    private void updatePartyInfo(Minecraft mc, RichPresenceProfile.ContextType baseContext) {
        if (baseContext != RichPresenceProfile.ContextType.MULTIPLAYER) {
            rpc.setPartyInfo(0, 0);
            return;
        }
        ClientPacketListener connection = mc.getConnection();
        int online = connection != null ? connection.getOnlinePlayers().size() : 0;
        ServerData data = mc.getCurrentServer();
        int max = data != null && data.players != null ? data.players.max() : 0;
        rpc.setPartyInfo(online, max);
    }

    /**
     * One-time chat notice when a launcher's own Rich Presence is going to
     * hide everything this mod sends (see {@link LauncherConflict}).
     */
    private void maybeWarnAboutLauncher(Minecraft mc) {
        if (conflictNoticeSent || config.isLauncherWarningDismissed() || !config.isEnabled()) return;
        LauncherConflict.Launcher launcher = LauncherConflict.current();
        if (launcher == LauncherConflict.Launcher.NONE) return;

        conflictNoticeSent = true;
        mc.gui.getChat().addMessage(Component.translatable("discordrpc.conflict.chat",
                launcher.displayName()).withStyle(ChatFormatting.GOLD));
        DiscordRPCMod.LOGGER.warn("{} has its own Discord Rich Presence enabled - it will hide this mod's presence. "
                + "Disable it in the launcher settings, then fully close and reopen the launcher.", launcher.displayName());
    }

    /**
     * Maps a Minecraft screen to a {@link RichPresenceProfile.ScreenContext}
     * key. Uses class-name matching so this stays compatible across patches
     * that relocate or rename screen classes.
     */
    private static String mapScreenToKey(Screen screen) {
        if (screen == null) return "";
        if (screen instanceof TitleScreen) return RichPresenceProfile.ScreenContext.MAIN_MENU.key;

        String cn = screen.getClass().getName();
        if (cn.contains("JoinMultiplayerScreen") || cn.contains("MultiplayerScreen")) {
            return RichPresenceProfile.ScreenContext.SELECT_SERVER.key;
        }
        if (cn.contains("CreateWorldScreen") || cn.contains("WorldCreationScreen")
                || cn.contains("NewWorldScreen") || cn.contains("WorldOpenFlows")) {
            return RichPresenceProfile.ScreenContext.CREATE_WORLD.key;
        }
        if (cn.contains("SelectWorldScreen") || cn.contains("WorldSelectionScreen")
                || cn.contains("WorldSelectScreen")) {
            return RichPresenceProfile.ScreenContext.SELECT_WORLD.key;
        }
        if (cn.contains("RealmsMainScreen") || cn.contains("RealmsScreen")
                || cn.contains("RealmsBrowse") || cn.contains("RealmsList")) {
            return RichPresenceProfile.ScreenContext.REALMS.key;
        }
        if (cn.contains("LanguageSelectScreen") || cn.contains("LanguageScreen")
                || cn.contains("LanguageSettingsScreen")) {
            return RichPresenceProfile.ScreenContext.LANGUAGE.key;
        }
        if (cn.contains("OptionsScreen") || cn.contains("VideoSettings")
                || cn.contains("SoundSettings") || cn.contains("ControlsScreen")
                || cn.contains("AccessibilityOptions") || cn.contains("ChatOptions")
                || cn.contains("SkinCustomization")) {
            return RichPresenceProfile.ScreenContext.OPTIONS.key;
        }
        return "";
    }
}
