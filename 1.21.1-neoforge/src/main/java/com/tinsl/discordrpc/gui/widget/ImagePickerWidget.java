package com.tinsl.discordrpc.gui.widget;

import com.mojang.blaze3d.platform.NativeImage;
import com.tinsl.discordrpc.DiscordRPCMod;
import com.tinsl.discordrpc.images.ImageException;
import com.tinsl.discordrpc.images.ImageLibrary;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionException;

/**
 * Scrollable thumbnail grid styled like a vanilla inset list (dark panel,
 * white selection frame, classic gray scrollbar), split into sections:
 * Recent (the player's uploads and links, newest first), Your Images (files
 * in {@code config/discordrpc/images/} not uploaded yet) and Built-in (the
 * images on the mod's Discord application).
 */
public class ImagePickerWidget extends AbstractWidget {

    /** What the screen does with a click. */
    public interface Listener {
        /** A built-in name or an uploaded/linked image's link was picked. */
        void onPick(String key);
        /** A file from the images folder that still needs uploading was clicked. */
        void onUploadFile(Path file);
        /** Right-click on a Recent image. */
        void onForget(String url);
    }

    private static final int C_BORDER     = 0xFFA0A0A0;
    private static final int C_BG         = 0xFF000000;
    private static final int C_CELL_HOVER = 0x30FFFFFF;
    private static final int C_SELECT     = 0xFFFFFFFF;
    private static final int C_TEXT       = 0xFFA0A0A0;
    private static final int C_TEXT_SEL   = 0xFFFFFFFF;
    private static final int C_HEADER     = 0xFFE0E0E0;
    private static final int C_THUMB_BG   = 0xFF1B1B1B;
    private static final int C_BUSY       = 0xB0000000;
    private static final int C_SCROLL_BG  = 0xFF000000;
    private static final int C_SCROLL     = 0xFF808080;
    private static final int C_SCROLL_HI  = 0xFFC0C0C0;

    static final int THUMB_SIZE = BundledImageRegistry.THUMB_SIZE;

    private static final int THUMB    = 32;
    private static final int CELL     = THUMB + 22;
    private static final int GAP      = 4;
    private static final int SB_W     = 6;
    private static final int HEADER_H = 12;
    /** How long a typed link has to sit still before its preview is fetched. */
    private static final long LINK_SETTLE_MS = 600;

    private static final Map<String, ResourceLocation> LOCAL_CACHE = new LinkedHashMap<>();
    private static final Map<String, ResourceLocation> REMOTE_CACHE = new HashMap<>();
    private static final Set<String> REMOTE_PENDING = new HashSet<>();
    private static final Map<String, ImageException> REMOTE_ERRORS = new HashMap<>();
    private static final Map<String, Long> LINK_FIRST_SEEN = new HashMap<>();

    private enum Source { BUNDLED, LOCAL, LIBRARY }
    private record Entry(String key, String label, Path localPath, Source source) {}
    private record Section(Component title, List<Entry> entries) {}
    private record Cell(Entry entry, int x, int y) {}
    private record Header(Component text, int y) {}

    private final List<Section> sections = new ArrayList<>();
    private final List<Cell> cells = new ArrayList<>();
    private final List<Header> headers = new ArrayList<>();
    private final Listener listener;
    private final boolean includeNone;

    private int scrollY = 0;
    private int cols = 1;
    private int laidOutWidth = -1;
    private int contentH = 0;
    private String selectedKey = null;
    private Path busyFile = null;

    public ImagePickerWidget(int x, int y, int width, int height, Path localImagesDir,
                             ImageLibrary library, boolean includeNone, Listener listener) {
        super(x, y, width, height, Component.translatable("discordrpc.images.narration"));
        this.includeNone = includeNone;
        this.listener = listener;
        build(localImagesDir, library);
        layout();
        // Hug the content when there are only a few images.
        int fitted = contentH + 2;
        if (fitted < this.height) this.height = fitted;
    }

