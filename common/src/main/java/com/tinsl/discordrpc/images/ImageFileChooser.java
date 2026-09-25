package com.tinsl.discordrpc.images;

import com.tinsl.discordrpc.DiscordRPCMod;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The system "open file" dialog, through the tinyfd library Minecraft already
 * ships with LWJGL. It runs on its own thread so the game keeps drawing while
 * the dialog is open (tinyfd uses a native dialog on Windows and a helper
 * process on macOS and Linux, so no platform needs the main thread).
 */
public final class ImageFileChooser {

    private static final AtomicBoolean OPEN = new AtomicBoolean();

    private ImageFileChooser() {}

    public static boolean isOpen() {
        return OPEN.get();
    }

    /**
     * Completes with the chosen file, or null if the player cancelled, a
     * dialog is already open, or the system has no dialog to show.
     */
    public static CompletableFuture<Path> choose(String title, String filterDescription) {
        if (!OPEN.compareAndSet(false, true)) return CompletableFuture.completedFuture(null);
        CompletableFuture<Path> result = new CompletableFuture<>();
        Thread t = new Thread(() -> {
            try {
                result.complete(showDialog(title, filterDescription));
            } catch (Throwable e) {
                DiscordRPCMod.LOGGER.warn("The file chooser could not open: {}", e.toString());
                result.complete(null);
            } finally {
                OPEN.set(false);
            }
        }, "DiscordPresence file chooser");
        t.setDaemon(true);
        t.start();
        return result;
    }

    private static Path showDialog(String title, String filterDescription) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer patterns = stack.mallocPointer(4);
            patterns.put(stack.UTF8("*.png"));
            patterns.put(stack.UTF8("*.jpg"));
            patterns.put(stack.UTF8("*.jpeg"));
            patterns.put(stack.UTF8("*.gif"));
            patterns.flip();
            String start = System.getProperty("user.home", "") + File.separator;
            String picked = TinyFileDialogs.tinyfd_openFileDialog(title, start, patterns, filterDescription, false);
            return picked == null || picked.isBlank() ? null : Path.of(picked);
        }
    }
}
