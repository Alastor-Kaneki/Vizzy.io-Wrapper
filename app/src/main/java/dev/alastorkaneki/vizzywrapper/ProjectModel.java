package dev.alastorkaneki.vizzywrapper;

import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Serializable project graph for the native editor. */
public final class ProjectModel {
    public static final int FORMAT_VERSION = 2;

    public String id = UUID.randomUUID().toString();
    public String name = "Untitled project";
    public int canvasWidth = 1920;
    public int canvasHeight = 1080;
    public int frameRate = 30;
    public long durationMs = 30_000L;
    public int backgroundColor = Color.BLACK;
    public int backgroundColor2 = Color.rgb(28, 0, 42);
    public boolean gradientBackground = true;
    public String audioUri = "";
    public int exportBitrate = 24_000_000;
    public String exportCodec = "video/avc";
    public int audioBitrate = 320_000;
    public final ArrayList<Layer> layers = new ArrayList<>();
    public final ArrayList<LrcLine> lyrics = new ArrayList<>();

    public ProjectModel() {
        Layer spectrum = Layer.create(LayerType.SPECTRUM);
        spectrum.name = "Spectrum";
        spectrum.y = 0.76f;
        spectrum.width = 0.82f;
        spectrum.height = 0.28f;
        spectrum.color = Color.rgb(198, 91, 255);
        spectrum.color2 = Color.rgb(115, 47, 255);
        spectrum.audioGain = 1.25f;
        layers.add(spectrum);

        Layer title = Layer.create(LayerType.TEXT);
        title.name = "Title";
        title.text = "VIZZY NATIVE";
        title.y = 0.25f;
        title.textSize = 0.075f;
        title.color = Color.WHITE;
        layers.add(title);
    }

    public static ProjectModel empty() {
        ProjectModel model = new ProjectModel();
        model.layers.clear();
        return model;
    }

    public Layer findLayer(String layerId) {
        for (Layer layer : layers) if (layer.id.equals(layerId)) return layer;
        return null;
    }

