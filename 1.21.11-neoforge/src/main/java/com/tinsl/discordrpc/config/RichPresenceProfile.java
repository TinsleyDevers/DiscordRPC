package com.tinsl.discordrpc.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single Rich Presence profile - describes one variant of how the player's
 * presence card looks (details, state, images, buttons, timestamp).
 *
 * Each profile can additionally carry:
 * - <b>Dimension overrides</b> - used by Singleplayer / Multiplayer / Specific-Server
 *   profiles. Maps a dimension id (e.g. {@code minecraft:the_nether}) to a partial
 *   {@link Override} that replaces only the fields the user customized.
 * - <b>Screen overrides</b> - used by the Main Menu profile. Maps a sub-screen key
 *   (see {@link ScreenContext}) to a partial {@link Override}.
 *
 * Empty / null fields in an override mean "fall back to the parent profile".
 */
public class RichPresenceProfile {

    public enum ContextType {
        ALWAYS("Always Active"),
        MAIN_MENU("Main Menu"),
        SINGLEPLAYER("Singleplayer"),
        MULTIPLAYER("Multiplayer"),
        SPECIFIC_SERVER("Specific Server"),
        // Legacy values kept so existing configs continue to load. New profiles
        // shouldn't use these - dimensions are now handled via per-dimension overrides.
        NETHER("In The Nether"),
        END("In The End"),
        AFK("AFK");

        private final String displayName;
        ContextType(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
    }

    public enum TimestampMode {
        NONE("None"),
        ELAPSED("Elapsed Time"),
        LOCAL("Local Time");

        private final String displayName;
        TimestampMode(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
        /** Key into the lang file for the settings UI. */
        public String translationKey() { return "discordrpc.timestamp." + name().toLowerCase(java.util.Locale.ROOT); }
    }

    /**
     * Sub-screens of the Main Menu that can each have their own override.
     * The {@link #key} is the stable string used in JSON, the {@link #displayName}
     * is shown in the UI.
     */
    public enum ScreenContext {
        MAIN_MENU    ("main_menu",    "Title Screen"),
        SELECT_SERVER("select_server","Select Server"),
        SELECT_WORLD ("select_world", "Select World"),
        CREATE_WORLD ("create_world", "Create New World"),
        OPTIONS      ("options",      "Options Menu"),
        REALMS       ("realms",       "Realms"),
        LANGUAGE     ("language",     "Language Select");

        public final String key;
        public final String displayName;

        ScreenContext(String key, String displayName) {
            this.key = key;
            this.displayName = displayName;
        }

        public static ScreenContext fromKey(String key) {
            if (key == null) return null;
            for (ScreenContext s : values()) if (s.key.equals(key)) return s;
            return null;
        }

        /** Key into the lang file for the overrides UI. */
        public String translationKey() { return "discordrpc.screen." + key; }
    }

    // ── Core profile fields ──
    private String name;
    private ContextType contextType;
    private String contextFilter;
    private int priority;

    private String details;
    private String state;
    private TimestampMode timestampMode;
    private boolean showPartySize;

    private String largeImageKey;
    private String largeImageText;
    private String smallImageKey;
    private String smallImageText;

    private String button1Label;
    private String button1Url;
    private String button2Label;
    private String button2Url;

    /** Overrides keyed by full dimension id, e.g. {@code minecraft:the_nether}. */
    private final Map<String, Override> dimensionOverrides = new LinkedHashMap<>();
    /** Overrides keyed by {@link ScreenContext#key}. Only meaningful on the Main Menu profile. */
    private final Map<String, Override> screenOverrides = new LinkedHashMap<>();

    public RichPresenceProfile(String name) {
        this.name = name;
        this.contextType = ContextType.ALWAYS;
        this.contextFilter = "";
        this.priority = 0;
        this.details = "Playing Minecraft";
        this.state = "";
        this.timestampMode = TimestampMode.ELAPSED;
        this.showPartySize = false;
        this.largeImageKey = "minecraft";
        this.largeImageText = "Minecraft {version}";
        this.smallImageKey = "";
        this.smallImageText = "";
        this.button1Label = "";
        this.button1Url = "";
        this.button2Label = "";
        this.button2Url = "";
    }

    /** Deep clone, with " (Copy)" appended to the name (used when duplicating in the UI). */
    public RichPresenceProfile copy() {
        RichPresenceProfile c = new RichPresenceProfile(this.name + " (Copy)");
        copyTopLevelFieldsInto(c);
        return c;
    }