    private void build(Path localDir, ImageLibrary library) {
        sections.clear();

        List<Entry> recent = new ArrayList<>();
        for (ImageLibrary.Entry e : library.recent()) {
            recent.add(new Entry(e.url(), e.name(), null, Source.LIBRARY));
        }

        Set<String> bundledKeys = new HashSet<>();
        BundledImageRegistry.getAll().keySet().forEach(k -> bundledKeys.add(k.toLowerCase(Locale.ROOT)));

        List<Entry> local = new ArrayList<>();
        if (localDir != null && Files.exists(localDir)) {
            try (var files = Files.list(localDir)) {
                files
                    .filter(p -> ImageLibrary.isImageFileName(p.getFileName().toString()))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .forEach(p -> {
                        String fname = p.getFileName().toString();
                        String key = keyOf(fname);
                        if (bundledKeys.contains(key.toLowerCase(Locale.ROOT))) return;
                        if (library.isUploaded(fname)) return;
                        local.add(new Entry(key, key, p, Source.LOCAL));
                        loadLocalThumbnail(key, p);
                    });
            } catch (Exception ignored) {}
        }

        List<Entry> builtIn = new ArrayList<>();
        if (includeNone) {
            builtIn.add(new Entry("", Component.translatable("discordrpc.images.none").getString(), null, Source.BUNDLED));
        }
        BundledImageRegistry.getAll().forEach((key, loc) -> builtIn.add(new Entry(key, key, null, Source.BUNDLED)));

        if (!recent.isEmpty()) sections.add(new Section(Component.translatable("discordrpc.images.section.recent"), recent));
        if (!local.isEmpty()) sections.add(new Section(Component.translatable("discordrpc.images.section.local"), local));
        if (!builtIn.isEmpty()) sections.add(new Section(Component.translatable("discordrpc.images.section.built_in"), builtIn));
    }

    /** Places every header and cell for the current width. Positions are relative to the scroll origin. */
    private void layout() {
        laidOutWidth = getWidth();
        cols = Math.max(1, (getWidth() - GAP - SB_W) / (CELL + GAP));
        cells.clear();
        headers.clear();
        boolean titled = sections.size() > 1;
        int stride = CELL + GAP;
        int y = GAP;
        for (Section s : sections) {
            if (titled) {
                headers.add(new Header(s.title(), y));
                y += HEADER_H;
            }
            for (int i = 0; i < s.entries().size(); i++) {
                cells.add(new Cell(s.entries().get(i), GAP + (i % cols) * stride, y + (i / cols) * stride));
            }
            y += ((s.entries().size() + cols - 1) / cols) * stride;
        }
        contentH = y;
    }

    private int maxScroll() {
        return Math.max(0, contentH - (getHeight() - 2));
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        if (laidOutWidth != w) layout();

        // Inset panel, EditBox-style: light border around a black body.
        g.fill(x, y, x + w, y + h, C_BORDER);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, C_BG);

        var font = Minecraft.getInstance().font;

        if (cells.isEmpty()) {
            Component msg = Component.translatable("discordrpc.images.none_found");
            g.drawCenteredString(font, msg, x + w / 2, y + h / 2 - 4, C_TEXT);
            return;
        }

        int maxScroll = maxScroll();
        scrollY = Math.max(0, Math.min(scrollY, maxScroll));
        int originX = x, originY = y - scrollY;

        g.enableScissor(x + 1, y + 1, x + w - SB_W - 1, y + h - 1);

        for (Header hd : headers) {
            int hy = originY + hd.y();
            if (hy + 10 < y || hy > y + h) continue;
            g.drawString(font, hd.text(), originX + GAP, hy + 1, C_HEADER, false);
        }

