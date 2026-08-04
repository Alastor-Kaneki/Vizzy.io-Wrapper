package dev.alastorkaneki.vizzywrapper;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Offline native renderer using Canvas -> Bitmap -> GLES -> MediaCodec, with AAC audio muxing. */
public final class NativeVideoExporter {
    public interface Listener {
        void onProgress(float progress, String stage);
        boolean isCancelled();
    }

    private NativeVideoExporter() {}

    public static Result export(Context context, ProjectModel project, Uri outputUri, Listener listener) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        int width = makeEven(Math.max(64, project.canvasWidth));
        int height = makeEven(Math.max(64, project.canvasHeight));
        int frameRate = Math.max(1, Math.min(240, project.frameRate));
        String mime = project.exportCodec == null || project.exportCodec.trim().isEmpty()
                ? MediaFormat.MIMETYPE_VIDEO_AVC : project.exportCodec;

        ExportCapabilities.Capability capability = ExportCapabilities.best(mime);
        if (capability == null) throw new IOException("No " + mime + " encoder is installed on this device.");
        if (!capability.supports(width, height, frameRate)) {
            throw new IOException("Requested " + width + "×" + height + " at " + frameRate
                    + " FPS is outside " + capability.name + " limits (up to "
                    + capability.widths.getUpper() + "×" + capability.heights.getUpper() + ").");
        }
        int bitrate = capability.clampBitrate(Math.max(100_000, project.exportBitrate));

        File tempAudio = new File(context.getCacheDir(), "vizzy-export-audio-" + System.nanoTime() + ".m4a");
        PcmAudioTranscoder.Result audio = PcmAudioTranscoder.transcode(
                resolver, project.audioUri, tempAudio, project.audioBitrate, frameRate,
                value -> update(listener, value * 0.18f, "Preparing audio")
        );
        long audioDurationUs = audio.durationUs;
        long projectDurationUs = Math.max(1_000_000L, project.durationMs * 1000L);
        long durationUs = audioDurationUs > 0L ? Math.max(projectDurationUs, audioDurationUs) : projectDurationUs;
        long totalFrames = Math.max(1L, (durationUs * frameRate + 999_999L) / 1_000_000L);