    /** Deep clone preserving the exact name (used for live edit-buffer copies). */
    public RichPresenceProfile deepCopy() {
        RichPresenceProfile c = new RichPresenceProfile(this.name);
        copyTopLevelFieldsInto(c);
        return c;
    }

    private void copyTopLevelFieldsInto(RichPresenceProfile c) {
        c.contextType   = this.contextType;
        c.contextFilter = this.contextFilter;
        c.priority      = this.priority;
        c.details       = this.details;
        c.state         = this.state;
        c.timestampMode = this.timestampMode;
        c.showPartySize = this.showPartySize;
        c.largeImageKey  = this.largeImageKey;
        c.largeImageText = this.largeImageText;
        c.smallImageKey  = this.smallImageKey;
        c.smallImageText = this.smallImageText;
        c.button1Label  = this.button1Label;
        c.button1Url    = this.button1Url;
        c.button2Label  = this.button2Label;
        c.button2Url    = this.button2Url;
        for (Map.Entry<String, Override> e : this.dimensionOverrides.entrySet()) {
            c.dimensionOverrides.put(e.getKey(), e.getValue().copy());
        }
        for (Map.Entry<String, Override> e : this.screenOverrides.entrySet()) {
            c.screenOverrides.put(e.getKey(), e.getValue().copy());
        }
    }

    /**
     * Copies only the visual presence fields (text, images, buttons, timestamp,
     * party-size flag) from {@code src} into this profile. Management fields
     * (name, contextType, priority, contextFilter, overrides) are intentionally
     * left untouched.
     */
    public void applyPresenceFrom(RichPresenceProfile src) {
        this.details       = src.details;
        this.state         = src.state;
        this.timestampMode = src.timestampMode;
        this.showPartySize = src.showPartySize;
        this.largeImageKey  = src.largeImageKey;
        this.largeImageText = src.largeImageText;
        this.smallImageKey  = src.smallImageKey;
        this.smallImageText = src.smallImageText;
        this.button1Label  = src.button1Label;
        this.button1Url    = src.button1Url;
        this.button2Label  = src.button2Label;
        this.button2Url    = src.button2Url;
    }

    /**
     * Returns a new transient profile that's this one with {@code override} applied.
     * If {@code override} is null, returns this profile unchanged.
     * The returned object's {@code overrides} maps are intentionally empty - it's a
     * "flattened" view used for activity construction and live preview only.
     */
    public RichPresenceProfile resolveWith(Override override) {
        if (override == null) return this;
        RichPresenceProfile r = new RichPresenceProfile(this.name);
        r.contextType   = this.contextType;
        r.contextFilter = this.contextFilter;
        r.priority      = this.priority;
        r.details       = override.details       != null ? override.details       : this.details;
        r.state         = override.state         != null ? override.state         : this.state;
        r.timestampMode = override.timestampMode != null ? override.timestampMode : this.timestampMode;
        r.showPartySize = override.showPartySize != null ? override.showPartySize : this.showPartySize;
        r.largeImageKey  = override.largeImageKey  != null ? override.largeImageKey  : this.largeImageKey;
        r.largeImageText = override.largeImageText != null ? override.largeImageText : this.largeImageText;
        r.smallImageKey  = override.smallImageKey  != null ? override.smallImageKey  : this.smallImageKey;
        r.smallImageText = override.smallImageText != null ? override.smallImageText : this.smallImageText;
        r.button1Label = override.button1Label != null ? override.button1Label : this.button1Label;
        r.button1Url   = override.button1Url   != null ? override.button1Url   : this.button1Url;
        r.button2Label = override.button2Label != null ? override.button2Label : this.button2Label;
        r.button2Url   = override.button2Url   != null ? override.button2Url   : this.button2Url;
        return r;
    }

    // ────────────────────────────────────────────────────────
    //  JSON
    // ────────────────────────────────────────────────────────

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("name", name);
        json.addProperty("contextType", contextType.name());
        json.addProperty("contextFilter", contextFilter);
        json.addProperty("priority", priority);
        json.addProperty("details", details);
        json.addProperty("state", state);
        json.addProperty("timestampMode", timestampMode.name());
        json.addProperty("showPartySize", showPartySize);
        json.addProperty("largeImageKey", largeImageKey);
        json.addProperty("largeImageText", largeImageText);
        json.addProperty("smallImageKey", smallImageKey);
        json.addProperty("smallImageText", smallImageText);
        json.addProperty("button1Label", button1Label);
        json.addProperty("button1Url", button1Url);
        json.addProperty("button2Label", button2Label);
        json.addProperty("button2Url", button2Url);

