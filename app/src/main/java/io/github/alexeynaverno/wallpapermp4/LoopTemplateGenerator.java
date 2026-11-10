package io.github.alexeynaverno.wallpapermp4;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.StatFs;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/** Creates a stored, silent MP4 containing the source frames followed by their reverse interior. */
final class LoopTemplateGenerator {
    interface ProgressListener {
        void onProgress(int percent);
    }

    static final class Result {
        final long durationUs;
        final int width;
        final int height;
        final int frameCount;

        Result(long durationUs, int width, int height, int frameCount) {
            this.durationUs = durationUs;
            this.width = width;
            this.height = height;
            this.frameCount = frameCount;
        }
    }

    private static final class VideoIndex {
        final List<Long> timestampsUs;
        final long durationUs;
        final int width;
        final int height;

        VideoIndex(List<Long> timestampsUs, long durationUs, int width, int height) {
            this.timestampsUs = timestampsUs;
            this.durationUs = durationUs;
            this.width = width;
            this.height = height;
        }
    }

    private static final String TAG = "WallpaperTemplate";
    private static final int JPEG_QUALITY = 96;
    private static final int MAX_SOURCE_FRAMES = 120_000;
    private static final long RESERVED_FREE_BYTES = 64L * 1024L * 1024L;

    private LoopTemplateGenerator() {
    }

    static Result generate(
            Context context,
            Uri sourceUri,
            File outputFile,
            File cacheDirectory,
            ProgressListener progressListener) throws IOException {
        checkInterrupted();
        File sourceCopy = copySourceToCache(context, sourceUri, cacheDirectory);
        try {
            return generateFromSourceFile(
                    sourceCopy, outputFile, cacheDirectory, progressListener);
        } finally {
            if (sourceCopy.exists() && !sourceCopy.delete()) {
                Log.w(TAG, "Could not remove the temporary source copy: " + sourceCopy);
            }
        }
    }

