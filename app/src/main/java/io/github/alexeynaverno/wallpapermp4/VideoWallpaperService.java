package io.github.alexeynaverno.wallpapermp4;

import android.app.WallpaperColors;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.SurfaceHolder;

import java.io.IOException;

/** Plays a previously generated, silent ping-pong MP4 as a live wallpaper. */
public final class VideoWallpaperService extends WallpaperService {
    static final String ACTION_VIDEO_OR_PALETTE_CHANGED =
            "io.github.alexeynaverno.wallpapermp4.action.VIDEO_OR_PALETTE_CHANGED";
    private static final String TAG = "VideoWallpaper";

    @Override
    public Engine onCreateEngine() {
        return new VideoEngine();
    }

    private final class VideoEngine extends Engine {
        private final BroadcastReceiver updateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!ACTION_VIDEO_OR_PALETTE_CHANGED.equals(intent.getAction())) {
                    return;
                }
                notifyColorsChanged();
                releasePlayer();
                if (surfaceReady && wallpaperVisible) {
                    startPlayer();
                }
            }
        };

        private SurfaceHolder surfaceHolder;
        private MediaPlayer mediaPlayer;
        private boolean surfaceReady;
        private boolean wallpaperVisible;
        private boolean playerPrepared;
        private boolean receiverRegistered;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            surfaceHolder = holder;
            wallpaperVisible = isVisible();

            IntentFilter filter = new IntentFilter(ACTION_VIDEO_OR_PALETTE_CHANGED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                VideoWallpaperService.this.registerReceiver(
                        updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                VideoWallpaperService.this.registerReceiver(updateReceiver, filter);
            }
            receiverRegistered = true;
            notifyColorsChanged();
        }

        @Override
        public WallpaperColors onComputeColors() {
            return WallpaperPaletteStore.getWallpaperColors(VideoWallpaperService.this);
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            surfaceHolder = holder;
            surfaceReady = true;
            wallpaperVisible = isVisible();
            if (wallpaperVisible) {
                startPlayer();
            }
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            surfaceHolder = holder;
            if (mediaPlayer != null && playerPrepared && holder.getSurface().isValid()) {
                try {
                    mediaPlayer.setSurface(holder.getSurface());
                } catch (RuntimeException exception) {
                    Log.w(TAG, "Could not update the wallpaper video surface.", exception);
                    releasePlayer();
                    if (wallpaperVisible) {
                        startPlayer();
                    }
                }
            }
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            super.onSurfaceDestroyed(holder);
            surfaceReady = false;
            surfaceHolder = null;
            releasePlayer();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            wallpaperVisible = visible;
            if (!visible) {
                pausePlayer();
            } else if (surfaceReady) {
                if (mediaPlayer == null) {
                    startPlayer();
                } else {
                    resumePlayer();
                }
            }
        }

        @Override
        public void onDestroy() {
            wallpaperVisible = false;
            releasePlayer();
            if (receiverRegistered) {
                try {
                    VideoWallpaperService.this.unregisterReceiver(updateReceiver);
                } catch (IllegalArgumentException ignored) {
                    // The receiver may already have been removed during service teardown.
                }
                receiverRegistered = false;
            }
            surfaceHolder = null;
            super.onDestroy();
        }

        private void startPlayer() {
            SurfaceHolder holder = surfaceHolder;
            if (!surfaceReady || !wallpaperVisible || holder == null
                    || !holder.getSurface().isValid() || mediaPlayer != null) {
                return;
            }

            Uri videoUri = WallpaperPaletteStore.getVideoUri(VideoWallpaperService.this);
            if (videoUri == null) {
                return;
            }

            MediaPlayer player = new MediaPlayer();
            mediaPlayer = player;
            playerPrepared = false;
            player.setOnPreparedListener(preparedPlayer -> {
                if (mediaPlayer != preparedPlayer || !surfaceReady) {
                    preparedPlayer.release();
                    return;
                }
                playerPrepared = true;
                if (wallpaperVisible) {
                    try {
                        preparedPlayer.start();
                    } catch (IllegalStateException exception) {
                        Log.w(TAG, "Could not start the selected wallpaper video.", exception);
                        releasePlayer();
                    }
                }
            });
            player.setOnErrorListener((failedPlayer, what, extra) -> {
                Log.w(TAG, "The device could not play the selected MP4 (" + what + ", " + extra + ").");
                if (mediaPlayer == failedPlayer) {
                    releasePlayer();
                }
                return true;
            });

            try {
                player.setDataSource(VideoWallpaperService.this, videoUri);
                player.setSurface(holder.getSurface());
                player.setLooping(true);
                player.setVolume(0f, 0f);
                player.setVideoScalingMode(
                        MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
                player.prepareAsync();
            } catch (IOException | RuntimeException exception) {
                Log.w(TAG, "Could not prepare the selected wallpaper MP4.", exception);
                releasePlayer();
            }
        }

        private void pausePlayer() {
            MediaPlayer player = mediaPlayer;
            if (player != null && playerPrepared && player.isPlaying()) {
                try {
                    player.pause();
                } catch (IllegalStateException exception) {
                    Log.w(TAG, "Could not pause the wallpaper video.", exception);
                    releasePlayer();
                }
            }
        }

        private void resumePlayer() {
            MediaPlayer player = mediaPlayer;
            if (player != null && playerPrepared && !player.isPlaying()) {
                try {
                    player.start();
                } catch (IllegalStateException exception) {
                    Log.w(TAG, "Could not resume the wallpaper video.", exception);
                    releasePlayer();
                    startPlayer();
                }
            }
        }

        private void releasePlayer() {
            MediaPlayer player = mediaPlayer;
            mediaPlayer = null;
            playerPrepared = false;
            if (player != null) {
                player.release();
            }
        }
    }
}
