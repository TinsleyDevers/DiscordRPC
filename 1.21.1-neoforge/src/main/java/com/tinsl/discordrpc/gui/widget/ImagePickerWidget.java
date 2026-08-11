package com.tinsl.discordrpc.gui.widget;

import com.mojang.blaze3d.platform.NativeImage;
import com.tinsl.discordrpc.DiscordRPCMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Scrollable thumbnail grid styled like a vanilla inset list (dark panel,
 * white selection frame, classic gray scrollbar). Shows images bundled in the
 * mod JAR plus anything the player drops into {@code config/discordrpc/images/}.
 */
public class ImagePickerWidget extends AbstractWidget {

    private static final int C_BORDER     = 0xFFA0A0A0;
    private static final int C_BG         = 0xFF000000;
    private static final int C_CELL_HOVER = 0x30FFFFFF;
    private static final int C_SELECT     = 0xFFFFFFFF;
    private static final int C_TEXT       = 0xFFA0A0A0;
    private static final int C_TEXT_SEL   = 0xFFFFFFFF;
    private static final int C_THUMB_BG   = 0xFF1B1B1B;
    private static final int C_SCROLL_BG  = 0xFF000000;
    private static final int C_SCROLL     = 0xFF808080;
    private static final int C_SCROLL_HI  = 0xFFC0C0C0;

    static final int THUMB_SIZE = BundledImageRegistry.THUMB_SIZE;

    private static final int THUMB = 32;
    private static final int CELL  = THUMB + 22;
    private static final int GAP   = 4;
    private static final int SB_W  = 6;

    private static final Map<String, ResourceLocation> LOCAL_CACHE = new LinkedHashMap<>();

    private enum Source { BUNDLED, LOCAL }
    private record Entry(String key, Path localPath, Source source) {}

    private final List<Entry> entries = new ArrayList<>();
    private final Consumer<String> onSelect;
    private final boolean includeNone;

    private int scrollY = 0;
    private int cols = 1;
    private int selectedIdx = -1;

    public ImagePickerWidget(int x, int y, int width, int height,
                             Path localImagesDir, Consumer<String> onSelect) {
        this(x, y, width, height, localImagesDir, false, onSelect);
    }

    public ImagePickerWidget(int x, int y, int width, int height,
                             Path localImagesDir, boolean includeNone, Consumer<String> onSelect) {
        super(x, y, width, height, Component.translatable("discordrpc.images.narration"));
        this.includeNone = includeNone;
        this.onSelect = onSelect;
        build(localImagesDir);
        recompute();
        // Hug the content when there are only a few images.
        int rows = (int) Math.ceil((double) entries.size() / cols);
        int fitted = GAP + rows * (CELL + GAP) + 2;
        if (fitted < this.height) this.height = fitted;
    }

    private void build(Path localDir) {
        entries.clear();

        if (includeNone) {
            entries.add(new Entry("", null, Source.LOCAL));
        }

        BundledImageRegistry.getAll().forEach((key, loc) ->
                entries.add(new Entry(key, null, Source.BUNDLED)));

        Set<String> seen = new HashSet<>();
        entries.forEach(e -> seen.add(e.key().toLowerCase()));

        if (localDir != null && Files.exists(localDir)) {
            try (var files = Files.list(localDir)) {
                files
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg");
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
                    .forEach(p -> {
                        String fname = p.getFileName().toString();
                        int dot = fname.lastIndexOf('.');
                        String key = dot > 0 ? fname.substring(0, dot) : fname;
                        if (!seen.contains(key.toLowerCase())) {
                            entries.add(new Entry(key, p, Source.LOCAL));
                            seen.add(key.toLowerCase());
                            loadLocalThumbnail(key, p);
                        }
                    });
            } catch (Exception ignored) {}
        }
    }

