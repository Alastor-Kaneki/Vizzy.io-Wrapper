package dev.alastorkaneki.vizzywrapper;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlendMode;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ImageDecoder;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/** Canvas renderer shared by interactive preview and offline export. */
public final class NativeRenderer {
    private static final float[] EMPTY_SPECTRUM = new float[64];

    private NativeRenderer() {}

    public static void render(
            Canvas canvas,
            RectF viewport,
            ProjectModel project,
            long timeMs,
            float[] spectrum,
            ContentResolver resolver,
            AssetCache cache,
            boolean export
    ) {
        if (project == null) return;
        float[] bands = spectrum == null ? EMPTY_SPECTRUM : spectrum;
        drawBackground(canvas, viewport, project);

        boolean anySolo = false;
        for (ProjectModel.Layer layer : project.layers) if (layer.solo && layer.activeAt(timeMs)) anySolo = true;

        for (ProjectModel.Layer layer : project.layers) {
            if (!layer.activeAt(timeMs)) continue;
            if (anySolo && !layer.solo) continue;
            drawLayer(canvas, viewport, project, layer, timeMs, bands, resolver, cache, export);
        }
    }

    private static void drawBackground(Canvas canvas, RectF viewport, ProjectModel project) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        if (project.gradientBackground) {
            paint.setShader(new LinearGradient(
                    viewport.left, viewport.top, viewport.right, viewport.bottom,
                    project.backgroundColor, project.backgroundColor2, Shader.TileMode.CLAMP
            ));
        } else {
            paint.setColor(project.backgroundColor);
        }
        canvas.drawRect(viewport, paint);
        paint.setShader(null);
    }

    private static void drawLayer(
            Canvas canvas,
            RectF viewport,
            ProjectModel project,
            ProjectModel.Layer layer,
            long timeMs,
            float[] spectrum,
            ContentResolver resolver,
            AssetCache cache,
            boolean export
    ) {
        float audio = bandValue(layer, spectrum);
        float x = layer.valueAt("x", timeMs, layer.x);
        float y = layer.valueAt("y", timeMs, layer.y);
        float width = Math.max(0.001f, layer.valueAt("width", timeMs, layer.width));
        float height = Math.max(0.001f, layer.valueAt("height", timeMs, layer.height));
        float rotation = layer.valueAt("rotation", timeMs, layer.rotation);
        float opacity = clamp(layer.valueAt("opacity", timeMs, layer.opacity), 0f, 1f);
        float pulse = 1f + audio * layer.audioGain * 0.18f;
        width *= pulse;
        height *= pulse;

        float cx = viewport.left + x * viewport.width();
        float cy = viewport.top + y * viewport.height();
        float w = width * viewport.width();
        float h = height * viewport.height();
        RectF localRect = new RectF(-w / 2f, -h / 2f, w / 2f, h / 2f);

        float shakeX = 0f;
        float shakeY = 0f;
        float glitch = 0f;
        for (ProjectModel.Effect effect : layer.effects) {
            if (!effect.enabled) continue;
            if (effect.type == ProjectModel.EffectType.CAMERA_SHAKE) {
                float phase = timeMs * 0.018f * (0.5f + effect.amount);
                shakeX += (float) Math.sin(phase * 1.7f) * viewport.width() * 0.012f * effect.intensity * (0.25f + audio);
                shakeY += (float) Math.cos(phase * 1.31f) * viewport.height() * 0.012f * effect.intensity * (0.25f + audio);
            } else if (effect.type == ProjectModel.EffectType.GLITCH) {
                glitch = Math.max(glitch, effect.intensity);
            }
        }

        int save = canvas.save();
        canvas.translate(cx + shakeX, cy + shakeY);
        canvas.rotate(rotation);

        Paint paint = basePaint(layer, opacity);
        applyColorEffects(layer, paint);

        if (glitch > 0.001f) {
            int glitchSave = canvas.saveLayer(localRect, null);
            canvas.save();
            canvas.translate(-w * 0.014f * glitch, 0f);
            Paint red = new Paint(paint);
            red.setColorFilter(new ColorMatrixColorFilter(colorOnlyMatrix(1f, 0.15f, 0.15f)));
            drawLayerBody(canvas, localRect, project, layer, timeMs, spectrum, resolver, cache, red, export);
            canvas.restore();
            canvas.save();
            canvas.translate(w * 0.014f * glitch, 0f);
            Paint cyan = new Paint(paint);
            cyan.setColorFilter(new ColorMatrixColorFilter(colorOnlyMatrix(0.15f, 0.9f, 1f)));
            drawLayerBody(canvas, localRect, project, layer, timeMs, spectrum, resolver, cache, cyan, export);
            canvas.restore();
            drawLayerBody(canvas, localRect, project, layer, timeMs, spectrum, resolver, cache, paint, export);
            canvas.restoreToCount(glitchSave);
        } else {
            drawLayerBody(canvas, localRect, project, layer, timeMs, spectrum, resolver, cache, paint, export);
        }

        drawOverlays(canvas, localRect, layer, timeMs, audio);
        canvas.restoreToCount(save);
    }

    private static Paint basePaint(ProjectModel.Layer layer, float opacity) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        paint.setAlpha(Math.round(opacity * 255f));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            paint.setBlendMode(switch (layer.blendMode) {
                case ADD -> BlendMode.PLUS;
                case SCREEN -> BlendMode.SCREEN;
                case MULTIPLY -> BlendMode.MULTIPLY;
                case OVERLAY -> BlendMode.OVERLAY;
                default -> BlendMode.SRC_OVER;
            });
        } else if (layer.blendMode == ProjectModel.BlendModeName.ADD) {
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        } else if (layer.blendMode == ProjectModel.BlendModeName.MULTIPLY) {
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.MULTIPLY));
        } else if (layer.blendMode == ProjectModel.BlendModeName.SCREEN) {
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        }
        for (ProjectModel.Effect effect : layer.effects) {
            if (effect.enabled && effect.type == ProjectModel.EffectType.GLOW) {
                paint.setShadowLayer(Math.max(1f, effect.amount * 32f), 0f, 0f, effect.color);
            }
            if (effect.enabled && effect.type == ProjectModel.EffectType.BLUR) {
                paint.setMaskFilter(new BlurMaskFilter(Math.max(0.5f, effect.amount * 18f), BlurMaskFilter.Blur.NORMAL));
            }
        }
        return paint;
    }

    private static void applyColorEffects(ProjectModel.Layer layer, Paint paint) {
        for (ProjectModel.Effect effect : layer.effects) {
            if (!effect.enabled) continue;
            if (effect.type == ProjectModel.EffectType.COLORIZE) {
                float r = Color.red(effect.color) / 255f;
                float g = Color.green(effect.color) / 255f;
                float b = Color.blue(effect.color) / 255f;
                ColorMatrix matrix = new ColorMatrix(new float[]{
                        r, 0, 0, 0, 0,
                        0, g, 0, 0, 0,
                        0, 0, b, 0, 0,
                        0, 0, 0, 1, 0
                });
                paint.setColorFilter(new ColorMatrixColorFilter(matrix));
            }
        }
    }

    private static ColorMatrix colorOnlyMatrix(float r, float g, float b) {
        return new ColorMatrix(new float[]{
                r, 0, 0, 0, 0,
                0, g, 0, 0, 0,
                0, 0, b, 0, 0,
                0, 0, 0, 1, 0
        });
    }

    private static void drawLayerBody(
            Canvas canvas,
            RectF rect,
            ProjectModel project,
            ProjectModel.Layer layer,
            long timeMs,
            float[] spectrum,
            ContentResolver resolver,
            AssetCache cache,
            Paint paint,
            boolean export
    ) {
        switch (layer.type) {
            case IMAGE -> drawImage(canvas, rect, layer, resolver, cache, paint);
            case VIDEO -> drawVideo(canvas, rect, layer, timeMs, resolver, cache, paint);
            case TEXT -> drawText(canvas, rect, expandVariables(layer.text, project, timeMs), layer, paint);
            case LYRICS -> drawLyrics(canvas, rect, project, timeMs, layer, paint);
            case SPECTRUM -> drawSpectrum(canvas, rect, layer, spectrum, paint);
            case WAVEFORM -> drawWaveform(canvas, rect, layer, spectrum, paint);
            case PARTICLES -> drawParticles(canvas, rect, layer, timeMs, spectrum, paint, false);
            case CONFETTI -> drawParticles(canvas, rect, layer, timeMs, spectrum, paint, true);
            case SHAPE -> drawShape(canvas, rect, layer, paint);
            case SHADER -> drawProceduralShader(canvas, rect, layer, timeMs, spectrum, paint);
            case CAMERA -> { }
        }
    }

    private static void drawImage(Canvas canvas, RectF rect, ProjectModel.Layer layer, ContentResolver resolver, AssetCache cache, Paint paint) {
        Bitmap bitmap = cache.bitmap(resolver, layer.uri, Math.round(rect.width()), Math.round(rect.height()));
        if (bitmap == null) {
            drawMissing(canvas, rect, "IMAGE", paint);
            return;
        }
        Rect src = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
        RectF dst = centerCrop(src.width(), src.height(), rect);
        canvas.save();
        canvas.clipRect(rect);
        canvas.drawBitmap(bitmap, src, dst, paint);
        canvas.restore();
    }

    private static void drawVideo(Canvas canvas, RectF rect, ProjectModel.Layer layer, long timeMs, ContentResolver resolver, AssetCache cache, Paint paint) {
        Bitmap frame = cache.videoFrame(resolver, layer.uri, timeMs * 1000L);
        if (frame == null) {
            drawMissing(canvas, rect, "VIDEO", paint);
            return;
        }
        Rect src = new Rect(0, 0, frame.getWidth(), frame.getHeight());
        RectF dst = centerCrop(src.width(), src.height(), rect);
        canvas.save();
        canvas.clipRect(rect);
        canvas.drawBitmap(frame, src, dst, paint);
        canvas.restore();
    }

    private static RectF centerCrop(int sourceWidth, int sourceHeight, RectF target) {
        float scale = Math.max(target.width() / Math.max(1, sourceWidth), target.height() / Math.max(1, sourceHeight));
        float w = sourceWidth * scale;
        float h = sourceHeight * scale;
        return new RectF(-w / 2f, -h / 2f, w / 2f, h / 2f);
    }

    private static void drawText(Canvas canvas, RectF rect, String text, ProjectModel.Layer layer, Paint inherited) {
        Paint paint = new Paint(inherited);
        paint.setColor(layer.color);
        paint.setTypeface(Typeface.create(layer.fontFamily, Typeface.NORMAL));
        paint.setTextSize(Math.max(8f, layer.textSize * Math.max(rect.width(), rect.height()) * 2.4f));
        paint.setTextAlign(layer.alignment == 0 ? Paint.Align.LEFT : layer.alignment == 2 ? Paint.Align.RIGHT : Paint.Align.CENTER);
        float anchor = layer.alignment == 0 ? rect.left : layer.alignment == 2 ? rect.right : 0f;
        drawMultiline(canvas, text, rect, anchor, paint);
    }

    private static void drawMultiline(Canvas canvas, String text, RectF rect, float anchor, Paint paint) {
        String[] lines = text == null ? new String[]{""} : text.split("\\n", -1);
        Paint.FontMetrics fm = paint.getFontMetrics();
        float lineHeight = (fm.descent - fm.ascent) * 1.08f;
        float total = lineHeight * lines.length;
        float y = -total / 2f - fm.ascent;
        for (String line : lines) {
            canvas.drawText(line, anchor, y, paint);
            y += lineHeight;
        }
    }

    private static void drawLyrics(Canvas canvas, RectF rect, ProjectModel project, long timeMs, ProjectModel.Layer layer, Paint inherited) {
        ProjectModel.LrcLine current = project.lyricAt(timeMs);
        String text = current == null ? (layer.text.trim().isEmpty() ? "Import an .lrc file" : layer.text) : current.text;
        Paint paint = new Paint(inherited);
        paint.setColor(layer.color);
        paint.setTypeface(Typeface.create(layer.fontFamily, Typeface.BOLD));
        paint.setTextSize(Math.max(12f, layer.textSize * Math.max(rect.width(), rect.height()) * 2.5f));
        paint.setTextAlign(Paint.Align.CENTER);
        drawMultiline(canvas, text, rect, 0f, paint);

        if (current != null && !current.words.isEmpty()) {
            float progress = 0f;
            for (int i = 0; i < current.words.size(); i++) {
                ProjectModel.LrcWord word = current.words.get(i);
                long end = i + 1 < current.words.size() ? current.words.get(i + 1).timeMs : current.endMs;
                if (timeMs >= end) progress = (i + 1f) / current.words.size();
                else if (timeMs >= word.timeMs) {
                    float local = (timeMs - word.timeMs) / (float) Math.max(1L, end - word.timeMs);
                    progress = (i + clamp(local, 0f, 1f)) / current.words.size();
                    break;
                }
            }
            Paint underline = new Paint(inherited);
            underline.setColor(layer.color2);
            underline.setStrokeWidth(Math.max(2f, rect.height() * 0.025f));
            underline.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(rect.left * 0.72f, rect.bottom * 0.42f,
                    rect.left * 0.72f + rect.width() * 0.72f * progress, rect.bottom * 0.42f, underline);
        }
    }

    private static void drawSpectrum(Canvas canvas, RectF rect, ProjectModel.Layer layer, float[] spectrum, Paint inherited) {
        int bars = Math.max(4, Math.min(256, layer.spectrumBars));
        Paint paint = new Paint(inherited);
        paint.setStrokeCap(Paint.Cap.ROUND);
        if (layer.spectrumStyle == 1) {
            float radius = Math.min(rect.width(), rect.height()) * 0.24f;
            float maxLength = Math.min(rect.width(), rect.height()) * 0.24f;
            paint.setStrokeWidth(Math.max(1f, (float) (2 * Math.PI * radius / bars) * 0.62f));
            for (int i = 0; i < bars; i++) {
                float t = i / (float) bars;
                float angle = (float) (t * Math.PI * 2.0 - Math.PI / 2.0);
                float value = sampleSpectrum(spectrum, t) * layer.audioGain;
                paint.setColor(lerpColor(layer.color, layer.color2, t));
                float startX = (float) Math.cos(angle) * radius;
                float startY = (float) Math.sin(angle) * radius;
                float endX = (float) Math.cos(angle) * (radius + maxLength * value);
                float endY = (float) Math.sin(angle) * (radius + maxLength * value);
                canvas.drawLine(startX, startY, endX, endY, paint);
            }
        } else if (layer.spectrumStyle == 2) {
            Path path = new Path();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, rect.height() * layer.strokeWidth));
            for (int i = 0; i < bars; i++) {
                float t = i / (float) Math.max(1, bars - 1);
                float value = sampleSpectrum(spectrum, t) * layer.audioGain;
                float x = rect.left + rect.width() * t;
                float y = rect.bottom - rect.height() * clamp(value, 0f, 1.2f);
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            paint.setShader(new LinearGradient(rect.left, 0, rect.right, 0, layer.color, layer.color2, Shader.TileMode.CLAMP));
            canvas.drawPath(path, paint);
            paint.setShader(null);
        } else {
            float gap = rect.width() / bars * 0.2f;
            float barWidth = Math.max(1f, rect.width() / bars - gap);
            for (int i = 0; i < bars; i++) {
                float t = i / (float) Math.max(1, bars - 1);
                float value = clamp(sampleSpectrum(spectrum, t) * layer.audioGain, 0.01f, 1.35f);
                paint.setColor(lerpColor(layer.color, layer.color2, t));
                float left = rect.left + i * (barWidth + gap);
                canvas.drawRoundRect(left, rect.bottom - rect.height() * value, left + barWidth, rect.bottom,
                        barWidth * 0.45f, barWidth * 0.45f, paint);
            }
        }
    }

    private static void drawWaveform(Canvas canvas, RectF rect, ProjectModel.Layer layer, float[] spectrum, Paint inherited) {
        Paint paint = new Paint(inherited);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, rect.height() * layer.strokeWidth));
        paint.setShader(new LinearGradient(rect.left, 0, rect.right, 0, layer.color, layer.color2, Shader.TileMode.CLAMP));
        Path path = new Path();
        int points = Math.max(64, layer.spectrumBars * 2);
        for (int i = 0; i < points; i++) {
            float t = i / (float) Math.max(1, points - 1);
            float sample = sampleSpectrum(spectrum, Math.abs(t * 2f - 1f));
            float carrier = (float) Math.sin(t * Math.PI * (6f + layer.speed * 8f));
            float y = carrier * sample * rect.height() * 0.46f * layer.audioGain;
            float x = rect.left + t * rect.width();
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        canvas.drawPath(path, paint);
        paint.setShader(null);
    }

    private static void drawParticles(Canvas canvas, RectF rect, ProjectModel.Layer layer, long timeMs, float[] spectrum, Paint inherited, boolean confetti) {
        int count = Math.max(1, Math.min(1200, layer.particleCount));
        Random random = new Random(layer.id.hashCode());
        float energy = average(spectrum) * layer.audioGain;
        Paint paint = new Paint(inherited);
        for (int i = 0; i < count; i++) {
            float seedX = random.nextFloat();
            float seedY = random.nextFloat();
            float seedSize = random.nextFloat();
            float speed = (0.08f + random.nextFloat() * 0.24f) * layer.speed;
            float phase = (timeMs / 1000f * speed + seedY) % 1f;
            float x = rect.left + ((seedX + (float) Math.sin((phase + i) * 6.28f) * 0.08f) % 1f) * rect.width();
            float y = confetti ? rect.top + phase * rect.height() : rect.bottom - phase * rect.height();
            float size = Math.max(1f, rect.width() * (0.002f + seedSize * 0.008f) * (1f + energy * 2f));
            paint.setColor(lerpColor(layer.color, layer.color2, random.nextFloat()));
            if (confetti) {
                canvas.save();
                canvas.rotate(timeMs * 0.09f * (i % 5 + 1), x, y);
                canvas.drawRect(x - size, y - size * 0.35f, x + size, y + size * 0.35f, paint);
                canvas.restore();
            } else {
                canvas.drawCircle(x, y, size, paint);
                if (i > 0 && i % 7 == 0) canvas.drawLine(x, y, x + size * 4f, y - size * 2f, paint);
            }
        }
    }

    private static void drawShape(Canvas canvas, RectF rect, ProjectModel.Layer layer, Paint inherited) {
        Paint paint = new Paint(inherited);
        paint.setColor(layer.color);
        if (layer.spectrumStyle == 1) {
            canvas.drawOval(rect, paint);
        } else if (layer.spectrumStyle == 2) {
            Path triangle = new Path();
            triangle.moveTo(0f, rect.top);
            triangle.lineTo(rect.right, rect.bottom);
            triangle.lineTo(rect.left, rect.bottom);
            triangle.close();
            canvas.drawPath(triangle, paint);
        } else {
            float radius = Math.min(rect.width(), rect.height()) * 0.08f;
            canvas.drawRoundRect(rect, radius, radius, paint);
        }
    }

    private static void drawProceduralShader(Canvas canvas, RectF rect, ProjectModel.Layer layer, long timeMs, float[] spectrum, Paint inherited) {
        Paint paint = new Paint(inherited);
        int rings = 18;
        float energy = average(spectrum) * layer.audioGain;
        for (int i = rings; i >= 0; i--) {
            float t = i / (float) rings;
            float wobble = (float) Math.sin(timeMs * 0.0012f * layer.speed + i * 0.72f);
            float radius = Math.min(rect.width(), rect.height()) * (0.05f + t * 0.62f) * (1f + energy * 0.18f);
            int color = lerpColor(layer.color, layer.color2, (t + wobble * 0.12f + 1f) % 1f);
            paint.setShader(new RadialGradient(wobble * rect.width() * 0.12f, -wobble * rect.height() * 0.08f,
                    Math.max(1f, radius), Color.argb(Math.round(160f * (1f - t * 0.55f)), Color.red(color), Color.green(color), Color.blue(color)),
                    Color.TRANSPARENT, Shader.TileMode.CLAMP));
            canvas.drawCircle(0f, 0f, radius, paint);
        }
        paint.setShader(null);
    }

    private static void drawOverlays(Canvas canvas, RectF rect, ProjectModel.Layer layer, long timeMs, float audio) {
        for (ProjectModel.Effect effect : layer.effects) {
            if (!effect.enabled) continue;
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setAlpha(Math.round(clamp(effect.intensity, 0f, 1f) * 190f));
            switch (effect.type) {
                case VIGNETTE -> {
                    paint.setShader(new RadialGradient(0f, 0f, Math.max(rect.width(), rect.height()) * 0.72f,
                            Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP));
                    canvas.drawRect(rect, paint);
                }
                case VHS -> {
                    paint.setColor(Color.WHITE);
                    paint.setAlpha(Math.round(18f + effect.intensity * 38f));
                    float spacing = Math.max(3f, rect.height() * 0.018f);
                    for (float y = rect.top + (timeMs * 0.08f % spacing); y < rect.bottom; y += spacing) {
                        canvas.drawRect(rect.left, y, rect.right, y + 1f, paint);
                    }
                }
                case GOD_RAYS -> {
                    paint.setColor(effect.color);
                    paint.setAlpha(Math.round(45f * effect.intensity * (0.3f + audio)));
                    for (int i = 0; i < 12; i++) {
                        Path ray = new Path();
                        float a = (float) (i / 12.0 * Math.PI * 2 + timeMs * 0.0002);
                        ray.moveTo(0f, 0f);
                        ray.lineTo((float) Math.cos(a - 0.08f) * rect.width(), (float) Math.sin(a - 0.08f) * rect.height());
                        ray.lineTo((float) Math.cos(a + 0.08f) * rect.width(), (float) Math.sin(a + 0.08f) * rect.height());
                        ray.close();
                        canvas.drawPath(ray, paint);
                    }
                }
                default -> { }
            }
        }
    }

    private static String expandVariables(String input, ProjectModel project, long timeMs) {
        String text = input == null ? "" : input;
        ProjectModel.LrcLine line = project.lyricAt(timeMs);
        return text
                .replace("{time}", formatTime(timeMs))
                .replace("{duration}", formatTime(project.durationMs))
                .replace("{project}", project.name)
                .replace("{lyrics}", line == null ? "" : line.text);
    }

    private static String formatTime(long ms) {
        long seconds = Math.max(0L, ms / 1000L);
        return String.format(Locale.US, "%02d:%02d", seconds / 60L, seconds % 60L);
    }

    private static void drawMissing(Canvas canvas, RectF rect, String label, Paint inherited) {
        Paint box = new Paint(inherited);
        box.setStyle(Paint.Style.STROKE);
        box.setStrokeWidth(Math.max(2f, rect.width() * 0.006f));
        box.setColor(Color.rgb(145, 80, 190));
        canvas.drawRect(rect, box);
        Paint text = new Paint(inherited);
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(Math.max(12f, rect.height() * 0.18f));
        canvas.drawText(label, 0f, -text.ascent() / 2f, text);
    }

    private static float bandValue(ProjectModel.Layer layer, float[] spectrum) {
        if (spectrum.length == 0) return 0f;
        if (layer.audioBand >= 0) return spectrum[Math.min(spectrum.length - 1, layer.audioBand)];
        return average(spectrum);
    }

    private static float average(float[] values) {
        if (values == null || values.length == 0) return 0f;
        float sum = 0f;
        for (float value : values) sum += value;
        return sum / values.length;
    }

    private static float sampleSpectrum(float[] spectrum, float normalized) {
        if (spectrum == null || spectrum.length == 0) return 0f;
        float x = clamp(normalized, 0f, 1f) * (spectrum.length - 1);
        int left = (int) x;
        int right = Math.min(spectrum.length - 1, left + 1);
        float t = x - left;
        return spectrum[left] + (spectrum[right] - spectrum[left]) * t;
    }

    private static int lerpColor(int a, int b, float t) {
        t = clamp(t, 0f, 1f);
        return Color.argb(
                Math.round(Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t)
        );
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    public static final class AssetCache {
        private final int maxBitmaps;
        private final LinkedHashMap<String, Bitmap> bitmaps;
        private final LinkedHashMap<String, MediaMetadataRetriever> videos;
        private final LinkedHashMap<String, VideoFrame> videoFrames;

        public AssetCache(int maxBitmaps) {
            this.maxBitmaps = Math.max(2, maxBitmaps);
            this.bitmaps = new LinkedHashMap<>(16, 0.75f, true);
            this.videos = new LinkedHashMap<>(8, 0.75f, true);
            this.videoFrames = new LinkedHashMap<>(8, 0.75f, true);
        }

        public synchronized Bitmap bitmap(ContentResolver resolver, String uri, int wantedWidth, int wantedHeight) {
            if (uri == null || uri.trim().isEmpty()) return null;
            String key = uri + "@" + rounded(wantedWidth) + "x" + rounded(wantedHeight);
            Bitmap cached = bitmaps.get(key);
            if (cached != null && !cached.isRecycled()) return cached;
            Bitmap decoded = decodeBitmap(resolver, Uri.parse(uri), Math.max(64, wantedWidth), Math.max(64, wantedHeight));
            if (decoded != null) {
                bitmaps.put(key, decoded);
                trimBitmaps();
            }
            return decoded;
        }

        public synchronized Bitmap videoFrame(ContentResolver resolver, String uri, long timeUs) {
            if (uri == null || uri.trim().isEmpty()) return null;
            long bucket = Math.max(0L, timeUs / 33_333L);
            VideoFrame previous = videoFrames.get(uri);
            if (previous != null && previous.bucket == bucket && previous.bitmap != null && !previous.bitmap.isRecycled()) {
                return previous.bitmap;
            }
            MediaMetadataRetriever retriever = videos.get(uri);
            if (retriever == null) {
                retriever = new MediaMetadataRetriever();
                try {
                    android.content.res.AssetFileDescriptor afd = resolver.openAssetFileDescriptor(Uri.parse(uri), "r");
                    if (afd == null) return null;
                    retriever.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                    afd.close();
                } catch (Throwable failure) {
                    try { retriever.release(); } catch (Throwable ignoredAgain) { }
                    return null;
                }
                videos.put(uri, retriever);
            }
            try {
                Bitmap frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (previous != null && previous.bitmap != null && previous.bitmap != frame && !previous.bitmap.isRecycled()) {
                    previous.bitmap.recycle();
                }
                videoFrames.put(uri, new VideoFrame(bucket, frame));
                return frame;
            } catch (RuntimeException e) {
                return null;
            }
        }

        private Bitmap decodeBitmap(ContentResolver resolver, Uri uri, int wantedWidth, int wantedHeight) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ImageDecoder.Source source = ImageDecoder.createSource(resolver, uri);
                    return ImageDecoder.decodeBitmap(source, (decoder, info, source1) -> {
                        int sourceW = info.getSize().getWidth();
                        int sourceH = info.getSize().getHeight();
                        float scale = Math.min(1f, Math.max(wantedWidth / (float) Math.max(1, sourceW), wantedHeight / (float) Math.max(1, sourceH)));
                        decoder.setTargetSize(Math.max(1, Math.round(sourceW * scale)), Math.max(1, Math.round(sourceH * scale)));
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    });
                }
                try (InputStream input = resolver.openInputStream(uri)) {
                    if (input == null) return null;
                    return BitmapFactory.decodeStream(input);
                }
            } catch (IOException | SecurityException | RuntimeException e) {
                return null;
            }
        }

        private int rounded(int value) {
            return Math.max(64, ((value + 127) / 128) * 128);
        }

        private void trimBitmaps() {
            while (bitmaps.size() > maxBitmaps) {
                Map.Entry<String, Bitmap> eldest = bitmaps.entrySet().iterator().next();
                Bitmap bitmap = eldest.getValue();
                bitmaps.remove(eldest.getKey());
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            }
        }

        public synchronized void clear() {
            for (Bitmap bitmap : bitmaps.values()) if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            bitmaps.clear();
            for (VideoFrame frame : videoFrames.values()) if (frame.bitmap != null && !frame.bitmap.isRecycled()) frame.bitmap.recycle();
            videoFrames.clear();
            for (MediaMetadataRetriever retriever : videos.values()) {
                try { retriever.release(); } catch (RuntimeException ignored) { }
            }
            videos.clear();
        }

        private static final class VideoFrame {
            final long bucket;
            final Bitmap bitmap;

            VideoFrame(long bucket, Bitmap bitmap) {
                this.bucket = bucket;
                this.bitmap = bitmap;
            }
        }
    }
}
