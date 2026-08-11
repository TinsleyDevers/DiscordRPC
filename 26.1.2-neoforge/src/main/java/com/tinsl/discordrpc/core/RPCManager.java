package com.tinsl.discordrpc.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tinsl.discordrpc.DiscordRPCMod;
import com.tinsl.discordrpc.config.ModConfig;
import com.tinsl.discordrpc.config.RichPresenceProfile;
import net.minecraft.client.Minecraft;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Owns the IPC connection and the periodic presence updates. All Discord I/O
 * happens on a single daemon worker thread; the game thread only flips state
 * (context, server, dimension) and pokes {@link #forceUpdate()}.
 */
public class RPCManager {
    private final ModConfig config;
    private final DiscordIPC ipc;
    private PlaceholderEngine placeholderEngine;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> updateTask;
    private ScheduledFuture<?> reconnectTask;
    private int reconnectAttempts = 0;

    private long sessionStartTime;
    private RichPresenceProfile.ContextType currentContext = RichPresenceProfile.ContextType.MAIN_MENU;
    private String currentServerIp = "";
    /** Full dimension id (e.g. "minecraft:the_nether") - drives dimension-override resolution. */
    private String currentDimensionId = "";
    /** Key from {@link RichPresenceProfile.ScreenContext#key} - drives screen-override resolution. */
    private String currentScreenKey = "";
    private long lastActivityTime;
    private boolean isAfk = false;

    public RPCManager(ModConfig config) {
        this.config = config;
        this.ipc = new DiscordIPC();
        this.sessionStartTime = System.currentTimeMillis() / 1000;
        this.lastActivityTime = System.currentTimeMillis();
    }

    public void connect() {
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "DiscordPresence-Worker");
                t.setDaemon(true);
                return t;
            });
        }

        cancelReconnect();
        reconnectAttempts = 0;
        scheduler.execute(this::attemptConnect);
    }

    private void attemptConnect() {
        if (!config.isEnabled()) return;
        if (ipc.connect(ModConfig.APPLICATION_ID)) {
            reconnectAttempts = 0;
            sessionStartTime = System.currentTimeMillis() / 1000;
            startUpdateLoop();
        } else if (config.isAutoReconnect()) {
            scheduleReconnect();
        }
    }

    public void disconnect() {
        stopUpdateLoop();
        cancelReconnect();
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.execute(() -> {
                ipc.clearActivity();
                ipc.close();
            });
        }
    }

    /** Called from the JVM shutdown hook - synchronous, best effort. */
    public void shutdown() {
        try {
            ipc.clearActivity();
            ipc.close();
        } catch (Exception ignored) {}

        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
        }
    }

    private void startUpdateLoop() {
        stopUpdateLoop();
        updateTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                updatePresence();
            } catch (Exception e) {
                DiscordRPCMod.LOGGER.error("Error updating presence", e);
                handleConnectionLost();
            }
        }, 0, config.getUpdateInterval(), TimeUnit.SECONDS);
    }

    private void stopUpdateLoop() {
        if (updateTask != null) {
            updateTask.cancel(false);
            updateTask = null;
        }
    }

    /**
     * Reconnect with exponential backoff: 2s, 4s, 8s .. capped at the
     * configured reconnect delay (so quick Discord restarts recover fast
     * without hammering the pipe when Discord is closed for good).
     */
    private void scheduleReconnect() {
        cancelReconnect();
        int cap = Math.max(config.getReconnectDelay(), 8);
        int delay = Math.min(cap, 2 << Math.min(reconnectAttempts, 5));
        reconnectTask = scheduler.schedule(() -> {
            reconnectAttempts++;
            attemptConnect();
        }, delay, TimeUnit.SECONDS);
    }

    private void cancelReconnect() {
        if (reconnectTask != null) {
            reconnectTask.cancel(false);
            reconnectTask = null;
        }
    }

    private void handleConnectionLost() {
        stopUpdateLoop();
        ipc.close();
        if (config.isAutoReconnect()) {
            scheduleReconnect();
        }
    }

    /**
     * Checks connection health and triggers a reconnect if needed.
     * Safe to call from the game thread - the work happens on the worker.
     */
    public void ensureConnected() {
        if (!config.isEnabled()) return;
        if (ipc.isConnected()) return;
        if (config.isAutoReconnect() && (reconnectTask == null || reconnectTask.isDone())) {
            connect();
        }
    }

    public void updatePresence() {
        if (!config.isEnabled()) return;
        if (!ipc.isConnected()) {
            if (config.isAutoReconnect() && (reconnectTask == null || reconnectTask.isDone())) {
                scheduleReconnect();
            }
            return;
        }

        if (placeholderEngine == null) {
            placeholderEngine = new PlaceholderEngine(config);
        }

        checkAfkStatus();

        RichPresenceProfile.ContextType ctx = currentContext;
        if (isAfk && config.isAfkDetection()) {
            ctx = RichPresenceProfile.ContextType.AFK;
        }

        RichPresenceProfile profile = config.getBestProfile(ctx, currentServerIp);
        if (profile == null) return;

        RichPresenceProfile.Override ov = pickActiveOverride(profile, ctx);
        RichPresenceProfile resolved = ov != null ? profile.resolveWith(ov) : profile;

        ipc.setActivity(buildActivity(resolved));
    }

    /**
     * Returns the override to layer on top of {@code base}, or null for the
     * plain base profile. Main-menu profiles use screen overrides keyed by the
     * active menu screen; in-world profiles use dimension overrides.
     */
    private RichPresenceProfile.Override pickActiveOverride(RichPresenceProfile base, RichPresenceProfile.ContextType ctx) {
        if (ctx == RichPresenceProfile.ContextType.MAIN_MENU) {
            if (currentScreenKey == null || currentScreenKey.isEmpty()) return null;
            return base.getScreenOverrides().get(currentScreenKey);
        }
        if (ctx == RichPresenceProfile.ContextType.SINGLEPLAYER
                || ctx == RichPresenceProfile.ContextType.MULTIPLAYER
                || ctx == RichPresenceProfile.ContextType.SPECIFIC_SERVER) {
            if (currentDimensionId == null || currentDimensionId.isEmpty()) return null;
            return base.getDimensionOverrides().get(currentDimensionId);
        }
        return null;
    }

    private JsonObject buildActivity(RichPresenceProfile profile) {
        JsonObject activity = new JsonObject();

        String details = placeholderEngine.resolve(profile.getDetails());
        if (!details.isEmpty()) {
            activity.addProperty("details", truncate(details, 128));
        }

        String state = placeholderEngine.resolve(profile.getState());
        if (!state.isEmpty()) {
            activity.addProperty("state", truncate(state, 128));
        }

        if (profile.getTimestampMode() != RichPresenceProfile.TimestampMode.NONE) {
            JsonObject timestamps = new JsonObject();
            if (profile.getTimestampMode() == RichPresenceProfile.TimestampMode.ELAPSED) {
                timestamps.addProperty("start", sessionStartTime);
            } else if (profile.getTimestampMode() == RichPresenceProfile.TimestampMode.LOCAL) {
                timestamps.addProperty("start", System.currentTimeMillis() / 1000);
            }
            activity.add("timestamps", timestamps);
        }

        JsonObject assets = new JsonObject();
        boolean hasAssets = false;

        String largeKey = profile.getLargeImageKey();
        if (largeKey != null && !largeKey.isEmpty()) {
            assets.addProperty("large_image", largeKey);
            hasAssets = true;
            String largeText = placeholderEngine.resolve(profile.getLargeImageText());
            if (!largeText.isEmpty()) {
                assets.addProperty("large_text", truncate(largeText, 128));
            }
        }

        String smallKey = profile.getSmallImageKey();
        if (smallKey != null && !smallKey.isEmpty()) {
            assets.addProperty("small_image", smallKey);
            hasAssets = true;
            String smallText = placeholderEngine.resolve(profile.getSmallImageText());
            if (!smallText.isEmpty()) {
                assets.addProperty("small_text", truncate(smallText, 128));
            }
        }

        if (hasAssets) {
            activity.add("assets", assets);
        }

        JsonArray buttons = new JsonArray();
        addButton(buttons, profile.getButton1Label(), profile.getButton1Url());
        addButton(buttons, profile.getButton2Label(), profile.getButton2Url());
        if (!buttons.isEmpty()) {
            activity.add("buttons", buttons);
        }

        if (profile.isShowPartySize() && currentContext == RichPresenceProfile.ContextType.MULTIPLAYER) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getConnection() != null) {
                int online = mc.getConnection().getOnlinePlayers().size();
                int max = mc.getSingleplayerServer() != null
                        ? mc.getSingleplayerServer().getMaxPlayers()
                        : Math.max(online, 1);
                JsonObject party = new JsonObject();
                party.addProperty("id", "discordrpc_party");
                JsonArray size = new JsonArray();
                size.add(online);
                size.add(max);
                party.add("size", size);
                activity.add("party", party);
            }
        }

        return activity;
    }

    private static void addButton(JsonArray buttons, String label, String url) {
        if (label != null && !label.isEmpty() && url != null && !url.isEmpty()) {
            JsonObject btn = new JsonObject();
            btn.addProperty("label", truncate(label, 32));
            btn.addProperty("url", url);
            buttons.add(btn);
        }
    }

    private void checkAfkStatus() {
        if (!config.isAfkDetection()) {
            isAfk = false;
            return;
        }
        long now = System.currentTimeMillis();
        isAfk = (now - lastActivityTime) > (config.getAfkTimeout() * 1000L);
    }

    public void onPlayerActivity() {
        lastActivityTime = System.currentTimeMillis();
        isAfk = false;
    }

    public void setContext(RichPresenceProfile.ContextType context) {
        this.currentContext = context;
    }

    public void setCurrentServerIp(String ip) {
        this.currentServerIp = ip != null ? ip : "";
    }

    /** Sets the active dimension id (e.g. "minecraft:the_nether") used for per-dimension overrides. */
    public void setCurrentDimensionId(String id) {
        this.currentDimensionId = id != null ? id : "";
    }

    /** Sets the active main-menu screen key used for per-screen overrides. */
    public void setCurrentScreenKey(String key) {
        this.currentScreenKey = key != null ? key : "";
    }

    public String getCurrentDimensionId() { return currentDimensionId; }
    public String getCurrentScreenKey() { return currentScreenKey; }

    public void resetSessionTime() {
        this.sessionStartTime = System.currentTimeMillis() / 1000;
    }

    public DiscordIPC.State getConnectionState() {
        return ipc.getState();
    }

    public boolean isConnected() {
        return ipc.isConnected();
    }

    /** Display name of the connected Discord account, or "" when unknown. */
    public String getDiscordUser() {
        return ipc.getDiscordUser();
    }

    /**
     * Submits an immediate presence update without touching the connection.
     * Safe to call even if the update loop is already running.
     */
    public void forceUpdate() {
        if (scheduler != null && !scheduler.isShutdown() && ipc.isConnected()) {
            scheduler.execute(() -> {
                try {
                    updatePresence();
                } catch (Exception e) {
                    DiscordRPCMod.LOGGER.error("Error in forced presence update", e);
                }
            });
        }
    }

    /**
     * Restarts the periodic update loop (e.g. after the interval changed in
     * settings). Does nothing if not currently connected.
     */
    public void restartUpdateLoop() {
        if (ipc.isConnected() && scheduler != null && !scheduler.isShutdown()) {
            startUpdateLoop();
        }
    }

    public RichPresenceProfile.ContextType getCurrentContext() {
        return currentContext;
    }

    private static String truncate(String str, int maxLen) {
        return str.length() <= maxLen ? str : str.substring(0, maxLen - 3) + "...";
    }
}
