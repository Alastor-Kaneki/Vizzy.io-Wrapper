package dev.alastorkaneki.vizzywrapper;

import java.util.ArrayList;
import java.util.Arrays;

/** Sparse timestamped spectrum frames produced while audio is decoded for export. */
public final class AudioAnalysis {
    private final ArrayList<Long> timesUs = new ArrayList<>();
    private final ArrayList<float[]> bands = new ArrayList<>();

    public void add(long timeUs, float[] values) {
        timesUs.add(Math.max(0L, timeUs));
        bands.add(Arrays.copyOf(values, values.length));
    }

    public int size() {
        return timesUs.size();
    }

    public float[] at(long timeUs) {
        if (timesUs.isEmpty()) return new float[64];
        int low = 0;
        int high = timesUs.size() - 1;
        int result = 0;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (timesUs.get(mid) <= timeUs) {
                result = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return bands.get(result);
    }

    /** Streaming analyzer using 2048-sample windows and logarithmic 64-band FFT output. */
    public static final class Builder {
        private static final int FFT_SIZE = 2048;
        private final int sampleRate;
        private final int frameRate;
        private final AudioAnalysis output = new AudioAnalysis();
        private final float[] mono = new float[FFT_SIZE];
        private int fill;
        private long totalSamples;
        private long nextCaptureSample;

        public Builder(int sampleRate, int frameRate) {
            this.sampleRate = Math.max(8_000, sampleRate);
            this.frameRate = Math.max(1, frameRate);
        }

        public void consumePcm16(byte[] pcm, int offset, int length, int channels) {
            int channelCount = Math.max(1, channels);
            int frameBytes = channelCount * 2;
            int end = offset + length - frameBytes + 1;
            for (int index = offset; index < end; index += frameBytes) {
                float sum = 0f;
                for (int channel = 0; channel < channelCount; channel++) {
                    int pos = index + channel * 2;
                    int lo = pcm[pos] & 0xff;
                    int hi = pcm[pos + 1];
                    short value = (short) ((hi << 8) | lo);
                    sum += value / 32768f;
                }
                mono[fill++] = sum / channelCount;
                totalSamples++;
                if (fill == FFT_SIZE) {
                    long centerSample = totalSamples - FFT_SIZE / 2L;
                    if (centerSample >= nextCaptureSample) {
                        output.add(centerSample * 1_000_000L / sampleRate, analyze(mono));
                        nextCaptureSample = centerSample + sampleRate / frameRate;
                    }
                    System.arraycopy(mono, FFT_SIZE / 2, mono, 0, FFT_SIZE / 2);
                    fill = FFT_SIZE / 2;
                }
            }
        }

        public AudioAnalysis build() {
            if (output.size() == 0) output.add(0L, new float[64]);
            return output;
        }

        private static float[] analyze(float[] samples) {
            int n = samples.length;
            double[] real = new double[n];
            double[] imag = new double[n];
            for (int i = 0; i < n; i++) {
                double window = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (n - 1));
                real[i] = samples[i] * window;
            }
            fft(real, imag);
            float[] out = new float[64];
            int maxBin = n / 2;
            for (int band = 0; band < out.length; band++) {
                double startRatio = band / (double) out.length;
                double endRatio = (band + 1) / (double) out.length;
                int start = Math.max(1, (int) Math.pow(maxBin - 1, startRatio));
                int end = Math.max(start + 1, (int) Math.pow(maxBin - 1, endRatio));
                end = Math.min(maxBin, end);
                double peak = 0.0;
                for (int bin = start; bin < end; bin++) {
                    double magnitude = Math.hypot(real[bin], imag[bin]) / n;
                    peak = Math.max(peak, magnitude);
                }
                out[band] = (float) Math.max(0.0, Math.min(1.0, Math.log10(1.0 + peak * 80.0)));
            }
            return out;
        }

        private static void fft(double[] real, double[] imag) {
            int n = real.length;
            int j = 0;
            for (int i = 1; i < n; i++) {
                int bit = n >> 1;
                while ((j & bit) != 0) {
                    j ^= bit;
                    bit >>= 1;
                }
                j ^= bit;
                if (i < j) {
                    double tempR = real[i]; real[i] = real[j]; real[j] = tempR;
                    double tempI = imag[i]; imag[i] = imag[j]; imag[j] = tempI;
                }
            }
            for (int len = 2; len <= n; len <<= 1) {
                double angle = -2.0 * Math.PI / len;
                double wLenR = Math.cos(angle);
                double wLenI = Math.sin(angle);
                for (int i = 0; i < n; i += len) {
                    double wR = 1.0;
                    double wI = 0.0;
                    for (int k = 0; k < len / 2; k++) {
                        int even = i + k;
                        int odd = even + len / 2;
                        double oddR = real[odd] * wR - imag[odd] * wI;
                        double oddI = real[odd] * wI + imag[odd] * wR;
                        real[odd] = real[even] - oddR;
                        imag[odd] = imag[even] - oddI;
                        real[even] += oddR;
                        imag[even] += oddI;
                        double nextR = wR * wLenR - wI * wLenI;
                        wI = wR * wLenI + wI * wLenR;
                        wR = nextR;
                    }
                }
            }
        }
    }
}