        if (!dimensionOverrides.isEmpty()) {
            JsonObject dims = new JsonObject();
            for (Map.Entry<String, Override> e : dimensionOverrides.entrySet()) {
                dims.add(e.getKey(), e.getValue().toJson());
            }
            json.add("dimensionOverrides", dims);
        }
        if (!screenOverrides.isEmpty()) {
            JsonObject scr = new JsonObject();
            for (Map.Entry<String, Override> e : screenOverrides.entrySet()) {
                scr.add(e.getKey(), e.getValue().toJson());
            }
            json.add("screenOverrides", scr);
        }
        return json;
    }

    /**
     * Tolerant, clamped parsing: any field that is missing, null or of the
     * wrong type falls back to {@code def}, and strings are length-capped so
     * hand-edited or imported JSON can't smuggle oversized values past the
     * GUI's own limits.
     */
    public static RichPresenceProfile fromJson(JsonObject json) {
        RichPresenceProfile p = new RichPresenceProfile(str(json, "name", "Unnamed", 64));
        if (json.has("contextType") && json.get("contextType").isJsonPrimitive()) {
            try { p.contextType = ContextType.valueOf(json.get("contextType").getAsString()); }
            catch (IllegalArgumentException ignored) {}
        }
        p.contextFilter = str(json, "contextFilter", p.contextFilter, 128);
        if (json.has("priority") && json.get("priority").isJsonPrimitive()) {
            try { p.priority = json.get("priority").getAsInt(); } catch (Exception ignored) {}
        }
        p.details = str(json, "details", p.details, 256);
        p.state = str(json, "state", p.state, 256);
        if (json.has("timestampMode") && json.get("timestampMode").isJsonPrimitive()) {
            try { p.timestampMode = TimestampMode.valueOf(json.get("timestampMode").getAsString()); }
            catch (IllegalArgumentException ignored) {}
        }
        if (json.has("showPartySize") && json.get("showPartySize").isJsonPrimitive()) {
            try { p.showPartySize = json.get("showPartySize").getAsBoolean(); } catch (Exception ignored) {}
        }
        p.largeImageKey = str(json, "largeImageKey", p.largeImageKey, 256);
        p.largeImageText = str(json, "largeImageText", p.largeImageText, 256);
        p.smallImageKey = str(json, "smallImageKey", p.smallImageKey, 256);
        p.smallImageText = str(json, "smallImageText", p.smallImageText, 256);
        p.button1Label = str(json, "button1Label", p.button1Label, 32);
        p.button1Url = str(json, "button1Url", p.button1Url, 512);
        p.button2Label = str(json, "button2Label", p.button2Label, 32);
        p.button2Url = str(json, "button2Url", p.button2Url, 512);

        if (json.has("dimensionOverrides") && json.get("dimensionOverrides").isJsonObject()) {
            JsonObject dims = json.getAsJsonObject("dimensionOverrides");
            for (Map.Entry<String, JsonElement> e : dims.entrySet()) {
                if (e.getValue().isJsonObject()) {
                    p.dimensionOverrides.put(e.getKey(), Override.fromJson(e.getValue().getAsJsonObject()));
                }
            }
        }
        if (json.has("screenOverrides") && json.get("screenOverrides").isJsonObject()) {
            JsonObject scr = json.getAsJsonObject("screenOverrides");
            for (Map.Entry<String, JsonElement> e : scr.entrySet()) {
                if (e.getValue().isJsonObject()) {
                    p.screenOverrides.put(e.getKey(), Override.fromJson(e.getValue().getAsJsonObject()));
                }
            }
        }
        return p;
    }

    /** Reads a string field, tolerating null / wrong types, capped at {@code maxLen}. */
    private static String str(JsonObject json, String key, String def, int maxLen) {
        if (!json.has(key) || !json.get(key).isJsonPrimitive()) return def;
        try {
            String v = json.get(key).getAsString();
            return v.length() <= maxLen ? v : v.substring(0, maxLen);
        } catch (Exception e) {
            return def;
        }
    }

    // ────────────────────────────────────────────────────────
    //  DEFAULT FACTORIES (the only built-in profiles)
    // ────────────────────────────────────────────────────────

