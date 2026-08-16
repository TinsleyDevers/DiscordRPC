package com.tinsl.discordrpc.gui;

import com.tinsl.discordrpc.config.ModConfig;
import com.tinsl.discordrpc.config.RichPresenceProfile;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.Util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Lists the .json files in {@code config/discordrpc/profiles/} and imports the
 * clicked one. A server profile is added as a new entry; a profile for one of
 * the base tabs (Main Menu / Singleplayer / Multiplayer) is applied onto that
 * tab's existing profile instead - every import stays visible and editable in
 * the GUI rather than becoming an unmanageable hidden profile.
 */
public class ProfileImportScreen extends Screen {

    private static final int HEADER_HEIGHT = 33;
    private static final int FOOTER_HEIGHT = 33;

    private final Screen parent;
    private final ModConfig config;
    private final List<AbstractWidget> dyn = new ArrayList<>();

    private Component statusMessage = null;
    private int statusColor = 0xFF55FF55;
    private long statusUntilMs = 0;
    /** Directory listing cached per init - never re-scanned during rendering. */
    private List<Path> files = List.of();
    /** File whose Delete button was clicked once and is waiting for the confirming click. */
    private Path pendingDelete = null;

    public ProfileImportScreen(Screen parent, ModConfig config) {
        super(Component.translatable("discordrpc.import.title"));
        this.parent = parent;
        this.config = config;
    }

