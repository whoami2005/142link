package com.krishna.amzplugin.pd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.cookie.BasicClientCookie;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/**
 * Unified Pandora API client — handles authentication + all REST calls.
 */
public class PandoraClient {

    private static final Logger log = LoggerFactory.getLogger(PandoraClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASE = "https://www.pandora.com";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private final Supplier<HttpInterface> httpSupplier;
    private final String externalTokenUrl;
    private final boolean tryExternalFirst;
    private final HttpClient javaHttp = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private volatile String csrf;
    private volatile String auth;
    private volatile Instant validUntil;

    public PandoraClient(Supplier<HttpInterface> httpSupplier, String externalTokenUrl, String initialCsrf, boolean tryExternalFirst) {
        this.httpSupplier = httpSupplier;
        this.externalTokenUrl = externalTokenUrl;
        this.tryExternalFirst = tryExternalFirst;
        if (initialCsrf != null && !initialCsrf.isEmpty()) this.csrf = initialCsrf;
        for (int i = 1; i <= 3; i++) {
            try { authenticate(); break; }
            catch (Exception e) { if (i < 3) try { Thread.sleep(i * 1000L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } }
        }
    }

    // ── auth ────────────────────────────────────────────────────────────────

    private synchronized void authenticate() throws IOException {
        if (tryExternalFirst) {
            try { fetchExternalToken(); return; } catch (Exception e) { log.warn("External token failed ({}), trying anon login", e.getMessage()); }
            anonLogin();
        } else {
            try { anonLogin(); return; } catch (Exception e) { log.warn("Anon login failed ({}), trying external token", e.getMessage()); }
            try { fetchExternalToken(); } catch (Exception e) { throw new IOException("All Pandora auth methods exhausted", e); }
        }
    }

    private void fetchExternalToken() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(externalTokenUrl)).header("User-Agent", UA).timeout(Duration.ofSeconds(15)).GET().build();
        HttpResponse<String> resp = javaHttp.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new IOException("Token endpoint HTTP " + resp.statusCode());
        JsonNode j = JSON.readTree(resp.body());
        if (!j.path("success").asBoolean(false)) throw new IOException("Token endpoint returned failure");
        String c = j.path("csrfToken").asText(null);
        String a = j.path("authToken").asText(null);
        if (c == null || a == null || c.isEmpty() || a.isEmpty()) throw new IOException("Empty tokens from endpoint");
        this.csrf = c;
        this.auth = a;
        this.validUntil = Instant.now().plusSeconds(Math.max(j.path("expires_in_seconds").asLong(300) - 30, 30));
        log.debug("Pandora tokens via external API OK");
    }

