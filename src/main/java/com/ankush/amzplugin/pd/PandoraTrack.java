package com.ankush.amzplugin.pd;

import com.ankush.amzplugin.mirror.MirroringAudioSourceManager;
import com.ankush.amzplugin.mirror.MirroringAudioTrack;
import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAudioTrack;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;

/** Pandora mirrored track — resolves via ISRC or query fallback. */
public class PandoraTrack extends MirroringAudioTrack {

    public PandoraTrack(AudioTrackInfo info, MirroringAudioSourceManager src) {
        this(info, null, null, null, null, null, false, src);
    }

    public PandoraTrack(AudioTrackInfo info, String albumName, String albumUrl, String artistUrl,
                        String artistArt, String preview, boolean isPreview, MirroringAudioSourceManager src) {
        super(info, albumName, albumUrl, artistUrl, artistArt, preview, isPreview, src);
    }

    @Override
    protected InternalAudioTrack createAudioTrack(AudioTrackInfo info, SeekableInputStream stream) {
        return new MpegAudioTrack(info, stream);
    }

    @Override
    protected AudioTrack makeShallowClone() { return new PandoraTrack(trackInfo, (MirroringAudioSourceManager) sourceManager); }
}
