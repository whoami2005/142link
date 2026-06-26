package com.krishna.amzplugin.ig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Session-based Instagram media resolver.
 * Scrapes session tokens from IG homepage and queries their internal APIs.
 */
public class IGMediaResolver {

    private static final Logger log = LoggerFactory.getLogger(IGMediaResolver.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private final HttpClient http;
    private final AtomicReference<SessionData> session = new AtomicReference<>();

    public IGMediaResolver() {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(12))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ── session management ──────────────────────────────────────────────────

    private static class SessionData {
        final String csrf, appId, lsd, docId;
        SessionData(String csrf, String appId, String lsd, String docId) {
            this.csrf = csrf; this.appId = appId; this.lsd = lsd; this.docId = docId;
        }
    }

    private SessionData acquireSession() {
        try {
            var req = HttpRequest.newBuilder(URI.create("https://www.instagram.com/"))
                    .header("User-Agent", UA).header("Accept", "text/html")
                    .timeout(Duration.ofSeconds(10)).GET().build();
            var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 || resp.body() == null) return null;
            String html = resp.body();
            String csrf = grab("\"csrf_token\":\"(.*?)\"", html);
            String appId = grab("\"appId\":\"(.*?)\"", html);
            String lsd = grab("\"LSD\",\\[],\\{\"token\":\"(.*?)\"\\},", html);
            if (lsd == null) lsd = grab("name=\"lsd\" value=\"(.*?)\"", html);
            String docId = grab("\"PostPage\",\\[],\"(\\d+)\",", html);
            if (docId == null) docId = "10015901848480474";
            if (csrf == null || appId == null || lsd == null) {
                log.error("IG session scrape incomplete — csrf={} appId={} lsd={}", csrf != null, appId != null, lsd != null);
                return null;
            }
            log.info("IG session tokens acquired");
            return new SessionData(csrf, appId, lsd, docId);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        catch (Exception e) { log.error("IG session init error: {}", e.getMessage()); return null; }
    }

    private SessionData ensureSession() {
        SessionData s = session.get();
        if (s != null) return s;
        s = acquireSession();
        if (s != null) session.set(s);
        return s;
    }

    private SessionData refreshSession() {
        session.set(null);
        return ensureSession();
    }

    // ── public API ──────────────────────────────────────────────────────────

    public static class MediaInfo {
        public final String streamUrl, title, author, artworkUrl;
        public final long durationMs;
        public MediaInfo(String streamUrl, String title, String author, long durationMs, String artworkUrl) {
            this.streamUrl = streamUrl; this.title = title; this.author = author;
            this.durationMs = durationMs; this.artworkUrl = artworkUrl;
        }
    }

    /**
     * Resolve a post or reel by shortcode via the GraphQL API.
     */
    public MediaInfo resolveByShortcode(String shortcode, String type) {
        return withRetry(() -> queryGraphQL(shortcode, type));
    }

    /**
     * Resolve an audio clip page by numeric audio cluster ID.
     */
    public MediaInfo resolveAudioClip(String audioId) {
        return withRetry(() -> queryAudioEndpoint(audioId));
    }

    // ── internal queries ────────────────────────────────────────────────────

    private MediaInfo queryGraphQL(String shortcode, String type) {
        SessionData s = ensureSession();
        if (s == null) return null;
        try {
            String vars = "{\"shortcode\":\"" + shortcode + "\",\"fetch_comment_count\":\"null\"," +
                    "\"fetch_related_profile_media_count\":\"null\",\"parent_comment_count\":\"null\"," +
                    "\"child_comment_count\":\"null\",\"fetch_like_count\":\"null\"," +
                    "\"fetch_tagged_user_count\":\"null\",\"fetch_preview_comment_count\":\"null\"," +
                    "\"has_threaded_comments\":\"false\",\"hoisted_comment_id\":\"null\",\"hoisted_reply_id\":\"null\"}";
            String payload = form("av", "0", "__user", "0", "__a", "1", "__req", "3", "dpr", "1",
                    "__ccg", "UNKNOWN", "lsd", s.lsd, "jazoest", "2957", "doc_id", s.docId,
                    "variables", vars, "fb_api_req_friendly_name", "PolarisPostActionLoadPostQueryQuery",
                    "fb_api_caller_class", "RelayModern");
            if (payload == null) return null;
            String ref = "https://www.instagram.com/" + (type != null ? type : "p") + "/" + shortcode + "/";
            var req = HttpRequest.newBuilder(URI.create("https://www.instagram.com/api/graphql"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("X-CSRFToken", s.csrf).header("X-IG-App-ID", s.appId)
                    .header("X-FB-LSD", s.lsd).header("X-FB-Friendly-Name", "PolarisPostActionLoadPostQueryQuery")
                    .header("X-ASBD-ID", "129477").header("User-Agent", UA)
                    .header("Origin", "https://www.instagram.com").header("Referer", ref)
                    .header("Sec-Fetch-Site", "same-origin").timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
            var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 || resp.body() == null) return null;
            JsonNode root = JSON.readTree(resp.body());
            JsonNode media = root.at("/data/xdt_shortcode_media");
            if (media.isMissingNode()) return null;
            JsonNode vid = pickVideo(media);
            if (vid == null) return null;
            String url = vid.path("video_url").asText(null);
            if (url == null) return null;
            String caption = media.at("/edge_media_to_caption/edges/0/node/text").asText("Instagram Video");
            if (caption.length() > 100) caption = caption.substring(0, 97) + "...";
            return new MediaInfo(url, caption, media.at("/owner/username").asText("Unknown"),
                    (long) (vid.path("video_duration").asDouble(0) * 1000),
                    vid.path("display_url").asText(media.path("display_url").asText("")));
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        catch (Exception e) { log.debug("IG GQL query failed: {}", e.getMessage()); return null; }
    }

    private MediaInfo queryAudioEndpoint(String audioId) {
        SessionData s = ensureSession();
        if (s == null) return null;
        try {
            String payload = form("audio_cluster_id", audioId, "lsd", s.lsd, "jazoest", "2957", "__user", "0", "__a", "1");
            var req = HttpRequest.newBuilder(URI.create("https://www.instagram.com/api/v1/clips/music/"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("X-CSRFToken", s.csrf).header("X-IG-App-ID", s.appId)
                    .header("X-FB-LSD", s.lsd).header("X-FB-Friendly-Name", "PolarisClipsAudioRoute")
                    .header("X-ASBD-ID", "129477").header("User-Agent", UA)
                    .header("Origin", "https://www.instagram.com")
                    .header("Referer", "https://www.instagram.com/reels/audio/" + audioId + "/")
                    .header("Sec-Fetch-Site", "same-origin").timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
            var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 || resp.body() == null) return null;
            String body = resp.body();
            if (body.startsWith("for (;;);")) body = body.substring(9);
            JsonNode root = JSON.readTree(body);
            JsonNode meta = (root.has("payload") ? root.get("payload") : root).path("metadata");
            if (meta.isMissingNode()) return null;
            return extractAudioMeta(meta);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        catch (Exception e) { log.debug("IG audio query failed: {}", e.getMessage()); return null; }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private MediaInfo withRetry(java.util.function.Supplier<MediaInfo> action) {
        MediaInfo result = action.get();
        if (result == null) { refreshSession(); result = action.get(); }
        return result;
    }

    private MediaInfo extractAudioMeta(JsonNode meta) {
        JsonNode orig = meta.path("original_sound_info");
        boolean isOrig = !orig.isMissingNode() && !orig.isNull();
        if (isOrig) {
            String url = orig.path("progressive_download_url").asText(null);
            if (url == null) return null;
            return new MediaInfo(url, orig.path("original_audio_title").asText("Instagram Audio"),
                    orig.at("/ig_artist/username").asText("Unknown"), orig.path("duration_in_ms").asLong(0),
                    orig.at("/ig_artist/profile_pic_url").asText(""));
        }
        JsonNode music = meta.path("music_info");
        if (music.isMissingNode()) return null;
        JsonNode asset = music.path("music_asset_info");
        JsonNode consumption = music.path("music_consumption_info");
        String url = asset.path("progressive_download_url").asText(null);
        if (url == null && consumption.has("dash_manifest")) {
            Matcher m = Pattern.compile("<BaseURL>(.*?)</BaseURL>").matcher(consumption.get("dash_manifest").asText(""));
            if (m.find()) url = m.group(1).replace("&amp;", "&");
        }
        if (url == null) url = music.path("progressive_download_url").asText(null);
        if (url == null) return null;
        return new MediaInfo(url, asset.path("title").asText("Instagram Audio"),
                asset.path("artist_name").asText("Unknown"), asset.path("duration_in_ms").asLong(0),
                asset.path("cover_artwork_thumbnail_uri").asText(""));
    }

    private JsonNode pickVideo(JsonNode media) {
        if (media.path("is_video").asBoolean(false)) return media;
        if ("XDTGraphSidecar".equals(media.path("__typename").asText(""))) {
            for (JsonNode edge : media.at("/edge_sidecar_to_children/edges"))
                if (edge.at("/node/is_video").asBoolean(false)) return edge.path("node");
        }
        return null;
    }

    private static String grab(String regex, String text) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String form(String... kv) {
        var sb = new StringBuilder();
        for (int i = 0; i < kv.length; i += 2) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(kv[i], StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(kv[i + 1], StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