    public static RichPresenceProfile createDefaultMenu() {
        RichPresenceProfile p = new RichPresenceProfile("Main Menu");
        p.contextType = ContextType.MAIN_MENU;
        p.priority = 10;
        p.details = "In the Main Menu";
        p.state = "Minecraft {version}";
        p.largeImageKey = "minecraftlogo";
        p.largeImageText = "Minecraft {version}";
        p.timestampMode = TimestampMode.ELAPSED;

        // Pre-populate one override per screen context so players can edit them immediately.
        p.screenOverrides.put(ScreenContext.MAIN_MENU.key,    screenOv("On the Title Screen",     "Minecraft {version}"));
        p.screenOverrides.put(ScreenContext.SELECT_SERVER.key, screenOv("Selecting a Server",      "Browsing multiplayer servers"));
        p.screenOverrides.put(ScreenContext.SELECT_WORLD.key,  screenOv("Selecting a World",       "Browsing singleplayer worlds"));
        p.screenOverrides.put(ScreenContext.CREATE_WORLD.key,  screenOv("Creating a New World",    "Configuring world settings"));
        p.screenOverrides.put(ScreenContext.OPTIONS.key,       screenOv("In the Options Menu",     "Adjusting settings"));
        p.screenOverrides.put(ScreenContext.REALMS.key,        screenOv("Browsing Realms",         "Looking for a Realm to join"));
        p.screenOverrides.put(ScreenContext.LANGUAGE.key,      screenOv("Changing Language",       "In language settings"));
        return p;
    }

    private static Override screenOv(String details, String state) {
        Override ov = new Override();
        ov.details = details;
        ov.state   = state;
        return ov;
    }

    public static RichPresenceProfile createDefaultSingleplayer() {
        RichPresenceProfile p = new RichPresenceProfile("Singleplayer");
        p.contextType = ContextType.SINGLEPLAYER;
        p.priority = 20;
        p.details = "Playing Singleplayer";
        p.state = "{dimension} \u2014 {biome}";
        p.largeImageKey = "minecraftlogo";
        p.largeImageText = "{world}";
        p.timestampMode = TimestampMode.ELAPSED;
        return p;
    }

    public static RichPresenceProfile createDefaultMultiplayer() {
        RichPresenceProfile p = new RichPresenceProfile("Multiplayer");
        p.contextType = ContextType.MULTIPLAYER;
        p.priority = 20;
        p.details = "Playing Multiplayer";
        p.state = "{server}";
        p.largeImageKey = "minecraftlogo";
        p.largeImageText = "{server}";
        p.timestampMode = TimestampMode.ELAPSED;
        return p;
    }

    /** Used when the user clicks "+ Add Server Profile" in the Multiplayer tab. */
    public static RichPresenceProfile createServerProfile(String name, String ipFilter) {
        RichPresenceProfile p = new RichPresenceProfile(name);
        p.contextType = ContextType.SPECIFIC_SERVER;
        p.contextFilter = ipFilter;
        p.priority = 30;
        p.details = "Playing on " + name;
        p.state = "{online} / {max_players} players online";
        p.largeImageKey = "minecraftlogo";
        p.largeImageText = "{server}";
        p.timestampMode = TimestampMode.ELAPSED;
        return p;
    }

    // ────────────────────────────────────────────────────────
    //  GETTERS / SETTERS
    // ────────────────────────────────────────────────────────

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public ContextType getContextType() { return contextType; }
    public void setContextType(ContextType c) { this.contextType = c; }
    public String getContextFilter() { return contextFilter; }
    public void setContextFilter(String f) { this.contextFilter = f; }
    public int getPriority() { return priority; }
    public void setPriority(int p) { this.priority = p; }
    public String getDetails() { return details; }
    public void setDetails(String d) { this.details = d; }
    public String getState() { return state; }
    public void setState(String s) { this.state = s; }
    public TimestampMode getTimestampMode() { return timestampMode; }
    public void setTimestampMode(TimestampMode t) { this.timestampMode = t; }
    public boolean isShowPartySize() { return showPartySize; }
    public void setShowPartySize(boolean v) { this.showPartySize = v; }
    public String getLargeImageKey() { return largeImageKey; }
    public void setLargeImageKey(String k) { this.largeImageKey = k; }
    public String getLargeImageText() { return largeImageText; }
    public void setLargeImageText(String t) { this.largeImageText = t; }
    public String getSmallImageKey() { return smallImageKey; }
    public void setSmallImageKey(String k) { this.smallImageKey = k; }
    public String getSmallImageText() { return smallImageText; }
    public void setSmallImageText(String t) { this.smallImageText = t; }
    public String getButton1Label() { return button1Label; }
    public void setButton1Label(String l) { this.button1Label = l; }
    public String getButton1Url() { return button1Url; }
    public void setButton1Url(String u) { this.button1Url = u; }
    public String getButton2Label() { return button2Label; }
    public void setButton2Label(String l) { this.button2Label = l; }
    public String getButton2Url() { return button2Url; }
    public void setButton2Url(String u) { this.button2Url = u; }

