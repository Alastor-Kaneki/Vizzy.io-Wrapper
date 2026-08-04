package dev.alastorkaneki.vizzywrapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** LRC and enhanced-LRC parser supporting offsets, multiple timestamps and word timing. */
public final class LrcParser {
    private static final Pattern LINE_TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern WORD_TIME = Pattern.compile("<(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?>");
    private static final Pattern OFFSET = Pattern.compile("\\[offset:([+-]?\\d+)]", Pattern.CASE_INSENSITIVE);

    private LrcParser() {}

    public static Result parse(InputStream stream) throws IOException {
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) text.append(line).append('\n');
        }
        return parse(text.toString());
    }

    public static Result parse(String raw) {
        Result result = new Result();
        long offsetMs = 0L;
        String[] rows = raw.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (String row : rows) {
            Matcher offsetMatcher = OFFSET.matcher(row);
            if (offsetMatcher.find()) {
                try { offsetMs = Long.parseLong(offsetMatcher.group(1)); }
                catch (NumberFormatException ignored) { }
                continue;
            }
            readMetadata(row, result);
            Matcher matcher = LINE_TIME.matcher(row);
            ArrayList<Long> times = new ArrayList<>();
            int contentStart = 0;
            while (matcher.find()) {
                times.add(toMs(matcher.group(1), matcher.group(2), matcher.group(3)));
                contentStart = matcher.end();
            }
            if (times.isEmpty()) continue;
            String content = row.substring(Math.min(contentStart, row.length())).trim();
            for (long timestamp : times) {
                ProjectModel.LrcLine line = new ProjectModel.LrcLine();
                line.timeMs = Math.max(0L, timestamp + offsetMs);
                parseEnhancedWords(content, line, offsetMs);
                if (line.text.isEmpty()) line.text = stripWordTags(content).trim();
                result.lines.add(line);
            }
        }
        result.lines.sort(Comparator.comparingLong(line -> line.timeMs));
        for (int i = 0; i < result.lines.size(); i++) {
            ProjectModel.LrcLine line = result.lines.get(i);
            line.endMs = i + 1 < result.lines.size() ? result.lines.get(i + 1).timeMs : Long.MAX_VALUE;
        }
        return result;
    }

    private static void readMetadata(String row, Result result) {
        String lower = row.toLowerCase(Locale.US);
        if (lower.startsWith("[ar:")) result.artist = tagValue(row);
        else if (lower.startsWith("[ti:")) result.title = tagValue(row);
        else if (lower.startsWith("[al:")) result.album = tagValue(row);
        else if (lower.startsWith("[by:")) result.author = tagValue(row);
        else if (lower.startsWith("[re:")) result.editor = tagValue(row);
        else if (lower.startsWith("[ve:")) result.version = tagValue(row);
    }

    private static String tagValue(String row) {
        int colon = row.indexOf(':');
        int end = row.lastIndexOf(']');
        return colon >= 0 && end > colon ? row.substring(colon + 1, end).trim() : "";
    }

    private static void parseEnhancedWords(String content, ProjectModel.LrcLine line, long offsetMs) {
        Matcher matcher = WORD_TIME.matcher(content);
        int previousEnd = 0;
        ProjectModel.LrcWord previousWord = null;
        StringBuilder plain = new StringBuilder();
        while (matcher.find()) {
            String between = content.substring(previousEnd, matcher.start());
            if (previousWord != null) {
                previousWord.text = between;
                plain.append(between);
            } else if (!between.isEmpty()) {
                plain.append(between);
            }
            ProjectModel.LrcWord word = new ProjectModel.LrcWord();
            word.timeMs = Math.max(0L, toMs(matcher.group(1), matcher.group(2), matcher.group(3)) + offsetMs);
            line.words.add(word);
            previousWord = word;
            previousEnd = matcher.end();
        }
        if (previousWord != null) {
            String tail = content.substring(previousEnd);
            previousWord.text = tail;
            plain.append(tail);
            line.text = plain.toString().trim();
        } else {
            line.text = content.trim();
        }
    }

    private static String stripWordTags(String value) {
        return WORD_TIME.matcher(value).replaceAll("");
    }

    private static long toMs(String minutes, String seconds, String fraction) {
        long min = parseLong(minutes);
        long sec = parseLong(seconds);
        long frac = parseLong(fraction);
        long fracMs;
        int digits = fraction == null ? 0 : fraction.length();
        if (digits <= 0) fracMs = 0L;
        else if (digits == 1) fracMs = frac * 100L;
        else if (digits == 2) fracMs = frac * 10L;
        else fracMs = frac;
        return min * 60_000L + sec * 1_000L + fracMs;
    }

    private static long parseLong(String value) {
        if (value == null || value.isEmpty()) return 0L;
        try { return Long.parseLong(value); }
        catch (NumberFormatException ignored) { return 0L; }
    }

    public static final class Result {
        public final ArrayList<ProjectModel.LrcLine> lines = new ArrayList<>();
        public String artist = "";
        public String title = "";
        public String album = "";
        public String author = "";
        public String editor = "";
        public String version = "";
    }
}
