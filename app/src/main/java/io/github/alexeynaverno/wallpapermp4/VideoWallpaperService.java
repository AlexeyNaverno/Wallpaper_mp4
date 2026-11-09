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

/** Plays the selected MP4 as a silent forward/reverse live wallpaper. */
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
                // The system asks onComputeColors() for the new values after this notification.
                notifyColorsChanged();
                stopReversePlayback();
                releasePlayer();
                waitingForForwardRestart = false;
                if (surfaceReady && wallpaperVisible) {
                    startPlayer();
                }
            }
        };

        private SurfaceHolder surfaceHolder;
        private MediaPlayer mediaPlayer;
        private ReverseFramePlayer reversePlayback;
        private boolean surfaceReady;
        private boolean wallpaperVisible;
        private boolean playerPrepared;
        private boolean receiverRegistered;
        private boolean waitingForForwardRestart;
        private boolean reversePhase;

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

            // Also refresh at initial activation; a palette may have been saved before this engine
            // was created by the system wallpaper preview.
            notifyColorsChanged();
        }

        @Override
        public WallpaperColors onComputeColors() {
            // This is the public live-wallpaper color API consumed by SystemUI / Monet.
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
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            super.onSurfaceDestroyed(holder);
            surfaceReady = false;
            surfaceHolder = null;
            waitingForForwardRestart = false;
            stopReversePlayback();
            releasePlayer();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            wallpaperVisible = visible;
            if (reversePlayback != null) {
                reversePlayback.setVisible(visible);
            }

            if (!visible) {
                pausePlayer();
            } else if (surfaceReady) {
                if (reversePhase) {
                    // It may be decoding, or its completion callback may be queued on the main
                    // thread. Let that callback reattach the surface before forward playback.
                    return;
                }
                if (waitingForForwardRestart) {
                    continueForwardAfterReverse();
                } else if (mediaPlayer == null) {
                    startPlayer();
                } else {
                    resumePlayer();
                }
            }
        }

        @Override
        public void onDestroy() {
            wallpaperVisible = false;
            stopReversePlayback();
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
            if (!surfaceReady || !wallpaperVisible || surfaceHolder == null
                    || !surfaceHolder.getSurface().isValid() || mediaPlayer != null) {
                return;
            }

            Uri videoUri = WallpaperPaletteStore.getVideoUri(VideoWallpaperService.this);
            if (videoUri == null) {
                return;
            }

            waitingForForwardRestart = false;
            MediaPlayer player = new MediaPlayer();
            mediaPlayer = player;
            playerPrepared = false;
            player.setOnPreparedListener(preparedPlayer -> {
                if (mediaPlayer != preparedPlayer || !surfaceReady) {
                    preparedPlayer.release();
                    return;
                }
                playerPrepared = true;
                createReversePlayback(videoUri, preparedPlayer);
                if (wallpaperVisible) {
                    try {
                        preparedPlayer.start();
                    } catch (IllegalStateException exception) {
                        Log.w(TAG, "Could not start the selected wallpaper video.", exception);
                        releasePlayer();
                    }
                }
            });
            player.setOnCompletionListener(completedPlayer -> {
                if (mediaPlayer == completedPlayer) {
                    beginReversePlayback(completedPlayer);
                }
            });
            player.setOnErrorListener((failedPlayer, what, extra) -> {
                Log.w(TAG, "The device could not play the selected MP4 (" + what + ", " + extra + ").");
                if (mediaPlayer == failedPlayer) {
                    stopReversePlayback();
                    releasePlayer();
                }
                return true;
            });

            try {
                player.setDataSource(VideoWallpaperService.this, videoUri);
                player.setSurface(surfaceHolder.getSurface());
                player.setLooping(false);
                player.setVolume(0f, 0f);
                player.setVideoScalingMode(
                        MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
                player.prepareAsync();
            } catch (IOException | RuntimeException exception) {
                Log.w(TAG, "Could not prepare the selected wallpaper video.", exception);
                stopReversePlayback();
                releasePlayer();
            }
        }

        private void createReversePlayback(Uri videoUri, MediaPlayer player) {
            stopReversePlayback();
            reversePlayback = new ReverseFramePlayer(
                    VideoWallpaperService.this,
                    videoUri,
                    surfaceHolder,
                    player.getDuration(),
                    new ReverseFramePlayer.Listener() {
                        @Override
                        public void onReverseFinished(ReverseFramePlayer finishedPlayback) {
                            onReversePlaybackFinished(finishedPlayback);
                        }

                        @Override
                        public void onReverseFailed(
                                ReverseFramePlayer failedPlayback, Exception error) {
                            Log.w(TAG, "Ping-pong playback is unavailable for this video.", error);
                            if (reversePhase) {
                                onReversePlaybackFinished(failedPlayback);
                            } else if (reversePlayback == failedPlayback) {
                                reversePlayback = null;
                            }
                        }
                    });
            reversePlayback.setVisible(wallpaperVisible);
        }

        private void beginReversePlayback(MediaPlayer completedPlayer) {
            if (reversePlayback == null || surfaceHolder == null || !surfaceReady) {
                restartForwardFromCompletion(completedPlayer);
                return;
            }

            try {
                // Detach the codec from the wallpaper surface so the reverse-frame renderer can
                // draw to it with Canvas. It is reattached when the reverse segment is complete.
                completedPlayer.setSurface(null);
                reversePhase = true;
                reversePlayback.setVisible(wallpaperVisible);
                reversePlayback.start();
            } catch (RuntimeException exception) {
                Log.w(TAG, "Could not switch to reverse-frame rendering.", exception);
                reversePhase = false;
                stopReversePlayback();
                restartForwardFromCompletion(completedPlayer);
            }
        }

        private void onReversePlaybackFinished(ReverseFramePlayer finishedPlayback) {
            if (reversePlayback != finishedPlayback) {
                return;
            }
            reversePlayback = null;
            reversePhase = false;
            if (!wallpaperVisible || !surfaceReady) {
                waitingForForwardRestart = true;
                return;
            }
            continueForwardAfterReverse();
        }

        private void continueForwardAfterReverse() {
            MediaPlayer player = mediaPlayer;
            SurfaceHolder holder = surfaceHolder;
            if (!wallpaperVisible || !surfaceReady || holder == null
                    || !holder.getSurface().isValid()) {
                waitingForForwardRestart = true;
                return;
            }
            if (player == null || !playerPrepared) {
                waitingForForwardRestart = false;
                startPlayer();
                return;
            }

            try {
                player.setSurface(holder.getSurface());
                waitingForForwardRestart = false;
                // MediaPlayer restarts from the beginning when start() is called after completion.
                player.start();
            } catch (RuntimeException exception) {
                Log.w(TAG, "Could not resume forward playback after the reverse segment.", exception);
                waitingForForwardRestart = false;
                releasePlayer();
                startPlayer();
            }
        }

        private void restartForwardFromCompletion(MediaPlayer completedPlayer) {
            if (completedPlayer != mediaPlayer) {
                return;
            }
            if (!wallpaperVisible || !surfaceReady) {
                waitingForForwardRestart = true;
                return;
            }
            continueForwardAfterReverse();
        }

        private void stopReversePlayback() {
            ReverseFramePlayer current = reversePlayback;
            reversePlayback = null;
            reversePhase = false;
            if (current != null) {
                current.stop();
            }
        }

        private void pausePlayer() {
            if (mediaPlayer != null && playerPrepared && mediaPlayer.isPlaying()) {
                try {
                    mediaPlayer.pause();
                } catch (IllegalStateException exception) {
                    Log.w(TAG, "Could not pause the wallpaper video.", exception);
                    releasePlayer();
                }
            }
        }

        private void resumePlayer() {
            if (mediaPlayer != null && playerPrepared && !mediaPlayer.isPlaying()) {
                try {
                    mediaPlayer.start();
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
