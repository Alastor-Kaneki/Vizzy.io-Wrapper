package dev.alastorkaneki.vizzywrapper;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Range;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Queries installed Android encoders instead of imposing arbitrary resolution or bitrate limits. */
public final class ExportCapabilities {
    private ExportCapabilities() {}

    public static Capability best(String mime) {
        MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
        Capability best = null;
        for (MediaCodecInfo info : list.getCodecInfos()) {
            if (!info.isEncoder()) continue;
            boolean supports = false;
            for (String type : info.getSupportedTypes()) if (type.equalsIgnoreCase(mime)) supports = true;
            if (!supports) continue;
            try {
                MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(mime);
                MediaCodecInfo.VideoCapabilities video = caps.getVideoCapabilities();
                Capability candidate = new Capability(
                        info.getName(), mime,
                        video.getSupportedWidths(), video.getSupportedHeights(),
                        video.getBitrateRange(), video.getSupportedFrameRates(),
                        isHardwareAccelerated(info), isSoftwareOnly(info)
                );
                if (best == null || score(candidate) > score(best)) best = candidate;
            } catch (IllegalArgumentException ignored) { }
        }
        return best;
    }

    public static List<Capability> all(String mime) {
        ArrayList<Capability> out = new ArrayList<>();
        MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
        for (MediaCodecInfo info : list.getCodecInfos()) {
            if (!info.isEncoder()) continue;
            boolean supports = false;
            for (String type : info.getSupportedTypes()) if (type.equalsIgnoreCase(mime)) supports = true;
            if (!supports) continue;
            try {
                MediaCodecInfo.VideoCapabilities video = info.getCapabilitiesForType(mime).getVideoCapabilities();
                out.add(new Capability(info.getName(), mime,
                        video.getSupportedWidths(), video.getSupportedHeights(),
                        video.getBitrateRange(), video.getSupportedFrameRates(),
                        isHardwareAccelerated(info), isSoftwareOnly(info)));
            } catch (IllegalArgumentException ignored) { }
        }
        out.sort(Comparator.comparingLong(ExportCapabilities::score).reversed());
        return out;
    }

    private static boolean isHardwareAccelerated(MediaCodecInfo info) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isHardwareAccelerated();
    }

    private static boolean isSoftwareOnly(MediaCodecInfo info) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isSoftwareOnly();
    }

    private static long score(Capability cap) {
        long area = (long) cap.widths.getUpper() * (long) cap.heights.getUpper();
        long hardware = cap.hardware ? 1L << 60 : 0L;
        return hardware + area;
    }

    public static String describe(String mime) {
        Capability cap = best(mime);
        if (cap == null) return "No encoder installed";
        return cap.name + " • up to " + cap.widths.getUpper() + "×" + cap.heights.getUpper()
                + " • " + cap.frameRates.getUpper().intValue() + " FPS"
                + " • " + (cap.bitrates.getUpper() / 1_000_000) + " Mbps";
    }

    public static final class Capability {
        public final String name;
        public final String mime;
        public final Range<Integer> widths;
        public final Range<Integer> heights;
        public final Range<Integer> bitrates;
        public final Range<Integer> frameRates;
        public final boolean hardware;
        public final boolean software;

        Capability(String name, String mime, Range<Integer> widths, Range<Integer> heights,
                   Range<Integer> bitrates, Range<Integer> frameRates, boolean hardware, boolean software) {
            this.name = name;
            this.mime = mime;
            this.widths = widths;
            this.heights = heights;
            this.bitrates = bitrates;
            this.frameRates = frameRates;
            this.hardware = hardware;
            this.software = software;
        }

        public boolean supports(int width, int height, int fps) {
            return widths.contains(width) && heights.contains(height) && frameRates.contains(fps);
        }

        public int clampBitrate(int bitrate) {
            return Math.max(bitrates.getLower(), Math.min(bitrates.getUpper(), bitrate));
        }
    }
}