    private static Result generateFromSourceFile(
            File sourceFile,
            File outputFile,
            File cacheDirectory,
            ProgressListener progressListener) throws IOException {
        VideoIndex videoIndex = readVideoTimestamps(sourceFile);
        List<Long> sourceTimestampsUs = videoIndex.timestampsUs;
        if (sourceTimestampsUs.isEmpty()) {
            throw new IOException("No video frames were found in the selected MP4.");
        }
        if (sourceTimestampsUs.size() > MAX_SOURCE_FRAMES) {
            throw new IOException("This video is too long to prepare as a wallpaper template.");
        }

        File frameDirectory = new File(cacheDirectory,
                "wallpaper_frames_" + Long.toHexString(System.nanoTime()));
        if (!frameDirectory.mkdirs() && !frameDirectory.isDirectory()) {
            throw new IOException("Could not create temporary storage for video frames.");
        }

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Bitmap firstFrame = null;
        H264Mp4Encoder encoder = null;
        boolean success = false;
        try {
            retriever.setDataSource(sourceFile.getAbsolutePath());
            int sourceWidth = videoIndex.width > 0
                    ? videoIndex.width
                    : readPositiveMetadata(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            int sourceHeight = videoIndex.height > 0
                    ? videoIndex.height
                    : readPositiveMetadata(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            int sourceRotation = normalizeRotation(readMetadataInt(
                    retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION, 0));
            long metadataDurationUs = videoIndex.durationUs > 0L
                    ? videoIndex.durationUs : readDurationUs(retriever);

            long firstTimestampUs = sourceTimestampsUs.get(0);
            long[] relativeTimestampsUs = toRelativeTimestamps(sourceTimestampsUs, firstTimestampUs);
            int frameRate = estimateFrameRate(relativeTimestampsUs);
            long frameIntervalUs = estimateLastFrameInterval(relativeTimestampsUs, frameRate);
            long estimatedDurationUs = relativeTimestampsUs[relativeTimestampsUs.length - 1]
                    + frameIntervalUs;
            long durationUs = Math.max(metadataDurationUs, estimatedDurationUs);

            firstFrame = retrieveFrame(retriever, firstTimestampUs, sourceWidth, sourceHeight);
            if (firstFrame == null) {
                throw new IOException("The video decoder could not retrieve the first frame.");
            }
            int outputWidth = firstFrame.getWidth();
            int outputHeight = firstFrame.getHeight();
            int outputRotation = isFrameAlreadyRotated(
                    firstFrame, sourceWidth, sourceHeight, sourceRotation) ? 0 : sourceRotation;
            encoder = new H264Mp4Encoder(
                    outputFile, outputWidth, outputHeight, frameRate, outputRotation);

            int frameCount = sourceTimestampsUs.size();
            List<File> reverseFrames = new ArrayList<>(Math.max(0, frameCount - 2));
            long reversePivotUs = frameCount > 2
                    ? relativeTimestampsUs[frameCount - 2]
                    : 0L;
            long loopDurationUs = durationUs + reversePivotUs;
            for (int index = 0; index < frameCount; index++) {
                checkInterrupted();
                Bitmap frame = index == 0
                        ? firstFrame
                        : retrieveFrame(
                                retriever,
                                sourceTimestampsUs.get(index),
                                sourceWidth,
                                sourceHeight);
                try {
                    if (frame == null) {
                        throw new IOException("The video decoder could not retrieve frame "
                                + (index + 1) + " of " + frameCount + ".");
                    }
                    if (frame.getWidth() != outputWidth || frame.getHeight() != outputHeight) {
                        Bitmap decodedFrame = frame;
                        frame = Bitmap.createScaledBitmap(
                                decodedFrame, outputWidth, outputHeight, true);
                        if (decodedFrame != firstFrame && !decodedFrame.isRecycled()) {
                            decodedFrame.recycle();
                        }
                    }

                    if (index > 0 && index < frameCount - 1) {
                        File savedFrame = new File(
                                frameDirectory,
                                String.format(Locale.US, "frame_%08d.jpg", index));
                        saveTemporaryFrame(frame, savedFrame, frameDirectory);
                        reverseFrames.add(savedFrame);
                    }
                    encoder.writeFrame(frame, relativeTimestampsUs[index]);

                    if (index % 5 == 0 || index == frameCount - 1) {
                        progressListener.onProgress((int) (55L * (index + 1) / frameCount));
                    }
                } finally {
                    if (frame != null && frame != firstFrame && !frame.isRecycled()) {
                        frame.recycle();
                    }
                }
            }
            if (firstFrame != null && !firstFrame.isRecycled()) {
                firstFrame.recycle();
                firstFrame = null;
            }

            for (int index = frameCount - 2; index >= 1; index--) {
                checkInterrupted();
                File savedFrame = reverseFrames.get(index - 1);
                Bitmap frame = BitmapFactory.decodeFile(savedFrame.getAbsolutePath());
                if (frame == null) {
                    throw new IOException("A temporary video frame could not be read back.");
                }
                try {
                    long reverseTimestampUs = durationUs
                            + reversePivotUs - relativeTimestampsUs[index];
                    encoder.writeFrame(frame, reverseTimestampUs);
                } finally {
                    if (!frame.isRecycled()) {
                        frame.recycle();
                    }
                    // Free temporary disk space progressively during the reverse pass.
                    if (savedFrame.exists() && !savedFrame.delete()) {
                        Log.w(TAG, "Could not remove a temporary frame: " + savedFrame);
                    }
                }
                if ((frameCount - index) % 5 == 0 || index == 1) {
                    int completedReverseFrames = frameCount - 1 - index;
                    int reverseCount = Math.max(1, frameCount - 2);
                    progressListener.onProgress(
                            55 + (int) (40L * completedReverseFrames / reverseCount));
                }
            }

            int encodedWidth = encoder.getOutputWidth();
            int encodedHeight = encoder.getOutputHeight();
            encoder.finish();
            encoder.close();
            encoder = null;
            if (!outputFile.isFile() || outputFile.length() == 0L) {
                throw new IOException("The generated MP4 file is empty.");
            }
            progressListener.onProgress(100);
            success = true;
            return new Result(loopDurationUs, encodedWidth, encodedHeight, frameCount);
        } catch (OutOfMemoryError error) {
            throw new IOException("There is not enough memory to process this video at its original resolution.", error);
        } catch (RuntimeException exception) {
            throw new IOException("The selected MP4 could not be processed on this device.", exception);
        } finally {
            if (firstFrame != null && !firstFrame.isRecycled()) {
                firstFrame.recycle();
            }
            if (encoder != null) {
                encoder.close();
            }
            try {
                retriever.release();
            } catch (IOException | RuntimeException ignored) {
                // Preserve the generation result or the original decoding error.
            }
            deleteRecursively(frameDirectory);
            if (!success && outputFile.exists() && !outputFile.delete()) {
                Log.w(TAG, "Could not delete an incomplete MP4: " + outputFile);
            }
        }
    }

    private static File copySourceToCache(Context context, Uri sourceUri, File cacheDirectory)
            throws IOException {
        File sourceCopy = new File(cacheDirectory,
                "wallpaper_source_" + Long.toHexString(System.nanoTime()) + ".mp4");
        boolean copied = false;
        try (InputStream input = context.getContentResolver().openInputStream(sourceUri)) {
            if (input == null) {
                throw new IOException("Android could not open the selected video document.");
            }
            try (FileOutputStream output = new FileOutputStream(sourceCopy)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    checkInterrupted();
                    StatFs storage = new StatFs(cacheDirectory.getAbsolutePath());
                    if (storage.getAvailableBytes() < RESERVED_FREE_BYTES) {
                        throw new IOException("Not enough free storage to prepare this video. "
                                + "Free space and try again.");
                    }
                    output.write(buffer, 0, read);
                }
                output.flush();
                copied = sourceCopy.length() > 0L;
            }
        } catch (RuntimeException exception) {
            throw new IOException("Android could not copy the selected video for processing.", exception);
        } finally {
            if (!copied && sourceCopy.exists() && !sourceCopy.delete()) {
                Log.w(TAG, "Could not remove the incomplete source copy: " + sourceCopy);
            }
        }
        if (!copied) {
            throw new IOException("The selected video file is empty.");
        }
        return sourceCopy;
    }

    private static VideoIndex readVideoTimestamps(File sourceFile) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(sourceFile.getAbsolutePath());
            int videoTrack = -1;
            int videoWidth = 0;
            int videoHeight = 0;
            long videoDurationUs = 0L;
            for (int index = 0; index < extractor.getTrackCount(); index++) {
                MediaFormat format = extractor.getTrackFormat(index);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoTrack = index;
                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        videoDurationUs = Math.max(0L, format.getLong(MediaFormat.KEY_DURATION));
                    }
                    if (format.containsKey(MediaFormat.KEY_WIDTH)) {
                        videoWidth = Math.max(0, format.getInteger(MediaFormat.KEY_WIDTH));
                    }
                    if (format.containsKey(MediaFormat.KEY_HEIGHT)) {
                        videoHeight = Math.max(0, format.getInteger(MediaFormat.KEY_HEIGHT));
                    }
                    break;
                }
            }
            if (videoTrack < 0) {
                throw new IOException("The selected MP4 does not contain a video track.");
            }

            extractor.selectTrack(videoTrack);
            TreeSet<Long> orderedTimestamps = new TreeSet<>();
            while (true) {
                checkInterrupted();
                long timestampUs = extractor.getSampleTime();
                if (timestampUs < 0L) {
                    break;
                }
                if (extractor.getSampleTrackIndex() == videoTrack) {
                    orderedTimestamps.add(timestampUs);
                    if (orderedTimestamps.size() > MAX_SOURCE_FRAMES) {
                        throw new IOException("This video contains too many frames to process.");
                    }
                }
                if (!extractor.advance()) {
                    break;
                }
            }
            return new VideoIndex(
                    new ArrayList<>(orderedTimestamps), videoDurationUs, videoWidth, videoHeight);
        } catch (RuntimeException exception) {
            throw new IOException("The selected MP4 could not be indexed.", exception);
        } finally {
            try {
                extractor.release();
            } catch (RuntimeException ignored) {
                // Release the extractor after reading the source timestamps.
            }
        }
    }

    private static Bitmap retrieveFrame(
            MediaMetadataRetriever retriever,
            long timestampUs,
            int width,
            int height) throws IOException {
        try {
            if (width > 0 && height > 0) {
                return retriever.getScaledFrameAtTime(
                        timestampUs,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                        width,
                        height);
            }
            return retriever.getFrameAtTime(
                    timestampUs, MediaMetadataRetriever.OPTION_CLOSEST);
        } catch (RuntimeException exception) {
            throw new IOException("The device could not decode a video frame.", exception);
        }
    }

    private static void saveTemporaryFrame(Bitmap bitmap, File output, File temporaryDirectory)
            throws IOException {
        checkInterrupted();
        StatFs storage = new StatFs(temporaryDirectory.getAbsolutePath());
        if (storage.getAvailableBytes() < RESERVED_FREE_BYTES) {
            throw new IOException("Not enough free storage to prepare this video. "
                    + "Free space and try again.");
        }
        try (FileOutputStream stream = new FileOutputStream(output)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)) {
                throw new IOException("Could not store a temporary video frame.");
            }
            stream.flush();
        } catch (RuntimeException exception) {
            throw new IOException("Could not store a temporary video frame.", exception);
        }
    }

    private static long[] toRelativeTimestamps(List<Long> timestamps, long firstTimestampUs) {
        long[] relative = new long[timestamps.size()];
        for (int index = 0; index < timestamps.size(); index++) {
            relative[index] = Math.max(0L, timestamps.get(index) - firstTimestampUs);
        }
        return relative;
    }

    private static int estimateFrameRate(long[] relativeTimestampsUs) {
        if (relativeTimestampsUs.length < 2) {
            return 30;
        }
        List<Long> intervals = new ArrayList<>(relativeTimestampsUs.length - 1);
        for (int index = 1; index < relativeTimestampsUs.length; index++) {
            long interval = relativeTimestampsUs[index] - relativeTimestampsUs[index - 1];
            if (interval > 0L) {
                intervals.add(interval);
            }
        }
        if (intervals.isEmpty()) {
            return 30;
        }
        Collections.sort(intervals);
        long medianIntervalUs = intervals.get(intervals.size() / 2);
        int frameRate = (int) Math.round(1_000_000d / medianIntervalUs);
        return Math.max(1, Math.min(120, frameRate));
    }

    private static long estimateLastFrameInterval(long[] relativeTimestampsUs, int frameRate) {
        if (relativeTimestampsUs.length > 1) {
            long lastInterval = relativeTimestampsUs[relativeTimestampsUs.length - 1]
                    - relativeTimestampsUs[relativeTimestampsUs.length - 2];
            if (lastInterval > 0L) {
                return lastInterval;
            }
        }
        return Math.max(1L, 1_000_000L / Math.max(1, frameRate));
    }

    private static long readDurationUs(MediaMetadataRetriever retriever) {
        String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
        if (value == null) {
            return 0L;
        }
        try {
            long durationMs = Long.parseLong(value);
            return durationMs > 0L ? durationMs * 1_000L : 0L;
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static int readPositiveMetadata(MediaMetadataRetriever retriever, int key) {
        String value = retriever.extractMetadata(key);
        if (value == null) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static int readMetadataInt(MediaMetadataRetriever retriever, int key, int fallback) {
        String value = retriever.extractMetadata(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int normalizeRotation(int rotation) {
        int normalized = ((rotation % 360) + 360) % 360;
        if (normalized == 90 || normalized == 180 || normalized == 270) {
            return normalized;
        }
        return 0;
    }

    private static boolean isFrameAlreadyRotated(
            Bitmap firstFrame, int sourceWidth, int sourceHeight, int rotation) {
        return (rotation == 90 || rotation == 270)
                && firstFrame.getWidth() == sourceHeight
                && firstFrame.getHeight() == sourceWidth;
    }

    private static void deleteRecursively(File directory) {
        File[] children = directory.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    deleteRecursively(child);
                } else if (!child.delete()) {
                    Log.w(TAG, "Could not remove a temporary file: " + child);
                }
            }
        }
        if (directory.exists() && !directory.delete()) {
            Log.w(TAG, "Could not remove a temporary directory: " + directory);
        }
    }

    private static void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Video template processing was cancelled.");
        }
    }
}
