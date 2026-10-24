package com.tinsl.discordrpc.gui.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * The Discord accounts found on this PC, as a vanilla selection list (the
 * look of the language and resource pack lists): one row per account with
 * the Discord app it is open in on the right, and a white outline on the
 * picked row. Picking a row only marks it; the screen saves it on Done.
 */
public class AccountListWidget extends AbstractWidget {
    public static final int ROW_HEIGHT = 18;

    /** One row. {@code id} is empty for Auto; {@code detail} is the grey right-hand text. */
    public record Entry(String id, String name, String detail) {}

    private static final int C_BACKGROUND = 0x60000000;
    private static final int C_HOVER = 0x20FFFFFF;
    private static final int C_SELECTED_FILL = 0xFF000000;
    private static final int C_OUTLINE = 0xFFFFFFFF;
    private static final int C_NAME = 0xFFFFFFFF;
    private static final int C_DETAIL = 0xFF808080;

    private final List<Entry> entries;
    private final Consumer<Entry> onPick;
    private String selectedId;

    public AccountListWidget(int x, int y, int w, List<Entry> entries, String selectedId, Consumer<Entry> onPick) {
        super(x, y, w, Math.max(1, entries.size()) * ROW_HEIGHT, Component.translatable("discordrpc.settings.discord_account"));
        this.entries = List.copyOf(entries);
        this.selectedId = selectedId;
        this.onPick = onPick;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
        Font font = Minecraft.getInstance().font;
        int x = getX(), y = getY(), w = getWidth();
        g.fill(x, y, x + w, y + getHeight(), C_BACKGROUND);

        int hovered = isHovered ? rowAt(my) : -1;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            int top = y + i * ROW_HEIGHT;
            if (e.id().equals(selectedId)) {
                g.fill(x, top, x + w, top + ROW_HEIGHT, C_OUTLINE);
                g.fill(x + 1, top + 1, x + w - 1, top + ROW_HEIGHT - 1, C_SELECTED_FILL);
            } else if (i == hovered) {
                g.fill(x, top, x + w, top + ROW_HEIGHT, C_HOVER);
            }

            int textY = top + (ROW_HEIGHT - 8) / 2;
            int detailW = font.width(e.detail());
            int detailX = x + w - 6 - detailW;
            g.drawString(font, e.detail(), detailX, textY, C_DETAIL, false);
            String name = fit(font, e.name(), detailX - x - 14);
            g.drawString(font, name, x + 6, textY, C_NAME, true);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!active || !visible || button != 0) return false;
        int row = isMouseOver(mx, my) ? rowAt(my) : -1;
        if (row < 0) return false;
        Entry e = entries.get(row);
        if (!e.id().equals(selectedId)) {
            selectedId = e.id();
            playDownSound(Minecraft.getInstance().getSoundManager());
            onPick.accept(e);
        }
        return true;
    }

    private int rowAt(double my) {
        int row = (int) Math.floor((my - getY()) / ROW_HEIGHT);
        return row >= 0 && row < entries.size() ? row : -1;
    }

    private static String fit(Font font, String s, int maxW) {
        if (font.width(s) <= maxW) return s;
        return font.plainSubstrByWidth(s, Math.max(0, maxW - font.width(".."))) + "..";
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        out.add(NarratedElementType.TITLE, getMessage());
        for (Entry e : entries) {
            if (e.id().equals(selectedId)) {
                out.add(NarratedElementType.USAGE, Component.literal(e.name() + ", " + e.detail()));
            }
        }
    }
}
