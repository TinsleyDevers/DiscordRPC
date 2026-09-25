package com.tinsl.discordrpc.images;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Talks to catbox.moe, the free image host that holds player uploads, and
 * downloads images players link directly.
 *
 * <p>Discord can only show a presence image that is either an asset on the
 * mod's own Discord application or a public https link. Players can't add
 * assets to that application, so their images go to catbox (anonymous, no
 * account or API key) and the link is what the presence sends.
 */
final class ImageHost {

    static final String NAME = "catbox.moe";

    private static final URI UPLOAD_API = URI.create("https://catbox.moe/user/api.php");
    private static final Pattern UPLOADED_LINK = Pattern.compile("https://files\\.catbox\\.moe/[A-Za-z0-9._-]+");
    private static final String USER_AGENT = "DiscordPresence (Minecraft mod; https://modrinth.com/mod/discordpresence)";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private ImageHost() {}

    /** Uploads the bytes and returns the public link. Blocking. */
    static String upload(ImageTools.Prepared image) throws ImageException {
        String boundary = "DiscordPresence" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream(image.bytes().length + 512);
        try {
            writeAscii(body, "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"reqtype\"\r\n\r\n"
                    + "fileupload\r\n"
                    + "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"fileToUpload\"; filename=\"image." + image.kind().ext + "\"\r\n"
                    + "Content-Type: " + image.kind().mime + "\r\n\r\n");
            body.write(image.bytes());
            writeAscii(body, "\r\n--" + boundary + "--\r\n");
        } catch (IOException e) {
            throw new ImageException("discordrpc.upload.error.read");
        }

        HttpRequest request = HttpRequest.newBuilder(UPLOAD_API)
                .timeout(Duration.ofSeconds(90))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();

        HttpResponse<String> response;
        try {
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ImageException("discordrpc.upload.error.network", NAME);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImageException("discordrpc.upload.error.network", NAME);
        }

        String text = response.body() == null ? "" : response.body().trim();
        if (response.statusCode() == 200 && UPLOADED_LINK.matcher(text).matches()) {
            return text;
        }
        String reason = text.isEmpty() || text.length() > 80 || text.startsWith("<")
                ? "HTTP " + response.statusCode() : text;
        throw new ImageException("discordrpc.upload.error.host", NAME, reason);
    }

    /** Downloads a linked image, refusing anything over {@code maxBytes}. Blocking. */
    static byte[] download(String link, long maxBytes) throws ImageException {
        URI uri;
        try {
            uri = URI.create(link.trim());
        } catch (IllegalArgumentException e) {
            throw new ImageException("discordrpc.upload.error.link");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http")) || uri.getHost() == null) {
            throw new ImageException("discordrpc.upload.error.link");
        }

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "image/*")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                if (response.statusCode() != 200) {
                    throw new ImageException("discordrpc.upload.error.link_status", response.statusCode());
                }
                byte[] bytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
                if (bytes.length > maxBytes) {
                    throw new ImageException("discordrpc.upload.error.size", maxBytes / (1024 * 1024));
                }
                return bytes;
            }
        } catch (IOException e) {
            throw new ImageException("discordrpc.upload.error.link_unreachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImageException("discordrpc.upload.error.link_unreachable");
        }
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.UTF_8));
    }
}