    public LrcLine lyricAt(long timeMs) {
        if (lyrics.isEmpty()) return null;
        int low = 0;
        int high = lyrics.size() - 1;
        int result = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (lyrics.get(mid).timeMs <= timeMs) {
                result = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return result >= 0 ? lyrics.get(result) : null;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject out = new JSONObject();
        out.put("formatVersion", FORMAT_VERSION);
        out.put("id", id);
        out.put("name", name);
        out.put("canvasWidth", canvasWidth);
        out.put("canvasHeight", canvasHeight);
        out.put("frameRate", frameRate);
        out.put("durationMs", durationMs);
        out.put("backgroundColor", backgroundColor);
        out.put("backgroundColor2", backgroundColor2);
        out.put("gradientBackground", gradientBackground);
        out.put("audioUri", audioUri);
        out.put("exportBitrate", exportBitrate);
        out.put("exportCodec", exportCodec);
        out.put("audioBitrate", audioBitrate);
        JSONArray layerArray = new JSONArray();
        for (Layer layer : layers) layerArray.put(layer.toJson());
        out.put("layers", layerArray);
        JSONArray lyricArray = new JSONArray();
        for (LrcLine line : lyrics) lyricArray.put(line.toJson());
        out.put("lyrics", lyricArray);
        return out;
    }

    public static ProjectModel fromJson(JSONObject json) throws JSONException {
        ProjectModel out = ProjectModel.empty();
        out.id = json.optString("id", UUID.randomUUID().toString());
        out.name = json.optString("name", "Untitled project");
        out.canvasWidth = Math.max(64, json.optInt("canvasWidth", 1920));
        out.canvasHeight = Math.max(64, json.optInt("canvasHeight", 1080));
        out.frameRate = Math.max(1, json.optInt("frameRate", 30));
        out.durationMs = Math.max(1_000L, json.optLong("durationMs", 30_000L));
        out.backgroundColor = json.optInt("backgroundColor", Color.BLACK);
        out.backgroundColor2 = json.optInt("backgroundColor2", Color.rgb(28, 0, 42));
        out.gradientBackground = json.optBoolean("gradientBackground", true);
        out.audioUri = json.optString("audioUri", "");
        out.exportBitrate = Math.max(100_000, json.optInt("exportBitrate", 24_000_000));
        out.exportCodec = json.optString("exportCodec", "video/avc");
        out.audioBitrate = Math.max(64_000, json.optInt("audioBitrate", 320_000));

        JSONArray layers = json.optJSONArray("layers");
        if (layers != null) {
            for (int i = 0; i < layers.length(); i++) {
                JSONObject item = layers.optJSONObject(i);
                if (item != null) out.layers.add(Layer.fromJson(item));
            }
        }
        JSONArray lyrics = json.optJSONArray("lyrics");
        if (lyrics != null) {
            for (int i = 0; i < lyrics.length(); i++) {
                JSONObject item = lyrics.optJSONObject(i);
                if (item != null) out.lyrics.add(LrcLine.fromJson(item));
            }
            out.lyrics.sort(Comparator.comparingLong(line -> line.timeMs));
        }
        return out;
    }

    public enum LayerType {
        IMAGE,
        VIDEO,
        TEXT,
        LYRICS,
        SPECTRUM,
        WAVEFORM,
        PARTICLES,
        CONFETTI,
        SHAPE,
        SHADER,
        CAMERA;

        public String label() {
            String lower = name().toLowerCase(Locale.US).replace('_', ' ');
            return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
        }
    }

    public enum BlendModeName {
        NORMAL, ADD, SCREEN, MULTIPLY, OVERLAY
    }

    public enum EffectType {
        GLOW,
        BLUR,
        GLITCH,
        VHS,
        VIGNETTE,
        CAMERA_SHAKE,
        FISHEYE,
        KALEIDOSCOPE,
        CHROMA_KEY,
        COLORIZE,
        MOTION_BLUR,
        GOD_RAYS,
        SHARPEN
    }

    public static final class Layer {
        public String id = UUID.randomUUID().toString();
        public String name = "Layer";
        public LayerType type = LayerType.IMAGE;
        public boolean visible = true;
        public boolean solo = false;
        public boolean locked = false;
        public long startMs = 0L;
        public long endMs = Long.MAX_VALUE;
        public float x = 0.5f;
        public float y = 0.5f;
        public float width = 0.65f;
        public float height = 0.65f;
        public float rotation = 0f;
        public float opacity = 1f;
        public int color = Color.WHITE;
        public int color2 = Color.rgb(152, 62, 220);
        public String text = "";
        public String uri = "";
        public String fontFamily = "sans-serif";
        public float textSize = 0.065f;
        public int alignment = 1;
        public int spectrumBars = 64;
        public int spectrumStyle = 0;
        public float strokeWidth = 0.012f;
        public int particleCount = 120;
        public float speed = 1f;
        public float audioGain = 1f;
        public int audioBand = -1;
        public BlendModeName blendMode = BlendModeName.NORMAL;
        public final ArrayList<Keyframe> keyframes = new ArrayList<>();
        public final ArrayList<Effect> effects = new ArrayList<>();

        public static Layer create(LayerType type) {
            Layer layer = new Layer();
            layer.type = type;
            layer.name = type.label();
            switch (type) {
                case TEXT, LYRICS -> {
                    layer.width = 0.9f;
                    layer.height = 0.25f;
                    layer.text = type == LayerType.TEXT ? "Text" : "{lyrics}";
                }
                case SPECTRUM, WAVEFORM -> {
                    layer.width = 0.85f;
                    layer.height = 0.35f;
                }
                case PARTICLES, CONFETTI, SHADER -> {
                    layer.width = 1f;
                    layer.height = 1f;
                }
                case SHAPE -> {
                    layer.width = 0.35f;
                    layer.height = 0.35f;
                }
                default -> { }
            }
            return layer;
        }

        public boolean activeAt(long timeMs) {
            return visible && timeMs >= startMs && timeMs <= endMs;
        }

        public float valueAt(String property, long timeMs, float baseValue) {
            ArrayList<Keyframe> matching = new ArrayList<>();
            for (Keyframe k : keyframes) if (k.property.equals(property)) matching.add(k);
            if (matching.isEmpty()) return baseValue;
            matching.sort(Comparator.comparingLong(k -> k.timeMs));
            if (timeMs <= matching.get(0).timeMs) return matching.get(0).value;
            if (timeMs >= matching.get(matching.size() - 1).timeMs) return matching.get(matching.size() - 1).value;
            Keyframe left = matching.get(0);
            Keyframe right = matching.get(matching.size() - 1);
            for (int i = 0; i < matching.size() - 1; i++) {
                if (timeMs >= matching.get(i).timeMs && timeMs <= matching.get(i + 1).timeMs) {
                    left = matching.get(i);
                    right = matching.get(i + 1);
                    break;
                }
            }
            float t = (timeMs - left.timeMs) / (float) Math.max(1L, right.timeMs - left.timeMs);
            t = applyEasing(t, right.easing);
            return left.value + (right.value - left.value) * t;
        }

        private static float applyEasing(float t, String easing) {
            return switch (easing) {
                case "easeIn" -> t * t;
                case "easeOut" -> 1f - (1f - t) * (1f - t);
                case "easeInOut" -> t < 0.5f ? 2f * t * t : 1f - (float) Math.pow(-2f * t + 2f, 2) / 2f;
                case "step" -> t < 1f ? 0f : 1f;
                default -> t;
            };
        }

        public JSONObject toJson() throws JSONException {
            JSONObject out = new JSONObject();
            out.put("id", id);
            out.put("name", name);
            out.put("type", type.name());
            out.put("visible", visible);
            out.put("solo", solo);
            out.put("locked", locked);
            out.put("startMs", startMs);
            out.put("endMs", endMs);
            out.put("x", x);
            out.put("y", y);
            out.put("width", width);
            out.put("height", height);
            out.put("rotation", rotation);
            out.put("opacity", opacity);
            out.put("color", color);
            out.put("color2", color2);
            out.put("text", text);
            out.put("uri", uri);
            out.put("fontFamily", fontFamily);
            out.put("textSize", textSize);
            out.put("alignment", alignment);
            out.put("spectrumBars", spectrumBars);
            out.put("spectrumStyle", spectrumStyle);
            out.put("strokeWidth", strokeWidth);
            out.put("particleCount", particleCount);
            out.put("speed", speed);
            out.put("audioGain", audioGain);
            out.put("audioBand", audioBand);
            out.put("blendMode", blendMode.name());
            JSONArray keyframeArray = new JSONArray();
            for (Keyframe keyframe : keyframes) keyframeArray.put(keyframe.toJson());
            out.put("keyframes", keyframeArray);
            JSONArray effectArray = new JSONArray();
            for (Effect effect : effects) effectArray.put(effect.toJson());
            out.put("effects", effectArray);
            return out;
        }

        public static Layer fromJson(JSONObject json) throws JSONException {
            Layer out = new Layer();
            out.id = json.optString("id", UUID.randomUUID().toString());
            out.name = json.optString("name", "Layer");
            try { out.type = LayerType.valueOf(json.optString("type", "IMAGE")); }
            catch (IllegalArgumentException ignored) { out.type = LayerType.IMAGE; }
            out.visible = json.optBoolean("visible", true);
            out.solo = json.optBoolean("solo", false);
            out.locked = json.optBoolean("locked", false);
            out.startMs = Math.max(0L, json.optLong("startMs", 0L));
            out.endMs = json.optLong("endMs", Long.MAX_VALUE);
            out.x = (float) json.optDouble("x", 0.5);
            out.y = (float) json.optDouble("y", 0.5);
            out.width = (float) json.optDouble("width", 0.65);
            out.height = (float) json.optDouble("height", 0.65);
            out.rotation = (float) json.optDouble("rotation", 0.0);
            out.opacity = (float) json.optDouble("opacity", 1.0);
            out.color = json.optInt("color", Color.WHITE);
            out.color2 = json.optInt("color2", Color.rgb(152, 62, 220));
            out.text = json.optString("text", "");
            out.uri = json.optString("uri", "");
            out.fontFamily = json.optString("fontFamily", "sans-serif");
            out.textSize = (float) json.optDouble("textSize", 0.065);
            out.alignment = json.optInt("alignment", 1);
            out.spectrumBars = Math.max(4, json.optInt("spectrumBars", 64));
            out.spectrumStyle = json.optInt("spectrumStyle", 0);
            out.strokeWidth = (float) json.optDouble("strokeWidth", 0.012);
            out.particleCount = Math.max(1, json.optInt("particleCount", 120));
            out.speed = (float) json.optDouble("speed", 1.0);
            out.audioGain = (float) json.optDouble("audioGain", 1.0);
            out.audioBand = json.optInt("audioBand", -1);
            try { out.blendMode = BlendModeName.valueOf(json.optString("blendMode", "NORMAL")); }
            catch (IllegalArgumentException ignored) { out.blendMode = BlendModeName.NORMAL; }
            JSONArray keys = json.optJSONArray("keyframes");
            if (keys != null) {
                for (int i = 0; i < keys.length(); i++) {
                    JSONObject item = keys.optJSONObject(i);
                    if (item != null) out.keyframes.add(Keyframe.fromJson(item));
                }
            }
            JSONArray fx = json.optJSONArray("effects");
            if (fx != null) {
                for (int i = 0; i < fx.length(); i++) {
                    JSONObject item = fx.optJSONObject(i);
                    if (item != null) out.effects.add(Effect.fromJson(item));
                }
            }
            return out;
        }
    }

    public static final class Keyframe {
        public String property = "opacity";
        public long timeMs;
        public float value;
        public String easing = "linear";

        public JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("property", property)
                    .put("timeMs", timeMs)
                    .put("value", value)
                    .put("easing", easing);
        }

        public static Keyframe fromJson(JSONObject json) {
            Keyframe out = new Keyframe();
            out.property = json.optString("property", "opacity");
            out.timeMs = Math.max(0L, json.optLong("timeMs", 0L));
            out.value = (float) json.optDouble("value", 0.0);
            out.easing = json.optString("easing", "linear");
            return out;
        }
    }

