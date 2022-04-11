package com.example.simplemusicapp;

import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat.MediaItem;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media.MediaBrowserServiceCompat;
import java.util.List;

public class SimpleMusicService extends MediaBrowserServiceCompat {
  // private static final String MY_MEDIA_ROOT_ID = "media_root_id";
  // private static final String MY_EMPTY_MEDIA_ROOT_ID = "empty_root_id";

  private MediaSessionCompat mediaSession;
  private PlaybackStateCompat.Builder playbackStateBuilder;

  @Override
  public void onCreate() {
    super.onCreate();

    // Create a MediaSessionCompat
    mediaSession = new MediaSessionCompat(this, SimpleMusicService.class.getSimpleName());

    // Set an initial PlaybackState with ACTION_PLAY, so media buttons can start the player
    playbackStateBuilder = new PlaybackStateCompat.Builder()
        .setActions(
            PlaybackStateCompat.ACTION_PLAY |
                PlaybackStateCompat.ACTION_PLAY_PAUSE);
    mediaSession.setPlaybackState(playbackStateBuilder.build());

    // MySessionCallback() has methods that handle callbacks from a media controller
    mediaSession.setCallback(new MySessionCallback());

    // Set the session's token so that client activities can communicate with it.
    setSessionToken(mediaSession.getSessionToken());
  }

  @Nullable
  @Override
  public BrowserRoot onGetRoot(
      @NonNull String clientPackageName, int clientUid, @Nullable Bundle rootHints) {
    return null;
  }

  @Override
  public void onLoadChildren(@NonNull String parentId, @NonNull Result<List<MediaItem>> result) {}
}