        ParcelFileDescriptor outputFd = resolver.openFileDescriptor(outputUri, "rw");
        if (outputFd == null) throw new IOException("Unable to open the selected output destination.");
        MediaMuxer muxer = null;
        MediaCodec videoEncoder = null;
        CodecInputSurface inputSurface = null;
        BitmapTextureRenderer textureRenderer = null;
        MediaExtractor audioExtractor = null;
        Bitmap frameBitmap = null;
        NativeRenderer.AssetCache assetCache = new NativeRenderer.AssetCache(8);
        boolean muxerStarted = false;
        try {
            muxer = new MediaMuxer(outputFd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int audioTrack = -1;
            int audioSourceTrack = -1;
            if (audio.file != null && audio.file.isFile() && audio.file.length() > 0L) {
                audioExtractor = new MediaExtractor();
                audioExtractor.setDataSource(audio.file.getAbsolutePath());
                audioSourceTrack = findTrack(audioExtractor, "audio/");
                if (audioSourceTrack >= 0) {
                    audioExtractor.selectTrack(audioSourceTrack);
                    audioTrack = muxer.addTrack(audioExtractor.getTrackFormat(audioSourceTrack));
                }
            }

            MediaFormat videoFormat = MediaFormat.createVideoFormat(mime, width, height);
            videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
            videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            if (mime.equals(MediaFormat.MIMETYPE_VIDEO_HEVC)) {
                videoFormat.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain);
            }

            videoEncoder = MediaCodec.createEncoderByType(mime);
            videoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Surface codecSurface = videoEncoder.createInputSurface();
            videoEncoder.start();
            inputSurface = new CodecInputSurface(codecSurface);
            inputSurface.makeCurrent();
            textureRenderer = new BitmapTextureRenderer();
            textureRenderer.setup();

            try {
                frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            } catch (OutOfMemoryError oom) {
                throw new IOException("Not enough memory for a " + width + "×" + height
                        + " render surface. Lower the resolution or close other apps.", oom);
            }
            Canvas frameCanvas = new Canvas(frameBitmap);
            RectF frameViewport = new RectF(0f, 0f, width, height);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            MuxerState muxerState = new MuxerState(muxer, audioTrack);

            for (long frame = 0L; frame < totalFrames; frame++) {
                if (listener != null && listener.isCancelled()) throw new ExportCancelledException();
                long ptsUs = frame * 1_000_000L / frameRate;
                frameCanvas.drawColor(Color.BLACK);
                NativeRenderer.render(frameCanvas, frameViewport, project, ptsUs / 1000L,
                        audio.analysis.at(ptsUs), resolver, assetCache, true);
                textureRenderer.draw(frameBitmap, width, height);
                inputSurface.setPresentationTime(ptsUs * 1000L);
                inputSurface.swapBuffers();
                drainVideo(videoEncoder, info, muxerState, false);
                float fraction = (frame + 1f) / totalFrames;
                update(listener, 0.18f + fraction * 0.76f,
                        "Rendering frame " + (frame + 1) + " / " + totalFrames);
            }

            videoEncoder.signalEndOfInputStream();
            drainVideo(videoEncoder, info, muxerState, true);
            muxerStarted = muxerState.started;
            if (!muxerStarted) throw new IOException("The video encoder produced no output format.");

            if (audioExtractor != null && audioTrack >= 0) {
                copyAudio(audioExtractor, muxer, audioSourceTrack, audioTrack, durationUs, listener);
            }

            muxer.stop();
            muxerStarted = false;
            update(listener, 1f, "Finished");
            return new Result(width, height, frameRate, bitrate, durationUs, capability.name);
        } finally {
            assetCache.clear();
            if (frameBitmap != null && !frameBitmap.isRecycled()) frameBitmap.recycle();
            if (textureRenderer != null) {
                try { textureRenderer.release(); } catch (RuntimeException ignored) { }
            }
            if (inputSurface != null) {
                try { inputSurface.release(); } catch (RuntimeException ignored) { }
            }
            if (videoEncoder != null) {
                try { videoEncoder.stop(); } catch (RuntimeException ignored) { }
                try { videoEncoder.release(); } catch (RuntimeException ignored) { }
            }
            if (audioExtractor != null) {
                try { audioExtractor.release(); } catch (RuntimeException ignored) { }
            }
            if (muxer != null) {
                if (muxerStarted) try { muxer.stop(); } catch (RuntimeException ignored) { }
                try { muxer.release(); } catch (RuntimeException ignored) { }
            }
            try { outputFd.close(); } catch (IOException ignored) { }
            if (tempAudio.exists()) tempAudio.delete();
        }
    }

    private static void drainVideo(MediaCodec encoder, MediaCodec.BufferInfo info, MuxerState state, boolean end) throws IOException {
        while (true) {
            int outputIndex = encoder.dequeueOutputBuffer(info, end ? 10_000L : 0L);
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!end) return;
                continue;
            }
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (state.started) throw new IOException("Video output format changed twice.");
                state.videoTrack = state.muxer.addTrack(encoder.getOutputFormat());
                state.muxer.start();
                state.started = true;
                continue;
            }
            if (outputIndex >= 0) {
                ByteBuffer output = encoder.getOutputBuffer(outputIndex);
                if (output == null) throw new IOException("Video encoder output buffer unavailable.");
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (info.size > 0) {
                    if (!state.started) throw new IOException("Video muxer has not started.");
                    output.position(info.offset);
                    output.limit(info.offset + info.size);
                    state.muxer.writeSampleData(state.videoTrack, output, info);
                }
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                encoder.releaseOutputBuffer(outputIndex, false);
                if (eos) return;
            }
        }
    }

    private static void copyAudio(MediaExtractor extractor, MediaMuxer muxer, int sourceTrack, int outputTrack,
                                  long durationUs, Listener listener) throws IOException {
        MediaFormat format = extractor.getTrackFormat(sourceTrack);
        int maxInput = format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                ? format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 1024 * 1024;
        ByteBuffer buffer = ByteBuffer.allocateDirect(Math.max(64 * 1024, maxInput));
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            if (listener != null && listener.isCancelled()) throw new ExportCancelledException();
            buffer.clear();
            int size = extractor.readSampleData(buffer, 0);
            if (size < 0) break;
            long ptsUs = extractor.getSampleTime();
            if (ptsUs > durationUs) break;
            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = Math.max(0L, ptsUs);
            info.flags = extractor.getSampleFlags();
            buffer.position(0);
            buffer.limit(size);
            muxer.writeSampleData(outputTrack, buffer, info);
            extractor.advance();
            update(listener, 0.94f + Math.min(1f, ptsUs / (float) Math.max(1L, durationUs)) * 0.06f, "Muxing audio");
        }
    }

    private static int findTrack(MediaExtractor extractor, String prefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }

    private static int makeEven(int value) {
        return (value & 1) == 0 ? value : value + 1;
    }

    private static void update(Listener listener, float progress, String stage) {
        if (listener != null) listener.onProgress(Math.max(0f, Math.min(1f, progress)), stage);
    }

    private static final class MuxerState {
        final MediaMuxer muxer;
        final int audioTrack;
        int videoTrack = -1;
        boolean started;

        MuxerState(MediaMuxer muxer, int audioTrack) {
            this.muxer = muxer;
            this.audioTrack = audioTrack;
        }
    }

    public static final class Result {
        public final int width;
        public final int height;
        public final int frameRate;
        public final int bitrate;
        public final long durationUs;
        public final String encoderName;

        Result(int width, int height, int frameRate, int bitrate, long durationUs, String encoderName) {
            this.width = width;
            this.height = height;
            this.frameRate = frameRate;
            this.bitrate = bitrate;
            this.durationUs = durationUs;
            this.encoderName = encoderName;
        }
    }

    public static final class ExportCancelledException extends IOException {
        ExportCancelledException() { super("Export cancelled."); }
    }
}
