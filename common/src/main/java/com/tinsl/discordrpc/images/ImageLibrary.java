package com.tinsl.discordrpc.images;

import com.tinsl.discordrpc.DiscordRPCMod;
import com.tinsl.zambielib.util.JsonStore;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * The player's own presence images: everything they've uploaded or linked,
 * newest first, saved in {@code config/discordrpc/image_library.json} with a
 * small thumbnail per image under {@code config/discordrpc/thumbnails/}.
 *
 * <p>An upload keeps a copy of the original in {@code config/discordrpc/images/},
 * sends a cleaned-up version to {@link ImageHost}, and remembers the link. The
 * link is what goes into a profile's image field, so the presence code needs
 * no special handling: Discord accepts a link wherever it takes an asset name.
 * Uploading the same file twice reuses the first link.
 */
public final class ImageLibrary {

    /** Biggest file a player can upload or link. */
    public static final long MAX_FILE_BYTES = 16L * 1024 * 1024;
    private static final int MAX_ENTRIES = 40;

    /** One image the player has used: an upload, or a link they pasted. */
    public record Entry(String url, String name, String file, String sha256, long lastUsed) {}

    /** On-disk shape of the library file. */
    private static final class Stored {
        int version = 1;
        List<StoredEntry> images = new ArrayList<>();
    }

    private static final class StoredEntry {
        String url;
        String name;
        String file;
        String sha256;
        long lastUsed;
    }

