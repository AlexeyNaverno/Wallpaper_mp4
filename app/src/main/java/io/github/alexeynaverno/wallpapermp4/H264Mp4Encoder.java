package io.github.alexeynaverno.wallpapermp4;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.EGL14;
import android.opengl.EGLExt;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Encodes full-resolution bitmaps into a silent H.264 MP4 using a MediaCodec input surface. */
final class H264Mp4Encoder implements AutoCloseable {
    private static final String MIME_TYPE = "video/avc";
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;
    private static final int EGL_OPENGL_ES2_BIT = 4;
    private static final long DRAIN_TIMEOUT_US = 10_000L;
    private static final long MAX_FINISH_WAIT_MS = 120_000L;

    private static final float[] VERTICES = {
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
    };
    // Bitmap rows begin at the top; flip the texture vertically when drawing to GL.
    private static final float[] TEXTURE_COORDINATES = {
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
    };

    private final int width;
    private final int height;
    private final MediaCodec.BufferInfo outputInfo = new MediaCodec.BufferInfo();

    private MediaCodec encoder;
    private MediaMuxer muxer;
    private Surface inputSurface;
    private boolean encoderStarted;
    private boolean muxerStarted;
    private boolean outputEosSeen;
    private boolean finished;
    private int muxerTrack = -1;
    private long lastPresentationTimeUs = -1L;

    private android.opengl.EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private android.opengl.EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private android.opengl.EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private int shaderProgram;
    private int textureId;
    private int positionAttribute;
    private int textureAttribute;
    private int samplerUniform;
    private int textureWidth;
    private int textureHeight;

    H264Mp4Encoder(
            File outputFile,
            int requestedWidth,
            int requestedHeight,
            int frameRate,
            int orientationHint) throws IOException {
        if (requestedWidth <= 0 || requestedHeight <= 0) {
            throw new IOException("The video has invalid frame dimensions.");
        }

        // H.264 encoders generally require even dimensions. Rounding up by one pixel preserves
        // the complete source picture and avoids cropping odd-sized input frames.
        width = (requestedWidth + 1) & ~1;
        height = (requestedHeight + 1) & ~1;
        int safeFrameRate = Math.max(1, Math.min(120, frameRate));
        long suggestedBitRate = (long) width * height * safeFrameRate / 10L;
        int bitRate = (int) Math.max(500_000L, Math.min(50_000_000L, suggestedBitRate));

        try {
            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
            format.setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, safeFrameRate);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);

            encoder = MediaCodec.createEncoderByType(MIME_TYPE);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = encoder.createInputSurface();
            encoder.start();
            encoderStarted = true;

