package com.tinsl.discordrpc.gui.widget;

import com.mojang.blaze3d.platform.NativeImage;
import com.tinsl.discordrpc.DiscordRPCMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStream;
import java.util.*;

/**
 * Loads all PNG/JPG images bundled inside the mod JAR under
 * {@code assets/discordrpc/images/} and registers them as
 * {@link DynamicTexture} entries so the {@link ImagePickerWidget}
 * can display them alongside any user-provided local images.
 *
 * Textures are loaded once and cached for the entire game session.
 */
public final class BundledImageRegistry {

    /** key → registered texture Identifier */
    private static final LinkedHashMap<String, Identifier> CACHE = new LinkedHashMap<>();
    private static boolean loaded = false;

    private BundledImageRegistry() {}

    /**
     * Returns an ordered map of {@code imageName → textureLocation} for every
     * image successfully loaded from the mod's bundled asset folder.
     * Safe to call from the render/init thread.
     */
    public static Map<String, Identifier> getAll() {
        if (!loaded) load();
        return Collections.unmodifiableMap(CACHE);
    }

    /** Target size for picker thumbnails. Must match {@link ImagePickerWidget#THUMB_SIZE}. */
    static final int THUMB_SIZE = 96;

    private static void load() {
        loaded = true;
        Minecraft mc = Minecraft.getInstance();

        try {
            Map<Identifier, Resource> resources = mc.getResourceManager()
                    .listResources("images",
                            loc -> loc.getNamespace().equals(DiscordRPCMod.MOD_ID)
                                    && (loc.getPath().endsWith(".png")
                                    || loc.getPath().endsWith(".jpg")
                                    || loc.getPath().endsWith(".jpeg")));

            List<Map.Entry<Identifier, Resource>> sorted = new ArrayList<>(resources.entrySet());
            sorted.sort(Comparator.comparing(e -> e.getKey().getPath().toLowerCase()));

            for (Map.Entry<Identifier, Resource> entry : sorted) {
                String path     = entry.getKey().getPath();
                String filename = path.substring(path.lastIndexOf('/') + 1);
                int    dot      = filename.lastIndexOf('.');
                String key      = dot > 0 ? filename.substring(0, dot) : filename;

                if (CACHE.containsKey(key)) continue;

                try (InputStream is = entry.getValue().open()) {
                    NativeImage original = NativeImage.read(is);
                    NativeImage thumb    = resample(original, THUMB_SIZE, THUMB_SIZE);
                    original.close();

                    String sanitized   = key.toLowerCase().replaceAll("[^a-z0-9_.-]", "_");
                    Identifier texLoc = Identifier.fromNamespaceAndPath(
                            DiscordRPCMod.MOD_ID, "bundled/" + sanitized);
                    DynamicTexture tex = new DynamicTexture(() -> texLoc.toString(), thumb);
                    mc.getTextureManager().register(texLoc, tex);
                    CACHE.put(key, texLoc);

                } catch (Exception e) {
                    DiscordRPCMod.LOGGER.warn("Failed to load bundled image: {}", path);
                }
            }

            DiscordRPCMod.LOGGER.info("Loaded {} bundled images for picker", CACHE.size());

        } catch (Exception e) {
            DiscordRPCMod.LOGGER.error("BundledImageRegistry failed", e);
        }
    }

    /**
     * Bilinear resize - averages the four nearest source pixels so downscaled
     * images remain smooth rather than blocky.
     * Pixel format: ABGR (A in high byte, R in low byte) as used by NativeImage.Format.RGBA.
     */
    static NativeImage resample(NativeImage src, int tw, int th) {
        NativeImage out = new NativeImage(NativeImage.Format.RGBA, tw, th, false);
        int sw = src.getWidth(), sh = src.getHeight();
        for (int dy = 0; dy < th; dy++) {
            float fy  = (float) dy * sh / th;
            int   y0  = (int) fy;
            int   y1  = Math.min(y0 + 1, sh - 1);
            float ty  = fy - y0;
            for (int dx = 0; dx < tw; dx++) {
                float fx  = (float) dx * sw / tw;
                int   x0  = (int) fx;
                int   x1  = Math.min(x0 + 1, sw - 1);
                float tx  = fx - x0;

                int c00 = src.getPixel(x0, y0);
                int c10 = src.getPixel(x1, y0);
                int c01 = src.getPixel(x0, y1);
                int c11 = src.getPixel(x1, y1);

                out.setPixel(dx, dy, blendBilinear(c00, c10, c01, c11, tx, ty));
            }
        }
        return out;
    }

    private static int blendBilinear(int c00, int c10, int c01, int c11, float tx, float ty) {
        int r = blend4(c00 & 0xFF, c10 & 0xFF, c01 & 0xFF, c11 & 0xFF, tx, ty);
        int g = blend4((c00 >> 8) & 0xFF, (c10 >> 8) & 0xFF, (c01 >> 8) & 0xFF, (c11 >> 8) & 0xFF, tx, ty);
        int b = blend4((c00 >> 16) & 0xFF, (c10 >> 16) & 0xFF, (c01 >> 16) & 0xFF, (c11 >> 16) & 0xFF, tx, ty);
        int a = blend4((c00 >> 24) & 0xFF, (c10 >> 24) & 0xFF, (c01 >> 24) & 0xFF, (c11 >> 24) & 0xFF, tx, ty);
        return r | (g << 8) | (b << 16) | (a << 24);
    }

    private static int blend4(int v00, int v10, int v01, int v11, float tx, float ty) {
        float top    = v00 + (v10 - v00) * tx;
        float bottom = v01 + (v11 - v01) * tx;
        return Math.round(top + (bottom - top) * ty);
    }

    /** Clears the cache (useful on resource reload). */
    public static void reset() {
        CACHE.clear();
        loaded = false;
    }
}
