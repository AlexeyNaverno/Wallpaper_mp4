package io.github.alexeynaverno.wallpapermp4;

import android.app.WallpaperColors;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Extracts a representative Android wallpaper palette without decoding the whole video. */
final class VideoPaletteExtractor {
    private static final int SAMPLE_COUNT = 5;
    private static final int SAMPLE_EDGE_PX = 64;

    private VideoPaletteExtractor() {
    }

    static WallpaperColors fromVideo(Context context, Uri uri) throws IOException {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        List<Bitmap> frames = new ArrayList<>(SAMPLE_COUNT);
        Bitmap sampleStrip = null;

        try {
            retriever.setDataSource(context, uri);
            long durationUs = readDurationUs(retriever);

            for (int index = 0; index < SAMPLE_COUNT; index++) {
                long timestampUs = durationUs > 0
                        ? (long) ((double) durationUs * (index + 1) / (SAMPLE_COUNT + 1))
                        : 0L;
                Bitmap frame = retriever.getScaledFrameAtTime(
                        timestampUs,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                        SAMPLE_EDGE_PX,
                        SAMPLE_EDGE_PX);
                if (frame != null) {
                    frames.add(frame);
                }
            }

            if (frames.isEmpty()) {
                throw new IOException("The video decoder did not return a frame.");
            }

            sampleStrip = Bitmap.createBitmap(
                    SAMPLE_EDGE_PX * frames.size(), SAMPLE_EDGE_PX, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(sampleStrip);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            for (int index = 0; index < frames.size(); index++) {
                drawCenterCrop(canvas, frames.get(index), index * SAMPLE_EDGE_PX, paint);
            }

            WallpaperColors colors = WallpaperColors.fromBitmap(sampleStrip);
            if (colors == null) {
                throw new IOException("Android could not derive colors from the video frames.");
            }
            return colors;
        } catch (RuntimeException exception) {
            throw new IOException("The selected video could not be decoded.", exception);
        } finally {
            for (Bitmap frame : frames) {
                if (!frame.isRecycled()) {
                    frame.recycle();
                }
            }
            if (sampleStrip != null && !sampleStrip.isRecycled()) {
                sampleStrip.recycle();
            }
            try {
                retriever.release();
            } catch (RuntimeException ignored) {
                // The extraction result (or original decoding failure) is more useful.
            }
        }
    }

    private static long readDurationUs(MediaMetadataRetriever retriever) {
        String durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
        if (durationMs == null) {
            return 0L;
        }
        try {
            long milliseconds = Long.parseLong(durationMs);
            return milliseconds > 0 ? milliseconds * 1_000L : 0L;
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static void drawCenterCrop(Canvas canvas, Bitmap bitmap, int left, Paint paint) {
        float scale = Math.max(
                (float) SAMPLE_EDGE_PX / bitmap.getWidth(),
                (float) SAMPLE_EDGE_PX / bitmap.getHeight());
        float width = bitmap.getWidth() * scale;
        float height = bitmap.getHeight() * scale;
        float centerX = left + SAMPLE_EDGE_PX / 2f;
        float centerY = SAMPLE_EDGE_PX / 2f;
        RectF destination = new RectF(
                centerX - width / 2f,
                centerY - height / 2f,
                centerX + width / 2f,
                centerY + height / 2f);

        int checkpoint = canvas.save();
        canvas.clipRect(left, 0, left + SAMPLE_EDGE_PX, SAMPLE_EDGE_PX);
        canvas.drawBitmap(bitmap, null, destination, paint);
        canvas.restoreToCount(checkpoint);
    }
}
