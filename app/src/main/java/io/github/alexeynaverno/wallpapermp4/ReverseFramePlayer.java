package io.github.alexeynaverno.wallpapermp4;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceHolder;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Decodes and draws the interior video frames in reverse order, one frame at a time. */
final class ReverseFramePlayer {
    interface Listener {
        void onReverseFinished(ReverseFramePlayer player);

        void onReverseFailed(ReverseFramePlayer player, Exception error);
    }

    private static final String TAG = "WallpaperReverse";
    private static final long DEFAULT_FRAME_INTERVAL_US = 33_333L;
    private static final long MAX_FRAME_RATE_INTERVAL_US = 33_333L;
    private static final int MAX_DECODE_EDGE_PX = 1080;

    private final Context context;
    private final Uri videoUri;
    private final SurfaceHolder surfaceHolder;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final HandlerThread workerThread;
    private final Handler worker;
    private final AtomicBoolean frameScheduled = new AtomicBoolean(false);
    private final Paint framePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final RectF destination = new RectF();
    private final Runnable frameRunnable = () -> {
        frameScheduled.set(false);
        renderNextFrame();
    };

    private volatile boolean visible = true;
    private volatile boolean active;
    private volatile boolean startRequested;
    private volatile boolean stopped;
    private volatile boolean ready;
    private volatile long durationUs;
    private volatile long frameIntervalUs = DEFAULT_FRAME_INTERVAL_US;

    // Accessed only by the worker thread after construction.
    private MediaMetadataRetriever retriever;
    private long nextFrameTimestampUs = -1L;
    private int consecutiveMissingFrames;

    ReverseFramePlayer(
            Context context,
            Uri videoUri,
            SurfaceHolder surfaceHolder,
            long fallbackDurationMs,
            Listener listener) {
        this.context = context.getApplicationContext();
        this.videoUri = videoUri;
        this.surfaceHolder = surfaceHolder;
        this.listener = listener;
        this.durationUs = fallbackDurationMs > 0 ? fallbackDurationMs * 1_000L : 0L;

        workerThread = new HandlerThread("WallpaperReverseFrames");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        worker.post(this::prepareRetriever);
    }

    void start() {
        if (stopped) {
            return;
        }
        startRequested = true;
        active = true;
        worker.post(this::scheduleCurrentFrame);
    }

    void setVisible(boolean isVisible) {
        visible = isVisible;
        if (!isVisible) {
            worker.removeCallbacks(frameRunnable);
            frameScheduled.set(false);
        } else if (active && startRequested) {
            worker.post(this::scheduleCurrentFrame);
        }
    }

    boolean isActive() {
        return active;
    }

