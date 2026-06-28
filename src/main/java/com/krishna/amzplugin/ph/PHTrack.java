package com.krishna.amzplugin.ph;

import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAudioTrack;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

/**
 * Playable PornHub audio track.
 * Re-resolves CDN URL on decode since MP4 direct links may expire.
 */
public class PHTrack extends DelegatedAudioTrack {

    private static final Logger log = LoggerFactory.getLogger(PHTrack.class);

    private final PHSourceManager mgr;
    private final String cdnUrl;

    public PHTrack(AudioTrackInfo info, String cdnUrl, PHSourceManager mgr) {
        super(info);
        this.cdnUrl = cdnUrl;
        this.mgr    = mgr;
    }

    @Override
    public void process(LocalAudioTrackExecutor executor) throws Exception {
        String url = cdnUrl;
        if (url == null || url.isEmpty()) url = reresolve();
        if (url == null || url.isEmpty())
            throw new FriendlyException(
                "PH stream unavailable for: " + trackInfo.title,
                FriendlyException.Severity.COMMON, null
            );

        log.debug("[PH] Streaming: {}", trackInfo.title);
        try (HttpInterface h = mgr.getHttpInterface();
             PersistentHttpStream stream = new PersistentHttpStream(h, new URI(url), null)) {
            processDelegate(new MpegAudioTrack(trackInfo, stream), executor);
        }
    }

    private String reresolve() {
        try {
            String uri = trackInfo.uri;
            if (uri == null) return null;
            PHMediaResolver.MediaInfo info = mgr.getResolver().resolve(uri);
            return info != null ? info.streamUrl : null;
        } catch (Exception e) {
            log.debug("[PH] Re-resolve failed: {}", e.getMessage());
            return null;
        }
    }

    @Override protected AudioTrack makeShallowClone() { return new PHTrack(trackInfo, cdnUrl, mgr); }
    @Override public AudioSourceManager getSourceManager() { return mgr; }
}
