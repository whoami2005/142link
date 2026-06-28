package com.krishna.amzplugin.ph;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lavalink AudioSourceManager for PornHub videos.
 * Registers as "pornhub" source — handles view_video.php and /embed/ URLs.
 */
public class PHSourceManager implements AudioSourceManager {

    private static final Logger log = LoggerFactory.getLogger(PHSourceManager.class);

    // Matches standard and embed PH URLs
    private static final Pattern RE_VIDEO = Pattern.compile(
        "https?://(?:www\\.)?pornhub\\.com/view_video\\.php[?&]viewkey=[a-zA-Z0-9]+"
    );
    private static final Pattern RE_EMBED = Pattern.compile(
        "https?://(?:www\\.)?pornhub\\.com/embed/([a-zA-Z0-9]+)"
    );

    private final PHMediaResolver resolver;
    private final HttpInterfaceManager httpManager;

    public PHSourceManager() {
        this.resolver    = new PHMediaResolver();
        this.httpManager = HttpClientTools.createDefaultThreadLocalManager();
    }

    @Override
    public String getSourceName() {
        return "pornhub";
    }

    public PHMediaResolver getResolver() {
        return resolver;
    }

    public HttpInterface getHttpInterface() {
        return httpManager.getInterface();
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference ref) {
        String id = ref.identifier;
        if (!RE_VIDEO.matcher(id).find() && !RE_EMBED.matcher(id).find()) return null;

        try {
            log.info("[PH] Loading: {}", id);
            PHMediaResolver.MediaInfo info = resolver.resolve(id);
            if (info == null || info.streamUrl == null) return AudioReference.NO_TRACK;

            // Extract viewkey for identifier
            Matcher m = Pattern.compile("[?&]viewkey=([a-zA-Z0-9]+)|/embed/([a-zA-Z0-9]+)").matcher(id);
            String identifier = m.find()
                ? (m.group(1) != null ? m.group(1) : m.group(2))
                : id;

            AudioTrackInfo trackInfo = new AudioTrackInfo(
                info.title,
                info.author,
                info.durationMs,
                identifier,
                false,
                id,
                info.artworkUrl,
                null
            );
            return new PHTrack(trackInfo, info.streamUrl, this);

        } catch (Exception e) {
            throw new FriendlyException(
                "PornHub load failed: " + e.getMessage(),
                FriendlyException.Severity.SUSPICIOUS, e
            );
        }
    }

    @Override public boolean isTrackEncodable(AudioTrack t) { return true; }
    @Override public void encodeTrack(AudioTrack t, DataOutput out) {}
    @Override
    public AudioTrack decodeTrack(AudioTrackInfo info, DataInput in) {
        return new PHTrack(info, null, this);
    }

    @Override
    public void shutdown() {
        try { httpManager.close(); }
        catch (IOException e) { log.error("[PH] Failed closing http manager", e); }
    }
}
