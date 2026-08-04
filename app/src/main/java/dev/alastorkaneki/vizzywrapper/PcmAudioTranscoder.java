package dev.alastorkaneki.vizzywrapper;

import android.content.ContentResolver;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/** Decodes arbitrary Android-supported audio, analyzes PCM and writes AAC for MP4 export. */
public final class PcmAudioTranscoder {
    public interface Progress {
        void onAudioProgress(float value);
    }

    private PcmAudioTranscoder() {}

    public static Result transcode(
            ContentResolver resolver,
            String inputUri,
            File outputFile,
            int requestedBitrate,
            int analysisFrameRate,
            Progress progress
    ) throws Exception {
        if (inputUri == null || inputUri.trim().isEmpty()) {
            return new Result(null, new AudioAnalysis(), 0L, null);
        }

        MediaExtractor extractor = new MediaExtractor();
        ParcelFileDescriptor inputFd = resolver.openFileDescriptor(Uri.parse(inputUri), "r");
        if (inputFd == null) throw new IOException("Unable to open the project audio.");
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        boolean muxerStarted = false;
        try {
            extractor.setDataSource(inputFd.getFileDescriptor());
            int trackIndex = findAudioTrack(extractor);
            if (trackIndex < 0) throw new IOException("The selected file has no audio track.");
            extractor.selectTrack(trackIndex);
            MediaFormat inputFormat = extractor.getTrackFormat(trackIndex);
            String inputMime = inputFormat.getString(MediaFormat.KEY_MIME);
            if (inputMime == null) throw new IOException("Audio codec information is missing.");
            int sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            long durationUs = inputFormat.containsKey(MediaFormat.KEY_DURATION)
                    ? inputFormat.getLong(MediaFormat.KEY_DURATION) : 0L;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            }

            decoder = MediaCodec.createDecoderByType(inputMime);
            decoder.configure(inputFormat, null, null, 0);
            decoder.start();

            int outputChannels = Math.max(1, Math.min(2, channels));
            MediaFormat aacFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, outputChannels);
            aacFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            aacFormat.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(64_000, Math.min(512_000, requestedBitrate)));
            aacFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024);
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            encoder.configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            if (outputFile.exists() && !outputFile.delete()) throw new IOException("Unable to replace temporary audio file.");
            muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            AudioAnalysis.Builder analyzer = new AudioAnalysis.Builder(sampleRate, analysisFrameRate);
            ArrayDeque<PcmPacket> packets = new ArrayDeque<>();
            PcmPacket activePacket = null;
            int activeOffset = 0;
            boolean extractorDone = false;
            boolean decoderDone = false;
            boolean encoderInputDone = false;
            boolean encoderDone = false;
            int outputTrack = -1;
            MediaCodec.BufferInfo decoderInfo = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo encoderInfo = new MediaCodec.BufferInfo();
            long lastProgressUs = 0L;

            while (!encoderDone) {
                if (!extractorDone) {
                    int inputIndex = decoder.dequeueInputBuffer(10_000L);
                    if (inputIndex >= 0) {
                        ByteBuffer input = decoder.getInputBuffer(inputIndex);
                        if (input == null) throw new IOException("Audio decoder input buffer unavailable.");
                        input.clear();
                        int size = extractor.readSampleData(input, 0);
                        long pts = extractor.getSampleTime();
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, Math.max(0L, pts), MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            extractorDone = true;
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, Math.max(0L, pts), extractor.getSampleFlags());
                            extractor.advance();
                        }
                    }
                }

                if (!decoderDone) {
                    while (true) {
                        int outputIndex = decoder.dequeueOutputBuffer(decoderInfo, 0L);
                        if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                        if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue;
                        if (outputIndex >= 0) {
                            ByteBuffer output = decoder.getOutputBuffer(outputIndex);
                            if (output != null && decoderInfo.size > 0) {
                                output.position(decoderInfo.offset);
                                output.limit(decoderInfo.offset + decoderInfo.size);
                                byte[] pcm = new byte[decoderInfo.size];
                                output.get(pcm);
                                if (channels != outputChannels) pcm = downmixPcm16(pcm, channels, outputChannels);
                                analyzer.consumePcm16(pcm, 0, pcm.length, outputChannels);
                                packets.add(new PcmPacket(pcm, decoderInfo.presentationTimeUs, false));
                                lastProgressUs = Math.max(lastProgressUs, decoderInfo.presentationTimeUs);
                                if (progress != null && durationUs > 0L) {
                                    progress.onAudioProgress(Math.min(1f, lastProgressUs / (float) durationUs));
                                }
                            }
                            boolean eos = (decoderInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                            decoder.releaseOutputBuffer(outputIndex, false);
                            if (eos) {
                                packets.add(new PcmPacket(new byte[0], Math.max(lastProgressUs, durationUs), true));
                                decoderDone = true;
                                break;
                            }
                        }
                    }
                }

                if (!encoderInputDone) {
                    if (activePacket == null) {
                        activePacket = packets.poll();
                        activeOffset = 0;
                    }
                    if (activePacket != null) {
                        int inputIndex = encoder.dequeueInputBuffer(0L);
                        if (inputIndex >= 0) {
                            ByteBuffer input = encoder.getInputBuffer(inputIndex);
                            if (input == null) throw new IOException("AAC encoder input buffer unavailable.");
                            input.clear();
                            if (activePacket.eos && activePacket.data.length == 0) {
                                encoder.queueInputBuffer(inputIndex, 0, 0, activePacket.ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                encoderInputDone = true;
                                activePacket = null;
                            } else {
                                int count = Math.min(input.remaining(), activePacket.data.length - activeOffset);
                                input.put(activePacket.data, activeOffset, count);
                                long frameOffset = activeOffset / (long) (outputChannels * 2);
                                long pts = activePacket.ptsUs + frameOffset * 1_000_000L / sampleRate;
                                encoder.queueInputBuffer(inputIndex, 0, count, pts, 0);
                                activeOffset += count;
                                if (activeOffset >= activePacket.data.length) activePacket = null;
                            }
                        }
                    }
                }

                while (true) {
                    int outputIndex = encoder.dequeueOutputBuffer(encoderInfo, 0L);
                    if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxerStarted) throw new IOException("AAC output format changed twice.");
                        outputTrack = muxer.addTrack(encoder.getOutputFormat());
                        muxer.start();
                        muxerStarted = true;
                        continue;
                    }
                    if (outputIndex >= 0) {
                        ByteBuffer output = encoder.getOutputBuffer(outputIndex);
                        if (output == null) throw new IOException("AAC encoder output buffer unavailable.");
                        if ((encoderInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) encoderInfo.size = 0;
                        if (encoderInfo.size > 0) {
                            if (!muxerStarted) throw new IOException("AAC muxer did not start.");
                            output.position(encoderInfo.offset);
                            output.limit(encoderInfo.offset + encoderInfo.size);
                            muxer.writeSampleData(outputTrack, output, encoderInfo);
                        }
                        encoderDone = (encoderInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        encoder.releaseOutputBuffer(outputIndex, false);
                        if (encoderDone) break;
                    }
                }
            }

            if (muxerStarted) {
                muxer.stop();
                muxerStarted = false;
            }
            MediaExtractor resultExtractor = new MediaExtractor();
            resultExtractor.setDataSource(outputFile.getAbsolutePath());
            int resultTrack = findAudioTrack(resultExtractor);
            MediaFormat resultFormat = resultTrack >= 0 ? resultExtractor.getTrackFormat(resultTrack) : null;
            resultExtractor.release();
            if (progress != null) progress.onAudioProgress(1f);
            return new Result(outputFile, analyzer.build(), durationUs, resultFormat);
        } finally {
            try { extractor.release(); } catch (RuntimeException ignored) { }
            try { inputFd.close(); } catch (IOException ignored) { }
            if (decoder != null) {
                try { decoder.stop(); } catch (RuntimeException ignored) { }
                try { decoder.release(); } catch (RuntimeException ignored) { }
            }
            if (encoder != null) {
                try { encoder.stop(); } catch (RuntimeException ignored) { }
                try { encoder.release(); } catch (RuntimeException ignored) { }
            }
            if (muxer != null) {
                if (muxerStarted) try { muxer.stop(); } catch (RuntimeException ignored) { }
                try { muxer.release(); } catch (RuntimeException ignored) { }
            }
        }
    }

    private static int findAudioTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) return i;
        }
        return -1;
    }

    private static byte[] downmixPcm16(byte[] source, int sourceChannels, int targetChannels) {
        if (sourceChannels <= targetChannels || sourceChannels <= 0) return source;
        int frames = source.length / (sourceChannels * 2);
        byte[] out = new byte[frames * targetChannels * 2];
        for (int frame = 0; frame < frames; frame++) {
            if (targetChannels == 1) {
                int sum = 0;
                for (int channel = 0; channel < sourceChannels; channel++) sum += readShort(source, (frame * sourceChannels + channel) * 2);
                writeShort(out, frame * 2, (short) (sum / sourceChannels));
            } else {
                short left = readShort(source, frame * sourceChannels * 2);
                short right = readShort(source, (frame * sourceChannels + 1) * 2);
                writeShort(out, frame * 4, left);
                writeShort(out, frame * 4 + 2, right);
            }
        }
        return out;
    }

    private static short readShort(byte[] data, int offset) {
        return (short) (((data[offset + 1]) << 8) | (data[offset] & 0xff));
    }

    private static void writeShort(byte[] data, int offset, short value) {
        data[offset] = (byte) (value & 0xff);
        data[offset + 1] = (byte) ((value >>> 8) & 0xff);
    }

    private static final class PcmPacket {
        final byte[] data;
        final long ptsUs;
        final boolean eos;

        PcmPacket(byte[] data, long ptsUs, boolean eos) {
            this.data = data;
            this.ptsUs = ptsUs;
            this.eos = eos;
        }
    }

    public static final class Result {
        public final File file;
        public final AudioAnalysis analysis;
        public final long durationUs;
        public final MediaFormat format;

        Result(File file, AudioAnalysis analysis, long durationUs, MediaFormat format) {
            this.file = file;
            this.analysis = analysis;
            this.durationUs = durationUs;
            this.format = format;
        }
    }
}