    private void anonLogin() throws IOException {
        if (csrf == null || csrf.isEmpty()) csrf = Long.toHexString(System.currentTimeMillis());
        HttpInterface h = httpSupplier.get();
        applyCookies(h, csrf);
        HttpPost post = new HttpPost(BASE + "/api/v1/auth/anonymousLogin");
        post.setHeader("Accept", "application/json");
        post.setHeader("Content-Type", "application/json");
        post.setHeader("X-CsrfToken", csrf);
        post.setHeader("origin", BASE);
        post.setHeader("User-Agent", UA);
        post.setEntity(new StringEntity("", StandardCharsets.UTF_8));
        try (org.apache.http.client.methods.CloseableHttpResponse resp = h.execute(post)) {
            String body = new String(resp.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode j = JSON.readTree(body);
            if (j.has("errorCode") && !j.get("errorCode").isNull())
                throw new IOException("Anon login error: " + j.path("errorCode").asLong() + " " + j.path("errorString").asText(""));
            String a = j.path("authToken").asText(null);
            if (a == null || a.isEmpty()) throw new IOException("No auth token from anon login");
            this.auth = a;
            this.validUntil = Instant.now().plusSeconds(86400);
            log.debug("Pandora anon login OK");
        }
    }

    private synchronized void ensureAuth() throws IOException {
        if (csrf == null || auth == null || validUntil == null || validUntil.isBefore(Instant.now())) authenticate();
    }

    public synchronized void invalidate() { csrf = null; auth = null; validUntil = null; }

    // ── API calls ───────────────────────────────────────────────────────────

    public JsonNode apiCall(String endpoint, String jsonBody) throws IOException {
        return apiCallInternal(endpoint, jsonBody, false);
    }

    private JsonNode apiCallInternal(String endpoint, String jsonBody, boolean retry) throws IOException {
        ensureAuth();
        HttpInterface h = httpSupplier.get();
        applyCookies(h, csrf);
        HttpPost post = new HttpPost(BASE + endpoint);
        post.setHeader("Accept", "application/json, text/plain, */*");
        post.setHeader("Content-Type", "application/json");
        post.setHeader("origin", BASE);
        post.setHeader("sec-fetch-mode", "cors");
        post.setHeader("sec-fetch-site", "same-origin");
        post.setHeader("X-Csrftoken", csrf);
        post.setHeader("X-Authtoken", auth);
        post.setHeader("User-Agent", UA);
        post.setEntity(new StringEntity(jsonBody, StandardCharsets.UTF_8));
        try (org.apache.http.client.methods.CloseableHttpResponse resp = h.execute(post)) {
            String raw = new String(resp.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode j = JSON.readTree(raw);
            if (!retry && j.has("errorCode") && !j.get("errorCode").isNull()) {
                long code = j.path("errorCode").asLong(-1);
                String msg = j.path("errorString").asText("");
                if (code == 1001 || (code == 0 && msg.contains("could not be validated"))) {
                    log.debug("Pandora auth error {}, refreshing", code);
                    invalidate();
                    return apiCallInternal(endpoint, jsonBody, true);
                }
            }
            return j;
        }
    }

    // ── convenience methods ─────────────────────────────────────────────────

    public JsonNode search(String query) throws IOException {
        ObjectNode req = JSON.createObjectNode();
        req.put("query", query);
        ArrayNode t = req.putArray("types"); t.add("TR"); t.add("AL"); t.add("AR"); t.add("PL");
        req.putNull("listener"); req.put("start", 0); req.put("count", 100);
        req.put("annotate", true); req.put("annotationRecipe", "CLASS_OF_2019");
        return apiCall("/api/v3/sod/search", JSON.writeValueAsString(req));
    }

    public JsonNode details(String pandoraId) throws IOException {
        ObjectNode req = JSON.createObjectNode();
        req.put("pandoraId", pandoraId);
        return apiCall("/api/v4/catalog/getDetails", JSON.writeValueAsString(req));
    }

    public JsonNode annotate(List<String> ids) throws IOException {
        ObjectNode req = JSON.createObjectNode();
        ArrayNode arr = req.putArray("pandoraIds");
        for (String id : ids) arr.add(id);
        return apiCall("/api/v4/catalog/annotateObjects", JSON.writeValueAsString(req));
    }

    public JsonNode playlistTracks(String plId) throws IOException {
        ObjectNode w = JSON.createObjectNode();
        ObjectNode r = JSON.createObjectNode();
        r.put("pandoraId", plId); r.put("playlistVersion", 0); r.put("offset", 0);
        r.put("limit", 5000); r.put("annotationLimit", 100);
        r.putArray("allowedTypes").add("TR"); r.put("bypassPrivacyRules", true);
        w.set("request", r);
        return apiCall("/api/v7/playlists/getTracks", JSON.writeValueAsString(w));
    }

    public JsonNode artistAllTracks(String artistId) throws IOException {
        ObjectNode req = JSON.createObjectNode();
        req.put("artistPandoraId", artistId); req.put("annotationLimit", 100);
        return apiCall("/api/v4/catalog/getAllArtistTracksWithCollaborations", JSON.writeValueAsString(req));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    static String artworkFor(JsonNode node) {
        if (node == null || node.isNull()) return null;
        JsonNode icon = node.path("icon");
        String artId = icon.path("artId").asText(null);
        if (artId != null && !artId.isEmpty()) return "https://content-images.p-cdn.com/" + artId + "_1080W_1080H.jpg";
        String layers = node.path("thorLayers").asText(null);
        if (layers != null && !layers.isEmpty()) {
            if (layers.startsWith("_;grid")) return "https://dyn-images.p-cdn.com/?l=" + URLEncoder.encode(layers, StandardCharsets.UTF_8) + "&w=1080&h=1080";
            return "https://content-images.p-cdn.com/" + layers + "_1080W_1080H.jpg";
        }
        return null;
    }

    private static void applyCookies(HttpInterface h, String csrfToken) {
        BasicCookieStore store = new BasicCookieStore();
        h.getContext().setCookieStore(store);
        RequestConfig existing = h.getContext().getRequestConfig();
        h.getContext().setRequestConfig(RequestConfig.copy(existing != null ? existing : RequestConfig.DEFAULT)
                .setCookieSpec(CookieSpecs.STANDARD).build());
        BasicClientCookie c = new BasicClientCookie("csrftoken", csrfToken);
        c.setPath("/"); c.setSecure(true); c.setDomain("pandora.com"); c.setAttribute("domain", ".pandora.com");
        store.addCookie(c);
    }
}
