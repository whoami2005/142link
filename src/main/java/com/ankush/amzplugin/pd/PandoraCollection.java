package com.ankush.amzplugin.pd;

import com.ankush.amzplugin.ExtendedAudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.util.List;

/** Pandora playlist/album/artist collection wrapper. */
public class PandoraCollection extends ExtendedAudioPlaylist {
    public PandoraCollection(String name, List<AudioTrack> tracks, Type type, String url, String artwork, String author, int total) {
        super(name, tracks, type, url, artwork, author, total);
    }
}