        Entry tooltipFor = null;
        for (Cell c : cells) {
            int cx = originX + c.x();
            int cy = originY + c.y();
            if (cy + CELL < y || cy > y + h) continue;

            Entry e = c.entry();
            boolean hovered = isHovered && mx >= cx && mx < cx + CELL && my >= cy && my < cy + CELL
                    && mx < x + w - SB_W && my >= y && my < y + h;
            boolean selected = Objects.equals(e.key(), selectedKey);
            int tx = cx + (CELL - THUMB) / 2;
            int ty = cy + 2;

            if (e.key().isEmpty()) {
                // "None" cell - clears the image slot.
                g.fill(tx, ty, tx + THUMB, ty + THUMB, C_THUMB_BG);
                int mid = tx + THUMB / 2;
                int midY = ty + THUMB / 2;
                g.fill(mid - 8, midY - 1, mid + 8, midY + 1, selected ? C_TEXT_SEL : C_TEXT);
            } else {
                ResourceLocation texLoc = switch (e.source()) {
                    case BUNDLED -> BundledImageRegistry.getAll().get(e.key());
                    case LOCAL -> LOCAL_CACHE.get(e.key());
                    case LIBRARY -> remoteTexture(e.key());
                };
                if (texLoc != null) {
                    g.blit(texLoc, tx, ty, 0f, 0f, THUMB, THUMB, THUMB, THUMB);
                } else {
                    g.fill(tx, ty, tx + THUMB, ty + THUMB, C_THUMB_BG);
                }
            }

            if (e.localPath() != null && e.localPath().equals(busyFile)) {
                // Upload in flight: dim the thumbnail and tick a little dot counter.
                g.fill(tx, ty, tx + THUMB, ty + THUMB, C_BUSY);
                String dots = ".".repeat(1 + (int) (System.currentTimeMillis() / 400 % 3));
                g.drawString(font, dots, tx + (THUMB - font.width(dots)) / 2, ty + THUMB / 2 - 4, C_TEXT_SEL, false);
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

            String label = e.label();
            int maxLabelW = CELL - 2;
            if (font.width(label) > maxLabelW) {
                while (label.length() > 1 && font.width(label + "..") > maxLabelW) {
                    label = label.substring(0, label.length() - 1);
                }
                label = label + "..";
            }
            g.drawString(font, label, cx + (CELL - font.width(label)) / 2, cy + THUMB + 4,
                    selected ? C_TEXT_SEL : C_TEXT, false);

            if (hovered && !e.key().isEmpty()) tooltipFor = e;
        }

        g.disableScissor();

        if (tooltipFor != null) {
            g.renderTooltip(font, tooltipText(tooltipFor), mx, my);
        }

        // Classic vanilla scrollbar: gray thumb with a lighter top-left edge.
        if (maxScroll > 0) {
            int innerH = h - 2;
            int sbX = x + w - SB_W - 1;
            int sbY = y + 1;
            g.fill(sbX, sbY, sbX + SB_W, sbY + innerH, C_SCROLL_BG);
            int thumbH = Math.max(16, innerH * innerH / contentH);
            int thumbY = sbY + (int) ((innerH - thumbH) * (double) scrollY / maxScroll);
            g.fill(sbX, thumbY, sbX + SB_W, thumbY + thumbH, C_SCROLL);
            g.fill(sbX, thumbY, sbX + SB_W - 1, thumbY + thumbH - 1, C_SCROLL_HI);
        }
    }

    private static Component tooltipText(Entry e) {
        return switch (e.source()) {
            case LIBRARY -> Component.translatable("discordrpc.images.recent.tooltip", e.label());
            case LOCAL -> Component.translatable("discordrpc.images.local.tooltip", e.label());
            case BUNDLED -> Component.literal(e.key());
        };
    }