    @Override
    protected void init() {
        clearWidgets();
        dyn.clear();

        int listW = Math.min(320, width - 16);
        int listX = (width - listW) / 2;
        int y = HEADER_HEIGHT + 16;
        int removeW = 56;

        files = listProfileFiles();
        for (Path file : files) {
            if (y + 20 > height - FOOTER_HEIGHT - 8) break;
            String name = stripExt(file.getFileName().toString());
            final Path f = file;
            addDyn(Button.builder(Component.translatable("discordrpc.import.entry", cap(name, 24)),
                            b -> importFile(f))
                    .bounds(listX, y, listW - removeW - 4, 20)
                    .tooltip(Tooltip.create(Component.literal(file.toString())))
                    .build());
            boolean confirming = f.equals(pendingDelete);
            addDyn(Button.builder(Component.translatable(
                                    confirming ? "discordrpc.button.confirm" : "discordrpc.button.delete"),
                            b -> deleteFile(f))
                    .bounds(listX + listW - removeW, y, removeW, 20)
                    .tooltip(Tooltip.create(Component.translatable("discordrpc.import.delete.tooltip")))
                    .build());
            y += 22;
        }

        int btnW = 150;
        int gap = 8;
        int fx = (width - btnW * 2 - gap) / 2;
        int fy = height - FOOTER_HEIGHT + (FOOTER_HEIGHT - 20) / 2;
        addRenderableWidget(Button.builder(Component.translatable("discordrpc.button.open_profiles_folder"),
                        b -> Util.getPlatform().openFile(config.getProfilesDir().toFile()))
                .bounds(fx, fy, btnW, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(fx + btnW + gap, fy, btnW, 20).build());
    }

    private List<Path> listProfileFiles() {
        Path dir = config.getProfilesDir();
        if (!Files.exists(dir)) return List.of();
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }

    private void importFile(Path file) {
        pendingDelete = null;
        RichPresenceProfile p = config.importProfile(file);
        if (p == null) {
            statusMessage = Component.translatable("discordrpc.import.failed", stripExt(file.getFileName().toString()));
            statusColor = 0xFFFF5555;
        } else {
            RichPresenceProfile base = findBaseProfile(p.getContextType());
            if (base != null) {
                // A Main Menu / Singleplayer / Multiplayer profile replaces the
                // presence of the existing tab profile instead of being added as
                // a hidden duplicate that could shadow it via priority.
                base.applyPresenceFrom(p);
                config.save();
                statusMessage = Component.translatable("discordrpc.import.applied", p.getName(), base.getName());
            } else {
                String origName = p.getName();
                String newName = origName + " (Imported)";
                int n = 2;
                while (nameTaken(newName)) {
                    newName = origName + " (Imported " + (n++) + ")";
                }
                p.setName(newName);
                if (p.getContextType() != RichPresenceProfile.ContextType.SPECIFIC_SERVER) {
                    // Contexts with no tab of their own (Always / legacy) must
                    // never out-prioritize the profiles the GUI can manage.
                    p.setPriority(Math.min(p.getPriority(), 0));
                }
                config.getProfiles().add(p);
                config.save();
                statusMessage = Component.translatable("discordrpc.import.done", origName);
            }
            statusColor = 0xFF55FF55;
        }
        statusUntilMs = System.currentTimeMillis() + 2500;
        init();
    }

    /** The single GUI-managed profile for a base context, or null for other contexts. */
    private RichPresenceProfile findBaseProfile(RichPresenceProfile.ContextType ctx) {
        if (ctx != RichPresenceProfile.ContextType.MAIN_MENU
                && ctx != RichPresenceProfile.ContextType.SINGLEPLAYER
                && ctx != RichPresenceProfile.ContextType.MULTIPLAYER) {
            return null;
        }
        for (RichPresenceProfile p : config.getProfiles()) {
            if (p.getContextType() == ctx) return p;
        }
        return null;
    }

    private void deleteFile(Path file) {
        if (!file.equals(pendingDelete)) {
            // First click arms the confirmation; the delete is permanent.
            pendingDelete = file;
            statusMessage = Component.translatable("discordrpc.import.delete_confirm",
                    stripExt(file.getFileName().toString()));
            statusColor = 0xFFFFAA00;
            statusUntilMs = System.currentTimeMillis() + 4000;
            init();
            return;
        }
        pendingDelete = null;
        try {
            Files.deleteIfExists(file);
            statusMessage = Component.translatable("discordrpc.import.deleted", stripExt(file.getFileName().toString()));
            statusColor = 0xFF55FF55;
        } catch (Exception e) {
            statusMessage = Component.translatable("discordrpc.import.delete_failed");
            statusColor = 0xFFFF5555;
        }
        statusUntilMs = System.currentTimeMillis() + 2500;
        init();
    }

    private boolean nameTaken(String n) {
        for (RichPresenceProfile p : config.getProfiles()) {
            if (n.equals(p.getName())) return true;
        }
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        // Custom text draws after super.render so it can never end up under
        // the background layers; same ordering the 1.21+ blur requires.
        renderBackground(g);
        super.render(g, mx, my, pt);
        int bandTop = HEADER_HEIGHT;
        int bandBottom = height - FOOTER_HEIGHT;

        g.drawCenteredString(font, getTitle(), width / 2, (HEADER_HEIGHT - 9) / 2 + 1, 0xFFFFFFFF);
        g.drawCenteredString(font, Component.translatable("discordrpc.import.hint"),
                width / 2, bandTop + 4, 0xFFA0A0A0);

        if (files.isEmpty()) {
            g.drawCenteredString(font, Component.translatable("discordrpc.import.empty"),
                    width / 2, height / 2 - 10, 0xFFA0A0A0);
            g.drawCenteredString(font, Component.translatable("discordrpc.import.empty.hint"),
                    width / 2, height / 2 + 2, 0xFF808080);
        }

        long now = System.currentTimeMillis();
        if (statusMessage != null && statusUntilMs > now) {
            int sw = font.width(statusMessage);
            int sx = (width - sw) / 2;
            int sy = bandBottom - 14;
            g.fill(sx - 4, sy - 2, sx + sw + 4, sy + 10, 0x90000000);
            g.drawString(font, statusMessage, sx, sy, statusColor, true);
        }
    }

    private void addDyn(AbstractWidget w) {
        dyn.add(w);
        addRenderableWidget(w);
    }

    private static String cap(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "..";
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    @Override
    public void onClose() {
        if (parent instanceof ConfigScreen cs) cs.reloadAfterImport();
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