    public static final class Effect {
        public EffectType type = EffectType.GLOW;
        public float intensity = 0.5f;
        public float amount = 0.5f;
        public int color = Color.WHITE;
        public boolean enabled = true;

        public JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("type", type.name())
                    .put("intensity", intensity)
                    .put("amount", amount)
                    .put("color", color)
                    .put("enabled", enabled);
        }

        public static Effect fromJson(JSONObject json) {
            Effect out = new Effect();
            try { out.type = EffectType.valueOf(json.optString("type", "GLOW")); }
            catch (IllegalArgumentException ignored) { out.type = EffectType.GLOW; }
            out.intensity = (float) json.optDouble("intensity", 0.5);
            out.amount = (float) json.optDouble("amount", 0.5);
            out.color = json.optInt("color", Color.WHITE);
            out.enabled = json.optBoolean("enabled", true);
            return out;
        }
    }

    public static final class LrcWord {
        public long timeMs;
        public String text = "";

        public JSONObject toJson() throws JSONException {
            return new JSONObject().put("timeMs", timeMs).put("text", text);
        }

        public static LrcWord fromJson(JSONObject json) {
            LrcWord out = new LrcWord();
            out.timeMs = Math.max(0L, json.optLong("timeMs", 0L));
            out.text = json.optString("text", "");
            return out;
        }
    }

    public static final class LrcLine {
        public long timeMs;
        public long endMs = Long.MAX_VALUE;
        public String text = "";
        public final ArrayList<LrcWord> words = new ArrayList<>();

        public JSONObject toJson() throws JSONException {
            JSONObject out = new JSONObject()
                    .put("timeMs", timeMs)
                    .put("endMs", endMs)
                    .put("text", text);
            JSONArray wordArray = new JSONArray();
            for (LrcWord word : words) wordArray.put(word.toJson());
            out.put("words", wordArray);
            return out;
        }

        public static LrcLine fromJson(JSONObject json) {
            LrcLine out = new LrcLine();
            out.timeMs = Math.max(0L, json.optLong("timeMs", 0L));
            out.endMs = json.optLong("endMs", Long.MAX_VALUE);
            out.text = json.optString("text", "");
            JSONArray words = json.optJSONArray("words");
            if (words != null) {
                for (int i = 0; i < words.length(); i++) {
                    JSONObject item = words.optJSONObject(i);
                    if (item != null) out.words.add(LrcWord.fromJson(item));
                }
            }
            return out;
        }
    }
}
