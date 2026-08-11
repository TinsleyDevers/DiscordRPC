package com.tinsl.discordrpc.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tinsl.discordrpc.DiscordRPCMod;
import com.tinsl.discordrpc.config.ModConfig;
import com.tinsl.discordrpc.config.RichPresenceProfile;

import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Owns the IPC connection and the periodic presence updates. All Discord I/O
 * and all connection lifecycle state live on a single daemon worker thread;
 * the game thread only writes the volatile context fields and submits work.
 *
 * Updates are deduplicated (an unchanged activity is never re-sent - Discord
 * keeps showing the last one) and rate limited to one send per
 * {@link #MIN_SEND_GAP_MS}, comfortably inside Discord's ~5 updates / 20s
 * budget no matter how fast screens or dimensions change.
 */
public class RPCManager {
    /** Minimum wall-clock gap between SET_ACTIVITY frames. */
    private static final long MIN_SEND_GAP_MS = 4000;

    private final ModConfig config;
    private final DiscordIPC ipc;
    private final ScheduledExecutorService scheduler;
    private PlaceholderEngine placeholderEngine;

    // ── Worker-thread-confined lifecycle state (never touched off-worker) ──
    private ScheduledFuture<?> updateTask;
    private ScheduledFuture<?> reconnectTask;
    private ScheduledFuture<?> pendingFlush;
    private int reconnectAttempts = 0;
    /** JSON of the last successfully sent activity ("" = cleared), null = nothing sent yet. */
    private String lastSentPayload = null;
    private long lastSendMs = 0;

    // ── Game-thread-written, worker-read context (volatile, no other sync) ──
    private volatile long sessionStartTime;
    private volatile RichPresenceProfile.ContextType currentContext = RichPresenceProfile.ContextType.MAIN_MENU;
    private volatile String currentServerIp = "";
    /** Full dimension id (e.g. "minecraft:the_nether") - drives dimension-override resolution. */
    private volatile String currentDimensionId = "";
    /** Key from {@link RichPresenceProfile.ScreenContext#key} - drives screen-override resolution. */
    private volatile String currentScreenKey = "";
    private volatile long lastActivityTime;
    private volatile boolean isAfk = false;
    /** Current player counts fed by the per-version ticker; max 0 = unknown, party omitted. */
    private volatile int partySize = 0;
    private volatile int partyMax = 0;

    public RPCManager(ModConfig config) {
        this.config = config;
        this.ipc = new DiscordIPC();
        this.sessionStartTime = System.currentTimeMillis() / 1000;
        this.lastActivityTime = System.currentTimeMillis();

        ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "DiscordPresence-Worker");
            t.setDaemon(true);
            return t;
        });
        // On shutdown(): drop queued reconnects/loops immediately so the hook
        // only waits for the in-flight task plus the final clear-and-close.
        ex.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        ex.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        ex.setRemoveOnCancelPolicy(true);
        this.scheduler = ex;
    }

    /** Starts (or restarts) connecting. Resets reconnect backoff - user intent. */
    public void connect() {
        submit(() -> {
            cancelReconnect();
            reconnectAttempts = 0;
            attemptConnect();
        });
    }

    private void attemptConnect() {
        if (!config.isEnabled()) return;
        if (ipc.connect(ModConfig.APPLICATION_ID)) {
            reconnectAttempts = 0;
            // Discord forgot our activity along with the old connection.
            lastSentPayload = null;
            lastSendMs = 0;
            startUpdateLoop();
        } else if (config.isAutoReconnect()) {
            scheduleReconnect();
        }
    }

    public void disconnect() {
        submit(() -> {
            stopUpdateLoop();
            cancelReconnect();
            ipc.clearActivity();
            ipc.close();
            lastSentPayload = null;
        });
    }

    /** Called from the JVM shutdown hook - synchronous, bounded, best effort. */
    public void shutdown() {
        try {
            if (!scheduler.isShutdown()) {
                scheduler.execute(() -> {
                    ipc.clearActivity();
                    ipc.close();
                });
                scheduler.shutdown();
                if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            }
        } catch (Exception ignored) {
            scheduler.shutdownNow();
        }
        // If the worker is stuck in a blocking pipe read it never saw the
        // interrupt; closing the pipe out from under it is what unsticks it.
        ipc.abort();
    }

    private void startUpdateLoop() {
        stopUpdateLoop();
        updateTask = scheduler.scheduleAtFixedRate(this::safeUpdate,
                0, config.getUpdateInterval(), TimeUnit.SECONDS);
    }

    private void stopUpdateLoop() {
        if (updateTask != null) {
            updateTask.cancel(false);
            updateTask = null;
        }
    }

    /**
     * Runs one presence update, keeping the connection unless the failure was
     * actual I/O. A logic error (e.g. a race against the game tearing down the
     * world mid-read) is logged and retried next interval instead of burning a
     * healthy connection.
     */
    private void safeUpdate() {
        try {
            updatePresence();
        } catch (Exception e) {
            if (isIoFailure(e)) {
                DiscordRPCMod.LOGGER.warn("Lost Discord connection: {}", e.getMessage());
                handleConnectionLost();
            } else {
                DiscordRPCMod.LOGGER.error("Error building presence update (connection kept)", e);
            }
        }
    }

    private static boolean isIoFailure(Throwable t) {
        while (t != null) {
            if (t instanceof IOException) return true;
            t = t.getCause();
        }
        return false;
    }

    /**
     * Reconnect with exponential backoff: 2s, 4s, 8s .. capped at the
     * configured reconnect delay (so quick Discord restarts recover fast
     * without hammering the pipe when Discord is closed for good).
     */
    private void scheduleReconnect() {
        cancelReconnect();
        int cap = Math.max(config.getReconnectDelay(), 5);
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
        lastSentPayload = null;
        if (config.isAutoReconnect()) {
            scheduleReconnect();
        }
    }

    /**
     * Checks connection health and triggers a reconnect if needed.
     * Safe to call from the game thread - the work happens on the worker.
     * Does not reset backoff (a user-facing {@link #connect()} does).
     */
    public void ensureConnected() {
        submit(() -> {
            if (!config.isEnabled() || ipc.isConnected()) return;
            if (reconnectTask != null && !reconnectTask.isDone()) return;
            attemptConnect();
        });
    }

    /** Worker thread only - the periodic loop and flushes land here. */
    private void updatePresence() {
        if (!config.isEnabled()) {
            sendActivity(null);
            return;
        }
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

        if (ctx == RichPresenceProfile.ContextType.MAIN_MENU && !config.isShowInMainMenu()) {
            sendActivity(null);
            return;
        }

        RichPresenceProfile profile = config.getBestProfile(ctx, currentServerIp);
        if (profile == null) {
            sendActivity(null);
            return;
        }

        RichPresenceProfile.Override ov = pickActiveOverride(profile, ctx);
        RichPresenceProfile resolved = ov != null ? profile.resolveWith(ov) : profile;

        sendActivity(buildActivity(resolved));
    }

    /**
     * Deduplicated, rate-limited send. Unchanged payloads are dropped; a
     * changed payload inside the rate window schedules exactly one flush that
     * rebuilds fresh state once the window opens.
     */
    private void sendActivity(JsonObject activity) {
        if (!ipc.isConnected()) return;
        String payload = activity == null ? "" : activity.toString();
        if (payload.equals(lastSentPayload)) return;
        if (activity == null && lastSentPayload == null) return;

        long now = System.currentTimeMillis();
        long wait = MIN_SEND_GAP_MS - (now - lastSendMs);
        if (wait > 0) {
            if (pendingFlush == null || pendingFlush.isDone()) {
                pendingFlush = scheduler.schedule(this::safeUpdate, wait, TimeUnit.MILLISECONDS);
            }
            return;
        }

        boolean ok = activity == null ? ipc.clearActivity() : ipc.setActivity(activity);
        if (ok) {
            lastSentPayload = payload;
            lastSendMs = now;
        } else if (ipc.isConnected()) {
            // Guard rail; setActivity currently only fails by disconnecting.
            DiscordRPCMod.LOGGER.debug("Activity send failed without disconnect");
        } else {
            handleConnectionLost();
        }
    }

    /**
     * Returns the override to layer on top of {@code base}, or null for the
     * plain base profile. Main-menu profiles use screen overrides keyed by the
     * active menu screen; in-world profiles use dimension overrides.
     */
    private RichPresenceProfile.Override pickActiveOverride(RichPresenceProfile base, RichPresenceProfile.ContextType ctx) {
        if (ctx == RichPresenceProfile.ContextType.MAIN_MENU) {
            String screenKey = currentScreenKey;
            if (screenKey == null || screenKey.isEmpty()) return null;
            return base.getScreenOverrides().get(screenKey);
        }
        if (ctx == RichPresenceProfile.ContextType.SINGLEPLAYER
                || ctx == RichPresenceProfile.ContextType.MULTIPLAYER
                || ctx == RichPresenceProfile.ContextType.SPECIFIC_SERVER) {
            String dimId = currentDimensionId;
            if (dimId == null || dimId.isEmpty()) return null;
            return base.getDimensionOverrides().get(dimId);
        }
        return null;
    }

    private JsonObject buildActivity(RichPresenceProfile profile) {
        JsonObject activity = new JsonObject();

        // Discord requires details/state to be 2..128 chars when present.
        String details = placeholderEngine.resolve(profile.getDetails());
        if (details.length() >= 2) {
            activity.addProperty("details", truncate(details, 128));
        }

        String state = placeholderEngine.resolve(profile.getState());
        if (state.length() >= 2) {
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
            if (largeText.length() >= 2) {
                assets.addProperty("large_text", truncate(largeText, 128));
            }
        }

        String smallKey = profile.getSmallImageKey();
        if (smallKey != null && !smallKey.isEmpty()) {
            assets.addProperty("small_image", smallKey);
            hasAssets = true;
            String smallText = placeholderEngine.resolve(profile.getSmallImageText());
            if (smallText.length() >= 2) {
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
            int size = partySize;
            int max = partyMax;
            // Only send a party when the ticker gave us a real max - Discord
            // renders "(size of max)", and faking max as size reads as a
            // permanently full server.
            if (size > 0 && max >= size) {
                JsonObject party = new JsonObject();
                party.addProperty("id", "discordrpc_party");
                JsonArray sizeArr = new JsonArray();
                sizeArr.add(size);
                sizeArr.add(max);
                party.add("size", sizeArr);
                activity.add("party", party);
            }
        }

        return activity;
    }

    private static void addButton(JsonArray buttons, String label, String url) {
        if (label == null || label.isEmpty() || url == null || url.isEmpty()) return;
        String u = url.trim();
        // Discord only opens http(s) button URLs, max 512 chars.
        if (!(u.startsWith("https://") || u.startsWith("http://")) || u.length() > 512) return;
        JsonObject btn = new JsonObject();
        btn.addProperty("label", truncate(label, 32));
        btn.addProperty("url", u);
        buttons.add(btn);
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

    /**
     * Player counts for the party display, fed by the per-version ticker.
     * Pass max = 0 when the real server capacity is unknown - the party is
     * then omitted rather than shown as "(n of n)".
     */
    public void setPartyInfo(int size, int max) {
        this.partySize = Math.max(size, 0);
        this.partyMax = Math.max(max, 0);
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
     * Safe to call from any thread and at any rate - dedup and the rate
     * limiter decide whether anything actually reaches Discord.
     */
    public void forceUpdate() {
        if (ipc.isConnected()) {
            submit(this::safeUpdate);
        }
    }

    /**
     * Restarts the periodic update loop (e.g. after the interval changed in
     * settings). Does nothing if not currently connected.
     */
    public void restartUpdateLoop() {
        submit(() -> {
            if (ipc.isConnected()) startUpdateLoop();
        });
    }

    public RichPresenceProfile.ContextType getCurrentContext() {
        return currentContext;
    }

    private void submit(Runnable task) {
        if (!scheduler.isShutdown()) {
            try {
                scheduler.execute(task);
            } catch (Exception ignored) {} // racing shutdown
        }
    }

    /** Length-capped, never splits a surrogate pair at the cut point. */
    private static String truncate(String str, int maxLen) {
        if (str.length() <= maxLen) return str;
        int cut = maxLen - 3;
        if (cut > 0 && Character.isHighSurrogate(str.charAt(cut - 1))) cut--;
        return str.substring(0, cut) + "...";
    }
}
