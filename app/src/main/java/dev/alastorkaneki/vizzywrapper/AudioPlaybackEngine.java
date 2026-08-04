package dev.alastorkaneki.vizzywrapper;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.audiofx.Visualizer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.util.Arrays;

/** Native audio playback with real-time FFT capture for preview rendering. */
public final class AudioPlaybackEngine {
    public interface Listener {
        void onPrepared(long durationMs);
        void onProgress(long positionMs, boolean playing);
        void onSpectrum(float[] bands);
        void onError(String message);
    }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float[] spectrum = new float[64];
    private MediaPlayer player;
    private Visualizer visualizer;
    private boolean released;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (released) return;
            MediaPlayer current = player;
            if (current != null) {
                try {
                    listener.onProgress(current.getCurrentPosition(), current.isPlaying());
                } catch (IllegalStateException ignored) { }
            }
            main.postDelayed(this, 33L);
        }
    };

    public AudioPlaybackEngine(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        main.post(ticker);
    }

    public void load(String uriString) {
        releasePlayerOnly();
        if (uriString == null || uriString.trim().isEmpty()) {
            listener.onError("Choose an audio file first.");
            return;
        }
        MediaPlayer next = new MediaPlayer();
        player = next;
        next.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        next.setOnPreparedListener(mp -> {
            setupVisualizer(mp.getAudioSessionId());
            listener.onPrepared(mp.getDuration());
        });
        next.setOnCompletionListener(mp -> listener.onProgress(mp.getDuration(), false));
        next.setOnErrorListener((mp, what, extra) -> {
            listener.onError("Audio playback failed (" + what + "/" + extra + ").");
            return true;
        });
        try {
            next.setDataSource(context, Uri.parse(uriString));
            next.prepareAsync();
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            listener.onError("Unable to open audio: " + e.getMessage());
            releasePlayerOnly();
        }
    }

    private void setupVisualizer(int sessionId) {
        if (visualizer != null) {
            try { visualizer.release(); } catch (RuntimeException ignored) { }
            visualizer = null;
        }
        try {
            Visualizer next = new Visualizer(sessionId);
            int[] range = Visualizer.getCaptureSizeRange();
            int size = Math.min(2048, range[1]);
            size = Integer.highestOneBit(Math.max(range[0], size));
            next.setCaptureSize(size);
            int rate = Math.min(Visualizer.getMaxCaptureRate(), 20_000);
            next.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int samplingRate) { }

                @Override public void onFftDataCapture(Visualizer visualizer, byte[] fft, int samplingRate) {
                    mapFft(fft);
                    listener.onSpectrum(Arrays.copyOf(spectrum, spectrum.length));
                }
            }, rate, false, true);
            next.setEnabled(true);
            visualizer = next;
        } catch (RuntimeException ignored) {
            Arrays.fill(spectrum, 0f);
            listener.onSpectrum(Arrays.copyOf(spectrum, spectrum.length));
        }
    }

    private void mapFft(byte[] fft) {
        Arrays.fill(spectrum, 0f);
        if (fft == null || fft.length < 4) return;
        int bins = fft.length / 2;
        for (int band = 0; band < spectrum.length; band++) {
            double startRatio = band / (double) spectrum.length;
            double endRatio = (band + 1) / (double) spectrum.length;
            int start = Math.max(1, (int) Math.pow(bins - 1, startRatio));
            int end = Math.max(start + 1, (int) Math.pow(bins - 1, endRatio));
            end = Math.min(bins, end);
            float max = 0f;
            for (int i = start; i < end; i++) {
                int realIndex = i * 2;
                if (realIndex + 1 >= fft.length) break;
                float re = fft[realIndex];
                float im = fft[realIndex + 1];
                float magnitude = (float) Math.sqrt(re * re + im * im);
                if (magnitude > max) max = magnitude;
            }
            float normalized = (float) (Math.log10(1f + max) / 2.25f);
            spectrum[band] = Math.max(0f, Math.min(1f, normalized));
        }
    }

    public void toggle() {
        MediaPlayer current = player;
        if (current == null) return;
        try {
            if (current.isPlaying()) current.pause();
            else current.start();
        } catch (IllegalStateException ignored) { }
    }

    public void pause() {
        MediaPlayer current = player;
        if (current == null) return;
        try { if (current.isPlaying()) current.pause(); }
        catch (IllegalStateException ignored) { }
    }

    public void seekTo(long positionMs) {
        MediaPlayer current = player;
        if (current == null) return;
        try { current.seekTo((int) Math.max(0L, Math.min(Integer.MAX_VALUE, positionMs))); }
        catch (IllegalStateException ignored) { }
    }

    public boolean isPlaying() {
        MediaPlayer current = player;
        if (current == null) return false;
        try { return current.isPlaying(); }
        catch (IllegalStateException ignored) { return false; }
    }

    public long position() {
        MediaPlayer current = player;
        if (current == null) return 0L;
        try { return current.getCurrentPosition(); }
        catch (IllegalStateException ignored) { return 0L; }
    }

    public float[] spectrum() {
        return Arrays.copyOf(spectrum, spectrum.length);
    }

    private void releasePlayerOnly() {
        if (visualizer != null) {
            try { visualizer.setEnabled(false); } catch (RuntimeException ignored) { }
            try { visualizer.release(); } catch (RuntimeException ignored) { }
            visualizer = null;
        }
        if (player != null) {
            try { player.reset(); } catch (RuntimeException ignored) { }
            try { player.release(); } catch (RuntimeException ignored) { }
            player = null;
        }
    }

    public void release() {
        released = true;
        main.removeCallbacks(ticker);
        releasePlayerOnly();
    }
}
