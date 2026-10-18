package com.tinsl.discordrpc.images;

import com.tinsl.discordrpc.DiscordRPCMod;
import net.minecraft.client.Minecraft;
import org.lwjgl.PointerBuffer;
import org.lwjgl.sdl.SDLDialog;
import org.lwjgl.sdl.SDL_DialogFileCallback;
import org.lwjgl.sdl.SDL_DialogFileFilter;
import org.lwjgl.system.MemoryUtil;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The system "open file" dialog. Minecraft 26.3 moved from GLFW to SDL3 and no
 * longer ships tinyfd, so this target asks SDL for the dialog instead of the
 * shared tinyfd version in common/.
 *
 * SDL wants the call made on the main thread and answers through a callback,
 * which on some systems arrives on a thread of its own. The filter strings and
 * the callback have to stay alive until that answer comes, so they are freed
 * afterwards, back on the game thread. SDL's plain dialog takes no title, so
 * the title argument is only kept to match the other targets.
 */
public final class ImageFileChooser {

    private static final AtomicBoolean OPEN = new AtomicBoolean();

    private ImageFileChooser() {}

    public static boolean isOpen() {
        return OPEN.get();
    }

    /**
     * Completes with the chosen file, or null if the player cancelled, a
     * dialog is already open, or the system has no dialog to show. Call it
     * from the game thread.
     */
    public static CompletableFuture<Path> choose(String title, String filterDescription) {
        if (!OPEN.compareAndSet(false, true)) return CompletableFuture.completedFuture(null);
        CompletableFuture<Path> result = new CompletableFuture<>();

        ByteBuffer name = null;
        ByteBuffer pattern = null;
        ByteBuffer start = null;
        SDL_DialogFileFilter.Buffer filters = null;
        SDL_DialogFileCallback[] holder = new SDL_DialogFileCallback[1];
        try {
            name = MemoryUtil.memUTF8(filterDescription);
            pattern = MemoryUtil.memUTF8("png;jpg;jpeg;gif");
            start = MemoryUtil.memUTF8(System.getProperty("user.home", "") + File.separator);
            filters = SDL_DialogFileFilter.calloc(1);
            filters.get(0).name(name).pattern(pattern);

            ByteBuffer nameRef = name;
            ByteBuffer patternRef = pattern;
            ByteBuffer startRef = start;
            SDL_DialogFileFilter.Buffer filtersRef = filters;
            holder[0] = SDL_DialogFileCallback.create((userdata, filelist, filter) -> {
                Path picked = null;
                try {
                    if (filelist == MemoryUtil.NULL) {
                        DiscordRPCMod.LOGGER.warn("The file chooser could not open: {}",
                                org.lwjgl.sdl.SDLError.SDL_GetError());
                    } else {
                        long first = PointerBuffer.create(filelist, 1).get(0);
                        if (first != MemoryUtil.NULL) {
                            String path = MemoryUtil.memUTF8(first);
                            if (!path.isBlank()) picked = Path.of(path);
                        }
                    }
                } catch (Throwable e) {
                    DiscordRPCMod.LOGGER.warn("The file chooser failed: {}", e.toString());
                } finally {
                    OPEN.set(false);
                    result.complete(picked);
                    // Never free a callback from inside itself.
                    Minecraft.getInstance().execute(() -> {
                        holder[0].free();
                        filtersRef.free();
                        MemoryUtil.memFree(nameRef);
                        MemoryUtil.memFree(patternRef);
                        MemoryUtil.memFree(startRef);
                    });
                }
            });

            long window = Minecraft.getInstance().getWindow().handle();
            SDLDialog.SDL_ShowOpenFileDialog(holder[0], MemoryUtil.NULL, window, filters, start, false);
        } catch (Throwable e) {
            DiscordRPCMod.LOGGER.warn("The file chooser could not open: {}", e.toString());
            if (holder[0] != null) holder[0].free();
            if (filters != null) filters.free();
            if (name != null) MemoryUtil.memFree(name);
            if (pattern != null) MemoryUtil.memFree(pattern);
            if (start != null) MemoryUtil.memFree(start);
            OPEN.set(false);
            result.complete(null);
        }
        return result;
    }
}
