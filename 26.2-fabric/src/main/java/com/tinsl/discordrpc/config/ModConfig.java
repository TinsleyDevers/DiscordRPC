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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Mod settings + profile storage, persisted as pretty-printed JSON under
 * {@code config/discordrpc/}. Only touches java.nio and Gson so the same file
 * works on every loader - the loader-specific entrypoint supplies its config
 * directory to the constructor.
 */
public class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Locked - not configurable by the user. */
    public static final String APPLICATION_ID = "1493462784099356792";

    private boolean enabled = true;
    private int updateInterval = 3;
    private boolean autoReconnect = true;
    private int reconnectDelay = 15;
    private boolean showInMainMenu = true;
    private boolean afkDetection = true;
    private int afkTimeout = 300;
    private boolean hideServerIp = false;
    private boolean hideCoordinates = false;
    private boolean launcherWarningDismissed = false;

    private final List<RichPresenceProfile> profiles = new ArrayList<>();
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

            InputStream manifestStream = ModConfig.class.getResourceAsStream(
                    "/assets/discordrpc/bundled_images.json");
            if (manifestStream == null) {
                DiscordRPCMod.LOGGER.warn("bundled_images.json not found in JAR");
                return;
            }

            String manifestJson = new String(manifestStream.readAllBytes(), StandardCharsets.UTF_8);
            manifestStream.close();
            JsonArray fileList = JsonParser.parseString(manifestJson).getAsJsonArray();

            int copied = 0;
            for (JsonElement el : fileList) {
                String filename = el.getAsString();
                Path target = imagesDir.resolve(filename);
                if (Files.exists(target)) continue;

                InputStream imgStream = ModConfig.class.getResourceAsStream(
                        "/assets/discordrpc/images/" + filename);
                if (imgStream == null) {
                    DiscordRPCMod.LOGGER.warn("Bundled image not found: {}", filename);
                    continue;
                }

                Files.copy(imgStream, target, StandardCopyOption.REPLACE_EXISTING);
                imgStream.close();
                copied++;
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
            copyBundledImages();

            if (Files.exists(configFile)) {
                String content = Files.readString(configFile, StandardCharsets.UTF_8);
                JsonObject root = JsonParser.parseString(content).getAsJsonObject();

                if (root.has("enabled")) enabled = root.get("enabled").getAsBoolean();
                if (root.has("updateInterval")) updateInterval = root.get("updateInterval").getAsInt();
                if (root.has("autoReconnect")) autoReconnect = root.get("autoReconnect").getAsBoolean();
                if (root.has("reconnectDelay")) reconnectDelay = root.get("reconnectDelay").getAsInt();
                if (root.has("showInMainMenu")) showInMainMenu = root.get("showInMainMenu").getAsBoolean();
                if (root.has("afkDetection")) afkDetection = root.get("afkDetection").getAsBoolean();
                if (root.has("afkTimeout")) afkTimeout = root.get("afkTimeout").getAsInt();
                if (root.has("hideServerIp")) hideServerIp = root.get("hideServerIp").getAsBoolean();
                if (root.has("hideCoordinates")) hideCoordinates = root.get("hideCoordinates").getAsBoolean();
                if (root.has("launcherWarningDismissed")) launcherWarningDismissed = root.get("launcherWarningDismissed").getAsBoolean();
                if (root.has("activeProfileIndex")) activeProfileIndex = root.get("activeProfileIndex").getAsInt();

                profiles.clear();
                if (root.has("profiles")) {
                    JsonArray arr = root.getAsJsonArray("profiles");
                    for (JsonElement el : arr) {
                        profiles.add(RichPresenceProfile.fromJson(el.getAsJsonObject()));
                    }
                }
                DiscordRPCMod.LOGGER.info("Loaded {} profiles from config", profiles.size());
            }
        } catch (Exception e) {
            DiscordRPCMod.LOGGER.error("Failed to load config, using defaults", e);
        }

        if (profiles.isEmpty()) {
            createDefaults();
        }
    }

    private void createDefaults() {
        profiles.clear();
        profiles.add(RichPresenceProfile.createDefaultMenu());
        profiles.add(RichPresenceProfile.createDefaultSingleplayer());
        profiles.add(RichPresenceProfile.createDefaultMultiplayer());
        save();
    }

    public void save() {
        try {
            Files.createDirectories(configDir);

            JsonObject root = new JsonObject();
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

            Files.writeString(configFile, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            DiscordRPCMod.LOGGER.error("Failed to save config", e);
        }
    }

    public void resetToDefaults() {
        enabled = true;
        updateInterval = 3;
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
     * for every PNG / JPG / GIF file found.
     */
    public List<String> getAvailableImageKeys() {
        try {
            return Files.list(imagesDir)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return n.endsWith(".png") || n.endsWith(".jpg")
                                || n.endsWith(".jpeg") || n.endsWith(".gif");
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

    /** Returns the full path of the image file matching the given key, or null if not found. */
    public Path getImagePath(String key) {
        for (String ext : new String[]{".png", ".jpg", ".jpeg", ".gif"}) {
            Path p = imagesDir.resolve(key + ext);
            if (Files.exists(p)) return p;
        }
        return null;
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
                    && serverIp != null && serverIp.toLowerCase().contains(filter.toLowerCase());
        }

        return pCtx == ctx;
    }

    // Profile import / export

    public Path getProfilesDir() {
        try { Files.createDirectories(profilesDir); } catch (IOException ignored) {}
        return profilesDir;
    }

    public void exportProfile(RichPresenceProfile profile, String filename) {
        try {
            Files.createDirectories(profilesDir);
            String safeName = filename.replaceAll("[^a-zA-Z0-9_\\- ]", "").trim();
            if (safeName.isEmpty()) safeName = "profile";
            Path target = profilesDir.resolve(safeName + ".json");
            Files.writeString(target, GSON.toJson(profile.toJson()), StandardCharsets.UTF_8);
            DiscordRPCMod.LOGGER.info("Exported profile to {}", target.getFileName());
        } catch (Exception e) {
            DiscordRPCMod.LOGGER.error("Failed to export profile", e);
        }
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
            return Files.list(profilesDir)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .map(p -> {
                        String name = p.getFileName().toString();
                        return name.substring(0, name.length() - 5);
                    })
                    .sorted()
                    .collect(Collectors.toList());
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