    private Cell cellAt(double mx, double my) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        if (mx < x || mx >= x + w - SB_W || my < y || my >= y + h) return null;
        for (Cell c : cells) {
            int cx = x + c.x();
            int cy = y - scrollY + c.y();
            if (mx >= cx && mx < cx + CELL && my >= cy && my < cy + CELL) return c;
        }
        return null;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!active || !visible) return false;
        Cell hit = cellAt(mx, my);
        if (hit == null) return false;
        Entry e = hit.entry();
        if (button == 1) {
            if (e.source() != Source.LIBRARY) return false;
            listener.onForget(e.key());
            return true;
        }
        if (button != 0) return false;
        if (e.source() == Source.LOCAL) {
            listener.onUploadFile(e.localPath());
        } else {
            selectedKey = e.key();
            listener.onPick(e.key());
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (!isHovered) return false;
        this.scrollY = Math.max(0, Math.min(maxScroll(), this.scrollY - (int) (scrollY * (CELL + GAP))));
        return true;
    }

    public void setSelectedKey(String key) {
        selectedKey = key;
    }

    /** Marks the file whose upload is running so its cell shows progress. */
    public void setBusyFile(Path file) {
        busyFile = file;
    }

    public int getScroll() { return scrollY; }
    public void setScroll(int scroll) { this.scrollY = Math.max(0, Math.min(maxScroll(), scroll)); }

    public boolean isEmpty() { return cells.isEmpty(); }

    // Thumbnails

    private static String keyOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static ResourceLocation registerThumbnail(String path, byte[] png) {
        try {
            NativeImage img = NativeImage.read(png);
            ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(DiscordRPCMod.MOD_ID, path);
            Minecraft.getInstance().getTextureManager().register(loc, new DynamicTexture(img));
            return loc;
        } catch (Exception e) {
            return null;
        }
    }

    private static void loadLocalThumbnail(String key, Path p) {
        if (key.isEmpty() || LOCAL_CACHE.containsKey(key)) return;
        byte[] png = ImageLibrary.thumbnailOf(p);
        if (png == null) return;
        String sanitized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        ResourceLocation loc = registerThumbnail("local/" + sanitized, png);
        if (loc != null) LOCAL_CACHE.put(key, loc);
    }

    public static void resetLocalCache() {
        LOCAL_CACHE.clear();
    }

    /**
     * Decodes thumbnails for any not-yet-cached images in {@code dir}.
     * Incremental: already-cached keys are skipped, so calling this on every
     * screen open only pays for files added since the last call.
     */
    public static void preloadFromDirectory(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        Map<String, ResourceLocation> bundled = BundledImageRegistry.getAll();
        try (var files = Files.list(dir)) {
            files
                .filter(p -> ImageLibrary.isImageFileName(p.getFileName().toString()))
                .forEach(p -> {
                    String key = keyOf(p.getFileName().toString());
                    if (!bundled.containsKey(key)) loadLocalThumbnail(key, p);
                });
        } catch (Exception ignored) {}
    }

    /**
     * Thumbnail for an uploaded or linked image: from memory, then the disk
     * cache, then a background download. Returns null until it's ready.
     */
    private static ResourceLocation remoteTexture(String url) {
        ResourceLocation loc = REMOTE_CACHE.get(url);
        if (loc != null || REMOTE_PENDING.contains(url) || REMOTE_ERRORS.containsKey(url)) return loc;

        ImageLibrary library = DiscordRPCMod.getInstance().getModConfig().getImageLibrary();
        String texPath = "remote/" + library.thumbnailPath(url).getFileName().toString().replace(".png", "");
        byte[] cached = library.readThumbnail(url);
        if (cached != null) {
            loc = registerThumbnail(texPath, cached);
            if (loc != null) {
                REMOTE_CACHE.put(url, loc);
                return loc;
            }
        }

        // A link being typed changes every keystroke; only fetch once it settles.
        long now = System.currentTimeMillis();
        if (LINK_FIRST_SEEN.size() > 256) LINK_FIRST_SEEN.clear();
        long firstSeen = LINK_FIRST_SEEN.computeIfAbsent(url, u -> now);
        if (now - firstSeen < LINK_SETTLE_MS) return null;

        REMOTE_PENDING.add(url);
        library.fetchThumbnail(url).whenComplete((png, err) -> Minecraft.getInstance().execute(() -> {
            REMOTE_PENDING.remove(url);
            ResourceLocation done = png != null ? registerThumbnail(texPath, png) : null;
            if (done != null) {
                REMOTE_CACHE.put(url, done);
            } else {
                REMOTE_ERRORS.put(url, asImageException(err));
            }
        }));
        return null;
    }

    private static ImageException asImageException(Throwable err) {
        Throwable cause = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
        return cause instanceof ImageException ie ? ie : new ImageException("discordrpc.upload.error.link_not_image");
    }

    /** True while a link's preview is still downloading. */
    public static boolean isLinkLoading(String url) {
        if (url == null) return false;
        String u = url.trim();
        return !REMOTE_CACHE.containsKey(u) && !REMOTE_ERRORS.containsKey(u);
    }

    /** Why a link's preview failed, or null. */
    public static ImageException linkError(String url) {
        return url == null ? null : REMOTE_ERRORS.get(url.trim());
    }

    /** Lets links that failed to load try again, e.g. when the settings screen reopens. */
    public static void retryFailedLinks() {
        REMOTE_ERRORS.clear();
    }

    public static ResourceLocation findTexture(String key) {
        if (key == null || key.isEmpty()) return null;
        if (ImageLibrary.isLink(key)) return remoteTexture(key.trim());
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

    /** True for names of images that ship with the mod (and so exist on Discord). */
    public static boolean isBuiltIn(String key) {
        if (key == null) return false;
        for (String k : BundledImageRegistry.getAll().keySet()) if (k.equalsIgnoreCase(key)) return true;
        return false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        defaultButtonNarrationText(out);
    }
}
