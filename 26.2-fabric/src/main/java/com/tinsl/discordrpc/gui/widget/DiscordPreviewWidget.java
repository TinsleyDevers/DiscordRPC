package com.tinsl.discordrpc.gui.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Live preview of the Discord activity card. Deliberately drawn in Discord's
 * own dark palette - it is a preview of what Discord will show, not part of
 * the Minecraft UI. Every section is a clickable {@link Zone}; the screen
 * swaps its editor panel to whatever zone the player clicks.
 */
public class DiscordPreviewWidget extends AbstractWidget {

    public enum Zone {
        NONE,
        LARGE_IMAGE,
        SMALL_IMAGE,
        DETAILS,
        STATE,
        TIMESTAMP,
        BUTTON1,
        BUTTON2;

        /** Key into the lang file, e.g. "discordrpc.zone.large_image". */
        public String translationKey() {
            return "discordrpc.zone." + name().toLowerCase(Locale.ROOT);
        }
    }

    // Discord dark-theme palette
    private static final int C_CARD_EDGE  = 0xFF1E1F22;
    private static final int C_CARD       = 0xFF2B2D31;
    private static final int C_HEADER     = 0xFFB5BAC1;
    private static final int C_WHITE      = 0xFFFFFFFF;
    private static final int C_BODY       = 0xFFDBDEE1;
    private static final int C_MUTED      = 0xFF949BA4;
    private static final int C_IMG_BG     = 0xFF1E1F22;
    private static final int C_IMG_INNER  = 0xFF35373C;
    private static final int C_BTN        = 0xFF4E5058;
    private static final int C_BTN_HOVER  = 0xFF6D6F78;
    private static final int C_GREEN      = 0xFF23A55A;
    private static final int C_RED        = 0xFFED4245;
    private static final int C_SEP        = 0xFF3F4147;
    private static final int C_EMPTY      = 0xFF5C5E66;
    private static final int C_ZONE_HOVER = 0x28FFFFFF;

    private String details = "";
    private String state = "";
    private String button1Label = "";
    private String button2Label = "";
    private String elapsedTime = "";
    private boolean showTimestamp = true;
    private boolean hasLargeImage = true;
    private boolean hasSmallImage = false;
    private boolean isConnected = false;
    private Identifier largeImageTex;
    private Identifier smallImageTex;

    private Zone hoveredZone = Zone.NONE;
    private Zone selectedZone = Zone.NONE;
    private final Consumer<Zone> onZoneClicked;
    private final Map<Zone, int[]> zoneRects = new EnumMap<>(Zone.class);