    private static void loadLocalThumbnail(String key, Path p) {
        if (key.isEmpty() || LOCAL_CACHE.containsKey(key)) return;
        String fn = p.getFileName().toString().toLowerCase();
        if (!fn.endsWith(".png") && !fn.endsWith(".jpg") && !fn.endsWith(".jpeg")) return;
        try {
            if (Files.size(p) > 4 * 1024 * 1024) return;
            NativeImage original;
            try (var in = Files.newInputStream(p)) {
                original = NativeImage.read(in);
            }
            NativeImage thumb = BundledImageRegistry.resample(original, THUMB_SIZE, THUMB_SIZE);
            original.close();
            String sanitized = key.toLowerCase().replaceAll("[^a-z0-9_.-]", "_");
            ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(
                    DiscordRPCMod.MOD_ID, "local/" + sanitized);
            DynamicTexture tex = new DynamicTexture(thumb);
            Minecraft.getInstance().getTextureManager().register(loc, tex);
            LOCAL_CACHE.put(key, loc);
        } catch (Exception ignored) {}
    }

    public static void resetLocalCache() {
        LOCAL_CACHE.clear();
    }

    private void recompute() {
        cols = Math.max(1, (getWidth() - GAP - SB_W) / (CELL + GAP));
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        recompute();

        // Inset panel, EditBox-style: light border around a black body.
        g.fill(x, y, x + w, y + h, C_BORDER);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, C_BG);

        var font = Minecraft.getInstance().font;

        if (entries.isEmpty()) {
            Component msg = Component.translatable("discordrpc.images.none_found");
            g.drawCenteredString(font, msg, x + w / 2, y + h / 2 - 4, C_TEXT);
            return;
        }

        int rows = (int) Math.ceil((double) entries.size() / cols);
        int stride = CELL + GAP;
        int totalH = GAP + rows * stride;
        int innerH = h - 2;
        int maxScroll = Math.max(0, totalH - innerH);
        scrollY = Math.max(0, Math.min(scrollY, maxScroll));

        g.enableScissor(x + 1, y + 1, x + w - SB_W - 1, y + h - 1);

        int startX = x + GAP;
        int startY = y + GAP - scrollY;

        for (int i = 0; i < entries.size(); i++) {
            int col = i % cols;
            int row = i / cols;
            int cx = startX + col * stride;
            int cy = startY + row * stride;

            if (cy + CELL < y || cy > y + h) continue;

            boolean hovered = isHovered && mx >= cx && mx < cx + CELL && my >= cy && my < cy + CELL;
            boolean selected = (i == selectedIdx);

            Entry e = entries.get(i);
            int tx = cx + (CELL - THUMB) / 2;
            int ty = cy + 2;

            if (e.key().isEmpty()) {
                // "None" cell - clears the image slot.
                g.fill(tx, ty, tx + THUMB, ty + THUMB, C_THUMB_BG);
                int mid = tx + THUMB / 2;
                int midY = ty + THUMB / 2;
                g.fill(mid - 8, midY - 1, mid + 8, midY + 1, selected ? C_TEXT_SEL : C_TEXT);
            } else {
                ResourceLocation texLoc = e.source() == Source.BUNDLED
                        ? BundledImageRegistry.getAll().get(e.key())
                        : LOCAL_CACHE.get(e.key());
                if (texLoc != null) {
                    g.blit(texLoc, tx, ty, 0f, 0f, THUMB, THUMB, THUMB, THUMB);
                } else {
                    g.fill(tx, ty, tx + THUMB, ty + THUMB, C_THUMB_BG);
                }
            }

            if (hovered && !selected) {
                g.fill(cx, cy, cx + CELL, cy + CELL, C_CELL_HOVER);
            }
            if (selected) {
                // White frame like the selected world/pack entry.
                g.fill(cx, cy, cx + CELL, cy + 1, C_SELECT);
                g.fill(cx, cy + CELL - 1, cx + CELL, cy + CELL, C_SELECT);
                g.fill(cx, cy, cx + 1, cy + CELL, C_SELECT);
                g.fill(cx + CELL - 1, cy, cx + CELL, cy + CELL, C_SELECT);
            }

            String label = e.key().isEmpty()
                    ? Component.translatable("discordrpc.images.none").getString()
                    : e.key();
            int maxLabelW = CELL - 2;
            if (font.width(label) > maxLabelW) {
                while (label.length() > 1 && font.width(label + "..") > maxLabelW) {
                    label = label.substring(0, label.length() - 1);
                }
                label = label + "..";
            }
            g.drawString(font, label, cx + (CELL - font.width(label)) / 2, cy + THUMB + 4,
                    selected ? C_TEXT_SEL : C_TEXT, false);

            if (hovered && !e.key().isEmpty()) {
                g.renderTooltip(font, Component.literal(e.key()), mx, my);
            }
        }

