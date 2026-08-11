package com.tinsl.discordrpc.core;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Detects launchers whose built-in Discord Rich Presence fights with ours.
 *
 * Discord displays the activity of whichever application connected to it
 * first. The Modrinth App connects as soon as it starts - long before any mod
 * loads - and additionally bootstraps a small RPC bridge inside the game JVM
 * (the "Theseus RPC Read thread"), so while its "Discord RPC" setting is on,
 * the presence this mod sends is accepted by Discord but never shown.
 *
 * That can't be fixed from inside the game process. What we can do is notice
 * the situation and tell the player exactly how to turn the launcher side off,
 * instead of leaving them wondering why the mod "doesn't work".
 */
public final class LauncherConflict {

    /** While the answer is NONE, re-run detection at most this often - the
     *  injected RPC thread can appear after client setup already ran. */
    private static final long RECHECK_INTERVAL_MS = 30_000;

    public enum Launcher {
        NONE(""),
        MODRINTH_APP("Modrinth App");

        private final String displayName;

        Launcher(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    private static volatile Launcher detected = null;
    private static volatile long lastCheckMs = 0;
    private static volatile Path gameDirCache = null;

    private LauncherConflict() {}

    /** Runs detection now (remembering the game dir for later rechecks). */
    public static Launcher detect(Path gameDir) {
        gameDirCache = gameDir;
        return recheck(true);
    }

    /**
     * Cached detection result. A NONE result is re-verified every
     * {@link #RECHECK_INTERVAL_MS} because launcher threads can spawn late;
     * a positive result is final for the session.
     */
    public static Launcher current() {
        return recheck(false);
    }

    private static synchronized Launcher recheck(boolean force) {
        Launcher result = detected;
        long now = System.currentTimeMillis();
        if (result == null || force
                || (result == Launcher.NONE && now - lastCheckMs > RECHECK_INTERVAL_MS)) {
            result = runDetection(gameDirCache);
            detected = result;
            lastCheckMs = now;
        }
        return result;
    }

    private static Launcher runDetection(Path gameDir) {
        // The Modrinth App injects its RPC bridge thread into this very JVM.
        try {
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                String name = thread.getName();
                if (name != null && name.toLowerCase(Locale.ROOT).contains("theseus")) {
                    return Launcher.MODRINTH_APP;
                }
            }
        } catch (Exception ignored) {}

        // Environment the launcher exports to the game process. Only THESEUS
        // is specific enough - matching "MODRINTH" here false-positives on
        // developers with MODRINTH_TOKEN etc. exported globally.
        try {
            for (String key : System.getenv().keySet()) {
                if (key.toUpperCase(Locale.ROOT).contains("THESEUS")) {
                    return Launcher.MODRINTH_APP;
                }
            }
        } catch (Exception ignored) {}

        // Instances live inside the launcher's data directory
        // (e.g. %APPDATA%/ModrinthApp/profiles/<name> on Windows).
        if (gameDir != null) {
            String path = gameDir.toAbsolutePath().toString().toLowerCase(Locale.ROOT);
            if (path.contains("modrinthapp") || path.contains("com.modrinth.theseus")) {
                return Launcher.MODRINTH_APP;
            }
        }

        return Launcher.NONE;
    }
}
