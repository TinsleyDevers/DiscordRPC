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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal Discord IPC client. Talks to the local Discord app over a named pipe
 * (Windows) or unix domain socket (macOS, Linux, including Flatpak and Snap
 * installs) and speaks just enough of the protocol for Rich Presence:
 * HANDSHAKE, SET_ACTIVITY frames, PING/PONG and CLOSE.
 *
 * Every running Discord app (Stable, PTB, Canary) opens its own pipe, numbered
 * in the order the apps started. The READY handshake says which account is
 * signed in and which build answered, so the player can pick an account and
 * the connection follows it no matter which app grabbed which number.
 *
 * Discord holds back its reply to a second handshake from the same app for up
 * to 30 seconds. So a pipe is handshaken once and then kept: the one showing
 * the presence is the live link, the others wait idle in {@link #parked} with
 * no activity set (nothing shows on those accounts). Listing accounts reads
 * the parked links, and switching accounts swaps links without a new
 * handshake, so both are instant.
 *
 * All methods block on pipe I/O, so they must only be called from the RPC
 * worker thread - never from the render thread. The one exception is
 * {@link #abort()}, which exists precisely so another thread (the shutdown
 * hook) can yank the pipes out from under a blocked read.
 */
public class DiscordIPC {
    private static final int OP_HANDSHAKE = 0;
    private static final int OP_FRAME = 1;
    private static final int OP_CLOSE = 2;
    private static final int OP_PING = 3;
    private static final int OP_PONG = 4;

    private static final int MAX_FRAME_SIZE = 1 << 20;
    /** Frames to scan for our nonce before giving up on correlating a reply. */
    private static final int MAX_REPLY_SCAN = 8;
    /** Discord numbers its pipes discord-ipc-0 through discord-ipc-9. */
    private static final int PIPE_SLOTS = 10;
    private static final String PIPE_DIR = "\\\\.\\pipe\\";

    public enum State { DISCONNECTED, CONNECTING, CONNECTED }

    /** Which Discord build answered. Only used for labels; accounts are matched by id. */
    public enum Client {
        STABLE("Discord"),
        PTB("Discord PTB"),
        CANARY("Discord Canary"),
        DEVELOPMENT("Discord Development");

        private final String displayName;

        Client(String displayName) { this.displayName = displayName; }

        /** Product name, the same in every language. */
        public String displayName() { return displayName; }

        /**
         * Reads the build from the READY config ("//ptb.discord.com/api" and so
         * on). Sandboxed Linux installs also give it away in the socket path.
         */
        static Client detect(String apiEndpoint, String environment, String where) {
            String api = apiEndpoint == null ? "" : apiEndpoint.toLowerCase(Locale.ROOT);
            String path = where == null ? "" : where.toLowerCase(Locale.ROOT);
            if (api.contains("ptb.") || path.contains("discordptb") || path.contains("discord-ptb")) return PTB;
            if (api.contains("canary.") || path.contains("discordcanary") || path.contains("discord-canary")) return CANARY;
            if ("development".equalsIgnoreCase(environment)) return DEVELOPMENT;
            return STABLE;
        }
    }

    /** A Discord account signed in to one of the running Discord apps. */
    public record Account(String id, String name, Client client) {}

    /** One handshaken pipe or socket and the account that answered on it. */
    private static final class Link {
        final String where;
        final RandomAccessFile pipe;
        final SocketChannel socket;
        Account account;

        private Link(String where, RandomAccessFile pipe, SocketChannel socket) {
            this.where = where;
            this.pipe = pipe;
            this.socket = socket;
        }

        void close() {
            try { if (pipe != null) pipe.close(); } catch (Exception ignored) {}
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        }
    }

    private volatile State state = State.DISCONNECTED;
    private String clientId;

    /** The live connection, null while disconnected. Volatile so {@link #abort()} can close it. */
    private volatile Link link;
    /** Handshaken, idle links to the other Discord apps, by pipe. Concurrent for {@link #abort()}. */
    private final Map<String, Link> parked = new ConcurrentHashMap<>();

    private final boolean isWindows;
    /** Last connect found Discord running, just not with the wanted account signed in. */
    private volatile boolean wrongAccountsOnly = false;
    /** Downgrades the "is Discord running?" warning to debug after the first miss. */
    private boolean warnedUnreachable = false;
    /** Same, for "the wanted account is not open". */
    private boolean warnedAccountMissing = false;

    public DiscordIPC() {
        this.isWindows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * Makes the first suitable Discord app the live link. With
     * {@code wantedAccountId} empty the first Discord found wins; with an id,
     * only the app that has that account signed in will do. Returns false if
     * no suitable Discord app is running.
     */
    public synchronized boolean connect(String clientId, String wantedAccountId) {
        if (state == State.CONNECTED) return true;
        this.clientId = clientId;
        state = State.CONNECTING;
        boolean anyAccount = wantedAccountId == null || wantedAccountId.isEmpty();
        boolean sawOthers = false;

        // Auto keeps Discord's own order. A picked account tries the app already
        // parked with it first, which needs no handshake.
        List<String> order = endpoints();
        if (!anyAccount) order.sort(Comparator.comparing((String w) -> !parkedAs(w, wantedAccountId)));

        for (String where : order) {
            Link l = identify(where);
            if (l == null) continue;
            if (!anyAccount && !wantedAccountId.equals(l.account.id())) {
                // Somebody else's Discord. It stays parked, showing nothing.
                sawOthers = true;
                continue;
            }
            parked.remove(where);
            link = l;
            state = State.CONNECTED;
            wrongAccountsOnly = false;
            warnedUnreachable = false;
            warnedAccountMissing = false;
            Account a = l.account;
            DiscordRPCMod.LOGGER.info("Connected to {} at {}{}", a.client().displayName(), where,
                    a.name().isEmpty() ? "" : " (account: " + a.name() + ")");
            return true;
        }

        state = State.DISCONNECTED;
        wrongAccountsOnly = sawOthers;
        if (sawOthers) {
            if (!warnedAccountMissing) {
                warnedAccountMissing = true;
                DiscordRPCMod.LOGGER.warn("Discord is running, but not with the chosen account signed in "
                        + "- waiting for it (pick Auto in the settings to use any account)");
            }
        } else if (!warnedUnreachable) {
            warnedUnreachable = true;
            DiscordRPCMod.LOGGER.warn("Could not reach Discord IPC - is the Discord desktop app running?");
        } else {
            DiscordRPCMod.LOGGER.debug("Discord IPC still unreachable");
        }
        return false;
    }

    /**
     * Lists the accounts signed in to every running Discord app, live link
     * first. Apps already known answer from their parked link; only an app
     * that was not running before gets a handshake. Accounts open in two apps
     * at once are listed once.
     */
    public synchronized List<Account> scanAccounts(String clientId) {
        this.clientId = clientId;
        List<Account> found = new ArrayList<>();
        Link live = link;
        if (state == State.CONNECTED && live != null) found.add(live.account);

        for (String where : endpoints()) {
            if (live != null && live.where.equals(where)) continue;
            Link l = identify(where);
            if (l == null) continue;
            Account a = l.account;
            if (found.stream().noneMatch(f -> f.id().equals(a.id()))) found.add(a);
        }
        return found;
    }

    /**
     * Steps off the live link without closing it, so switching back later is
     * instant. The caller clears the activity first; a parked link shows
     * nothing on that account.
     */
    public synchronized void park() {
        Link l = link;
        if (l != null) parked.put(l.where, l);
        link = null;
        state = State.DISCONNECTED;
    }

    /** The parked link for a pipe, or a fresh handshake that gets parked. Null if nobody answers. */
    private Link identify(String where) {
        Link l = parked.get(where);
        if (l != null) return l;
        l = open(where);
        if (l == null) return null;
        try {
            l.account = handshake(l);
        } catch (Exception e) {
            l.close();
            return null;
        }
        parked.put(where, l);
        return l;
    }

    private boolean parkedAs(String where, String accountId) {
        Link l = parked.get(where);
        return l != null && accountId.equals(l.account.id());
    }

    /**
     * Every pipe or socket a Discord app is listening on, in Discord's own
     * order. On Windows this lists the pipe directory, which is instant and
     * never touches Discord. Parked links to apps that have closed are let go.
     */
    private List<String> endpoints() {
        List<String> out = new ArrayList<>();
        if (isWindows) {
            try (DirectoryStream<Path> pipes = Files.newDirectoryStream(Path.of(PIPE_DIR), "discord-ipc-*")) {
                for (Path p : pipes) out.add(PIPE_DIR + p.getFileName());
                out.sort(Comparator.comparingInt(DiscordIPC::slotOf));
            } catch (Exception e) {
                // If the pipe directory can't be listed, asking each slot always works.
                out.clear();
                for (int i = 0; i < PIPE_SLOTS; i++) out.add(PIPE_DIR + "discord-ipc-" + i);
            }
        } else {
            for (Path dir : unixSocketDirs()) {
                for (int i = 0; i < PIPE_SLOTS; i++) {
                    Path socket = dir.resolve("discord-ipc-" + i);
                    if (Files.exists(socket)) out.add(socket.toString());
                }
            }
        }
        parked.entrySet().removeIf(e -> {
            if (out.contains(e.getKey())) return false;
            e.getValue().close();
            return true;
        });
        return out;
    }

    private static int slotOf(String where) {
        try {
            return Integer.parseInt(where.substring(where.lastIndexOf('-') + 1));
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    private Link open(String where) {
        try {
            if (isWindows) return new Link(where, new RandomAccessFile(where, "rw"), null);
            return new Link(where, null, SocketChannel.open(UnixDomainSocketAddress.of(Path.of(where))));
        } catch (Exception e) {
            return null;
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
                "app/com.discordapp.DiscordPTB", "snap.discord", "snap.discord-canary", "snap.discord-ptb"};
        List<Path> dirs = new ArrayList<>();
        for (Path base : bases) {
            for (String sub : subDirs) {
                Path dir = sub.isEmpty() ? base : base.resolve(sub);
                if (!dirs.contains(dir) && Files.isDirectory(dir)) dirs.add(dir);
            }
        }
        return dirs;
    }

    /** Sends HANDSHAKE and reads READY, which names the signed-in account and the build. */
    private Account handshake(Link l) throws IOException {
        JsonObject handshake = new JsonObject();
        handshake.addProperty("v", 1);
        handshake.addProperty("client_id", clientId);
        send(l, OP_HANDSHAKE, handshake.toString());

        // Discord answers a valid handshake with a DISPATCH/READY frame that
        // includes the logged-in user. Anything else means the handshake failed.
        String response = readFrame(l);
        try {
            JsonObject json = JsonParser.parseString(response).getAsJsonObject();
            if (!"READY".equals(str(json, "evt"))) {
                throw new IOException("Unexpected handshake reply: " + trimForLog(response));
            }
            JsonObject data = json.has("data") && json.get("data").isJsonObject()
                    ? json.getAsJsonObject("data") : new JsonObject();
            String id = "", name = "";
            if (data.has("user") && data.get("user").isJsonObject()) {
                JsonObject user = data.getAsJsonObject("user");
                id = str(user, "id");
                name = str(user, "global_name");
                if (name.isEmpty()) name = str(user, "username");
            }
            String api = "", env = "";
            if (data.has("config") && data.get("config").isJsonObject()) {
                JsonObject cfg = data.getAsJsonObject("config");
                api = str(cfg, "api_endpoint");
                env = str(cfg, "environment");
            }
            return new Account(id, name, Client.detect(api, env, l.where));
        } catch (IllegalStateException | com.google.gson.JsonParseException e) {
            throw new IOException("Malformed handshake reply", e);
        }
    }

    /**
     * Sends the activity (or clears it when null). Safe no-op while
     * disconnected. Returns true when Discord acknowledged the frame; false
     * means the connection is gone and the caller should reconnect.
     */
    public synchronized boolean setActivity(JsonObject activity) {
        Link l = link;
        if (state != State.CONNECTED || l == null) return false;
        try {
            JsonObject args = new JsonObject();
            args.addProperty("pid", ProcessHandle.current().pid());
            if (activity != null) args.add("activity", activity);

            String nonce = UUID.randomUUID().toString();
            JsonObject payload = new JsonObject();
            payload.addProperty("cmd", "SET_ACTIVITY");
            payload.addProperty("nonce", nonce);
            payload.add("args", args);

            send(l, OP_FRAME, payload.toString());
            logIfError(awaitReply(l, nonce));
            return true;
        } catch (Exception e) {
            DiscordRPCMod.LOGGER.warn("Lost Discord connection while updating activity: {}", e.getMessage());
            handleDisconnect();
            return false;
        }
    }

    public synchronized boolean clearActivity() {
        return setActivity(null);
    }

    /**
     * Reads until the frame carrying {@code nonce} arrives, skipping
     * unsolicited DISPATCH events so an event frame can never be mistaken for
     * our reply (which would shift every later request/reply pairing).
     */
    private String awaitReply(Link l, String nonce) throws IOException {
        for (int i = 0; i < MAX_REPLY_SCAN; i++) {
            String reply = readFrame(l);
            try {
                JsonObject json = JsonParser.parseString(reply).getAsJsonObject();
                if (nonce.equals(str(json, "nonce"))) return reply;
                if (!"DISPATCH".equals(str(json, "cmd"))) return reply;
            } catch (Exception e) {
                return reply;
            }
        }
        return "{}";
    }

    private static void logIfError(String reply) {
        try {
            JsonObject json = JsonParser.parseString(reply).getAsJsonObject();
            if ("ERROR".equals(str(json, "evt"))) {
                DiscordRPCMod.LOGGER.warn("Discord rejected activity update: {}", trimForLog(reply));
            }
        } catch (Exception ignored) {}
    }

    private static String str(JsonObject json, String key) {
        try {
            return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private void send(Link l, int opcode, String data) throws IOException {
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(8 + bytes.length);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(opcode);
        buffer.putInt(bytes.length);
        buffer.put(bytes);
        buffer.flip();

        if (l.pipe != null) {
            l.pipe.write(buffer.array());
        } else if (l.socket != null) {
            while (buffer.hasRemaining()) l.socket.write(buffer);
        } else {
            throw new IOException("Not connected");
        }
    }

    /**
     * Reads frames until a data frame arrives. PINGs are answered inline;
     * a CLOSE frame tears the connection down and throws.
     */
    private String readFrame(Link l) throws IOException {
        while (true) {
            ByteBuffer header = ByteBuffer.allocate(8);
            header.order(ByteOrder.LITTLE_ENDIAN);
            readFully(l, header);
            header.flip();

            int op = header.getInt();
            int length = header.getInt();
            if (length < 0 || length > MAX_FRAME_SIZE) {
                throw new IOException("Invalid IPC frame length: " + length);
            }

            ByteBuffer body = ByteBuffer.allocate(length);
            readFully(l, body);
            body.flip();
            String payload = StandardCharsets.UTF_8.decode(body).toString();

            switch (op) {
                case OP_PING -> send(l, OP_PONG, payload);
                case OP_CLOSE -> {
                    // A handshake being refused only ends that attempt, never the live link.
                    if (l == link) handleDisconnect();
                    throw new IOException("Discord closed the connection: " + trimForLog(payload));
                }
                default -> {
                    return payload;
                }
            }
        }
    }

    private void readFully(Link l, ByteBuffer buffer) throws IOException {
        if (l.pipe != null) {
            byte[] bytes = new byte[buffer.remaining()];
            l.pipe.readFully(bytes);
            buffer.put(bytes);
        } else if (l.socket != null) {
            while (buffer.hasRemaining()) {
                if (l.socket.read(buffer) == -1) throw new IOException("Discord IPC stream ended");
            }
        } else {
            throw new IOException("Not connected");
        }
    }

    /** The live link failed; it is dead, so it is closed rather than parked. */
    private void handleDisconnect() {
        state = State.DISCONNECTED;
        closeLive();
    }

    /** Ends the live link. Parked links to the other Discord apps stay. */
    public synchronized void close() {
        Link l = link;
        if (state == State.CONNECTED && l != null) {
            try {
                send(l, OP_CLOSE, "{}");
            } catch (Exception ignored) {}
        }
        state = State.DISCONNECTED;
        closeLive();
    }

    /** Ends every link, live and parked: presence turned off, or the game is closing. */
    public synchronized void closeAll() {
        close();
        closeParked();
    }

    /**
     * Emergency teardown for the shutdown hook. Deliberately NOT synchronized:
     * if the worker thread is wedged inside a blocking read it holds this
     * object's monitor, and closing the underlying pipe/socket from here is
     * what forces that read to fail and the worker to exit.
     */
    public void abort() {
        state = State.DISCONNECTED;
        closeLive();
        closeParked();
    }

    private void closeLive() {
        Link l = link;
        if (l != null) {
            link = null;
            l.close();
        }
    }

    private void closeParked() {
        for (Link l : parked.values()) l.close();
        parked.clear();
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

    /** Account on the live connection, or null while disconnected. */
    public Account getAccount() {
        Link l = link;
        return state == State.CONNECTED && l != null ? l.account : null;
    }

    /** Display name of the connected Discord account, or "" when unknown. */
    public String getDiscordUser() {
        Account a = getAccount();
        return a == null ? "" : a.name();
    }

    /** True when the last connect found Discord open, but only with other accounts signed in. */
    public boolean isWaitingForAccount() {
        return state != State.CONNECTED && wrongAccountsOnly;
    }
}