    void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        active = false;
        startRequested = false;
        worker.removeCallbacks(frameRunnable);
        frameScheduled.set(false);
        worker.post(() -> {
            releaseRetriever();
            workerThread.quitSafely();
        });
    }

    private void prepareRetriever() {
        MediaMetadataRetriever candidate = new MediaMetadataRetriever();
        try {
            candidate.setDataSource(context, videoUri);
            long metadataDurationUs = readDurationUs(candidate);
            if (metadataDurationUs > 0) {
                durationUs = metadataDurationUs;
            }
            frameIntervalUs = readFrameIntervalUs(candidate, durationUs);

            if (stopped) {
                candidate.release();
                return;
            }
            retriever = candidate;
            ready = true;
            scheduleCurrentFrame();
        } catch (IOException | RuntimeException exception) {
            try {
                candidate.release();
            } catch (IOException | RuntimeException ignored) {
                // Preserve the decoding error for the listener.
            }
            fail(exception);
        }
    }

    private void scheduleCurrentFrame() {
        if (stopped || !active || !startRequested || !visible || !ready) {
            return;
        }
        if (nextFrameTimestampUs < 0L) {
            // Forward playback has already shown the final frame. Start at the frame before it,
            // and stop before frame zero, so neither endpoint is repeated at a turn-around.
            nextFrameTimestampUs = durationUs - (2L * frameIntervalUs);
        }
        if (nextFrameTimestampUs < frameIntervalUs) {
            finish();
            return;
        }
        scheduleFrame(0L);
    }

    private void scheduleFrame(long delayMs) {
        if (!stopped && active && visible && frameScheduled.compareAndSet(false, true)) {
            worker.postDelayed(frameRunnable, delayMs);
        }
    }

    private void renderNextFrame() {
        if (stopped || !active || !visible || !ready) {
            return;
        }
        if (nextFrameTimestampUs < frameIntervalUs) {
            finish();
            return;
        }

        long frameStartedAt = SystemClock.uptimeMillis();
        Bitmap frame = null;
        try {
            int[] dimensions = getDecodeDimensions();
            frame = retriever.getScaledFrameAtTime(
                    nextFrameTimestampUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    dimensions[0],
                    dimensions[1]);
            if (frame == null) {
                consecutiveMissingFrames++;
                if (consecutiveMissingFrames >= 3) {
                    fail(new IllegalStateException("The video decoder returned no reverse frames."));
                    return;
                }
            } else {
                consecutiveMissingFrames = 0;
                if (!stopped && active && visible) {
                    drawFrame(frame);
                }
            }
        } catch (RuntimeException exception) {
            fail(exception);
            return;
        } finally {
            if (frame != null && !frame.isRecycled()) {
                frame.recycle();
            }
        }

        if (stopped || !active || !visible) {
            return;
        }

        nextFrameTimestampUs -= frameIntervalUs;
        if (nextFrameTimestampUs < frameIntervalUs) {
            finish();
            return;
        }

        long frameIntervalMs = Math.max(1L, (frameIntervalUs + 500L) / 1_000L);
        long elapsedMs = SystemClock.uptimeMillis() - frameStartedAt;
        scheduleFrame(Math.max(0L, frameIntervalMs - elapsedMs));
    }

    private int[] getDecodeDimensions() {
        Rect surfaceFrame = surfaceHolder.getSurfaceFrame();
        int width = Math.max(1, surfaceFrame.width());
        int height = Math.max(1, surfaceFrame.height());
        int longestEdge = Math.max(width, height);
        if (longestEdge > MAX_DECODE_EDGE_PX) {
            float scale = (float) MAX_DECODE_EDGE_PX / longestEdge;
            width = Math.max(1, Math.round(width * scale));
            height = Math.max(1, Math.round(height * scale));
        }
        return new int[]{width, height};
    }

    private void drawFrame(Bitmap bitmap) {
        SurfaceHolder holder = surfaceHolder;
        if (holder == null || !holder.getSurface().isValid()) {
            return;
        }

        Canvas canvas = null;
        try {
            canvas = holder.lockCanvas();
            if (canvas == null) {
                return;
            }
            int canvasWidth = canvas.getWidth();
            int canvasHeight = canvas.getHeight();
            canvas.drawColor(Color.BLACK);

            float scale = Math.max(
                    (float) canvasWidth / bitmap.getWidth(),
                    (float) canvasHeight / bitmap.getHeight());
            float scaledWidth = bitmap.getWidth() * scale;
            float scaledHeight = bitmap.getHeight() * scale;
            float left = (canvasWidth - scaledWidth) / 2f;
            float top = (canvasHeight - scaledHeight) / 2f;
            destination.set(left, top, left + scaledWidth, top + scaledHeight);

            int saveCount = canvas.save();
            canvas.clipRect(0, 0, canvasWidth, canvasHeight);
            canvas.drawBitmap(bitmap, null, destination, framePaint);
            canvas.restoreToCount(saveCount);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Could not draw a reverse video frame.", exception);
        } finally {
            if (canvas != null) {
                holder.unlockCanvasAndPost(canvas);
            }
        }
    }

    private void finish() {
        if (stopped) {
            return;
        }
        active = false;
        startRequested = false;
        stopped = true;
        ready = false;
        worker.removeCallbacks(frameRunnable);
        frameScheduled.set(false);
        releaseRetriever();
        workerThread.quitSafely();
        mainHandler.post(() -> listener.onReverseFinished(this));
    }

    private void fail(Exception error) {
        if (stopped) {
            return;
        }
        Log.w(TAG, "Reverse playback could not decode or draw a frame.", error);
        active = false;
        startRequested = false;
        stopped = true;
        ready = false;
        worker.removeCallbacks(frameRunnable);
        frameScheduled.set(false);
        releaseRetriever();
        workerThread.quitSafely();
        mainHandler.post(() -> listener.onReverseFailed(this, error));
    }

    private void releaseRetriever() {
        MediaMetadataRetriever current = retriever;
        retriever = null;
        if (current != null) {
            try {
                current.release();
            } catch (IOException | RuntimeException ignored) {
                // Nothing remains to release if the platform decoder has already stopped.
            }
        }
    }

    private static long readDurationUs(MediaMetadataRetriever retriever) {
        String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
        if (duration == null) {
            return 0L;
        }
        try {
            long durationMs = Long.parseLong(duration);
            return durationMs > 0L ? durationMs * 1_000L : 0L;
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static long readFrameIntervalUs(
            MediaMetadataRetriever retriever, long durationUs) {
        long intervalUs = 0L;
        String frameCountValue = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT);
        if (frameCountValue != null && durationUs > 0L) {
            try {
                long frameCount = Long.parseLong(frameCountValue);
                if (frameCount > 1L) {
                    intervalUs = durationUs / frameCount;
                }
            } catch (NumberFormatException ignored) {
                // Try the capture frame-rate metadata below.
            }
        }

        if (intervalUs <= 0L) {
            String captureFrameRate = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
            if (captureFrameRate != null) {
                try {
                    double frameRate = Double.parseDouble(captureFrameRate);
                    if (frameRate > 0.0d) {
                        intervalUs = Math.round(1_000_000d / frameRate);
                    }
                } catch (NumberFormatException ignored) {
                    // Use the common 30 fps interval.
                }
            }
        }

        if (intervalUs <= 0L) {
            intervalUs = DEFAULT_FRAME_INTERVAL_US;
        }
        // Keep reverse decoding bounded to at most 30 frame lookups per second.
        return Math.max(MAX_FRAME_RATE_INTERVAL_US, intervalUs);
    }
}