    public Map<String, Override> getDimensionOverrides() { return dimensionOverrides; }
    public Map<String, Override> getScreenOverrides()    { return screenOverrides; }

    // ────────────────────────────────────────────────────────
    //  OVERRIDE - partial profile with null = "inherit parent"
    // ────────────────────────────────────────────────────────

    /**
     * Partial replacement applied on top of a base profile. Each field is nullable;
     * a null field means "use the parent profile's value".
     *
     * Strings use empty-string ("") to mean "explicitly empty / disabled" so users
     * can null out a parent's button or state line via an override.
     */
    public static class Override {
        public String details;
        public String state;
        public TimestampMode timestampMode;
        public Boolean showPartySize;
        public String largeImageKey;
        public String largeImageText;
        public String smallImageKey;
        public String smallImageText;
        public String button1Label;
        public String button1Url;
        public String button2Label;
        public String button2Url;

        public Override copy() {
            Override c = new Override();
            c.details = this.details;
            c.state = this.state;
            c.timestampMode = this.timestampMode;
            c.showPartySize = this.showPartySize;
            c.largeImageKey = this.largeImageKey;
            c.largeImageText = this.largeImageText;
            c.smallImageKey = this.smallImageKey;
            c.smallImageText = this.smallImageText;
            c.button1Label = this.button1Label;
            c.button1Url = this.button1Url;
            c.button2Label = this.button2Label;
            c.button2Url = this.button2Url;
            return c;
        }

        /** True when no field is set - UI uses this to show "Empty (inherits parent)". */
        public boolean isEmpty() {
            return details == null && state == null
                && timestampMode == null && showPartySize == null
                && largeImageKey == null && largeImageText == null
                && smallImageKey == null && smallImageText == null
                && button1Label == null && button1Url == null
                && button2Label == null && button2Url == null;
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            if (details != null)        o.addProperty("details", details);
            if (state != null)          o.addProperty("state", state);
            if (timestampMode != null)  o.addProperty("timestampMode", timestampMode.name());
            if (showPartySize != null)  o.addProperty("showPartySize", showPartySize);
            if (largeImageKey != null)  o.addProperty("largeImageKey", largeImageKey);
            if (largeImageText != null) o.addProperty("largeImageText", largeImageText);
            if (smallImageKey != null)  o.addProperty("smallImageKey", smallImageKey);
            if (smallImageText != null) o.addProperty("smallImageText", smallImageText);
            if (button1Label != null)   o.addProperty("button1Label", button1Label);
            if (button1Url != null)     o.addProperty("button1Url", button1Url);
            if (button2Label != null)   o.addProperty("button2Label", button2Label);
            if (button2Url != null)     o.addProperty("button2Url", button2Url);
            return o;
        }

        public static Override fromJson(JsonObject o) {
            Override ov = new Override();
            ov.details = str(o, "details", null, 256);
            ov.state = str(o, "state", null, 256);
            if (o.has("timestampMode") && o.get("timestampMode").isJsonPrimitive()) {
                try { ov.timestampMode = TimestampMode.valueOf(o.get("timestampMode").getAsString()); }
                catch (IllegalArgumentException ignored) {}
            }
            if (o.has("showPartySize") && o.get("showPartySize").isJsonPrimitive()) {
                try { ov.showPartySize = o.get("showPartySize").getAsBoolean(); } catch (Exception ignored) {}
            }
            ov.largeImageKey = str(o, "largeImageKey", null, 256);
            ov.largeImageText = str(o, "largeImageText", null, 256);
            ov.smallImageKey = str(o, "smallImageKey", null, 256);
            ov.smallImageText = str(o, "smallImageText", null, 256);
            ov.button1Label = str(o, "button1Label", null, 32);
            ov.button1Url = str(o, "button1Url", null, 512);
            ov.button2Label = str(o, "button2Label", null, 32);
            ov.button2Url = str(o, "button2Url", null, 512);
            return ov;
        }
    }
}
