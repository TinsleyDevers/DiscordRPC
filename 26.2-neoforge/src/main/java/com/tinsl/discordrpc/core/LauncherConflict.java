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

    private LauncherConflict() {}

    /**
     * Runs detection once and caches the result. Call after client startup so
     * launcher-injected threads have had a chance to appear.
     */
    public static Launcher detect(Path gameDir) {
        Launcher result = detected;
        if (result == null) {
            result = runDetection(gameDir);
            detected = result;
        }
        return result;
    }

    /** Last detection result without re-running (NONE if never run). */
    public static Launcher current() {
        Launcher result = detected;
        return result != null ? result : Launcher.NONE;
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

        // Environment the launcher exports to the game process.
        try {
            for (String key : System.getenv().keySet()) {
                String upper = key.toUpperCase(Locale.ROOT);
                if (upper.contains("THESEUS") || upper.contains("MODRINTH")) {
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
