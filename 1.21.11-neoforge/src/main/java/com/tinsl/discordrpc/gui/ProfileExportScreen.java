package com.tinsl.discordrpc.gui;

import com.tinsl.discordrpc.config.ModConfig;
import com.tinsl.discordrpc.config.RichPresenceProfile;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;

/**
 * Lists every configured profile and saves the clicked one to
 * {@code config/discordrpc/profiles/<name>.json} for sharing.
 */
public class ProfileExportScreen extends Screen {

    private static final Identifier HEADER_SEPARATOR = Identifier.withDefaultNamespace("textures/gui/header_separator.png");
    private static final Identifier FOOTER_SEPARATOR = Identifier.withDefaultNamespace("textures/gui/footer_separator.png");
    private static final Identifier INWORLD_HEADER_SEPARATOR = Identifier.withDefaultNamespace("textures/gui/inworld_header_separator.png");
    private static final Identifier INWORLD_FOOTER_SEPARATOR = Identifier.withDefaultNamespace("textures/gui/inworld_footer_separator.png");

    private static final int HEADER_HEIGHT = 33;
    private static final int FOOTER_HEIGHT = 33;

    private final Screen parent;
    private final ModConfig config;
    private final List<AbstractWidget> dyn = new ArrayList<>();

    private Component statusMessage = null;
    private long statusUntilMs = 0;

    public ProfileExportScreen(Screen parent, ModConfig config) {
        super(Component.translatable("discordrpc.export.title"));
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

        for (RichPresenceProfile p : config.getProfiles()) {
            if (y + 20 > height - FOOTER_HEIGHT - 8) break;
            addDyn(Button.builder(Component.translatable("discordrpc.export.entry", cap(p.getName(), 24)),
                            b -> exportProfile(p))
                    .bounds(listX, y, listW, 20)
                    .tooltip(Tooltip.create(Component.translatable("discordrpc.export.entry.tooltip")))
                    .build());
            y += 22;
        }

        int btnW = 150;
        int gap = 8;
        int fx = (width - btnW * 2 - gap) / 2;
        int fy = height - FOOTER_HEIGHT + (FOOTER_HEIGHT - 20) / 2;
        addRenderableWidget(Button.builder(Component.translatable("discordrpc.button.open_profiles_folder"),
                        b -> Util.getPlatform().openPath(config.getProfilesDir()))
                .bounds(fx, fy, btnW, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(fx + btnW + gap, fy, btnW, 20).build());
    }

    private void exportProfile(RichPresenceProfile p) {
        String filename = p.getName().isEmpty() ? "profile" : p.getName();
        config.exportProfile(p, filename);
        statusMessage = Component.translatable("discordrpc.export.done", p.getName());
        statusUntilMs = System.currentTimeMillis() + 2500;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        boolean inWorld = minecraft.level != null;
        int bandTop = HEADER_HEIGHT;
        int bandBottom = height - FOOTER_HEIGHT;
        g.blit(RenderPipelines.GUI_TEXTURED,
                inWorld ? INWORLD_HEADER_SEPARATOR : HEADER_SEPARATOR,
                0, bandTop - 2, 0.0F, 0.0F, width, 2, 32, 2);
        g.blit(RenderPipelines.GUI_TEXTURED,
                inWorld ? INWORLD_FOOTER_SEPARATOR : FOOTER_SEPARATOR,
                0, bandBottom, 0.0F, 0.0F, width, 2, 32, 2);

        g.drawCenteredString(font, getTitle(), width / 2, (HEADER_HEIGHT - 9) / 2 + 1, 0xFFFFFFFF);
        g.drawCenteredString(font, Component.translatable("discordrpc.export.hint"),
                width / 2, bandTop + 4, 0xFFA0A0A0);

        super.render(g, mx, my, pt);

        long now = System.currentTimeMillis();
        if (statusMessage != null && statusUntilMs > now) {
            int sw = font.width(statusMessage);
            int sx = (width - sw) / 2;
            int sy = bandBottom - 14;
            g.fill(sx - 4, sy - 2, sx + sw + 4, sy + 10, 0x90000000);
            g.drawString(font, statusMessage, sx, sy, 0xFF55FF55, true);
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

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
