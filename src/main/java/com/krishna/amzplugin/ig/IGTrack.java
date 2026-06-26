package com.krishna.amzplugin.ig;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Playable Instagram track — streams MP4 audio from CDN.
 * Re-resolves the stream URL on decode since CDN links expire.
 */
public class IGTrack extends DelegatedAudioTrack {

    private static final Logger log = LoggerFactory.getLogger(IGTrack.class);

    private static final Pattern P_AUDIO = Pattern.compile("instagram\\.com/reels/audio/(\\d+)");
    private static final Pattern P_REEL  = Pattern.compile("instagram\\.com/(?:reels?|reel)/([\\w-]+)");
    private static final Pattern P_POST  = Pattern.compile("instagram\\.com/p/([\\w-]+)");

    private final IGSourceManager mgr;
    private final String cdnUrl;

    public IGTrack(AudioTrackInfo info, String cdnUrl, IGSourceManager mgr) {
        super(info);
        this.cdnUrl = cdnUrl;
        this.mgr = mgr;
    }

    @Override
    public void process(LocalAudioTrackExecutor executor) throws Exception {
        String url = cdnUrl;
        if (url == null || url.isEmpty()) url = reresolve();
        if (url == null || url.isEmpty())
            throw new FriendlyException("Stream unavailable: " + trackInfo.title, FriendlyException.Severity.COMMON, null);
        try (HttpInterface h = mgr.getHttpInterface();
             PersistentHttpStream stream = new PersistentHttpStream(h, new URI(url), null)) {
            processDelegate(new MpegAudioTrack(trackInfo, stream), executor);
        }
    }

    private String reresolve() {
        try {
            String uri = trackInfo.uri;
            if (uri == null) return null;
            IGMediaResolver r = mgr.getResolver();
            Matcher m;
            if ((m = P_AUDIO.matcher(uri)).find()) {
                var info = r.resolveAudioClip(m.group(1));
                return info != null ? info.streamUrl : null;
            }
            if ((m = P_REEL.matcher(uri)).find()) {
                var info = r.resolveByShortcode(m.group(1), "reel");
                return info != null ? info.streamUrl : null;
            }
            if ((m = P_POST.matcher(uri)).find()) {
                var info = r.resolveByShortcode(m.group(1), "p");
                return info != null ? info.streamUrl : null;
            }
            return null;
        } catch (Exception e) { log.debug("IG re-resolve failed: {}", e.getMessage()); return null; }
    }

    @Override protected AudioTrack makeShallowClone() { return new IGTrack(trackInfo, cdnUrl, mgr); }
    @Override public AudioSourceManager getSourceManager() { return mgr; }
}