        g.disableScissor();

        // Classic vanilla scrollbar: gray thumb with a lighter top-left edge.
        if (maxScroll > 0) {
            int sbX = x + w - SB_W - 1;
            int sbY = y + 1;
            int sbH = innerH;
            g.fill(sbX, sbY, sbX + SB_W, sbY + sbH, C_SCROLL_BG);
            int thumbH = Math.max(16, sbH * innerH / totalH);
            int thumbY = sbY + (int) ((sbH - thumbH) * (double) scrollY / maxScroll);
            g.fill(sbX, thumbY, sbX + SB_W, thumbY + thumbH, C_SCROLL);
            g.fill(sbX, thumbY, sbX + SB_W - 1, thumbY + thumbH - 1, C_SCROLL_HI);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!active || !visible) return false;
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        if (mx < x || mx >= x + w - SB_W || my < y || my > y + h) return false;

        int stride = CELL + GAP;
        int startX = x + GAP;
        int startY = y + GAP - scrollY;

        for (int i = 0; i < entries.size(); i++) {
            int cx = startX + (i % cols) * stride;
            int cy = startY + (i / cols) * stride;
            if (mx >= cx && mx < cx + CELL && my >= cy && my < cy + CELL) {
                selectedIdx = i;
                if (onSelect != null) onSelect.accept(entries.get(i).key());
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (!isHovered) return false;
        int rows = (int) Math.ceil((double) entries.size() / Math.max(1, cols));
        int totalH = GAP + rows * (CELL + GAP);
        int maxS = Math.max(0, totalH - (getHeight() - 2));
        this.scrollY = Math.max(0, Math.min(maxS, this.scrollY - (int) (scrollY * (CELL + GAP))));
        return true;
    }

    public void setSelectedKey(String key) {
        selectedIdx = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).key().equals(key)) { selectedIdx = i; break; }
        }
    }

    public String getSelectedKey() {
        return (selectedIdx >= 0 && selectedIdx < entries.size())
                ? entries.get(selectedIdx).key() : null;
    }

    public boolean isEmpty() { return entries.isEmpty(); }

    /**
     * Decodes thumbnails for any not-yet-cached images in {@code dir}.
     * Incremental: already-cached keys are skipped, so calling this on every
     * screen open only pays for files added since the last call.
     */
    public static void preloadFromDirectory(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var files = Files.list(dir)) {
            files
                .filter(p -> {
                    String n = p.getFileName().toString().toLowerCase();
                    return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg");
                })
                .forEach(p -> {
                    String fname = p.getFileName().toString();
                    int dot = fname.lastIndexOf('.');
                    String key = dot > 0 ? fname.substring(0, dot) : fname;
                    loadLocalThumbnail(key, p);
                });
        } catch (Exception ignored) {}
    }

    public static ResourceLocation findTexture(String key) {
        if (key == null || key.isEmpty()) return null;
        Map<String, ResourceLocation> bundled = BundledImageRegistry.getAll();
        ResourceLocation loc = bundled.get(key);
        if (loc != null) return loc;
        loc = LOCAL_CACHE.get(key);
        if (loc != null) return loc;
        for (Map.Entry<String, ResourceLocation> e : bundled.entrySet())
            if (e.getKey().equalsIgnoreCase(key)) return e.getValue();
        for (Map.Entry<String, ResourceLocation> e : LOCAL_CACHE.entrySet())
            if (e.getKey().equalsIgnoreCase(key)) return e.getValue();
        return null;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        defaultButtonNarrationText(out);
    }
}
