package com.tinsl.discordrpc.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tinsl.discordrpc.DiscordRPCMod;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Minimal Discord IPC client. Talks to the local Discord app over a named pipe
 * (Windows) or unix domain socket (macOS, Linux, including Flatpak and Snap
 * installs) and speaks just enough of the protocol for Rich Presence:
 * HANDSHAKE, SET_ACTIVITY frames, PING/PONG and CLOSE.
 *
 * All methods block on pipe I/O, so they must only be called from the RPC
 * worker thread - never from the render thread.
 */
public class DiscordIPC {
    private static final int OP_HANDSHAKE = 0;
    private static final int OP_FRAME = 1;
    private static final int OP_CLOSE = 2;
    private static final int OP_PING = 3;
    private static final int OP_PONG = 4;

    private static final int MAX_FRAME_SIZE = 1 << 20;

    public enum State { DISCONNECTED, CONNECTING, CONNECTED }

    private volatile State state = State.DISCONNECTED;
    private String clientId;

    /** Windows named pipe; null on other platforms. */
    private RandomAccessFile pipe;
    /** Unix domain socket; null on Windows. */
    private SocketChannel socketChannel;

    private final boolean isWindows;
    /** Discord account name from the READY handshake, e.g. "zambied". Empty until connected. */
    private volatile String discordUser = "";

    public DiscordIPC() {
        this.isWindows = System.getProperty("os.name").toLowerCase().contains("win");
    }

    /**
     * Tries every known pipe/socket location until one accepts the handshake.
     * Returns false if Discord isn't running (or none of the locations worked).
     */
    public synchronized boolean connect(String clientId) {
        if (state == State.CONNECTED) return true;
        this.clientId = clientId;
        state = State.CONNECTING;

        if (isWindows) {
            for (int i = 0; i < 10; i++) {
                if (tryOpen("\\\\.\\pipe\\discord-ipc-" + i, null)) return true;
            }
        } else {
            for (Path dir : unixSocketDirs()) {
                for (int i = 0; i < 10; i++) {
                    Path socket = dir.resolve("discord-ipc-" + i);
                    if (!Files.exists(socket)) continue;
                    if (tryOpen(null, socket)) return true;
                }
            }
        }

        state = State.DISCONNECTED;
        DiscordRPCMod.LOGGER.warn("Could not reach Discord IPC - is the Discord desktop app running?");
        return false;
    }

    private boolean tryOpen(String windowsPipe, Path unixSocket) {
        try {
            if (windowsPipe != null) {
                pipe = new RandomAccessFile(windowsPipe, "rw");
            } else {
                socketChannel = SocketChannel.open(UnixDomainSocketAddress.of(unixSocket));
            }
            state = State.CONNECTED;
            performHandshake();
            DiscordRPCMod.LOGGER.info("Connected to Discord IPC at {}{}",
                    windowsPipe != null ? windowsPipe : unixSocket,
                    discordUser.isEmpty() ? "" : " (account: " + discordUser + ")");
            return true;
        } catch (Exception e) {
            closeResources();
            state = State.CONNECTING;
            return false;
        }
    }

    /**
     * Socket directories checked on macOS/Linux. Sandboxed Discord installs
     * (Flatpak, Snap) put the socket in a subdirectory of XDG_RUNTIME_DIR.
     */
    private static List<Path> unixSocketDirs() {
        List<Path> bases = new ArrayList<>();
        for (String env : new String[]{"XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"}) {
            String v = System.getenv(env);
            if (v != null && !v.isEmpty()) bases.add(Path.of(v));
        }
        bases.add(Path.of("/tmp"));

        String[] subDirs = {"", "app/com.discordapp.Discord", "app/com.discordapp.DiscordCanary",
                "snap.discord", "snap.discord-canary"};
        List<Path> dirs = new ArrayList<>();
        for (Path base : bases) {
            for (String sub : subDirs) {
                Path dir = sub.isEmpty() ? base : base.resolve(sub);
                if (!dirs.contains(dir) && Files.isDirectory(dir)) dirs.add(dir);
            }
        }
        return dirs;
    }

    private void performHandshake() throws IOException {
        JsonObject handshake = new JsonObject();
        handshake.addProperty("v", 1);
        handshake.addProperty("client_id", clientId);
        send(OP_HANDSHAKE, handshake.toString());

        // Discord answers a valid handshake with a DISPATCH/READY frame that
        // includes the logged-in user. Anything else means the handshake failed.
        String response = readFrame();
        try {
            JsonObject json = JsonParser.parseString(response).getAsJsonObject();
            String evt = json.has("evt") && !json.get("evt").isJsonNull() ? json.get("evt").getAsString() : "";
            if (!"READY".equals(evt)) {
                throw new IOException("Unexpected handshake reply: " + trimForLog(response));
            }
            JsonObject data = json.getAsJsonObject("data");
            if (data != null && data.has("user")) {
                JsonObject user = data.getAsJsonObject("user");
                if (user.has("global_name") && !user.get("global_name").isJsonNull()) {
                    discordUser = user.get("global_name").getAsString();
                } else if (user.has("username")) {
                    discordUser = user.get("username").getAsString();
                }
            }
        } catch (IllegalStateException | com.google.gson.JsonParseException e) {
            throw new IOException("Malformed handshake reply", e);
        }
    }