    public DiscordPreviewWidget(int x, int y, int width, Consumer<Zone> onZoneClicked) {
        super(x, y, width, 140, Component.translatable("discordrpc.preview.narration"));
        this.onZoneClicked = onZoneClicked;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        Font font = Minecraft.getInstance().font;
        int x = getX(), y = getY(), w = getWidth();
        zoneRects.clear();

        int pad = 8;
        int imgSize = Math.max(44, Math.min(56, w / 4));

        // Total height: header + body + two button rows + bottom padding.
        // Empty buttons still get a (ghost) row so their editors stay reachable.
        int bodyH = Math.max(imgSize, 48);
        int h = 24 + bodyH + 6 + 22 + 22 + 4;
        this.height = h;

        // Card with 1px rounded corners (tooltip-style corner trim)
        fillRounded(g, x - 1, y - 1, x + w + 1, y + h + 1, C_CARD_EDGE);
        fillRounded(g, x, y, x + w, y + h, C_CARD);

        // Header row: "PLAYING A GAME" + connection dot
        g.text(font, "PLAYING A GAME", x + pad, y + 6, C_HEADER, false);
        int dotColor = isConnected ? C_GREEN : C_RED;
        g.fill(x + w - 13, y + 6, x + w - 7, y + 12, dotColor);
        g.fill(x + w - 12, y + 5, x + w - 8, y + 13, dotColor);
        g.fill(x + pad, y + 18, x + w - pad, y + 19, C_SEP);

        int cy = y + 24;
        int textX;

        if (hasLargeImage) {
            int imgX = x + pad;
            zoneRects.put(Zone.LARGE_IMAGE, new int[]{imgX, cy, imgSize, imgSize});

            if (largeImageTex != null) {
                g.blit(RenderPipelines.GUI_TEXTURED, largeImageTex, imgX, cy, 0f, 0f, imgSize, imgSize, imgSize, imgSize);
            } else {
                fillRounded(g, imgX, cy, imgX + imgSize, cy + imgSize, C_IMG_BG);
                String q = "?";
                g.text(font, q, imgX + (imgSize - font.width(q)) / 2, cy + imgSize / 2 - 4, C_EMPTY, false);
            }

            // Small image badge, docked to the large image's bottom-right corner.
            int smSize = Math.max(16, imgSize / 3);
            int smX = imgX + imgSize - smSize + 4;
            int smY = cy + imgSize - smSize + 4;
            zoneRects.put(Zone.SMALL_IMAGE, new int[]{smX - 2, smY - 2, smSize + 4, smSize + 4});
            fillRounded(g, smX - 2, smY - 2, smX + smSize + 2, smY + smSize + 2, C_CARD);
            if (hasSmallImage && smallImageTex != null) {
                g.blit(RenderPipelines.GUI_TEXTURED, smallImageTex, smX, smY, 0f, 0f, smSize, smSize, smSize, smSize);
            } else if (hasSmallImage) {
                fillRounded(g, smX, smY, smX + smSize, smY + smSize, C_IMG_INNER);
            } else {
                fillRounded(g, smX, smY, smX + smSize, smY + smSize, C_IMG_BG);
                int mid = smX + smSize / 2;
                int midY = smY + smSize / 2;
                int arm = Math.max(2, smSize / 5);
                g.fill(mid - arm, midY - 1, mid + arm, midY + 1, C_EMPTY);
                g.fill(mid - 1, midY - arm, mid + 1, midY + arm, C_EMPTY);
            }

            textX = x + pad + imgSize + 8;
        } else {
            textX = x + pad;
        }

        int maxTW = (x + w - pad) - textX;

        g.text(font, Component.literal("Minecraft").withStyle(s -> s.withBold(true)), textX, cy, C_WHITE, false);

        int lineY = cy + 12;
        zoneRects.put(Zone.DETAILS, new int[]{textX - 2, lineY - 1, maxTW + 4, 11});
        if (!details.isEmpty()) {
            g.text(font, trimToWidth(font, details, maxTW), textX, lineY, C_BODY, false);
        } else {
            g.text(font, trimToWidth(font, emptyLabel(Zone.DETAILS), maxTW), textX, lineY, C_EMPTY, false);
        }
        lineY += 11;

        zoneRects.put(Zone.STATE, new int[]{textX - 2, lineY - 1, maxTW + 4, 11});
        if (!state.isEmpty()) {
            g.text(font, trimToWidth(font, state, maxTW), textX, lineY, C_BODY, false);
        } else {
            g.text(font, trimToWidth(font, emptyLabel(Zone.STATE), maxTW), textX, lineY, C_EMPTY, false);
        }
        lineY += 11;

        zoneRects.put(Zone.TIMESTAMP, new int[]{textX - 2, lineY - 1, maxTW + 4, 11});
        if (showTimestamp && !elapsedTime.isEmpty()) {
            g.text(font, elapsedTime, textX, lineY, C_MUTED, false);
        } else {
            g.text(font, trimToWidth(font, emptyLabel(Zone.TIMESTAMP), maxTW), textX, lineY, C_EMPTY, false);
        }

        // Buttons area. Both zones stay clickable even when empty so the
        // player can always reach the editors - an empty slot renders as a
        // ghost row, matching how details/state show their "empty" hints.
        int btnY = cy + bodyH + 6;
        int btnW = w - pad * 2;
        int btnX = x + pad;
        int btnH = 18;

        drawButtonRow(g, font, Zone.BUTTON1, button1Label, btnX, btnY, btnW, btnH, mx, my);
        btnY += btnH + 4;
        drawButtonRow(g, font, Zone.BUTTON2, button2Label, btnX, btnY, btnW, btnH, mx, my);

        // Hover + selection feedback
        hoveredZone = isHovered ? getZoneAt(mx, my) : Zone.NONE;

        if (hoveredZone != Zone.NONE && hoveredZone != selectedZone) {
            int[] r = zoneRects.get(hoveredZone);
            g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], C_ZONE_HOVER);
        }

        if (selectedZone != Zone.NONE && zoneRects.containsKey(selectedZone)) {
            int[] r = zoneRects.get(selectedZone);
            if (r[3] > 0) {
                drawOutline(g, r[0] - 2, r[1] - 2, r[2] + 4, r[3] + 4, C_WHITE);
            }
        }

        if (hoveredZone != Zone.NONE) {
            g.setTooltipForNextFrame(font,
                    Component.translatable("discordrpc.preview.edit_hint",
                            Component.translatable(hoveredZone.translationKey())),
                    mx, my);
        }
    }

    private void drawButtonRow(GuiGraphicsExtractor g, Font font, Zone zone, String label,
                               int btnX, int btnY, int btnW, int btnH, int mx, int my) {
        zoneRects.put(zone, new int[]{btnX, btnY, btnW, btnH});
        boolean over = mx >= btnX && mx < btnX + btnW && my >= btnY && my < btnY + btnH;
        if (!label.isEmpty()) {
            fillRounded(g, btnX, btnY, btnX + btnW, btnY + btnH, over ? C_BTN_HOVER : C_BTN);
            String text = trimToWidth(font, label, btnW - 8);
            g.text(font, text, btnX + (btnW - font.width(text)) / 2, btnY + 5, C_WHITE, false);
        } else {
            fillRounded(g, btnX, btnY, btnX + btnW, btnY + btnH, C_IMG_BG);
            String text = trimToWidth(font, emptyLabel(zone), btnW - 8);
            g.text(font, text, btnX + (btnW - font.width(text)) / 2, btnY + 5, C_EMPTY, false);
        }
    }

    /** Fill with 1px trimmed corners - the same trick vanilla tooltips use. */
    private static void fillRounded(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
        g.fill(x0 + 1, y0, x1 - 1, y1, color);
        g.fill(x0, y0 + 1, x0 + 1, y1 - 1, color);
        g.fill(x1 - 1, y0 + 1, x1, y1 - 1, color);
    }

    private static void drawOutline(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    private static String emptyLabel(Zone zone) {
        return Component.translatable(zone.translationKey() + ".empty").getString();
    }

    private static String trimToWidth(Font font, String text, int maxW) {
        if (font.width(text) <= maxW) return text;
        String ellipsis = "..";
        int budget = maxW - font.width(ellipsis);
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (char c : text.toCharArray()) {
            used += font.width(String.valueOf(c));
            if (used > budget) break;
            sb.append(c);
        }
        return sb + ellipsis;
    }

    // Interaction

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!active || !visible || event.button() != 0) return false;
        Zone zone = getZoneAt(event.x(), event.y());
        if (zone != Zone.NONE) {
            selectedZone = zone;
            if (onZoneClicked != null) onZoneClicked.accept(zone);
            return true;
        }
        return false;
    }

    /** Hit-test order: the small-image badge overlaps the large image's corner,
     *  so it must be checked first or it is unreachable. */
    private static final Zone[] HIT_ORDER = {
            Zone.SMALL_IMAGE, Zone.LARGE_IMAGE, Zone.DETAILS, Zone.STATE,
            Zone.TIMESTAMP, Zone.BUTTON1, Zone.BUTTON2
    };

    private Zone getZoneAt(double mx, double my) {
        for (Zone zone : HIT_ORDER) {
            int[] r = zoneRects.get(zone);
            if (r != null && r[3] > 0
                    && mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                return zone;
            }
        }
        return Zone.NONE;
    }

    // State setters (called by the screen every tick)

    public void setDetails(String v) { this.details = v != null ? v : ""; }
    public void setState(String v) { this.state = v != null ? v : ""; }
    public void setButton1Label(String v) { this.button1Label = v != null ? v : ""; }
    public void setButton2Label(String v) { this.button2Label = v != null ? v : ""; }
    public void setElapsedTime(String v) { this.elapsedTime = v != null ? v : ""; }
    public void setShowTimestamp(boolean v) { this.showTimestamp = v; }
    public void setHasLargeImage(boolean v) { this.hasLargeImage = v; }
    public void setHasSmallImage(boolean v) { this.hasSmallImage = v; }
    public void setConnected(boolean v) { this.isConnected = v; }
    public void setLargeImageTexture(Identifier tex) { this.largeImageTex = tex; }
    public void setSmallImageTexture(Identifier tex) { this.smallImageTex = tex; }
    public void setSelectedZone(Zone z) { this.selectedZone = z; }
    public Zone getSelectedZone() { return selectedZone; }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        defaultButtonNarrationText(out);
    }
}