            muxer = new MediaMuxer(
                    outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            if (orientationHint != 0) {
                muxer.setOrientationHint(orientationHint);
            }
            createEglInputSurface();
        } catch (IOException | RuntimeException exception) {
            close();
            if (exception instanceof IOException) {
                throw (IOException) exception;
            }
            throw new IOException("This device could not initialize an H.264 video encoder.", exception);
        }
    }

    int getOutputWidth() {
        return width;
    }

    int getOutputHeight() {
        return height;
    }

    void writeFrame(android.graphics.Bitmap bitmap, long presentationTimeUs) throws IOException {
        if (finished || bitmap == null || bitmap.isRecycled()) {
            throw new IOException("A video frame is no longer available for encoding.");
        }
        checkInterrupted();
        if (presentationTimeUs <= lastPresentationTimeUs) {
            presentationTimeUs = lastPresentationTimeUs + 1L;
        }
        if (presentationTimeUs > Long.MAX_VALUE / 1_000L) {
            throw new IOException("The video timestamp is too large.");
        }

        try {
            drawBitmapToEncoder(bitmap, presentationTimeUs * 1_000L);
            lastPresentationTimeUs = presentationTimeUs;
            drainEncoder(false);
        } catch (RuntimeException exception) {
            throw new IOException("The device could not encode a video frame.", exception);
        }
    }

    void finish() throws IOException {
        if (finished) {
            return;
        }
        checkInterrupted();
        try {
            encoder.signalEndOfInputStream();
            long deadline = System.currentTimeMillis() + MAX_FINISH_WAIT_MS;
            while (!outputEosSeen) {
                checkInterrupted();
                if (System.currentTimeMillis() > deadline) {
                    throw new IOException("The video encoder did not finish in time.");
                }
                drainEncoder(true);
            }
            if (!muxerStarted) {
                throw new IOException("The device did not produce an H.264 video track.");
            }
            muxer.stop();
            muxerStarted = false;
            finished = true;
        } catch (RuntimeException exception) {
            throw new IOException("The H.264 MP4 could not be finalized.", exception);
        }
    }

    @Override
    public void close() {
        releaseEgl();

        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }
        if (encoder != null) {
            if (encoderStarted) {
                try {
                    encoder.stop();
                } catch (RuntimeException ignored) {
                    // A failed or interrupted encode may already have stopped the codec.
                }
                encoderStarted = false;
            }
            try {
                encoder.release();
            } catch (RuntimeException ignored) {
                // Release any remaining platform codec state.
            }
            encoder = null;
        }
        if (muxer != null) {
            if (muxerStarted) {
                try {
                    muxer.stop();
                } catch (RuntimeException ignored) {
                    // The incomplete output is discarded by the template generator.
                }
                muxerStarted = false;
            }
            try {
                muxer.release();
            } catch (RuntimeException ignored) {
                // Release any remaining muxer state.
            }
            muxer = null;
        }
    }

    private void createEglInputSurface() throws IOException {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new IOException("Could not open an EGL display for video encoding.");
        }

        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw new IOException("Could not initialize EGL for video encoding.");
        }

        int[] configAttributes = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 0,
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };
        android.opengl.EGLConfig[] configs = new android.opengl.EGLConfig[1];
        int[] configCount = new int[1];
        if (!EGL14.eglChooseConfig(
                eglDisplay, configAttributes, 0, configs, 0, configs.length, configCount, 0)
                || configCount[0] == 0) {
            throw new IOException("No EGL configuration supports video encoding.");
        }

        int[] contextAttributes = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        eglContext = EGL14.eglCreateContext(
                eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttributes, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw new IOException("Could not create an EGL context for video encoding.");
        }

        int[] surfaceAttributes = {EGL14.EGL_NONE};
        eglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay, configs[0], inputSurface, surfaceAttributes, 0);
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw new IOException("Could not create the H.264 encoder input surface.");
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw new IOException("Could not activate the EGL encoder surface.");
        }

        shaderProgram = createProgram();
        positionAttribute = GLES20.glGetAttribLocation(shaderProgram, "aPosition");
        textureAttribute = GLES20.glGetAttribLocation(shaderProgram, "aTextureCoord");
        samplerUniform = GLES20.glGetUniformLocation(shaderProgram, "sTexture");

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        textureId = textures[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        checkGlError("creating the encoder texture");
    }

    private void drawBitmapToEncoder(android.graphics.Bitmap bitmap, long presentationTimeNs)
            throws IOException {
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw new IOException("Could not activate the EGL encoder surface.");
        }

        GLES20.glViewport(0, 0, width, height);
        GLES20.glUseProgram(shaderProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        if (bitmap.getWidth() != textureWidth || bitmap.getHeight() != textureHeight) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
            textureWidth = bitmap.getWidth();
            textureHeight = bitmap.getHeight();
        } else {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap);
        }

        FloatBuffer vertices = floatBuffer(VERTICES);
        FloatBuffer textureCoordinates = floatBuffer(TEXTURE_COORDINATES);
        GLES20.glEnableVertexAttribArray(positionAttribute);
        GLES20.glVertexAttribPointer(positionAttribute, 2, GLES20.GL_FLOAT, false, 0, vertices);
        GLES20.glEnableVertexAttribArray(textureAttribute);
        GLES20.glVertexAttribPointer(
                textureAttribute, 2, GLES20.GL_FLOAT, false, 0, textureCoordinates);
        GLES20.glUniform1i(samplerUniform, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        checkGlError("drawing a video frame");

        if (!EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentationTimeNs)) {
            throw new IOException("Could not set the video frame timestamp.");
        }
        if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
            throw new IOException("Could not submit a frame to the H.264 encoder.");
        }
    }

    private void drainEncoder(boolean waitForOutput) throws IOException {
        while (true) {
            checkInterrupted();
            int outputIndex;
            try {
                outputIndex = encoder.dequeueOutputBuffer(
                        outputInfo, waitForOutput ? DRAIN_TIMEOUT_US : 0L);
            } catch (RuntimeException exception) {
                throw new IOException("The H.264 encoder stopped unexpectedly.", exception);
            }

            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return;
            }
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) {
                    throw new IOException("The H.264 output format changed unexpectedly.");
                }
                muxerTrack = muxer.addTrack(encoder.getOutputFormat());
                muxer.start();
                muxerStarted = true;
                continue;
            }
            if (outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                continue;
            }
            if (outputIndex < 0) {
                continue;
            }

            boolean endOfStream = (outputInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            try {
                if (outputInfo.size > 0
                        && (outputInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    if (!muxerStarted) {
                        throw new IOException("The H.264 encoder produced data before its format.");
                    }
                    ByteBuffer encodedData = encoder.getOutputBuffer(outputIndex);
                    if (encodedData == null) {
                        throw new IOException("The H.264 encoder returned an empty output buffer.");
                    }
                    encodedData.position(outputInfo.offset);
                    encodedData.limit(outputInfo.offset + outputInfo.size);
                    muxer.writeSampleData(muxerTrack, encodedData, outputInfo);
                }
            } catch (RuntimeException exception) {
                throw new IOException("Could not write encoded video data to the MP4.", exception);
            } finally {
                encoder.releaseOutputBuffer(outputIndex, false);
            }

            if (endOfStream) {
                outputEosSeen = true;
                return;
            }
        }
    }

    private void releaseEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            if (eglSurface != EGL14.EGL_NO_SURFACE
                    && eglContext != EGL14.EGL_NO_CONTEXT
                    && EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                if (textureId != 0) {
                    int[] textures = {textureId};
                    GLES20.glDeleteTextures(1, textures, 0);
                    textureId = 0;
                }
                if (shaderProgram != 0) {
                    GLES20.glDeleteProgram(shaderProgram);
                    shaderProgram = 0;
                }
            }
            EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface);
                eglSurface = EGL14.EGL_NO_SURFACE;
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext);
                eglContext = EGL14.EGL_NO_CONTEXT;
            }
            EGL14.eglTerminate(eglDisplay);
            eglDisplay = EGL14.EGL_NO_DISPLAY;
        }
    }

    private static int createProgram() throws IOException {
        String vertexShader =
                "attribute vec4 aPosition;\n"
                        + "attribute vec2 aTextureCoord;\n"
                        + "varying vec2 vTextureCoord;\n"
                        + "void main() {\n"
                        + "  gl_Position = aPosition;\n"
                        + "  vTextureCoord = aTextureCoord;\n"
                        + "}\n";
        String fragmentShader =
                "precision mediump float;\n"
                        + "varying vec2 vTextureCoord;\n"
                        + "uniform sampler2D sTexture;\n"
                        + "void main() {\n"
                        + "  gl_FragColor = texture2D(sTexture, vTextureCoord);\n"
                        + "}\n";

        int vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexShader);
        int fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertex);
        GLES20.glAttachShader(program, fragment);
        GLES20.glLinkProgram(program);
        int[] status = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
        if (status[0] == 0) {
            String error = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            throw new IOException("Could not link the video encoder shader: " + error);
        }
        return program;
    }

    private static int compileShader(int type, String source) throws IOException {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            String error = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IOException("Could not compile the video encoder shader: " + error);
        }
        return shader;
    }

    private static FloatBuffer floatBuffer(float[] values) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        buffer.put(values);
        buffer.position(0);
        return buffer;
    }

    private static void checkGlError(String operation) throws IOException {
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            throw new IOException("OpenGL error while " + operation + ": 0x"
                    + Integer.toHexString(error));
        }
    }

    private static void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Video template processing was cancelled.");
        }
    }
}