    /** Sends the activity (or clears it when null). Safe no-op while disconnected. */
    public synchronized void setActivity(JsonObject activity) {
        if (state != State.CONNECTED) return;
        try {
            JsonObject args = new JsonObject();
            args.addProperty("pid", ProcessHandle.current().pid());
            if (activity != null) args.add("activity", activity);

            JsonObject payload = new JsonObject();
            payload.addProperty("cmd", "SET_ACTIVITY");
            payload.addProperty("nonce", UUID.randomUUID().toString());
            payload.add("args", args);

            send(OP_FRAME, payload.toString());
            String reply = readFrame();
            logIfError(reply);
        } catch (Exception e) {
            DiscordRPCMod.LOGGER.warn("Lost Discord connection while updating activity: {}", e.getMessage());
            handleDisconnect();
        }
    }

    public synchronized void clearActivity() {
        setActivity(null);
    }

    private static void logIfError(String reply) {
        try {
            JsonObject json = JsonParser.parseString(reply).getAsJsonObject();
            if (json.has("evt") && !json.get("evt").isJsonNull()
                    && "ERROR".equals(json.get("evt").getAsString())) {
                DiscordRPCMod.LOGGER.warn("Discord rejected activity update: {}", trimForLog(reply));
            }
        } catch (Exception ignored) {}
    }

    private void send(int opcode, String data) throws IOException {
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(8 + bytes.length);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(opcode);
        buffer.putInt(bytes.length);
        buffer.put(bytes);
        buffer.flip();

        if (isWindows && pipe != null) {
            pipe.write(buffer.array());
        } else if (socketChannel != null) {
            while (buffer.hasRemaining()) socketChannel.write(buffer);
        } else {
            throw new IOException("Not connected");
        }
    }

    /**
     * Reads frames until a data frame arrives. PINGs are answered inline;
     * a CLOSE frame tears the connection down and throws.
     */
    private String readFrame() throws IOException {
        while (true) {
            ByteBuffer header = ByteBuffer.allocate(8);
            header.order(ByteOrder.LITTLE_ENDIAN);
            readFully(header);
            header.flip();

            int op = header.getInt();
            int length = header.getInt();
            if (length < 0 || length > MAX_FRAME_SIZE) {
                throw new IOException("Invalid IPC frame length: " + length);
            }

            ByteBuffer body = ByteBuffer.allocate(length);
            readFully(body);
            body.flip();
            String payload = StandardCharsets.UTF_8.decode(body).toString();

            switch (op) {
                case OP_PING -> send(OP_PONG, payload);
                case OP_CLOSE -> {
                    handleDisconnect();
                    throw new IOException("Discord closed the connection: " + trimForLog(payload));
                }
                default -> {
                    return payload;
                }
            }
        }
    }

    private void readFully(ByteBuffer buffer) throws IOException {
        if (isWindows && pipe != null) {
            byte[] bytes = new byte[buffer.remaining()];
            pipe.readFully(bytes);
            buffer.put(bytes);
        } else if (socketChannel != null) {
            while (buffer.hasRemaining()) {
                if (socketChannel.read(buffer) == -1) throw new IOException("Discord IPC stream ended");
            }
        } else {
            throw new IOException("Not connected");
        }
    }

    private void handleDisconnect() {
        state = State.DISCONNECTED;
        discordUser = "";
        closeResources();
    }

    public synchronized void close() {
        if (state == State.CONNECTED) {
            try {
                send(OP_CLOSE, "{}");
            } catch (Exception ignored) {}
        }
        state = State.DISCONNECTED;
        discordUser = "";
        closeResources();
    }

    private void closeResources() {
        try {
            if (pipe != null) { pipe.close(); pipe = null; }
        } catch (Exception ignored) {}
        try {
            if (socketChannel != null) { socketChannel.close(); socketChannel = null; }
        } catch (Exception ignored) {}
    }

    private static String trimForLog(String s) {
        s = s.replace('\n', ' ');
        return s.length() <= 160 ? s : s.substring(0, 160) + "..";
    }

    public State getState() {
        return state;
    }

    public boolean isConnected() {
        return state == State.CONNECTED;
    }

    /** Display name of the connected Discord account, or "" when unknown. */
    public String getDiscordUser() {
        return discordUser;
    }
}
