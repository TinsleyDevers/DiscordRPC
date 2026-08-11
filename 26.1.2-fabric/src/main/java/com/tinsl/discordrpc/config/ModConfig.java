package com.tinsl.discordrpc.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tinsl.discordrpc.DiscordRPCMod;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Mod settings + profile storage, persisted as pretty-printed JSON under
 * {@code config/discordrpc/}. Only touches java.nio and Gson so the same file
 * works on every loader - the loader-specific entrypoint supplies its config
 * directory to the constructor.
 *
 * Durability rules: writes are atomic (temp file + move) so a crash never
 * leaves a half-written config, and a file that fails to parse is preserved
 * as {@code config.json.corrupt} instead of being overwritten with defaults.
 */
public class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Bumped when the JSON layout changes incompatibly; migrations key off it. */
    private static final int CONFIG_VERSION = 1;

    /** Locked - not configurable by the user. */
    public static final String APPLICATION_ID = "1493462784099356792";

    private boolean enabled = true;
    private int updateInterval = 5;
    private boolean autoReconnect = true;
    private int reconnectDelay = 15;
    private boolean showInMainMenu = true;
    private boolean afkDetection = true;
    private int afkTimeout = 300;
    private boolean hideServerIp = false;
    private boolean hideCoordinates = false;
    private boolean launcherWarningDismissed = false;

    /** Copy-on-write: the GUI mutates on the render thread while the RPC worker iterates. */
    private final List<RichPresenceProfile> profiles = new CopyOnWriteArrayList<>();
    private int activeProfileIndex = 0;

    private final Path configDir;
    private final Path configFile;
    private final Path imagesDir;
    private final Path profilesDir;

    /** @param loaderConfigDir the loader's config directory (e.g. {@code .minecraft/config}) */
    public ModConfig(Path loaderConfigDir) {
        configDir = loaderConfigDir.resolve("discordrpc");
        configFile = configDir.resolve("config.json");
        imagesDir = configDir.resolve("images");
        profilesDir = configDir.resolve("profiles");
    }

    /**
     * Copies images bundled in the mod JAR into the user-facing
     * config/discordrpc/images/ folder so they appear in the image picker.
     * Never overwrites files the user already has.
     */
    public void copyBundledImages() {
        try {
            Files.createDirectories(imagesDir);

            JsonArray fileList;
            try (InputStream manifestStream = ModConfig.class.getResourceAsStream(
                    "/assets/discordrpc/bundled_images.json")) {
                if (manifestStream == null) {
                    DiscordRPCMod.LOGGER.warn("bundled_images.json not found in JAR");
                    return;
                }
                String manifestJson = new String(manifestStream.readAllBytes(), StandardCharsets.UTF_8);
                fileList = JsonParser.parseString(manifestJson).getAsJsonArray();
            }

            int copied = 0;
            for (JsonElement el : fileList) {
                // Strip any path components and normalize case: jar entries are
                // lowercase and jar lookups are case-sensitive even on Windows.
                String filename = Path.of(el.getAsString()).getFileName().toString()
                        .toLowerCase(Locale.ROOT);
                Path target = imagesDir.resolve(filename);
                if (Files.exists(target)) continue;

                try (InputStream imgStream = ModConfig.class.getResourceAsStream(
                        "/assets/discordrpc/images/" + filename)) {
                    if (imgStream == null) {
                        DiscordRPCMod.LOGGER.warn("Bundled image not found: {}", filename);
                        continue;
                    }
                    Files.copy(imgStream, target, StandardCopyOption.REPLACE_EXISTING);
                    copied++;
                }
            }

            if (copied > 0) {
                DiscordRPCMod.LOGGER.info("Copied {} bundled images to config folder", copied);
            }
        } catch (Exception e) {
            DiscordRPCMod.LOGGER.error("Failed to copy bundled images", e);
        }
    }

    public void loadProfiles() {
        try {
            Files.createDirectories(configDir);
            Files.createDirectories(imagesDir);
        } catch (IOException e) {
            DiscordRPCMod.LOGGER.error("Failed to create config directories", e);
        }
        copyBundledImages();

        boolean safeToSaveDefaults = true;
        if (Files.exists(configFile)) {
            JsonObject root = null;
            try {
                String content = Files.readString(configFile, StandardCharsets.UTF_8);
                root = JsonParser.parseString(content).getAsJsonObject();
            } catch (Exception e) {
                DiscordRPCMod.LOGGER.error(
                        "config.json is corrupt - preserving it as config.json.corrupt and starting from defaults", e);
                safeToSaveDefaults = backUpCorruptConfig();
            }

            if (root != null) {
                // Each field is read independently so one bad value can never
                // discard the rest of the file (especially not the profiles).
                enabled = readBool(root, "enabled", enabled);
                setUpdateInterval(readInt(root, "updateInterval", updateInterval));
                autoReconnect = readBool(root, "autoReconnect", autoReconnect);
                setReconnectDelay(readInt(root, "reconnectDelay", reconnectDelay));
                showInMainMenu = readBool(root, "showInMainMenu", showInMainMenu);
                afkDetection = readBool(root, "afkDetection", afkDetection);
                setAfkTimeout(readInt(root, "afkTimeout", afkTimeout));
                hideServerIp = readBool(root, "hideServerIp", hideServerIp);
                hideCoordinates = readBool(root, "hideCoordinates", hideCoordinates);
                launcherWarningDismissed = readBool(root, "launcherWarningDismissed", launcherWarningDismissed);
                activeProfileIndex = readInt(root, "activeProfileIndex", activeProfileIndex);

                profiles.clear();
                if (root.has("profiles") && root.get("profiles").isJsonArray()) {
                    for (JsonElement el : root.getAsJsonArray("profiles")) {
                        try {
                            profiles.add(RichPresenceProfile.fromJson(el.getAsJsonObject()));
                        } catch (Exception e) {
                            DiscordRPCMod.LOGGER.warn("Skipping malformed profile entry: {}", e.toString());
                        }
                    }
                }
                DiscordRPCMod.LOGGER.info("Loaded {} profiles from config", profiles.size());
            }
        }

        if (profiles.isEmpty()) {
            addDefaultProfiles();
            if (safeToSaveDefaults) save();
        }
    }

    private static boolean readBool(JsonObject root, String key, boolean def) {
        try {
            return root.has(key) && root.get(key).isJsonPrimitive() ? root.get(key).getAsBoolean() : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static int readInt(JsonObject root, String key, int def) {
        try {
            return root.has(key) && root.get(key).isJsonPrimitive() ? root.get(key).getAsInt() : def;
        } catch (Exception e) {
            return def;
        }
    }

    /** Moves the unparseable file aside; false means it could not be preserved. */
    private boolean backUpCorruptConfig() {
        try {
            Files.move(configFile, configDir.resolve("config.json.corrupt"),
                    StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            DiscordRPCMod.LOGGER.error("Could not back up corrupt config.json - it will NOT be overwritten", e);
            return false;
        }
    }

    private void addDefaultProfiles() {
        profiles.clear();
        profiles.add(RichPresenceProfile.createDefaultMenu());
        profiles.add(RichPresenceProfile.createDefaultSingleplayer());
        profiles.add(RichPresenceProfile.createDefaultMultiplayer());
    }

    private void createDefaults() {
        addDefaultProfiles();
        save();
    }

    public void save() {
        try {
            Files.createDirectories(configDir);

            JsonObject root = new JsonObject();
            root.addProperty("configVersion", CONFIG_VERSION);
            root.addProperty("enabled", enabled);
            root.addProperty("updateInterval", updateInterval);
            root.addProperty("autoReconnect", autoReconnect);
            root.addProperty("reconnectDelay", reconnectDelay);
            root.addProperty("showInMainMenu", showInMainMenu);
            root.addProperty("afkDetection", afkDetection);
            root.addProperty("afkTimeout", afkTimeout);
            root.addProperty("hideServerIp", hideServerIp);
            root.addProperty("hideCoordinates", hideCoordinates);
            root.addProperty("launcherWarningDismissed", launcherWarningDismissed);
            root.addProperty("activeProfileIndex", activeProfileIndex);

            JsonArray arr = new JsonArray();
            for (RichPresenceProfile profile : profiles) {
                arr.add(profile.toJson());
            }
            root.add("profiles", arr);

            writeAtomic(configFile, GSON.toJson(root));
        } catch (Exception e) {
            DiscordRPCMod.LOGGER.error("Failed to save config", e);
        }
    }

    /** Write to a sibling temp file, then move over the target - a crash mid-write can never truncate the real file. */
    private static void writeAtomic(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void resetToDefaults() {
        enabled = true;
        updateInterval = 5;
        autoReconnect = true;
        reconnectDelay = 15;
        showInMainMenu = true;
        afkDetection = true;
        afkTimeout = 300;
        hideServerIp = false;
        hideCoordinates = false;
        activeProfileIndex = 0;
        createDefaults();
    }

    /** Returns the images folder path (created on first access). */
    public Path getImagesDir() {
        try { Files.createDirectories(imagesDir); } catch (IOException ignored) {}
        return imagesDir;
    }

    /**
     * Scans the images folder and returns keys (filenames without extension)
     * for every PNG / JPG file found.
     */
    public List<String> getAvailableImageKeys() {
        try (Stream<Path> files = Files.list(imagesDir)) {
            return files
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg");
                    })
                    .map(p -> {
                        String name = p.getFileName().toString();
                        int dot = name.lastIndexOf('.');
                        return dot > 0 ? name.substring(0, dot) : name;
                    })
                    .sorted()
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * Returns the full path of the image file matching the given key, or null
     * if not found. Case-insensitive so keys resolve the same way on every OS.
     */
    public Path getImagePath(String key) {
        for (String ext : new String[]{".png", ".jpg", ".jpeg"}) {
            Path p = imagesDir.resolve(key + ext);
            if (Files.exists(p)) return p;
        }
        String wanted = key.toLowerCase(Locale.ROOT);
        try (Stream<Path> files = Files.list(imagesDir)) {
            return files.filter(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                int dot = n.lastIndexOf('.');
                if (dot <= 0) return false;
                String base = n.substring(0, dot);
                String ext = n.substring(dot);
                return base.equals(wanted)
                        && (ext.equals(".png") || ext.equals(".jpg") || ext.equals(".jpeg"));
            }).findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    public RichPresenceProfile getActiveProfile() {
        if (profiles.isEmpty()) return null;
        if (activeProfileIndex < 0 || activeProfileIndex >= profiles.size()) activeProfileIndex = 0;
        return profiles.get(activeProfileIndex);
    }

    public RichPresenceProfile getBestProfile(RichPresenceProfile.ContextType currentContext, String serverIp) {
        return profiles.stream()
                .filter(p -> matchesContext(p, currentContext, serverIp))
                .max(Comparator.comparingInt(RichPresenceProfile::getPriority))
                .orElse(profiles.isEmpty() ? null : profiles.get(0));
    }

    private boolean matchesContext(RichPresenceProfile profile, RichPresenceProfile.ContextType ctx, String serverIp) {
        RichPresenceProfile.ContextType pCtx = profile.getContextType();
        if (pCtx == RichPresenceProfile.ContextType.ALWAYS) return true;

        // SPECIFIC_SERVER profiles match when we are on a multiplayer server AND
        // the IP contains the filter. The game context is set to MULTIPLAYER
        // (not SPECIFIC_SERVER), so we must allow that here.
        if (pCtx == RichPresenceProfile.ContextType.SPECIFIC_SERVER) {
            boolean onMultiplayer = ctx == RichPresenceProfile.ContextType.MULTIPLAYER
                                 || ctx == RichPresenceProfile.ContextType.SPECIFIC_SERVER;
            if (!onMultiplayer) return false;
            String filter = profile.getContextFilter();
            return filter != null && !filter.isEmpty()
                    && serverIp != null
                    && serverIp.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT));
        }

        return pCtx == ctx;
    }

    // Profile import / export

    public Path getProfilesDir() {
        try { Files.createDirectories(profilesDir); } catch (IOException ignored) {}
        return profilesDir;
    }

    /** Writes the profile as JSON into the profiles folder. Returns false on failure. */
    public boolean exportProfile(RichPresenceProfile profile, String filename) {
        try {
            Files.createDirectories(profilesDir);
            String safeName = filename.replaceAll("[^a-zA-Z0-9_\\- ]", "").trim();
            if (safeName.isEmpty() || isWindowsReservedName(safeName)) safeName = "profile";
            Path target = profilesDir.resolve(safeName + ".json");
            writeAtomic(target, GSON.toJson(profile.toJson()));
            DiscordRPCMod.LOGGER.info("Exported profile to {}", target.getFileName());
            return true;
        } catch (Exception e) {
            DiscordRPCMod.LOGGER.error("Failed to export profile", e);
            return false;
        }
    }

    /** CON, PRN, AUX, NUL, COM1-9, LPT1-9 cannot be created as filenames on Windows. */
    private static boolean isWindowsReservedName(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.equals("CON") || upper.equals("PRN") || upper.equals("AUX") || upper.equals("NUL")
                || upper.matches("(COM|LPT)[1-9]");
    }

    public RichPresenceProfile importProfile(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return RichPresenceProfile.fromJson(JsonParser.parseString(content).getAsJsonObject());
        } catch (Exception e) {
            DiscordRPCMod.LOGGER.error("Failed to import profile from {}", file, e);
            return null;
        }
    }

    public List<String> listExportedProfiles() {
        try {
            Files.createDirectories(profilesDir);
            try (Stream<Path> files = Files.list(profilesDir)) {
                return files
                        .filter(p -> p.getFileName().toString().endsWith(".json"))
                        .map(p -> {
                            String name = p.getFileName().toString();
                            return name.substring(0, name.length() - 5);
                        })
                        .sorted()
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    // Getters / setters (applicationId is intentionally absent)
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public int getUpdateInterval() { return updateInterval; }
    public void setUpdateInterval(int v) { this.updateInterval = Math.max(1, Math.min(60, v)); }
    public boolean isAutoReconnect() { return autoReconnect; }
    public void setAutoReconnect(boolean v) { this.autoReconnect = v; }
    public int getReconnectDelay() { return reconnectDelay; }
    public void setReconnectDelay(int v) { this.reconnectDelay = Math.max(5, Math.min(120, v)); }
    public boolean isShowInMainMenu() { return showInMainMenu; }
    public void setShowInMainMenu(boolean v) { this.showInMainMenu = v; }
    public boolean isAfkDetection() { return afkDetection; }
    public void setAfkDetection(boolean v) { this.afkDetection = v; }
    public int getAfkTimeout() { return afkTimeout; }
    public void setAfkTimeout(int v) { this.afkTimeout = Math.max(30, Math.min(3600, v)); }
    public boolean isHideServerIp() { return hideServerIp; }
    public void setHideServerIp(boolean v) { this.hideServerIp = v; }
    public boolean isHideCoordinates() { return hideCoordinates; }
    public void setHideCoordinates(boolean v) { this.hideCoordinates = v; }
    public boolean isLauncherWarningDismissed() { return launcherWarningDismissed; }
    public void setLauncherWarningDismissed(boolean v) { this.launcherWarningDismissed = v; }
    public List<RichPresenceProfile> getProfiles() { return profiles; }
    public int getActiveProfileIndex() { return activeProfileIndex; }
    public void setActiveProfileIndex(int index) { this.activeProfileIndex = index; }
}