    private static final ExecutorService WORKER = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "DiscordPresence images");
        t.setDaemon(true);
        return t;
    });

    private final Path libraryFile;
    private final Path thumbnailsDir;
    private final Path imagesDir;
    private final List<Entry> entries = new ArrayList<>();

    public ImageLibrary(Path configDir, Path imagesDir) {
        this.libraryFile = configDir.resolve("image_library.json");
        this.thumbnailsDir = configDir.resolve("thumbnails");
        this.imagesDir = imagesDir;
    }

    public static String hostName() {
        return ImageHost.NAME;
    }

    // Library state

    public synchronized void load() {
        Stored stored = JsonStore.load(libraryFile, Stored.class, Stored::new);
        entries.clear();
        if (stored.images != null) {
            for (StoredEntry s : stored.images) {
                if (s == null || !isLink(s.url)) continue;
                if (find(s.url) != null) continue;
                String name = s.name == null || s.name.isBlank() ? nameFromLink(s.url) : s.name;
                entries.add(new Entry(s.url.trim(), name, s.file, s.sha256, s.lastUsed));
            }
        }
        entries.sort(Comparator.comparingLong(Entry::lastUsed).reversed());
        while (entries.size() > MAX_ENTRIES) entries.remove(entries.size() - 1);
        deleteOrphanThumbnails();
    }

    private void save() {
        Stored stored = new Stored();
        for (Entry e : entries) {
            StoredEntry s = new StoredEntry();
            s.url = e.url();
            s.name = e.name();
            s.file = e.file();
            s.sha256 = e.sha256();
            s.lastUsed = e.lastUsed();
            stored.images.add(s);
        }
        try {
            JsonStore.save(libraryFile, stored);
        } catch (IOException e) {
            DiscordRPCMod.LOGGER.warn("Could not save the image library: {}", e.toString());
        }
    }

    /** Newest first. */
    public synchronized List<Entry> recent() {
        return List.copyOf(entries);
    }

    public synchronized Entry find(String url) {
        if (url == null) return null;
        String wanted = url.trim();
        for (Entry e : entries) if (e.url().equals(wanted)) return e;
        return null;
    }

    private synchronized Entry findBySha(String sha) {
        for (Entry e : entries) if (sha.equals(e.sha256())) return e;
        return null;
    }

    /** True when the file in the images folder has already been uploaded. */
    public synchronized boolean isUploaded(String fileName) {
        for (Entry e : entries) if (e.file() != null && e.file().equalsIgnoreCase(fileName)) return true;
        return false;
    }

    /** Moves an image to the front of the list because the player just picked it. */
    public synchronized void markUsed(String url) {
        Entry e = find(url);
        if (e == null) return;
        put(new Entry(e.url(), e.name(), e.file(), e.sha256(), System.currentTimeMillis()));
    }

    /** Adds a pasted link to the list if it isn't there yet. */
    public synchronized void remember(String url) {
        if (!isLink(url) || find(url) != null) return;
        put(new Entry(url.trim(), nameFromLink(url), null, null, System.currentTimeMillis()));
    }

    /** Drops an image from the list. The uploaded copy stays online and the original stays on disk. */
    public synchronized void remove(String url) {
        Entry e = find(url);
        if (e == null) return;
        entries.remove(e);
        try {
            Files.deleteIfExists(thumbnailPath(e.url()));
        } catch (IOException ignored) {}
        save();
    }

    private synchronized void put(Entry entry) {
        entries.removeIf(e -> e.url().equals(entry.url()));
        entries.add(0, entry);
        while (entries.size() > MAX_ENTRIES) {
            Entry dropped = entries.remove(entries.size() - 1);
            try {
                Files.deleteIfExists(thumbnailPath(dropped.url()));
            } catch (IOException ignored) {}
        }
        save();
    }

    // Thumbnails

    public Path thumbnailPath(String url) {
        return thumbnailsDir.resolve(sha256(url.trim().getBytes(StandardCharsets.UTF_8)).substring(0, 32) + ".png");
    }

    /** The cached thumbnail PNG for a link, or null if it hasn't been fetched yet. */
    public byte[] readThumbnail(String url) {
        Path p = thumbnailPath(url);
        try {
            return Files.exists(p) ? Files.readAllBytes(p) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private void writeThumbnail(String url, byte[] png) {
        try {
            Files.createDirectories(thumbnailsDir);
            Files.write(thumbnailPath(url), png);
        } catch (IOException e) {
            DiscordRPCMod.LOGGER.warn("Could not cache an image thumbnail: {}", e.toString());
        }
    }

    private synchronized void deleteOrphanThumbnails() {
        if (!Files.isDirectory(thumbnailsDir)) return;
        Set<Path> keep = new HashSet<>();
        for (Entry e : entries) keep.add(thumbnailPath(e.url()).getFileName());
        try (Stream<Path> files = Files.list(thumbnailsDir)) {
            files.filter(p -> !keep.contains(p.getFileName())).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    // Background work

    /**
     * Uploads a local image and adds it to the library. Completes on a worker
     * thread with the new entry, or exceptionally with an {@link ImageException}.
     */
    public CompletableFuture<Entry> upload(Path source) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return uploadBlocking(source);
            } catch (ImageException e) {
                throw new CompletionException(e);
            }
        }, WORKER);
    }

    private Entry uploadBlocking(Path source) throws ImageException {
        byte[] original = readImageFile(source);
        ImageTools.Kind kind = ImageTools.sniff(original);
        if (kind == null) throw new ImageException("discordrpc.upload.error.type");

        String sha = sha256(original);
        Path local = keepCopy(source, original, sha, kind);
        String localName = local != null ? local.getFileName().toString() : null;
        long now = System.currentTimeMillis();

        Entry known = findBySha(sha);
        if (known != null) {
            if (readThumbnail(known.url()) == null) writeThumbnailQuietly(known.url(), original);
            Entry again = new Entry(known.url(), known.name(), localName != null ? localName : known.file(), sha, now);
            put(again);
            return again;
        }

        ImageTools.Prepared prepared;
        try {
            prepared = ImageTools.prepareForUpload(original, kind);
        } catch (IOException | RuntimeException e) {
            throw new ImageException("discordrpc.upload.error.decode");
        }
        String url = ImageHost.upload(prepared);
        writeThumbnailQuietly(url, original);

        String name = baseName(localName != null ? localName : source.getFileName().toString());
        Entry entry = new Entry(url, cap(name, 32), localName, sha, now);
        put(entry);
        return entry;
    }

    /**
     * Downloads a linked image and caches its thumbnail. Completes with the
     * thumbnail PNG, or exceptionally with an {@link ImageException}.
     */
    public CompletableFuture<byte[]> fetchThumbnail(String url) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                byte[] bytes = ImageHost.download(url, MAX_FILE_BYTES);
                byte[] thumb;
                try {
                    thumb = ImageTools.thumbnail(bytes);
                } catch (IOException | RuntimeException e) {
                    throw new ImageException("discordrpc.upload.error.link_not_image");
                }
                writeThumbnail(url, thumb);
                return thumb;
            } catch (ImageException e) {
                throw new CompletionException(e);
            }
        }, WORKER);
    }

    /** A square PNG thumbnail of a local file, for the picker. Null if it can't be read. */
    public static byte[] thumbnailOf(Path file) {
        try {
            if (Files.size(file) > MAX_FILE_BYTES) return null;
            return ImageTools.thumbnail(Files.readAllBytes(file));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void writeThumbnailQuietly(String url, byte[] original) {
        try {
            writeThumbnail(url, ImageTools.thumbnail(original));
        } catch (IOException | RuntimeException ignored) {
            // the picker fetches it from the link later
        }
    }

    private static byte[] readImageFile(Path source) throws ImageException {
        try {
            if (!Files.isRegularFile(source)) throw new ImageException("discordrpc.upload.error.read");
            if (Files.size(source) > MAX_FILE_BYTES) {
                throw new ImageException("discordrpc.upload.error.size", MAX_FILE_BYTES / (1024 * 1024));
            }
            return Files.readAllBytes(source);
        } catch (IOException e) {
            throw new ImageException("discordrpc.upload.error.read");
        }
    }

    /**
     * Keeps the original in the images folder so the player has it later.
     * Returns the copy (or the source itself if it already lives there), or
     * null if it couldn't be written, which doesn't stop the upload.
     */
    private Path keepCopy(Path source, byte[] bytes, String sha, ImageTools.Kind kind) {
        try {
            Files.createDirectories(imagesDir);
            Path parent = source.toAbsolutePath().normalize().getParent();
            if (parent != null && Files.isSameFile(parent, imagesDir)) return source;

            String base = baseName(source.getFileName().toString())
                    .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
            if (base.isEmpty() || base.startsWith(".")) base = "image";
            base = cap(base, 48);
            for (int n = 1; n < 1000; n++) {
                Path target = imagesDir.resolve(n == 1 ? base + "." + kind.ext : base + " (" + n + ")." + kind.ext);
                if (!Files.exists(target)) {
                    Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
                    return target;
                }
                if (Files.size(target) == bytes.length && sha.equals(sha256(Files.readAllBytes(target)))) {
                    return target;
                }
            }
        } catch (IOException e) {
            DiscordRPCMod.LOGGER.warn("Could not keep a copy of {}: {}", source.getFileName(), e.toString());
        }
        return null;
    }

    // Helpers

    public static boolean isLink(String key) {
        if (key == null) return false;
        String k = key.trim().toLowerCase(Locale.ROOT);
        return k.startsWith("https://") || k.startsWith("http://");
    }

    public static boolean isImageFileName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".gif");
    }

    /** A short readable name for any image field value: the library name for links, else the value itself. */
    public String displayName(String key) {
        if (!isLink(key)) return key == null ? "" : key;
        Entry e = find(key);
        return e != null ? e.name() : nameFromLink(key);
    }

    public static String nameFromLink(String link) {
        try {
            URI uri = URI.create(link.trim());
            String path = uri.getPath();
            if (path != null && !path.isEmpty()) {
                String last = baseName(path.substring(path.lastIndexOf('/') + 1));
                if (!last.isBlank()) return cap(last, 32);
            }
            if (uri.getHost() != null) return uri.getHost();
        } catch (IllegalArgumentException ignored) {}
        return "link";
    }

    private static String baseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String cap(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
