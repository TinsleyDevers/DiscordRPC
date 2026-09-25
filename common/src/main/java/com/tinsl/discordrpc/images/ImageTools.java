package com.tinsl.discordrpc.images;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * Plain-Java image work for player uploads: sniffing the format, decoding
 * with a memory cap, resizing and re-encoding. Only ImageIO and raw pixel
 * arrays are used (no Graphics2D), so nothing here starts up AWT, which
 * matters on macOS where AWT and the game window fight over the main thread.
 */
final class ImageTools {

    /** Longest side of what gets uploaded. Discord never shows more than this. */
    static final int UPLOAD_MAX_SIDE = 1024;
    /** Picker and preview thumbnails are square and this size. */
    static final int THUMB_SIDE = 96;

    enum Kind {
        PNG("png", "image/png"),
        JPEG("jpg", "image/jpeg"),
        GIF("gif", "image/gif");

        final String ext;
        final String mime;

        Kind(String ext, String mime) {
            this.ext = ext;
            this.mime = mime;
        }
    }

    /** Bytes ready to send to the image host. */
    record Prepared(byte[] bytes, Kind kind) {}

    private ImageTools() {}

    /** Detects the format from the file's first bytes; null when unsupported. */
    static Kind sniff(byte[] b) {
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return Kind.PNG;
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return Kind.JPEG;
        if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') return Kind.GIF;
        return null;
    }

    /**
     * Shrinks big images, blows tiny pixel art up with hard edges so Discord
     * doesn't smear it, and re-encodes, which also drops metadata such as a
     * phone photo's GPS location. GIFs go up untouched so they stay animated.
     */
    static Prepared prepareForUpload(byte[] original, Kind kind) throws IOException {
        if (kind == Kind.GIF) return new Prepared(original, Kind.GIF);

        Pixels px = decode(original, UPLOAD_MAX_SIDE * 2);
        int longest = Math.max(px.w, px.h);
        if (longest > UPLOAD_MAX_SIDE) {
            px = resizeToFit(px, UPLOAD_MAX_SIDE);
        } else if (longest <= 256) {
            int factor = Math.max(1, Math.min((int) Math.ceil(512.0 / longest), UPLOAD_MAX_SIDE / longest));
            px = upscaleNearest(px, factor);
        }

        if (kind == Kind.JPEG && !px.hasAlpha()) {
            return new Prepared(encodeJpeg(px, 0.92f), Kind.JPEG);
        }
        return new Prepared(encodePng(px), Kind.PNG);
    }

    /** Square, center-cropped PNG thumbnail of any supported image (first frame for GIFs). */
    static byte[] thumbnail(byte[] original) throws IOException {
        Pixels px = decode(original, THUMB_SIDE * 6);
        int side = Math.min(px.w, px.h);
        px = crop(px, (px.w - side) / 2, (px.h - side) / 2, side, side);
        if (side > THUMB_SIDE) {
            px = boxResize(px, THUMB_SIDE, THUMB_SIDE);
        } else if (side < THUMB_SIDE) {
            px = nearestResize(px, THUMB_SIDE, THUMB_SIDE);
        }
        return encodePng(px);
    }

    // Decoding

    private record Pixels(int[] argb, int w, int h) {
        boolean hasAlpha() {
            for (int c : argb) if ((c >>> 24) != 0xFF) return true;
            return false;
        }
    }

    /**
     * Decodes the first frame. Images bigger than {@code maxSide} are read with
     * subsampling, so a huge or hostile file can't allocate its way to an
     * out-of-memory crash.
     */
    private static Pixels decode(byte[] bytes, int maxSide) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (in == null) throw new IOException("no image stream");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw new IOException("no reader for this format");
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w <= 0 || h <= 0) throw new IOException("empty image");
                int step = Math.max(1, (int) Math.ceil(Math.max(w, h) / (double) maxSide));
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(step, step, 0, 0);
                BufferedImage img = reader.read(0, param);
                int iw = img.getWidth(), ih = img.getHeight();
                return new Pixels(img.getRGB(0, 0, iw, ih, null, 0, iw), iw, ih);
            } finally {
                reader.dispose();
            }
        }
    }

    // Resizing

    private static Pixels resizeToFit(Pixels px, int maxSide) {
        double scale = (double) maxSide / Math.max(px.w, px.h);
        int w = Math.max(1, (int) Math.round(px.w * scale));
        int h = Math.max(1, (int) Math.round(px.h * scale));
        return boxResize(px, w, h);
    }

    /**
     * Area-average downscale. Colors are weighted by alpha so transparent
     * pixels don't bleed dark fringes into the edges.
     */
    private static Pixels boxResize(Pixels px, int dw, int dh) {
        int[] out = new int[dw * dh];
        for (int dy = 0; dy < dh; dy++) {
            int y0 = dy * px.h / dh;
            int y1 = Math.max(y0 + 1, (dy + 1) * px.h / dh);
            for (int dx = 0; dx < dw; dx++) {
                int x0 = dx * px.w / dw;
                int x1 = Math.max(x0 + 1, (dx + 1) * px.w / dw);
                long a = 0, r = 0, g = 0, b = 0;
                int n = 0;
                for (int y = y0; y < y1; y++) {
                    int row = y * px.w;
                    for (int x = x0; x < x1; x++) {
                        int c = px.argb[row + x];
                        int ca = c >>> 24;
                        a += ca;
                        r += (long) ((c >> 16) & 0xFF) * ca;
                        g += (long) ((c >> 8) & 0xFF) * ca;
                        b += (long) (c & 0xFF) * ca;
                        n++;
                    }
                }
                int oa = (int) (a / n);
                int or = a == 0 ? 0 : (int) (r / a);
                int og = a == 0 ? 0 : (int) (g / a);
                int ob = a == 0 ? 0 : (int) (b / a);
                out[dy * dw + dx] = (oa << 24) | (or << 16) | (og << 8) | ob;
            }
        }
        return new Pixels(out, dw, dh);
    }

    private static Pixels upscaleNearest(Pixels px, int factor) {
        return factor <= 1 ? px : nearestResize(px, px.w * factor, px.h * factor);
    }

    private static Pixels nearestResize(Pixels px, int dw, int dh) {
        int[] out = new int[dw * dh];
        for (int dy = 0; dy < dh; dy++) {
            int sy = dy * px.h / dh;
            for (int dx = 0; dx < dw; dx++) {
                out[dy * dw + dx] = px.argb[sy * px.w + dx * px.w / dw];
            }
        }
        return new Pixels(out, dw, dh);
    }

    private static Pixels crop(Pixels px, int x, int y, int w, int h) {
        if (x == 0 && y == 0 && w == px.w && h == px.h) return px;
        int[] out = new int[w * h];
        for (int row = 0; row < h; row++) {
            System.arraycopy(px.argb, (y + row) * px.w + x, out, row * w, w);
        }
        return new Pixels(out, w, h);
    }

    // Encoding

    private static byte[] encodePng(Pixels px) throws IOException {
        BufferedImage img = new BufferedImage(px.w, px.h, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, px.w, px.h, px.argb, 0, px.w);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(img, "png", out)) throw new IOException("no PNG writer");
        return out.toByteArray();
    }

    private static byte[] encodeJpeg(Pixels px, float quality) throws IOException {
        BufferedImage img = new BufferedImage(px.w, px.h, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, px.w, px.h, px.argb, 0, px.w);
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) return encodePng(px);
        ImageWriter writer = writers.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
