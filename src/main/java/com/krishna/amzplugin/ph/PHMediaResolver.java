package com.krishna.amzplugin.ph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PornHub media resolver using the public Webmasters API.
 * No credentials needed — api.pornhub.com/webmasters is publicly accessible.
 *
 * API Docs: https://www.pornhub.com/webmasters/documentation
 */
public class PHMediaResolver {

    private static final Logger log = LoggerFactory.getLogger(PHMediaResolver.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    // Matches: pornhub.com/view_video.php?viewkey=XXXXX
    private static final Pattern RE_VIEWKEY = Pattern.compile("[?&]viewkey=([a-zA-Z0-9]+)");
    // Matches: pornhub.com/embed/XXXXX
    private static final Pattern RE_EMBED   = Pattern.compile("/embed/([a-zA-Z0-9]+)");

    private final HttpClient http;

    public PHMediaResolver() {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(12))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public static class MediaInfo {
        public final String streamUrl, title, author, artworkUrl;
        public final long durationMs;

        public MediaInfo(String streamUrl, String title, String author, long durationMs, String artworkUrl) {
            this.streamUrl = streamUrl;
            this.title     = title;
            this.author    = author;
            this.durationMs = durationMs;
            this.artworkUrl = artworkUrl;
        }
    }

    /**
     * Resolve a PornHub video URL to a playable audio stream.
     * Uses the public Webmasters API: api.pornhub.com/webmasters/video_by_id?id=VIEWKEY
     *
     * @param url Full PornHub video URL (view_video.php?viewkey=xxx or /embed/xxx)
     * @return MediaInfo or null if resolution failed
     */
    public MediaInfo resolve(String url) {
        String viewkey = extractViewkey(url);
        if (viewkey == null) {
            log.warn("[PH] Could not extract viewkey from URL: {}", url);
            return null;
        }
        log.info("[PH] Resolving viewkey: {}", viewkey);
        return queryApi(viewkey);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private String extractViewkey(String url) {
        Matcher m = RE_VIEWKEY.matcher(url);
        if (m.find()) return m.group(1);
        m = RE_EMBED.matcher(url);
        if (m.find()) return m.group(1);
        return null;
    }

    private MediaInfo queryApi(String viewkey) {
        try {
            // PornHub public Webmasters API — no auth token required
            String apiUrl = "https://api.pornhub.com/webmasters/video_by_id?id=" + viewkey;
            var req = HttpRequest.newBuilder(URI.create(apiUrl))
                    .header("User-Agent", UA)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 || resp.body() == null) {
                log.warn("[PH] API returned HTTP {}", resp.statusCode());
                return null;
            }

            JsonNode root = JSON.readTree(resp.body());
            // API response: { "code": 200, "video": { ... } }
            if (!root.path("code").asText("").equals("200")) {
                log.warn("[PH] API code: {} for viewkey: {}", root.path("code").asText(), viewkey);
                return null;
            }

            JsonNode video = root.path("video");
            if (video.isMissingNode()) return null;

            String title    = video.path("title").asText("PornHub Video");
            String author   = extractAuthor(video);
            long   durationMs = parseDurationMs(video.path("duration").asText("0"));
            String thumb    = extractThumbnail(video);

            // mediaDefinitions: array of {videoUrl, quality, format}
            String streamUrl = extractBestStream(video);
            if (streamUrl == null) {
                log.warn("[PH] No playable stream found for viewkey: {}", viewkey);
                return null;
            }

            log.info("[PH] Resolved: \"{}\" by \"{}\" ({}ms)", title, author, durationMs);
            return new MediaInfo(streamUrl, title, author, durationMs, thumb);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.error("[PH] Resolution error for viewkey {}: {}", viewkey, e.getMessage());
            return null;
        }
    }

    private String extractBestStream(JsonNode video) {
        JsonNode defs = video.path("mediaDefinitions");
        if (defs.isMissingNode() || !defs.isArray()) return null;

        // Prefer lower quality MP4 for faster streaming (480p or 360p)
        // mediaDefinitions: [{videoUrl: "...", quality: "480", format: "mp4"}, ...]
        String best = null;
        int    bestQ = 0;

        for (JsonNode def : defs) {
            String format  = def.path("format").asText("");
            String qualStr = def.path("quality").asText("0");
            String vUrl    = def.path("videoUrl").asText(null);

            if (!"mp4".equalsIgnoreCase(format) || vUrl == null || vUrl.isEmpty()) continue;

            int q = 0;
            try { q = Integer.parseInt(qualStr.replaceAll("[^0-9]", "")); } catch (NumberFormatException ignored) {}

            // Target 480p for audio — high enough quality, low bandwidth
            if (q == 480) return vUrl;
            if (q > bestQ) { bestQ = q; best = vUrl; }
        }
        return best;
    }

    private String extractAuthor(JsonNode video) {
        // Try pornstars first, then channels
        JsonNode pornstars = video.path("pornstars");
        if (pornstars.isArray() && pornstars.size() > 0) {
            return pornstars.get(0).path("pornstar_name").asText("Unknown");
        }
        JsonNode channels = video.path("channels");
        if (channels.isArray() && channels.size() > 0) {
            return channels.get(0).path("name").asText("Unknown");
        }
        return video.path("author").asText("PornHub");
    }

    private long parseDurationMs(String raw) {
        // Format: "MM:SS" or "HH:MM:SS" or plain seconds
        try {
            String[] parts = raw.trim().split(":");
            if (parts.length == 1) return Long.parseLong(parts[0].trim()) * 1000L;
            if (parts.length == 2) return (Long.parseLong(parts[0]) * 60 + Long.parseLong(parts[1])) * 1000L;
            if (parts.length == 3) return (Long.parseLong(parts[0]) * 3600 + Long.parseLong(parts[1]) * 60 + Long.parseLong(parts[2])) * 1000L;
        } catch (NumberFormatException ignored) {}
        return 0L;
    }

    private String extractThumbnail(JsonNode video) {
        JsonNode thumbs = video.path("thumbs");
        if (thumbs.isArray() && thumbs.size() > 0) {
            return thumbs.get(0).path("src").asText("");
        }
        return video.path("default_thumb").asText("");
    }
}
