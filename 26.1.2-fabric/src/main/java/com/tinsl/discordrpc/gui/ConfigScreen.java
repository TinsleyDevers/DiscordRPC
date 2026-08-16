package com.tinsl.discordrpc.gui;

import com.tinsl.discordrpc.DiscordRPCMod;
import com.tinsl.discordrpc.config.ModConfig;
import com.tinsl.discordrpc.config.RichPresenceProfile;
import com.tinsl.discordrpc.config.RichPresenceProfile.ScreenContext;
import com.tinsl.discordrpc.core.DiscordIPC;
import com.tinsl.discordrpc.core.LauncherConflict;
import com.tinsl.discordrpc.gui.widget.BundledImageRegistry;
import com.tinsl.discordrpc.gui.widget.DiscordPreviewWidget;
import com.tinsl.discordrpc.gui.widget.ImagePickerWidget;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The mod's settings screen, laid out like a vanilla menu: tab bar up top
 * (Create World style), a menu-list background band for the content, and
 * Done / Cancel in the footer.
 *
 * <p>The three presence tabs show the Discord preview card on the left and an
 * editor panel on the right. Clicking any part of the card opens the editor
 * for that part. The Settings tab is a standard two-column options grid.
 *
 * <p>Override editing: each tab's home panel lists that context's overrides
 * (per-screen for the main menu, per-dimension in game). Editing an override
 * swaps the editor into a field list showing what is inherited vs overridden;
 * on exit the buffer is diffed against the parent so only changed fields are
 * stored.
 */
public class ConfigScreen extends Screen {

    private static final int TAB_MENU = 0;
    private static final int TAB_SINGLE = 1;
    private static final int TAB_MULTI = 2;
    private static final int TAB_SETTINGS = 3;

    private static final RichPresenceProfile.ContextType[] TAB_CONTEXTS = {
        RichPresenceProfile.ContextType.MAIN_MENU,
        RichPresenceProfile.ContextType.SINGLEPLAYER,
        RichPresenceProfile.ContextType.MULTIPLAYER,
        null
    };

    private static final String[] TAB_TITLE_KEYS = {
        "discordrpc.tab.main_menu", "discordrpc.tab.singleplayer",
        "discordrpc.tab.multiplayer", "discordrpc.tab.settings"
    };

    private static final String[][] COMMON_DIMENSIONS = {
        {"minecraft:overworld",  "Overworld"},
        {"minecraft:the_nether", "The Nether"},
        {"minecraft:the_end",    "The End"},
    };

    private static final Identifier FOOTER_SEPARATOR = Identifier.withDefaultNamespace("textures/gui/footer_separator.png");
    private static final Identifier INWORLD_FOOTER_SEPARATOR = Identifier.withDefaultNamespace("textures/gui/inworld_footer_separator.png");

    // Vanilla text palette
    private static final int COLOR_WHITE   = 0xFFFFFFFF;
    private static final int COLOR_LABEL   = 0xFFA0A0A0;
    private static final int COLOR_HINT    = 0xFF808080;
    private static final int COLOR_DIVIDER = 0x30FFFFFF;
    private static final int COLOR_VAR     = 0xFF55FFFF;
    private static final int COLOR_GREEN   = 0xFF55FF55;
    private static final int COLOR_YELLOW  = 0xFFFFFF55;
    private static final int COLOR_RED     = 0xFFFF5555;
    private static final int COLOR_GOLD    = 0xFFFFAA00;

    private static final int TAB_BAR_HEIGHT = 24;
    private static final int FOOTER_HEIGHT = 33;

    // Core state
    private final Screen parent;
    private final ModConfig config;
    private int currentTab;
    private DiscordPreviewWidget.Zone currentZone = DiscordPreviewWidget.Zone.NONE;

    /** Working copies of the *base* profile per tab (Main Menu / SP / MP). */
    private final Map<Integer, RichPresenceProfile> baseCopies = new HashMap<>();
    /** Real (saved) profile per tab. */
    private final Map<Integer, RichPresenceProfile> realProfiles = new HashMap<>();
    /** What the field editors are currently editing - a base copy or an override buffer. */
    private RichPresenceProfile editingProfile;

    private RichPresenceProfile editingServerProfile = null;
    private final List<RichPresenceProfile> serverProfiles = new ArrayList<>();

    private String editingOverrideKey = null;
    private boolean editingOverrideIsScreen = false;
    private RichPresenceProfile overrideParentProfile = null;
    private boolean addOverrideMode = false;
    private EditBox addOverrideCustomBox = null;

    private Component statusMessage = null;
    private long statusUntilMs = 0;
    /** Screen-open time, so the preview's elapsed clock starts at 00:00. */
    private final long openedAtMs = System.currentTimeMillis();

    /** The Multiplayer tab's own edit buffer, parked while a server profile is edited. */
    private RichPresenceProfile stashedMultiBase = null;
    /** Index armed for server-profile deletion (two-click confirm), -1 when none. */
    private int confirmDeleteServerIdx = -1;
    /** True after the first click on "Reset Everything" (two-click confirm). */
    private boolean confirmReset = false;

    // Layout
    private boolean compact;
    private int contentTop, contentBottom;
    private int previewX, previewY, previewW;
    private int panelX, panelY, panelW, panelBottom;

    // Widgets
    private DiscordPreviewWidget preview;
    private TabManager tabManager;
    private TabNavigationBar tabBar;
    private boolean suppressTabCallback = false;
    private final List<AbstractWidget> dynamicWidgets = new ArrayList<>();

    /** Static text placed during rebuild, drawn every frame. */
    private record TextLine(Component text, int x, int y, int color, boolean shadow) {}
    private final List<TextLine> textLines = new ArrayList<>();
    /** Y anchor for the placeholder reference block, -1 when hidden. */
    private int variablesY = -1;

    public ConfigScreen(Screen parent) {
        super(Component.translatable("discordrpc.config.title"));
        this.parent = parent;
        this.config = DiscordRPCMod.getInstance().getModConfig();
        this.currentTab = detectInitialTab();
    }

    private int detectInitialTab() {
        var rpc = DiscordRPCMod.getInstance().getRpcManager();
        if (rpc == null) return TAB_MENU;
        RichPresenceProfile.ContextType ctx = rpc.getCurrentContext();
        if (ctx == null) return TAB_MENU;
        return switch (ctx) {
            case MAIN_MENU -> TAB_MENU;
            case SINGLEPLAYER, NETHER, END -> TAB_SINGLE;
            case MULTIPLAYER, SPECIFIC_SERVER -> TAB_MULTI;
            default -> TAB_MENU;
        };
    }

    /** Content-free tab: selecting it just swaps this screen's panels. */
    private class PageTab implements Tab {
        private final int index;
        private final Component title;

        PageTab(int index) {
            this.index = index;
            this.title = Component.translatable(TAB_TITLE_KEYS[index]);
        }

        @Override
        public Component getTabTitle() {
            return title;
        }

        @Override
        public Component getTabExtraNarration() {
            return Component.empty();
        }

        @Override
        public void visitChildren(Consumer<AbstractWidget> consumer) {
        }

        @Override
        public void doLayout(ScreenRectangle rectangle) {
            if (!suppressTabCallback) {
                switchTab(index);
            }
        }
    }

    @Override
    protected void init() {
        clearWidgets();
        dynamicWidgets.clear();
        textLines.clear();
        variablesY = -1;

        // Incremental: bundled images load once per session, and only local
        // files added since the last open get decoded. Re-running on every
        // init() (which includes window resizes) is therefore cheap.
        ImagePickerWidget.preloadFromDirectory(config.getImagesDir());

        computeLayout();
        loadServerProfiles();
        loadProfileForTab(currentTab);

        tabManager = new TabManager(this::addRenderableWidget, this::removeWidget);
        tabBar = TabNavigationBar.builder(tabManager, width)
                .addTabs(new PageTab(TAB_MENU), new PageTab(TAB_SINGLE),
                         new PageTab(TAB_MULTI), new PageTab(TAB_SETTINGS))
                .build();
        addRenderableWidget(tabBar);
        tabBar.arrangeElements();
        tabManager.setTabArea(new ScreenRectangle(0, TAB_BAR_HEIGHT, width, contentBottom - TAB_BAR_HEIGHT));
        suppressTabCallback = true;
        tabBar.selectTab(currentTab, false);
        suppressTabCallback = false;

        preview = new DiscordPreviewWidget(previewX, previewY, previewW, this::onZoneClicked);
        addRenderableWidget(preview);

        int btnW = Math.min(150, (width - 24) / 2);
        int gap = 8;
        int btnX = (width - btnW * 2 - gap) / 2;
        int btnY = contentBottom + (FOOTER_HEIGHT - 20) / 2;
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> saveAndClose())
                .bounds(btnX, btnY, btnW, 20)
                .tooltip(Tooltip.create(Component.translatable("discordrpc.button.done.tooltip")))
                .build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(btnX + btnW + gap, btnY, btnW, 20).build());

        rebuildContent();
        updatePreview();
    }

    private void computeLayout() {
        contentTop = TAB_BAR_HEIGHT;
        contentBottom = height - FOOTER_HEIGHT;

        previewW = 232;
        compact = width < 540;

        if (!compact) {
            int editorW = Math.max(220, Math.min(340, width - previewW - 48));
            int blockW = previewW + 24 + editorW;
            int blockX = Math.max(8, (width - blockW) / 2);
            previewX = blockX;
            previewY = contentTop + 16;
            panelX = blockX + previewW + 24;
            panelY = contentTop + 10;
            panelW = editorW;
        } else {
            previewW = Math.min(232, width - 16);
            previewX = (width - previewW) / 2;
            previewY = contentTop + 8;
            panelW = Math.min(320, width - 16);
            panelX = (width - panelW) / 2;
            panelY = contentTop + 8;
        }
        panelBottom = contentBottom - 6;
    }

    /** True when the preview card should be on screen right now. */
    private boolean previewVisible() {
        if (currentTab == TAB_SETTINGS) return false;
        if (!compact) return true;
        // Small window: the card yields to whichever editor is open.
        return currentZone == DiscordPreviewWidget.Zone.NONE
                && (contentBottom - contentTop) >= 240;
    }

    private void applyPreviewVisibility() {
        if (preview == null) return;
        boolean show = previewVisible();
        preview.visible = show;
        preview.active = show;
        preview.setX(previewX);
        preview.setY(previewY);
    }

    /** Panel top for the current state - in compact mode content drops below the card. */
    private int contentStartY() {
        if (compact && previewVisible()) {
            return previewY + 168;
        }
        return panelY;
    }

    // Profile loading

    private void loadProfileForTab(int tab) {
        RichPresenceProfile.ContextType ctx = TAB_CONTEXTS[tab];
        if (ctx == null) {
            editingProfile = null;
            return;
        }

        RichPresenceProfile real = null;
        for (RichPresenceProfile p : config.getProfiles()) {
            if (p.getContextType() == ctx) { real = p; break; }
        }
        if (real == null) {
            real = getDefaultForContext(ctx);
            config.getProfiles().add(real);
        }
        realProfiles.put(tab, real);
        baseCopies.putIfAbsent(tab, real.deepCopy());
        editingProfile = baseCopies.get(tab);
    }

    private void loadServerProfiles() {
        serverProfiles.clear();
        for (RichPresenceProfile p : config.getProfiles()) {
            if (p.getContextType() == RichPresenceProfile.ContextType.SPECIFIC_SERVER) {
                serverProfiles.add(p);
            }
        }
    }

    private static RichPresenceProfile getDefaultForContext(RichPresenceProfile.ContextType ctx) {
        return switch (ctx) {
            case MAIN_MENU    -> RichPresenceProfile.createDefaultMenu();
            case SINGLEPLAYER -> RichPresenceProfile.createDefaultSingleplayer();
            case MULTIPLAYER  -> RichPresenceProfile.createDefaultMultiplayer();
            default -> {
                RichPresenceProfile p = new RichPresenceProfile(ctx.getDisplayName());
                p.setContextType(ctx);
                yield p;
            }
        };
    }

    // Tab switching

    private void switchTab(int tab) {
        if (tab == currentTab) return;
        commitOverrideIfEditing();
        if (editingServerProfile != null) restoreMultiBase();

        currentTab = tab;
        currentZone = DiscordPreviewWidget.Zone.NONE;
        confirmDeleteServerIdx = -1;
        confirmReset = false;
        editingServerProfile = null;
        editingOverrideKey = null;
        overrideParentProfile = null;
        addOverrideMode = false;
        loadProfileForTab(tab);
        rebuildContent();
        updatePreview();
    }

    private void onZoneClicked(DiscordPreviewWidget.Zone zone) {
        currentZone = zone;
        addOverrideMode = false;
        rebuildContent();
    }

    private void rebuildContent() {
        clearDynamic();
        applyPreviewVisibility();

        if (currentTab == TAB_SETTINGS) {
            buildSettingsPanel();
            return;
        }

        if (currentZone != DiscordPreviewWidget.Zone.NONE) {
            buildZoneEditor();
        } else if (addOverrideMode) {
            buildAddOverridePicker();
        } else if (editingOverrideKey != null) {
            buildOverrideFieldList();
        } else {
            buildHomePanel();
        }

    }

    // Home panel: server profiles (MP), overrides, placeholder reference

    private void buildHomePanel() {
        int x = panelX, w = panelW;
        int y = contentStartY();

        if (currentTab == TAB_MULTI) {
            if (editingServerProfile != null) {
                y = buildServerProfileEditor(x, y, w);
            } else {
                y = addSectionHeader(y, "discordrpc.section.server_profiles");
                y = buildServerProfileList(x, y, w);
            }
        }

        boolean screenMode = currentTab == TAB_MENU;
        y = addSectionHeader(y, screenMode
                ? "discordrpc.section.screen_overrides"
                : "discordrpc.section.dimension_overrides");
        y = buildOverridesList(x, y, w, screenMode);

        // Placeholder reference fills the leftover space (rendered as text).
        if (panelBottom - y > 40) {
            y = addSectionHeader(y + 2, "discordrpc.section.placeholders");
            variablesY = y;
        }

        addDynamic(Button.builder(Component.translatable("discordrpc.button.restore_defaults"),
                        b -> restoreCurrentTabDefaults())
                .bounds(x, panelBottom - 20, w, 20)
                .tooltip(Tooltip.create(Component.translatable("discordrpc.button.restore_defaults.tooltip")))
                .build());
    }

    private int buildServerProfileList(int x, int y, int w) {
        int removeW = 56;
        for (int i = 0; i < serverProfiles.size(); i++) {
            final RichPresenceProfile sp = serverProfiles.get(i);
            final int idx = i;
            if (y + 20 > panelBottom - 96) break;

            String filter = sp.getContextFilter();
            String label = sp.getName() + (filter == null || filter.isEmpty() ? "" : " (" + cap(filter, 18) + ")");
            addDynamic(Button.builder(Component.literal(cap(label, 30)), b -> selectServerProfile(sp))
                    .bounds(x, y, w - removeW - 4, 20)
                    .tooltip(Tooltip.create(Component.translatable("discordrpc.server.edit.tooltip")))
                    .build());
            boolean confirming = idx == confirmDeleteServerIdx;
            addDynamic(Button.builder(Component.translatable(
                                    confirming ? "discordrpc.button.confirm" : "discordrpc.button.remove"),
                            b -> {
                                if (idx != confirmDeleteServerIdx) {
                                    confirmDeleteServerIdx = idx;
                                    rebuildContent();
                                } else {
                                    confirmDeleteServerIdx = -1;
                                    deleteServerProfile(idx);
                                }
                            })
                    .bounds(x + w - removeW, y, removeW, 20).build());
            y += 22;
        }

        addDynamic(Button.builder(Component.translatable("discordrpc.button.add_server_profile"),
                        b -> addServerProfile())
                .bounds(x, y, w, 20).build());
        return y + 26;
    }

    private int buildServerProfileEditor(int x, int y, int w) {
        addDynamic(Button.builder(Component.translatable("discordrpc.button.back_to_multiplayer"),
                        b -> exitServerProfileEdit())
                .bounds(x, y, w, 20).build());
        y += 26;

        y = addFieldLabel(x, y, "discordrpc.server.name");
        EditBox nameBox = new EditBox(font, x, y, w, 20, Component.translatable("discordrpc.server.name"));
        nameBox.setHint(Component.literal("Hypixel"));
        nameBox.setMaxLength(32);
        nameBox.setValue(editingProfile.getName());
        nameBox.setResponder(v -> editingProfile.setName(v));
        addDynamic(nameBox);
        y += 26;

        y = addFieldLabel(x, y, "discordrpc.server.ip_filter");
        EditBox ipBox = new EditBox(font, x, y, w, 20, Component.translatable("discordrpc.server.ip_filter"));
        ipBox.setHint(Component.literal("hypixel.net"));
        ipBox.setMaxLength(128);
        ipBox.setValue(editingProfile.getContextFilter());
        ipBox.setResponder(v -> editingProfile.setContextFilter(v));
        addDynamic(ipBox);
        y += 24;
        addWrappedText(Component.translatable("discordrpc.server.ip_filter.hint"), x, y, w, COLOR_HINT, false);
        y += wrappedHeight(Component.translatable("discordrpc.server.ip_filter.hint"), w) + 6;

        int half = (w - 6) / 2;
        addDynamic(Button.builder(Component.translatable("discordrpc.button.save_server_profile"),
                        b -> saveAndExitServerProfile())
                .bounds(x, y, half, 20).build());
        addDynamic(Button.builder(Component.translatable("discordrpc.button.discard"),
                        b -> exitServerProfileEdit())
                .bounds(x + half + 6, y, w - half - 6, 20).build());
        return y + 28;
    }

    private void saveAndExitServerProfile() {
        if (editingServerProfile != null) {
            RichPresenceProfile copy = baseCopies.get(TAB_MULTI);
            if (copy != null) {
                editingServerProfile.applyPresenceFrom(copy);
                editingServerProfile.setName(uniqueProfileName(copy.getName(), editingServerProfile));
                editingServerProfile.setContextFilter(copy.getContextFilter());
                applyOverridesFrom(editingServerProfile, copy);
            }
        }
        config.save();

        var rpc = DiscordRPCMod.getInstance().getRpcManager();
        if (rpc != null) {
            if (rpc.isConnected()) { rpc.restartUpdateLoop(); rpc.forceUpdate(); }
            else if (config.isEnabled()) rpc.connect();
        }

        flashStatus(Component.translatable("discordrpc.status.server_profile_saved"));
        exitServerProfileEdit();
    }

    private void selectServerProfile(RichPresenceProfile sp) {
        // Park the Multiplayer tab's own edit buffer so its unsaved edits
        // survive a detour through a server profile.
        if (editingServerProfile == null && stashedMultiBase == null) {
            stashedMultiBase = baseCopies.get(TAB_MULTI);
        }
        editingServerProfile = sp;
        RichPresenceProfile copy = sp.deepCopy();
        editingProfile = copy;
        baseCopies.put(TAB_MULTI, copy);
        realProfiles.put(TAB_MULTI, sp);
        currentZone = DiscordPreviewWidget.Zone.NONE;
        confirmDeleteServerIdx = -1;
        editingOverrideKey = null;
        overrideParentProfile = null;
        rebuildContent();
        updatePreview();
    }

    private void exitServerProfileEdit() {
        editingServerProfile = null;
        currentZone = DiscordPreviewWidget.Zone.NONE;
        addOverrideMode = false;
        editingOverrideKey = null;
        overrideParentProfile = null;

        restoreMultiBase();
        editingProfile = baseCopies.get(TAB_MULTI);

        rebuildContent();
        updatePreview();
    }

    /** Points the Multiplayer tab back at its base profile, restoring the parked buffer. */
    private void restoreMultiBase() {
        RichPresenceProfile real = null;
        for (RichPresenceProfile p : config.getProfiles()) {
            if (p.getContextType() == RichPresenceProfile.ContextType.MULTIPLAYER) { real = p; break; }
        }
        if (real == null) {
            real = RichPresenceProfile.createDefaultMultiplayer();
            config.getProfiles().add(real);
        }
        realProfiles.put(TAB_MULTI, real);
        if (stashedMultiBase != null) {
            baseCopies.put(TAB_MULTI, stashedMultiBase);
            stashedMultiBase = null;
        } else {
            baseCopies.put(TAB_MULTI, real.deepCopy());
        }
    }

    private void addServerProfile() {
        RichPresenceProfile sp = RichPresenceProfile.createServerProfile(
                Component.translatable("discordrpc.server.default_name").getString(), "");
        config.getProfiles().add(sp);
        serverProfiles.add(sp);
        selectServerProfile(sp);
    }

    private void deleteServerProfile(int idx) {
        if (idx < 0 || idx >= serverProfiles.size()) return;
        RichPresenceProfile sp = serverProfiles.remove(idx);
        config.getProfiles().remove(sp);
        config.save();
        if (editingServerProfile == sp) {
            exitServerProfileEdit();
        } else {
            rebuildContent();
            updatePreview();
        }
    }

    /** Appends " (2)", " (3)" .. when another profile already uses the name -
     *  duplicate names would collide as export filenames. */
    private String uniqueProfileName(String wanted, RichPresenceProfile self) {
        if (wanted == null || wanted.isEmpty()) wanted = "Server";
        String name = wanted;
        int n = 2;
        outer:
        while (true) {
            for (RichPresenceProfile p : config.getProfiles()) {
                if (p != self && name.equals(p.getName())) {
                    name = wanted + " (" + (n++) + ")";
                    continue outer;
                }
            }
            return name;
        }
    }

    // Overrides: list / add / field list

    /** The profile whose overrides are being listed. */
    private RichPresenceProfile baseProfileForOverrides() {
        if (currentTab == TAB_MULTI && editingServerProfile != null) {
            return baseCopies.get(TAB_MULTI);
        }
        return baseCopies.get(currentTab);
    }

    private int buildOverridesList(int x, int y, int w, boolean screenMode) {
        RichPresenceProfile base = baseProfileForOverrides();
        if (base == null) return y;

        Map<String, RichPresenceProfile.Override> overrides = screenMode
                ? base.getScreenOverrides() : base.getDimensionOverrides();
        int removeW = 56;

        for (Map.Entry<String, RichPresenceProfile.Override> entry : overrides.entrySet()) {
            final String key = entry.getKey();
            if (y + 20 > panelBottom - 72) break;

            int fieldCount = countOverriddenFields(entry.getValue());
            Component label = Component.literal(cap(screenMode ? screenKeyLabel(key) : prettyDimensionId(key), 20))
                    .append(Component.literal(fieldCount == 0
                                    ? " - " + Component.translatable("discordrpc.override.empty").getString()
                                    : " - " + Component.translatable("discordrpc.override.field_count", fieldCount).getString())
                            .withStyle(s -> s.withColor(0x999999)));

            addDynamic(Button.builder(label, b -> startEditingOverride(key, screenMode))
                    .bounds(x, y, w - removeW - 4, 20)
                    .tooltip(Tooltip.create(Component.translatable("discordrpc.override.edit.tooltip")))
                    .build());
            addDynamic(Button.builder(Component.translatable("discordrpc.button.remove"),
                            b -> { overrides.remove(key); rebuildContent(); updatePreview(); })
                    .bounds(x + w - removeW, y, removeW, 20).build());
            y += 22;
        }

        addDynamic(Button.builder(Component.translatable(screenMode
                        ? "discordrpc.button.add_screen_override"
                        : "discordrpc.button.add_dimension_override"),
                        b -> { addOverrideMode = true; rebuildContent(); })
                .bounds(x, y, w, 20).build());
        return y + 26;
    }

    private void buildAddOverridePicker() {
        int x = panelX, w = panelW;
        int y = contentStartY();
        boolean screenMode = currentTab == TAB_MENU;

        y = addSectionHeader(y, screenMode
                ? "discordrpc.section.pick_screen"
                : "discordrpc.section.pick_dimension");

        RichPresenceProfile base = baseProfileForOverrides();
        Map<String, RichPresenceProfile.Override> existing = base == null
                ? Collections.emptyMap()
                : (screenMode ? base.getScreenOverrides() : base.getDimensionOverrides());

        if (screenMode) {
            for (ScreenContext sc : ScreenContext.values()) {
                if (existing.containsKey(sc.key)) continue;
                if (y + 20 > panelBottom - 48) break;
                final String key = sc.key;
                addDynamic(Button.builder(Component.translatable(sc.translationKey()),
                                b -> { createOverride(key, true); addOverrideMode = false; startEditingOverride(key, true); })
                        .bounds(x, y, w, 20).build());
                y += 22;
            }
        } else {
            for (String[] dim : COMMON_DIMENSIONS) {
                if (existing.containsKey(dim[0])) continue;
                if (y + 20 > panelBottom - 72) break;
                final String id = dim[0];
                addDynamic(Button.builder(Component.literal(dim[1]),
                                b -> { createOverride(id, false); addOverrideMode = false; startEditingOverride(id, false); })
                        .bounds(x, y, w, 20)
                        .tooltip(Tooltip.create(Component.literal(dim[0])))
                        .build());
                y += 22;
            }

            y = addFieldLabel(x, y + 2, "discordrpc.override.custom_dimension");
            int addW = 50;
            addOverrideCustomBox = new EditBox(font, x, y, w - addW - 4, 20,
                    Component.translatable("discordrpc.override.custom_dimension"));
            addOverrideCustomBox.setHint(Component.literal("modid:dimension_name"));
            addOverrideCustomBox.setMaxLength(128);
            addDynamic(addOverrideCustomBox);
            addDynamic(Button.builder(Component.translatable("discordrpc.button.add"),
                            b -> {
                                String v = addOverrideCustomBox.getValue().trim();
                                if (!v.isEmpty() && !existing.containsKey(v)) {
                                    createOverride(v, false);
                                    addOverrideMode = false;
                                    startEditingOverride(v, false);
                                }
                            })
                    .bounds(x + w - addW, y, addW, 20).build());
            y += 26;
        }

        addDynamic(Button.builder(CommonComponents.GUI_CANCEL,
                        b -> { addOverrideMode = false; rebuildContent(); })
                .bounds(x, y + 2, w, 20).build());
    }

    private void createOverride(String key, boolean screenMode) {
        RichPresenceProfile base = baseProfileForOverrides();
        if (base == null) return;
        Map<String, RichPresenceProfile.Override> map = screenMode
                ? base.getScreenOverrides() : base.getDimensionOverrides();
        map.putIfAbsent(key, new RichPresenceProfile.Override());
    }

    private void startEditingOverride(String key, boolean screenMode) {
        RichPresenceProfile base = baseProfileForOverrides();
        if (base == null) return;
        Map<String, RichPresenceProfile.Override> map = screenMode
                ? base.getScreenOverrides() : base.getDimensionOverrides();
        RichPresenceProfile.Override ov = map.computeIfAbsent(key, k -> new RichPresenceProfile.Override());

        editingOverrideKey = key;
        editingOverrideIsScreen = screenMode;
        overrideParentProfile = base;
        editingProfile = base.resolveWith(ov).deepCopy();
        currentZone = DiscordPreviewWidget.Zone.NONE;
        addOverrideMode = false;
        rebuildContent();
        updatePreview();
    }

    /** Diffs the override buffer back into a partial Override and stores it. */
    private void commitOverrideIfEditing() {
        if (editingOverrideKey == null || overrideParentProfile == null || editingProfile == null) return;

        RichPresenceProfile.Override ov = diffAgainstBase(overrideParentProfile, editingProfile);
        Map<String, RichPresenceProfile.Override> map = editingOverrideIsScreen
                ? overrideParentProfile.getScreenOverrides()
                : overrideParentProfile.getDimensionOverrides();
        if (ov.isEmpty()) {
            map.remove(editingOverrideKey);
        } else {
            map.put(editingOverrideKey, ov);
        }

        editingOverrideKey = null;
        overrideParentProfile = null;
        editingProfile = baseCopies.get(editingServerProfile != null ? TAB_MULTI : currentTab);
    }

    private void exitOverrideEditMode() {
        commitOverrideIfEditing();
        currentZone = DiscordPreviewWidget.Zone.NONE;
        rebuildContent();
        updatePreview();
    }

    private void buildOverrideFieldList() {
        if (overrideParentProfile == null) return;
        int x = panelX, w = panelW;
        int y = contentStartY();

        y = addSectionHeader(y, Component.translatable("discordrpc.override.editing", cap(currentOverrideLabel(), 22)));
        Component explain = Component.translatable("discordrpc.override.explain", cap(currentBaseLabel(), 20));
        addWrappedText(explain, x, y, w, COLOR_LABEL, false);
        y += wrappedHeight(explain, w) + 4;

        y = addOverrideFieldRow(x, y, w, "discordrpc.zone.details",
                editingProfile.getDetails(), overrideParentProfile.getDetails(), DiscordPreviewWidget.Zone.DETAILS);
        y = addOverrideFieldRow(x, y, w, "discordrpc.zone.state",
                editingProfile.getState(), overrideParentProfile.getState(), DiscordPreviewWidget.Zone.STATE);
        y = addOverrideFieldRow(x, y, w, "discordrpc.zone.large_image",
                editingProfile.getLargeImageKey(), overrideParentProfile.getLargeImageKey(), DiscordPreviewWidget.Zone.LARGE_IMAGE);
        y = addOverrideFieldRow(x, y, w, "discordrpc.zone.small_image",
                editingProfile.getSmallImageKey(), overrideParentProfile.getSmallImageKey(), DiscordPreviewWidget.Zone.SMALL_IMAGE);
        y = addOverrideFieldRow(x, y, w, "discordrpc.zone.timestamp",
                editingProfile.getTimestampMode().name(), overrideParentProfile.getTimestampMode().name(), DiscordPreviewWidget.Zone.TIMESTAMP);
        y = addOverrideFieldRow(x, y, w, "discordrpc.zone.button1",
                editingProfile.getButton1Label(), overrideParentProfile.getButton1Label(), DiscordPreviewWidget.Zone.BUTTON1);
        y = addOverrideFieldRow(x, y, w, "discordrpc.zone.button2",
                editingProfile.getButton2Label(), overrideParentProfile.getButton2Label(), DiscordPreviewWidget.Zone.BUTTON2);
        y += 4;

        int third = (w - 8) / 3;
        addDynamic(Button.builder(CommonComponents.GUI_DONE, b -> exitOverrideEditMode())
                .bounds(x, y, third, 20)
                .tooltip(Tooltip.create(Component.translatable("discordrpc.override.done.tooltip")))
                .build());
        addDynamic(Button.builder(Component.translatable("discordrpc.button.delete"),
                        b -> {
                            Map<String, RichPresenceProfile.Override> map = editingOverrideIsScreen
                                    ? overrideParentProfile.getScreenOverrides()
                                    : overrideParentProfile.getDimensionOverrides();
                            map.remove(editingOverrideKey);
                            editingOverrideKey = null;
                            overrideParentProfile = null;
                            editingProfile = baseCopies.get(editingServerProfile != null ? TAB_MULTI : currentTab);
                            currentZone = DiscordPreviewWidget.Zone.NONE;
                            rebuildContent();
                            updatePreview();
                        })
                .bounds(x + third + 4, y, third, 20)
                .tooltip(Tooltip.create(Component.translatable("discordrpc.override.delete.tooltip")))
                .build());
        addDynamic(Button.builder(Component.translatable("discordrpc.button.clear_fields"),
                        b -> {
                            editingProfile = overrideParentProfile.deepCopy();
                            rebuildContent();
                            updatePreview();
                        })
                .bounds(x + (third + 4) * 2, y, w - (third + 4) * 2, 20)
                .tooltip(Tooltip.create(Component.translatable("discordrpc.override.clear.tooltip")))
                .build());
    }

    private int addOverrideFieldRow(int x, int y, int w, String labelKey,
                                    String currentVal, String parentVal, DiscordPreviewWidget.Zone zone) {
        boolean overridden = !eq(currentVal, parentVal);
        Component status = overridden
                ? Component.literal(Component.translatable("discordrpc.override.state.overridden").getString()
                        + (currentVal == null || currentVal.isEmpty() ? "" : ": " + cap(currentVal, 16)))
                        .withStyle(s -> s.withColor(0x55FF55))
                : Component.translatable("discordrpc.override.state.inherited").withStyle(s -> s.withColor(0x999999));
        Component label = Component.translatable(labelKey).append(Component.literal(" - ")).append(status);

        addDynamic(Button.builder(label, b -> {
            currentZone = zone;
            rebuildContent();
        }).bounds(x, y, w, 20).build());
        return y + 22;
    }

    private static int countOverriddenFields(RichPresenceProfile.Override ov) {
        if (ov == null) return 0;
        int n = 0;
        if (ov.details != null) n++;
        if (ov.state != null) n++;
        if (ov.timestampMode != null) n++;
        if (ov.showPartySize != null) n++;
        if (ov.largeImageKey != null) n++;
        if (ov.largeImageText != null) n++;
        if (ov.smallImageKey != null) n++;
        if (ov.smallImageText != null) n++;
        if (ov.button1Label != null) n++;
        if (ov.button1Url != null) n++;
        if (ov.button2Label != null) n++;
        if (ov.button2Url != null) n++;
        return n;
    }

    private static RichPresenceProfile.Override diffAgainstBase(RichPresenceProfile base, RichPresenceProfile edited) {
        RichPresenceProfile.Override ov = new RichPresenceProfile.Override();
        if (!eq(base.getDetails(), edited.getDetails())) ov.details = edited.getDetails();
        if (!eq(base.getState(), edited.getState())) ov.state = edited.getState();
        if (base.getTimestampMode() != edited.getTimestampMode()) ov.timestampMode = edited.getTimestampMode();
        if (base.isShowPartySize() != edited.isShowPartySize()) ov.showPartySize = edited.isShowPartySize();
        if (!eq(base.getLargeImageKey(), edited.getLargeImageKey())) ov.largeImageKey = edited.getLargeImageKey();
        if (!eq(base.getLargeImageText(), edited.getLargeImageText())) ov.largeImageText = edited.getLargeImageText();
        if (!eq(base.getSmallImageKey(), edited.getSmallImageKey())) ov.smallImageKey = edited.getSmallImageKey();
        if (!eq(base.getSmallImageText(), edited.getSmallImageText())) ov.smallImageText = edited.getSmallImageText();
        if (!eq(base.getButton1Label(), edited.getButton1Label())) ov.button1Label = edited.getButton1Label();
        if (!eq(base.getButton1Url(), edited.getButton1Url())) ov.button1Url = edited.getButton1Url();
        if (!eq(base.getButton2Label(), edited.getButton2Label())) ov.button2Label = edited.getButton2Label();
        if (!eq(base.getButton2Url(), edited.getButton2Url())) ov.button2Url = edited.getButton2Url();
        return ov;
    }

    private static boolean eq(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        return a.equals(b);
    }

    private static void applyOverridesFrom(RichPresenceProfile dst, RichPresenceProfile src) {
        dst.getDimensionOverrides().clear();
        for (Map.Entry<String, RichPresenceProfile.Override> e : src.getDimensionOverrides().entrySet()) {
            dst.getDimensionOverrides().put(e.getKey(), e.getValue().copy());
        }
        dst.getScreenOverrides().clear();
        for (Map.Entry<String, RichPresenceProfile.Override> e : src.getScreenOverrides().entrySet()) {
            dst.getScreenOverrides().put(e.getKey(), e.getValue().copy());
        }
    }

    // Zone editors

    private void buildZoneEditor() {
        int x = panelX, w = panelW;
        int y = contentStartY();

        // Header: what is being edited, plus the context it belongs to.
        Component headerName = Component.translatable(currentZone.translationKey());
        Component context = null;
        if (editingOverrideKey != null) {
            context = Component.translatable("discordrpc.editor.context.override", currentOverrideLabel());
        } else if (editingServerProfile != null) {
            context = Component.literal(cap(editingServerProfile.getName(), 20));
        }
        addText(headerName, x, y, COLOR_WHITE, true);
        if (context != null) {
            addText(context.copy().withStyle(s -> s.withColor(0xA0A0A0)), x + font.width(headerName) + 8, y, COLOR_LABEL, false);
        }
        y += 12;
        addDynamic(Button.builder(Component.translatable("gui.back"), b -> {
            currentZone = DiscordPreviewWidget.Zone.NONE;
            if (preview != null) preview.setSelectedZone(DiscordPreviewWidget.Zone.NONE);
            rebuildContent();
        }).bounds(x, y, 60, 20).build());
        y += 26;

        switch (currentZone) {
            case DETAILS, STATE -> buildTextEditor(x, y, w);
            case LARGE_IMAGE -> buildImageEditor(x, y, w, true);
            case SMALL_IMAGE -> buildImageEditor(x, y, w, false);
            case TIMESTAMP -> buildTimestampEditor(x, y, w);
            case BUTTON1 -> buildButtonEditor(x, y, w, true);
            case BUTTON2 -> buildButtonEditor(x, y, w, false);
            default -> {}
        }
    }

    private void buildTextEditor(int x, int y, int w) {
        y = addFieldLabel(x, y, "discordrpc.editor.details");
        EditBox details = new EditBox(font, x, y, w, 20, Component.translatable("discordrpc.editor.details"));
        details.setHint(Component.literal("Playing Minecraft"));
        details.setMaxLength(128);
        details.setValue(editingProfile.getDetails());
        details.setResponder(s -> { editingProfile.setDetails(s); updatePreview(); });
        addDynamic(details);
        y += 26;

        y = addFieldLabel(x, y, "discordrpc.editor.state");
        EditBox state = new EditBox(font, x, y, w, 20, Component.translatable("discordrpc.editor.state"));
        state.setHint(Component.literal("{dimension} - {biome}"));
        state.setMaxLength(128);
        state.setValue(editingProfile.getState());
        state.setResponder(s -> { editingProfile.setState(s); updatePreview(); });
        addDynamic(state);
        y += 24;

        if (editingOverrideKey != null) {
            y = addInheritResetRow(x, y, w, () -> {
                editingProfile.setDetails(overrideParentProfile.getDetails());
                editingProfile.setState(overrideParentProfile.getState());
            });
        }

        if (panelBottom - y > 40) {
            y = addSectionHeader(y + 2, "discordrpc.section.placeholders");
            variablesY = y;
        }
    }

    private void buildImageEditor(int x, int y, int w, boolean isLarge) {
        String currentKey = isLarge ? editingProfile.getLargeImageKey() : editingProfile.getSmallImageKey();
        String currentText = isLarge ? editingProfile.getLargeImageText() : editingProfile.getSmallImageText();

        y = addFieldLabel(x, y, "discordrpc.editor.image_key");
        EditBox keyBox = new EditBox(font, x, y, w, 20, Component.translatable("discordrpc.editor.image_key"));
        keyBox.setHint(Component.literal("minecraft"));
        keyBox.setMaxLength(64);
        keyBox.setValue(currentKey);
        keyBox.setResponder(s -> {
            if (isLarge) editingProfile.setLargeImageKey(s);
            else editingProfile.setSmallImageKey(s);
            updatePreview();
        });
        addDynamic(keyBox);
        y += 26;

        y = addFieldLabel(x, y, "discordrpc.editor.image_tooltip");
        EditBox textBox = new EditBox(font, x, y, w, 20, Component.translatable("discordrpc.editor.image_tooltip"));
        textBox.setHint(Component.literal("Minecraft {version}"));
        textBox.setMaxLength(128);
        textBox.setValue(currentText);
        textBox.setResponder(s -> {
            if (isLarge) editingProfile.setLargeImageText(s);
            else editingProfile.setSmallImageText(s);
        });
        addDynamic(textBox);
        y += 24;

        if (editingOverrideKey != null) {
            y = addInheritResetRow(x, y, w, () -> {
                if (isLarge) {
                    editingProfile.setLargeImageKey(overrideParentProfile.getLargeImageKey());
                    editingProfile.setLargeImageText(overrideParentProfile.getLargeImageText());
                } else {
                    editingProfile.setSmallImageKey(overrideParentProfile.getSmallImageKey());
                    editingProfile.setSmallImageText(overrideParentProfile.getSmallImageText());
                }
            });
        }

        int pickerH = panelBottom - y;
        if (pickerH >= 48) {
            ImagePickerWidget picker = new ImagePickerWidget(
                    x, y, w, pickerH, config.getImagesDir(), !isLarge, key -> {
                        keyBox.setValue(key);
                        if (isLarge) editingProfile.setLargeImageKey(key);
                        else editingProfile.setSmallImageKey(key);
                        updatePreview();
                    });
            picker.setSelectedKey(currentKey);
            addDynamic(picker);
        }
    }

    private void buildTimestampEditor(int x, int y, int w) {
        addDynamic(CycleButton.<RichPresenceProfile.TimestampMode>builder(
                        mode -> Component.translatable(mode.translationKey()), editingProfile.getTimestampMode())
                .withValues(RichPresenceProfile.TimestampMode.values())
                .create(x, y, w, 20, Component.translatable("discordrpc.editor.timestamp"),
                        (btn, mode) -> { editingProfile.setTimestampMode(mode); updatePreview(); }));
        y += 24;

        addWrappedText(Component.translatable("discordrpc.editor.timestamp.hint"), x, y, w, COLOR_HINT, false);
        y += wrappedHeight(Component.translatable("discordrpc.editor.timestamp.hint"), w) + 4;

        if (editingOverrideKey != null) {
            addInheritResetRow(x, y, w, () ->
                    editingProfile.setTimestampMode(overrideParentProfile.getTimestampMode()));
        }
    }

    private void buildButtonEditor(int x, int y, int w, boolean isBtn1) {
        String label = isBtn1 ? editingProfile.getButton1Label() : editingProfile.getButton2Label();
        String url = isBtn1 ? editingProfile.getButton1Url() : editingProfile.getButton2Url();

        y = addFieldLabel(x, y, "discordrpc.editor.button_label");
        EditBox labelBox = new EditBox(font, x, y, w, 20, Component.translatable("discordrpc.editor.button_label"));
        labelBox.setHint(Component.literal("Join my server"));
        labelBox.setMaxLength(32);
        labelBox.setValue(label);
        labelBox.setResponder(s -> {
            if (isBtn1) editingProfile.setButton1Label(s);
            else editingProfile.setButton2Label(s);
            updatePreview();
        });
        addDynamic(labelBox);
        y += 26;

        y = addFieldLabel(x, y, "discordrpc.editor.button_url");
        EditBox urlBox = new EditBox(font, x, y, w, 20, Component.translatable("discordrpc.editor.button_url"));
        urlBox.setHint(Component.literal("https://example.com"));
        urlBox.setMaxLength(512);
        urlBox.setValue(url);
        urlBox.setResponder(s -> {
            if (isBtn1) editingProfile.setButton1Url(s);
            else editingProfile.setButton2Url(s);
        });
        addDynamic(urlBox);
        y += 24;

        addWrappedText(Component.translatable("discordrpc.editor.button.hint"), x, y, w, COLOR_HINT, false);
        y += wrappedHeight(Component.translatable("discordrpc.editor.button.hint"), w) + 4;

        if (editingOverrideKey != null) {
            addInheritResetRow(x, y, w, () -> {
                if (isBtn1) {
                    editingProfile.setButton1Label(overrideParentProfile.getButton1Label());
                    editingProfile.setButton1Url(overrideParentProfile.getButton1Url());
                } else {
                    editingProfile.setButton2Label(overrideParentProfile.getButton2Label());
                    editingProfile.setButton2Url(overrideParentProfile.getButton2Url());
                }
            });
        }
    }

    private int addInheritResetRow(int x, int y, int w, Runnable reset) {
        addDynamic(Button.builder(Component.translatable("discordrpc.button.reset_to_inherit"), b -> {
            if (overrideParentProfile != null) {
                reset.run();
                rebuildContent();
                updatePreview();
            }
        }).bounds(x, y, w, 20)
          .tooltip(Tooltip.create(Component.translatable("discordrpc.button.reset_to_inherit.tooltip")))
          .build());
        return y + 24;
    }

    // Settings tab

    private void buildSettingsPanel() {
        int colW = 150;
        int gap = 10;
        boolean twoCols = width >= colW * 2 + gap + 16;
        int blockW = twoCols ? colW * 2 + gap : colW;
        int x = (width - blockW) / 2;
        int y = contentTop + 12;

        // Connection line + reconnect button when it is down.
        var rpc = DiscordRPCMod.getInstance().getRpcManager();
        DiscordIPC.State state = rpc != null ? rpc.getConnectionState() : DiscordIPC.State.DISCONNECTED;
        Component status = switch (state) {
            case CONNECTED -> {
                String user = rpc.getDiscordUser();
                yield user.isEmpty()
                        ? Component.translatable("discordrpc.status.connected")
                        : Component.translatable("discordrpc.status.connected_as", user);
            }
            case CONNECTING -> Component.translatable("discordrpc.status.connecting");
            default -> Component.translatable("discordrpc.status.disconnected");
        };
        int statusColor = switch (state) {
            case CONNECTED -> COLOR_GREEN;
            case CONNECTING -> COLOR_YELLOW;
            default -> COLOR_RED;
        };
        addText(status, x, y + 2, statusColor, true);
        if (state == DiscordIPC.State.DISCONNECTED && config.isEnabled()) {
            addDynamic(Button.builder(Component.translatable("discordrpc.button.reconnect"), b -> {
                if (rpc != null) rpc.connect();
                flashStatus(Component.translatable("discordrpc.status.reconnecting"));
            }).bounds(x + blockW - 80, y - 3, 80, 20).build());
        }
        y += 20;

        // Launcher conflict notice, when one applies.
        if (LauncherConflict.current() != LauncherConflict.Launcher.NONE && !config.isLauncherWarningDismissed()) {
            String launcher = LauncherConflict.current().displayName();
            Component title = Component.translatable("discordrpc.conflict.title", launcher);
            Component body = Component.translatable("discordrpc.conflict.body", launcher);
            addText(title, x, y, COLOR_GOLD, true);
            y += 11;
            addWrappedText(body, x, y, blockW - 70, COLOR_LABEL, false);
            int bodyH = wrappedHeight(body, blockW - 70);
            addDynamic(Button.builder(Component.translatable("discordrpc.button.dismiss"), b -> {
                        config.setLauncherWarningDismissed(true);
                        config.save();
                        rebuildContent();
                    })
                    .bounds(x + blockW - 60, y, 60, 20)
                    .tooltip(Tooltip.create(Component.translatable("discordrpc.button.dismiss.tooltip")))
                    .build());
            y += Math.max(bodyH, 22) + 6;
        }

        int leftX = x;
        int rightX = twoCols ? x + colW + gap : x;
        int rowStep = 24;

        record Slot(int x, int y) {}
        List<Slot> slots = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            int sx = twoCols ? (i % 2 == 0 ? leftX : rightX) : leftX;
            int sy = y + (twoCols ? (i / 2) : i) * rowStep;
            slots.add(new Slot(sx, sy));
        }

        addDynamic(CycleButton.onOffBuilder(config.isEnabled())
                .create(slots.get(0).x(), slots.get(0).y(), colW, 20,
                        Component.translatable("discordrpc.settings.enabled"),
                        (b, v) -> config.setEnabled(v)));
        addDynamic(CycleButton.onOffBuilder(config.isAutoReconnect())
                .create(slots.get(1).x(), slots.get(1).y(), colW, 20,
                        Component.translatable("discordrpc.settings.auto_reconnect"),
                        (b, v) -> config.setAutoReconnect(v)));
        addDynamic(CycleButton.onOffBuilder(config.isShowInMainMenu())
                .create(slots.get(2).x(), slots.get(2).y(), colW, 20,
                        Component.translatable("discordrpc.settings.show_in_menu"),
                        (b, v) -> config.setShowInMainMenu(v)));
        addDynamic(CycleButton.onOffBuilder(config.isAfkDetection())
                .create(slots.get(3).x(), slots.get(3).y(), colW, 20,
                        Component.translatable("discordrpc.settings.afk_detection"),
                        (b, v) -> config.setAfkDetection(v)));
        addDynamic(CycleButton.onOffBuilder(config.isHideServerIp())
                .create(slots.get(4).x(), slots.get(4).y(), colW, 20,
                        Component.translatable("discordrpc.settings.hide_server_ip"),
                        (b, v) -> config.setHideServerIp(v)));
        addDynamic(CycleButton.onOffBuilder(config.isHideCoordinates())
                .create(slots.get(5).x(), slots.get(5).y(), colW, 20,
                        Component.translatable("discordrpc.settings.hide_coordinates"),
                        (b, v) -> config.setHideCoordinates(v)));

        addDynamic(new SecondsSlider(slots.get(6).x(), slots.get(6).y(), colW,
                "discordrpc.settings.update_interval", 1, 60, config.getUpdateInterval(),
                config::setUpdateInterval));
        addDynamic(new SecondsSlider(slots.get(7).x(), slots.get(7).y(), colW,
                "discordrpc.settings.afk_timeout", 30, 900, config.getAfkTimeout(),
                config::setAfkTimeout));

        y += (twoCols ? 4 : 8) * rowStep + 8;

        y = addSectionHeaderAt(x, y, blockW, "discordrpc.section.profile_sharing");
        int half = (blockW - 8) / 2;
        addDynamic(Button.builder(Component.translatable("discordrpc.button.export_profiles"),
                        b -> minecraft.setScreen(new ProfileExportScreen(this, config)))
                .bounds(x, y, half, 20)
                .tooltip(Tooltip.create(Component.translatable("discordrpc.button.export_profiles.tooltip")))
                .build());
        addDynamic(Button.builder(Component.translatable("discordrpc.button.import_profiles"),
                        b -> minecraft.setScreen(new ProfileImportScreen(this, config)))
                .bounds(x + half + 8, y, blockW - half - 8, 20)
                .tooltip(Tooltip.create(Component.translatable("discordrpc.button.import_profiles.tooltip")))
                .build());
        y += 24;
        addDynamic(Button.builder(Component.translatable("discordrpc.button.open_profiles_folder"),
                        b -> Util.getPlatform().openPath(config.getProfilesDir()))
                .bounds(x, y, half, 20)
                .tooltip(Tooltip.create(Component.translatable("discordrpc.button.open_profiles_folder.tooltip")))
                .build());
        addDynamic(Button.builder(Component.translatable(confirmReset
                                ? "discordrpc.button.confirm_reset" : "discordrpc.button.reset_everything"),
                        b -> {
                            if (!confirmReset) {
                                // First click arms the confirmation - resetting
                                // wipes every profile and saves immediately.
                                confirmReset = true;
                                rebuildContent();
                                flashStatus(Component.translatable("discordrpc.status.reset_confirm"));
                                return;
                            }
                            confirmReset = false;
                            config.resetToDefaults();
                            baseCopies.clear();
                            realProfiles.clear();
                            stashedMultiBase = null;
                            loadServerProfiles();
                            loadProfileForTab(currentTab);
                            rebuildContent();
                            flashStatus(Component.translatable("discordrpc.status.settings_reset"));
                        })
                .bounds(x + half + 8, y, blockW - half - 8, 20)
                .tooltip(Tooltip.create(Component.translatable("discordrpc.button.reset_everything.tooltip")))
                .build());
    }

    /** Vanilla slider showing "Label: 3s" / "Label: 5m". */
    private class SecondsSlider extends AbstractSliderButton {
        private final String langKey;
        private final int min, max;
        private final IntConsumer onApply;

        SecondsSlider(int x, int y, int w, String langKey, int min, int max, int initial, IntConsumer onApply) {
            super(x, y, w, 20, Component.empty(), (double) (initial - min) / (max - min));
            this.langKey = langKey;
            this.min = min;
            this.max = max;
            this.onApply = onApply;
            updateMessage();
        }

        private int seconds() {
            return min + (int) Math.round(value * (max - min));
        }

        private String pretty() {
            int s = seconds();
            if (s >= 60 && s % 60 == 0) return (s / 60) + "m";
            if (s > 60) return (s / 60) + "m " + (s % 60) + "s";
            return s + "s";
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable(langKey, pretty()));
        }

        @Override
        protected void applyValue() {
            onApply.accept(seconds());
        }
    }

    // Rendering

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        // The default screen background (blurred world / menu texture) shows through;
        // a footer separator is the only framing, like vanilla in-game menus.
        boolean inWorld = minecraft.level != null;
        g.blit(RenderPipelines.GUI_TEXTURED,
                inWorld ? INWORLD_FOOTER_SEPARATOR : FOOTER_SEPARATOR,
                0, contentBottom, 0.0F, 0.0F, width, 2, 32, 2);

        super.extractRenderState(g, mx, my, pt);

        for (TextLine line : textLines) {
            g.text(font, line.text(), line.x(), line.y(), line.color(), line.shadow());
        }

        if (variablesY >= 0) {
            renderVariablesReference(g, variablesY);
        }

        if (previewVisible() && preview != null) {
            renderPreviewHint(g);
        }

        long now = System.currentTimeMillis();
        if (statusMessage != null && statusUntilMs > now) {
            int sw = font.width(statusMessage);
            int sx = (width - sw) / 2;
            int sy = contentBottom - 14;
            g.fill(sx - 4, sy - 2, sx + sw + 4, sy + 10, 0x90000000);
            g.text(font, statusMessage, sx, sy, COLOR_GREEN, true);
        }
    }

    private void renderPreviewHint(GuiGraphicsExtractor g) {
        Component hint = Component.translatable("discordrpc.preview.hint");
        int y = preview.getY() + preview.getHeight() + 6;
        for (var line : font.split(hint, previewW - 8)) {
            int lw = font.width(line);
            g.text(font, line, previewX + (previewW - lw) / 2, y, COLOR_HINT, false);
            y += 10;
        }
    }

    private static final String[][] VARIABLES = {
        {"{player}", "Your name"},        {"{server}", "Server name"},
        {"{server_ip}", "Server IP"},     {"{world}", "World name"},
        {"{dimension}", "Dimension"},     {"{biome}", "Biome"},
        {"{x}", "X coordinate"},          {"{y}", "Y coordinate"},
        {"{z}", "Z coordinate"},          {"{health}", "Health"},
        {"{max_health}", "Max health"},   {"{hunger}", "Hunger"},
        {"{armor}", "Armor"},             {"{xp_level}", "XP level"},
        {"{gamemode}", "Game mode"},      {"{difficulty}", "Difficulty"},
        {"{online}", "Players online"},   {"{max_players}", "Player cap"},
        {"{ping}", "Ping"},               {"{fps}", "FPS"},
        {"{time}", "Time of day"},        {"{day}", "Day number"},
        {"{weather}", "Weather"},         {"{held_item}", "Held item"},
        {"{version}", "Game version"},    {"{modcount}", "Mod count"},
    };

    private void renderVariablesReference(GuiGraphicsExtractor g, int startY) {
        int halfW = panelW / 2 - 4;
        int rowH = 10;
        for (int i = 0; i < VARIABLES.length; i++) {
            int colX = (i % 2 == 0) ? panelX : panelX + halfW + 8;
            int rowY = startY + (i / 2) * rowH;
            if (rowY + 9 > panelBottom - 24) break;
            g.text(font, VARIABLES[i][0], colX, rowY, COLOR_VAR, false);
            int keyW = font.width(VARIABLES[i][0]);
            String desc = " " + VARIABLES[i][1];
            int maxW = halfW - keyW - 2;
            while (font.width(desc) > maxW && desc.length() > 2) {
                desc = desc.substring(0, desc.length() - 1);
            }
            g.text(font, desc, colX + keyW, rowY, COLOR_HINT, false);
        }
    }

    // Preview refresh

    private void updatePreview() {
        if (preview == null || editingProfile == null) return;
        preview.setDetails(editingProfile.getDetails());
        preview.setState(editingProfile.getState());
        preview.setHasLargeImage(!editingProfile.getLargeImageKey().isEmpty());
        preview.setHasSmallImage(!editingProfile.getSmallImageKey().isEmpty());
        preview.setButton1Label(editingProfile.getButton1Label());
        preview.setButton2Label(editingProfile.getButton2Label());
        preview.setShowTimestamp(editingProfile.getTimestampMode() != RichPresenceProfile.TimestampMode.NONE);
        preview.setSelectedZone(currentZone);

        if (editingProfile.getTimestampMode() == RichPresenceProfile.TimestampMode.ELAPSED) {
            long s = (System.currentTimeMillis() - openedAtMs) / 1000;
            preview.setElapsedTime(String.format("%02d:%02d elapsed", s / 60, s % 60));
        } else {
            preview.setElapsedTime("");
        }

        preview.setLargeImageTexture(ImagePickerWidget.findTexture(editingProfile.getLargeImageKey()));
        preview.setSmallImageTexture(ImagePickerWidget.findTexture(editingProfile.getSmallImageKey()));
    }

    @Override
    public void tick() {
        super.tick();
        updatePreview();
    }

    // Save / close

    private void saveAndClose() {
        commitOverrideIfEditing();

        for (Map.Entry<Integer, RichPresenceProfile> entry : realProfiles.entrySet()) {
            RichPresenceProfile copy = baseCopies.get(entry.getKey());
            if (copy != null) {
                entry.getValue().applyPresenceFrom(copy);
                entry.getValue().setName(copy.getName());
                if (entry.getValue().getContextType() == RichPresenceProfile.ContextType.SPECIFIC_SERVER) {
                    entry.getValue().setContextFilter(copy.getContextFilter());
                }
                applyOverridesFrom(entry.getValue(), copy);
            }
        }

        config.save();

        var rpc = DiscordRPCMod.getInstance().getRpcManager();
        if (rpc != null) {
            if (!config.isEnabled()) {
                rpc.disconnect();
            } else if (rpc.isConnected()) {
                rpc.restartUpdateLoop();
                rpc.forceUpdate();
            } else {
                rpc.connect();
            }
        }
        minecraft.setScreen(parent);
    }

    @Override
    public void onClose() {
        baseCopies.clear();
        realProfiles.clear();
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (tabBar != null && tabBar.keyPressed(event)) return true;
        return super.keyPressed(event);
    }

    private void restoreCurrentTabDefaults() {
        RichPresenceProfile.ContextType ctx = TAB_CONTEXTS[currentTab];
        if (ctx == null) return;
        RichPresenceProfile fresh = getDefaultForContext(ctx);
        baseCopies.put(currentTab, fresh);
        editingProfile = fresh;
        currentZone = DiscordPreviewWidget.Zone.NONE;
        editingServerProfile = null;
        editingOverrideKey = null;
        overrideParentProfile = null;
        addOverrideMode = false;
        rebuildContent();
        updatePreview();
        flashStatus(Component.translatable("discordrpc.status.profile_reset"));
    }

    void flashStatus(Component msg) {
        statusMessage = msg;
        statusUntilMs = System.currentTimeMillis() + 2200;
    }

    /** Re-loads all profiles from config (used after import). */
    void reloadAfterImport() {
        baseCopies.clear();
        realProfiles.clear();
        loadServerProfiles();
        loadProfileForTab(currentTab);
        rebuildContent();
        updatePreview();
    }

    // Small helpers

    private <T extends AbstractWidget> T addDynamic(T widget) {
        dynamicWidgets.add(widget);
        return addRenderableWidget(widget);
    }

    private void clearDynamic() {
        for (AbstractWidget w : dynamicWidgets) removeWidget(w);
        dynamicWidgets.clear();
        textLines.clear();
        variablesY = -1;
        addOverrideCustomBox = null;
    }

    private void addText(Component text, int x, int y, int color, boolean shadow) {
        textLines.add(new TextLine(text, x, y, color, shadow));
    }

    private void addWrappedText(Component text, int x, int y, int w, int color, boolean shadow) {
        int lineY = y;
        for (var line : font.split(text, w)) {
            textLines.add(new TextLine(Component.literal("").append(componentOf(line)), x, lineY, color, shadow));
            lineY += 10;
        }
    }

    /** font.split gives FormattedCharSequence; re-wrap for storage. */
    private static Component componentOf(net.minecraft.util.FormattedCharSequence seq) {
        StringBuilder sb = new StringBuilder();
        seq.accept((idx, style, cp) -> {
            sb.appendCodePoint(cp);
            return true;
        });
        return Component.literal(sb.toString());
    }

    private int wrappedHeight(Component text, int w) {
        return font.split(text, w).size() * 10;
    }

    private int addFieldLabel(int x, int y, String key) {
        addText(Component.translatable(key), x, y, COLOR_LABEL, false);
        return y + 11;
    }

    private int addSectionHeader(int y, String key) {
        return addSectionHeaderAt(panelX, y, panelW, key);
    }

    private int addSectionHeader(int y, Component text) {
        return addSectionHeaderAt(panelX, y, panelW, text);
    }

    private int addSectionHeaderAt(int x, int y, int w, String key) {
        return addSectionHeaderAt(x, y, w, Component.translatable(key));
    }

    private int addSectionHeaderAt(int x, int y, int w, Component text) {
        addText(text, x, y, COLOR_WHITE, true);
        return y + 13;
    }

    private static String cap(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 1) + "..";
    }

    private static String prettyDimensionId(String id) {
        if (id == null) return "";
        return switch (id) {
            case "minecraft:overworld" -> "Overworld";
            case "minecraft:the_nether" -> "The Nether";
            case "minecraft:the_end" -> "The End";
            default -> id;
        };
    }

    private static String screenKeyLabel(String key) {
        ScreenContext sc = ScreenContext.fromKey(key);
        return sc != null ? Component.translatable(sc.translationKey()).getString() : key;
    }

    private String currentBaseLabel() {
        if (editingServerProfile != null) return editingServerProfile.getName();
        return Component.translatable(TAB_TITLE_KEYS[currentTab]).getString();
    }

    private String currentOverrideLabel() {
        if (editingOverrideKey == null) return "";
        return editingOverrideIsScreen
                ? screenKeyLabel(editingOverrideKey)
                : prettyDimensionId(editingOverrideKey);
    }
}
