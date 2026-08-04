package dev.alastorkaneki.vizzywrapper;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

/** Fully native Android editor entry point. The WebView wrapper remains available as a fallback. */
public final class MainActivity extends Activity implements AudioPlaybackEngine.Listener, NativePreviewView.Listener {
    private static final int PICK_MEDIA = 2001;
    private static final int PICK_LRC = 2002;
    private static final int PICK_PROJECT = 2003;
    private static final int SAVE_PROJECT = 2004;
    private static final int CREATE_VIDEO = 2005;
    private static final int NOTIFICATION_PERMISSION = 2006;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable autosave = this::performAutosave;

    private ProjectModel project;
    private NativePreviewView preview;
    private AudioPlaybackEngine playback;
    private SeekBar seekBar;
    private TextView playButton;
    private TextView timeLabel;
    private TextView projectLabel;
    private TextView layerLabel;
    private LinearLayout topBar;
    private LinearLayout bottomBar;
    private boolean userSeeking;
    private long pendingExportDurationMs;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ImmersiveMode.apply(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();

        ProjectModel saved = ProjectStore.loadAutosave(this);
        project = saved == null ? new ProjectModel() : saved;
        preview.setProject(project);
        updateLabels();

        playback = new AudioPlaybackEngine(this, this);
        if (!project.audioUri.trim().isEmpty()) playback.load(project.audioUri);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        preview = new NativePreviewView(this);
        preview.setListener(this);
        FrameLayout.LayoutParams previewParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        previewParams.topMargin = dp(54);
        previewParams.bottomMargin = dp(116);
        root.addView(preview, previewParams);

        HorizontalScrollView topScroller = new HorizontalScrollView(this);
        topScroller.setHorizontalScrollBarEnabled(false);
        topScroller.setFillViewport(true);
        topScroller.setBackgroundColor(Color.argb(245, 0, 0, 0));
        topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(6), dp(5), dp(6), dp(5));
        topScroller.addView(topBar, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(topScroller, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54), Gravity.TOP));

        projectLabel = label("VIZZY NATIVE", 16, true);
        projectLabel.setPadding(dp(10), 0, dp(10), 0);
        topBar.addView(projectLabel, new LinearLayout.LayoutParams(dp(170), dp(44)));
        addTopButton("New", v -> confirmNew());
        addTopButton("Import", v -> showImportMenu());
        addTopButton("Add", v -> showAddLayer());
        addTopButton("Layers", v -> showLayers());
        addTopButton("Project", v -> showProjectSettings());
        addTopButton("Save", v -> saveProjectAs());
        addTopButton("Export", v -> showExportSettings());
        addTopButton("More", v -> showMore());

        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setPadding(dp(8), dp(6), dp(8), dp(7));
        bottomBar.setBackgroundColor(Color.argb(248, 0, 0, 0));
        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(116), Gravity.BOTTOM);
        root.addView(bottomBar, bottomParams);

        LinearLayout transport = new LinearLayout(this);
        transport.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.addView(transport, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));

        playButton = button("▶", 48);
        playButton.setOnClickListener(v -> playback.toggle());
        transport.addView(playButton, new LinearLayout.LayoutParams(dp(48), dp(42)));

        timeLabel = label("00:00 / 00:30", 13, false);
        timeLabel.setGravity(Gravity.CENTER);
        transport.addView(timeLabel, new LinearLayout.LayoutParams(dp(116), dp(42)));

        seekBar = new SeekBar(this);
        seekBar.setMax(10_000);
        seekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.rgb(184, 86, 240)));
        seekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.rgb(215, 136, 255)));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    long time = project.durationMs * progress / 10_000L;
                    preview.setCurrentTimeMs(time);
                    timeLabel.setText(formatTime(time) + " / " + formatTime(project.durationMs));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                userSeeking = false;
                playback.seekTo(project.durationMs * seekBar.getProgress() / 10_000L);
            }
        });
        transport.addView(seekBar, new LinearLayout.LayoutParams(0, dp(42), 1f));

        TextView editButton = button("Edit", 64);
        editButton.setOnClickListener(v -> editSelectedLayer());
        transport.addView(editButton, new LinearLayout.LayoutParams(dp(64), dp(42)));

        LinearLayout status = new LinearLayout(this);
        status.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
        layerLabel = label("No layer selected", 13, false);
        layerLabel.setPadding(dp(10), 0, dp(6), 0);
        status.addView(layerLabel, new LinearLayout.LayoutParams(0, dp(46), 1f));
        TextView effects = button("Effects", 78);
        effects.setOnClickListener(v -> showEffects());
        status.addView(effects, new LinearLayout.LayoutParams(dp(78), dp(42)));
        TextView automate = button("Automate", 86);
        automate.setOnClickListener(v -> showAutomation());
        status.addView(automate, new LinearLayout.LayoutParams(dp(86), dp(42)));
    }

    private void addTopButton(String text, View.OnClickListener listener) {
        TextView button = button(text, Math.max(62, text.length() * 10 + 26));
        button.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            listener.onClick(v);
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(Math.max(62, text.length() * 10 + 26)), dp(42));
        params.leftMargin = dp(3);
        topBar.addView(button, params);
    }

    private TextView button(String text, int widthDp) {
        TextView view = label(text, 14, true);
        view.setGravity(Gravity.CENTER);
        view.setClickable(true);
        view.setFocusable(true);
        view.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(10, 10, 10));
        bg.setStroke(dp(1), Color.rgb(142, 65, 190));
        bg.setCornerRadius(dp(12));
        view.setBackground(bg);
        view.setMinWidth(dp(widthDp));
        view.setForeground(getDrawable(android.R.drawable.list_selector_background));
        return view;
    }

    private TextView label(String text, int sizeSp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setTextColor(Color.WHITE);
        view.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private void confirmNew() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("New native project?")
                .setMessage("The current project is autosaved, but a new project will replace the active editor state.")
                .setPositiveButton("New project", (d, w) -> {
                    playback.pause();
                    project = new ProjectModel();
                    preview.clearCache();
                    preview.setProject(project);
                    seekBar.setProgress(0);
                    updateLabels();
                    scheduleAutosave();
                })
                .setNegativeButton("Cancel", null)
                .create();
        immersive(dialog);
    }

    private void showImportMenu() {
        String[] items = {"Audio, image or video", "Lyrics (.lrc)", "Native project (.json / .viznative)"};
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Import")
                .setItems(items, (d, which) -> {
                    if (which == 0) openMediaPicker();
                    else if (which == 1) openDocument(PICK_LRC, "text/*", new String[]{"text/plain", "application/octet-stream"});
                    else openDocument(PICK_PROJECT, "application/json", new String[]{"application/json", "text/plain", "application/octet-stream"});
                }).create();
        immersive(dialog);
    }

    private void openMediaPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "image/*", "video/*", "text/plain", "application/octet-stream"});
        startActivityForResult(intent, PICK_MEDIA);
    }

    private void openDocument(int requestCode, String type, String[] mimeTypes) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(type)
                .putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        startActivityForResult(intent, requestCode);
    }

    private void showAddLayer() {
        ProjectModel.LayerType[] values = ProjectModel.LayerType.values();
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) labels[i] = values[i].label();
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Add native layer")
                .setItems(labels, (d, which) -> {
                    ProjectModel.Layer layer = ProjectModel.Layer.create(values[which]);
                    if (layer.type == ProjectModel.LayerType.LYRICS && project.lyrics.isEmpty()) {
                        Toast.makeText(this, "Import an .lrc file to populate timed lyrics.", Toast.LENGTH_LONG).show();
                    }
                    project.layers.add(layer);
                    preview.setSelectedLayer(layer);
                    updateLabels();
                    scheduleAutosave();
                    editSelectedLayer();
                }).create();
        immersive(dialog);
    }

    private void showLayers() {
        if (project.layers.isEmpty()) {
            showAddLayer();
            return;
        }
        String[] labels = new String[project.layers.size()];
        int selected = 0;
        ProjectModel.Layer current = preview.getSelectedLayer();
        for (int i = 0; i < project.layers.size(); i++) {
            ProjectModel.Layer layer = project.layers.get(i);
            labels[i] = (layer.visible ? "◉ " : "○ ") + layer.name + "  ·  " + layer.type.label();
            if (layer == current) selected = i;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Layers")
                .setSingleChoiceItems(labels, selected, (d, which) -> {
                    preview.setSelectedLayer(project.layers.get(which));
                    d.dismiss();
                    updateLabels();
                    showLayerActions();
                })
                .setPositiveButton("Add", (d, w) -> showAddLayer())
                .setNegativeButton("Close", null)
                .create();
        immersive(dialog);
    }

    private void showLayerActions() {
        ProjectModel.Layer layer = preview.getSelectedLayer();
        if (layer == null) return;
        String[] items = {
                "Edit properties", layer.visible ? "Hide" : "Show", layer.solo ? "Disable solo" : "Solo",
                layer.locked ? "Unlock" : "Lock", "Duplicate", "Move forward", "Move backward",
                "Effects", "Add keyframe", "Delete"
        };
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(layer.name)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0 -> editSelectedLayer();
                        case 1 -> layer.visible = !layer.visible;
                        case 2 -> layer.solo = !layer.solo;
                        case 3 -> layer.locked = !layer.locked;
                        case 4 -> duplicateLayer(layer);
                        case 5 -> moveLayer(layer, 1);
                        case 6 -> moveLayer(layer, -1);
                        case 7 -> showEffects();
                        case 8 -> showAutomation();
                        case 9 -> deleteLayer(layer);
                    }
                    preview.invalidate();
                    updateLabels();
                    scheduleAutosave();
                }).create();
        immersive(dialog);
    }

    private void duplicateLayer(ProjectModel.Layer layer) {
        try {
            ProjectModel.Layer copy = ProjectModel.Layer.fromJson(layer.toJson());
            copy.id = java.util.UUID.randomUUID().toString();
            copy.name = layer.name + " copy";
            int index = project.layers.indexOf(layer);
            project.layers.add(Math.min(project.layers.size(), index + 1), copy);
            preview.setSelectedLayer(copy);
        } catch (Exception e) {
            toast("Could not duplicate layer: " + e.getMessage());
        }
    }

    private void moveLayer(ProjectModel.Layer layer, int direction) {
        int index = project.layers.indexOf(layer);
        int next = Math.max(0, Math.min(project.layers.size() - 1, index + direction));
        if (index != next) {
            project.layers.remove(index);
            project.layers.add(next, layer);
        }
    }

    private void deleteLayer(ProjectModel.Layer layer) {
        project.layers.remove(layer);
        preview.setSelectedLayer(project.layers.isEmpty() ? null : project.layers.get(project.layers.size() - 1));
    }

    private void editSelectedLayer() {
        ProjectModel.Layer layer = preview.getSelectedLayer();
        if (layer == null) {
            showLayers();
            return;
        }
        LinearLayout form = form();
        EditText name = field(form, "Name", layer.name, false);
        EditText text = field(form, "Text / variable", layer.text, false);
        EditText x = field(form, "X (0–1)", floatText(layer.x), true);
        EditText y = field(form, "Y (0–1)", floatText(layer.y), true);
        EditText width = field(form, "Width", floatText(layer.width), true);
        EditText height = field(form, "Height", floatText(layer.height), true);
        EditText rotation = field(form, "Rotation degrees", floatText(layer.rotation), true);
        EditText opacity = field(form, "Opacity (0–1)", floatText(layer.opacity), true);
        EditText start = field(form, "Start seconds", floatText(layer.startMs / 1000f), true);
        EditText end = field(form, "End seconds", layer.endMs == Long.MAX_VALUE ? "" : floatText(layer.endMs / 1000f), true);
        EditText color = field(form, "Primary color (#AARRGGBB)", colorText(layer.color), false);
        EditText color2 = field(form, "Secondary color", colorText(layer.color2), false);
        EditText size = field(form, "Text size", floatText(layer.textSize), true);
        EditText bars = field(form, "Bars / points", Integer.toString(layer.spectrumBars), true);
        EditText gain = field(form, "Audio gain", floatText(layer.audioGain), true);
        EditText speed = field(form, "Speed", floatText(layer.speed), true);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(layer.type.label() + " properties")
                .setView(wrapScroll(form))
                .setPositiveButton("Apply", null)
                .setNeutralButton("Reset transform", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    layer.name = nonBlank(name.getText().toString(), layer.type.label());
                    layer.text = text.getText().toString();
                    layer.x = parseFloat(x, layer.x);
                    layer.y = parseFloat(y, layer.y);
                    layer.width = Math.max(0.001f, parseFloat(width, layer.width));
                    layer.height = Math.max(0.001f, parseFloat(height, layer.height));
                    layer.rotation = parseFloat(rotation, layer.rotation);
                    layer.opacity = clamp(parseFloat(opacity, layer.opacity), 0f, 1f);
                    layer.startMs = Math.max(0L, Math.round(parseFloat(start, layer.startMs / 1000f) * 1000f));
                    String endValue = end.getText().toString().trim();
                    layer.endMs = endValue.isEmpty() ? Long.MAX_VALUE : Math.max(layer.startMs, Math.round(Float.parseFloat(endValue) * 1000f));
                    layer.color = parseColor(color.getText().toString(), layer.color);
                    layer.color2 = parseColor(color2.getText().toString(), layer.color2);
                    layer.textSize = Math.max(0.005f, parseFloat(size, layer.textSize));
                    layer.spectrumBars = Math.max(4, Math.min(512, Math.round(parseFloat(bars, layer.spectrumBars))));
                    layer.audioGain = Math.max(0f, parseFloat(gain, layer.audioGain));
                    layer.speed = Math.max(0f, parseFloat(speed, layer.speed));
                    dialog.dismiss();
                    preview.invalidate();
                    updateLabels();
                    scheduleAutosave();
                } catch (RuntimeException error) {
                    toast("Check the property values: " + error.getMessage());
                }
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                layer.x = layer.y = 0.5f;
                layer.width = layer.height = 0.65f;
                layer.rotation = 0f;
                preview.invalidate();
            });
        });
        immersive(dialog);
    }

    private void showEffects() {
        ProjectModel.Layer layer = preview.getSelectedLayer();
        if (layer == null) { showLayers(); return; }
        ProjectModel.EffectType[] types = ProjectModel.EffectType.values();
        String[] labels = new String[types.length];
        boolean[] checked = new boolean[types.length];
        for (int i = 0; i < types.length; i++) {
            labels[i] = pretty(types[i].name());
            checked[i] = findEffect(layer, types[i]) != null;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Effects · " + layer.name)
                .setMultiChoiceItems(labels, checked, (d, which, enabled) -> {
                    ProjectModel.Effect existing = findEffect(layer, types[which]);
                    if (enabled && existing == null) {
                        ProjectModel.Effect effect = new ProjectModel.Effect();
                        effect.type = types[which];
                        effect.color = layer.color2;
                        layer.effects.add(effect);
                    } else if (!enabled && existing != null) {
                        layer.effects.remove(existing);
                    }
                    preview.invalidate();
                    scheduleAutosave();
                })
                .setPositiveButton("Tune selected", (d, w) -> tuneFirstEffect(layer))
                .setNegativeButton("Done", null)
                .create();
        immersive(dialog);
    }

    private ProjectModel.Effect findEffect(ProjectModel.Layer layer, ProjectModel.EffectType type) {
        for (ProjectModel.Effect effect : layer.effects) if (effect.type == type) return effect;
        return null;
    }

    private void tuneFirstEffect(ProjectModel.Layer layer) {
        if (layer.effects.isEmpty()) {
            toast("Enable an effect first.");
            return;
        }
        String[] labels = new String[layer.effects.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = pretty(layer.effects.get(i).type.name());
        AlertDialog choose = new AlertDialog.Builder(this)
                .setTitle("Tune effect")
                .setItems(labels, (d, which) -> tuneEffect(layer.effects.get(which)))
                .create();
        immersive(choose);
    }

    private void tuneEffect(ProjectModel.Effect effect) {
        LinearLayout form = form();
        EditText intensity = field(form, "Intensity (0–1)", floatText(effect.intensity), true);
        EditText amount = field(form, "Amount (0–1)", floatText(effect.amount), true);
        EditText color = field(form, "Color", colorText(effect.color), false);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(pretty(effect.type.name()))
                .setView(form)
                .setPositiveButton("Apply", (d, w) -> {
                    effect.intensity = clamp(parseFloat(intensity, effect.intensity), 0f, 1f);
                    effect.amount = clamp(parseFloat(amount, effect.amount), 0f, 1f);
                    effect.color = parseColor(color.getText().toString(), effect.color);
                    preview.invalidate();
                    scheduleAutosave();
                })
                .setNegativeButton("Cancel", null).create();
        immersive(dialog);
    }

    private void showAutomation() {
        ProjectModel.Layer layer = preview.getSelectedLayer();
        if (layer == null) { showLayers(); return; }
        String[] properties = {"x", "y", "width", "height", "rotation", "opacity", "textSize", "audioGain"};
        AlertDialog choose = new AlertDialog.Builder(this)
                .setTitle("Add keyframe at " + formatTime(playback == null ? 0L : playback.position()))
                .setItems(properties, (d, which) -> addKeyframe(layer, properties[which]))
                .setNeutralButton("Clear all (" + layer.keyframes.size() + ")", (d, w) -> {
                    layer.keyframes.clear();
                    scheduleAutosave();
                })
                .setNegativeButton("Cancel", null).create();
        immersive(choose);
    }

    private void addKeyframe(ProjectModel.Layer layer, String property) {
        float current = switch (property) {
            case "x" -> layer.x;
            case "y" -> layer.y;
            case "width" -> layer.width;
            case "height" -> layer.height;
            case "rotation" -> layer.rotation;
            case "opacity" -> layer.opacity;
            case "textSize" -> layer.textSize;
            case "audioGain" -> layer.audioGain;
            default -> 0f;
        };
        EditText input = new EditText(this);
        input.setText(floatText(current));
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.GRAY);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(property + " keyframe")
                .setView(input)
                .setSingleChoiceItems(new String[]{"Linear", "Ease in", "Ease out", "Ease in/out", "Step"}, 0, null)
                .setPositiveButton("Add", (d, w) -> {
                    ProjectModel.Keyframe key = new ProjectModel.Keyframe();
                    key.property = property;
                    key.timeMs = playback == null ? 0L : playback.position();
                    key.value = Float.parseFloat(input.getText().toString());
                    int easing = ((AlertDialog) d).getListView().getCheckedItemPosition();
                    key.easing = switch (easing) {
                        case 1 -> "easeIn"; case 2 -> "easeOut"; case 3 -> "easeInOut"; case 4 -> "step"; default -> "linear";
                    };
                    layer.keyframes.add(key);
                    scheduleAutosave();
                    toast("Keyframe added.");
                })
                .setNegativeButton("Cancel", null).create();
        immersive(dialog);
    }

    private void showProjectSettings() {
        LinearLayout form = form();
        EditText name = field(form, "Project name", project.name, false);
        EditText width = field(form, "Canvas width", Integer.toString(project.canvasWidth), true);
        EditText height = field(form, "Canvas height", Integer.toString(project.canvasHeight), true);
        EditText fps = field(form, "Frame rate", Integer.toString(project.frameRate), true);
        EditText duration = field(form, "Duration seconds", floatText(project.durationMs / 1000f), true);
        EditText background = field(form, "Background", colorText(project.backgroundColor), false);
        EditText background2 = field(form, "Background 2", colorText(project.backgroundColor2), false);
        TextView capabilities = label("AVC: " + ExportCapabilities.describe("video/avc") + "\nHEVC: " + ExportCapabilities.describe("video/hevc"), 12, false);
        capabilities.setPadding(dp(8), dp(10), dp(8), dp(10));
        form.addView(capabilities);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Native project settings")
                .setView(wrapScroll(form))
                .setPositiveButton("Apply", (d, w) -> {
                    try {
                        project.name = nonBlank(name.getText().toString(), "Untitled project");
                        project.canvasWidth = makeEven(Math.max(64, Integer.parseInt(width.getText().toString())));
                        project.canvasHeight = makeEven(Math.max(64, Integer.parseInt(height.getText().toString())));
                        project.frameRate = Math.max(1, Math.min(240, Integer.parseInt(fps.getText().toString())));
                        project.durationMs = Math.max(1_000L, Math.round(Float.parseFloat(duration.getText().toString()) * 1000f));
                        project.backgroundColor = parseColor(background.getText().toString(), project.backgroundColor);
                        project.backgroundColor2 = parseColor(background2.getText().toString(), project.backgroundColor2);
                        preview.invalidate();
                        updateLabels();
                        scheduleAutosave();
                    } catch (RuntimeException error) {
                        toast("Invalid project settings: " + error.getMessage());
                    }
                })
                .setNegativeButton("Cancel", null).create();
        immersive(dialog);
    }

    private void showExportSettings() {
        LinearLayout form = form();
        EditText width = field(form, "Width (custom allowed)", Integer.toString(project.canvasWidth), true);
        EditText height = field(form, "Height (custom allowed)", Integer.toString(project.canvasHeight), true);
        EditText fps = field(form, "FPS", Integer.toString(project.frameRate), true);
        EditText bitrate = field(form, "Video bitrate Mbps", floatText(project.exportBitrate / 1_000_000f), true);
        EditText audioBitrate = field(form, "Audio bitrate kbps", Integer.toString(project.audioBitrate / 1000), true);
        String[] codecs = {"H.264 / AVC (widest compatibility)", "H.265 / HEVC (smaller files / higher resolutions)"};
        int selectedCodec = "video/hevc".equals(project.exportCodec) ? 1 : 0;
        TextView note = label("No app-level duration or output-size cap is used. The real limits are free storage, the selected document provider, RAM, and the installed hardware/software encoder. Output is streamed through a 64-bit file descriptor, so exports can exceed 4 GB when the provider and filesystem permit it.", 12, false);
        note.setPadding(dp(8), dp(12), dp(8), dp(4));
        form.addView(note);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Maximum-size native export")
                .setView(wrapScroll(form))
                .setSingleChoiceItems(codecs, selectedCodec, null)
                .setPositiveButton("Choose output", null)
                .setNegativeButton("Cancel", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                int outWidth = makeEven(Math.max(64, Integer.parseInt(width.getText().toString())));
                int outHeight = makeEven(Math.max(64, Integer.parseInt(height.getText().toString())));
                int outFps = Math.max(1, Math.min(240, Integer.parseInt(fps.getText().toString())));
                String mime = dialog.getListView().getCheckedItemPosition() == 1 ? "video/hevc" : "video/avc";
                ExportCapabilities.Capability cap = ExportCapabilities.best(mime);
                if (cap == null) throw new IllegalArgumentException("No matching encoder is installed.");
                if (!cap.supports(outWidth, outHeight, outFps)) {
                    throw new IllegalArgumentException(cap.name + " reports a maximum range of "
                            + cap.widths.getUpper() + "×" + cap.heights.getUpper() + " at up to "
                            + cap.frameRates.getUpper().intValue() + " FPS.");
                }
                project.canvasWidth = outWidth;
                project.canvasHeight = outHeight;
                project.frameRate = outFps;
                project.exportCodec = mime;
                project.exportBitrate = Math.max(100_000, Math.round(Float.parseFloat(bitrate.getText().toString()) * 1_000_000f));
                project.audioBitrate = Math.max(64_000, Integer.parseInt(audioBitrate.getText().toString()) * 1000);
                scheduleAutosave();
                dialog.dismiss();
                chooseVideoDestination();
            } catch (RuntimeException error) {
                toast("Export setting rejected: " + error.getMessage());
            }
        }));
        immersive(dialog);
    }

    private void chooseVideoDestination() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION);
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("video/mp4")
                .putExtra(Intent.EXTRA_TITLE, safeFileName(project.name) + ".mp4");
        startActivityForResult(intent, CREATE_VIDEO);
    }

    private void saveProjectAs() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, safeFileName(project.name) + ".viznative.json");
        startActivityForResult(intent, SAVE_PROJECT);
    }

    private void showMore() {
        String[] items = {"Legacy Vizzy.io web editor", "Import another .lrc", "Clear preview cache", "About native engine", "Exit"};
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("More")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0 -> startActivity(new Intent(this, LegacyWrapperActivity.class));
                        case 1 -> openDocument(PICK_LRC, "text/*", new String[]{"text/plain", "application/octet-stream"});
                        case 2 -> { preview.clearCache(); preview.invalidate(); }
                        case 3 -> showAbout();
                        case 4 -> finishAndRemoveTask();
                    }
                }).create();
        immersive(dialog);
    }

    private void showAbout() {
        String text = "Native alpha engine\n\n"
                + "Implemented: native canvas compositing, image/video/text/lyrics/spectrum/waveform/particle/confetti/shape/procedural layers, LRC and enhanced-LRC import, layer blending, effects, keyframes, real-time FFT preview, custom canvas sizes, SAF projects, and foreground MediaCodec exports with AAC audio.\n\n"
                + "The editor deliberately keeps the legacy website available while more advanced shader and community-project compatibility is ported.";
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Vizzy Native")
                .setMessage(text)
                .setPositiveButton("OK", null).create();
        immersive(dialog);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        ImmersiveMode.apply(this);
        if (resultCode != RESULT_OK || data == null) return;
        try {
            if (requestCode == PICK_MEDIA) {
                ClipData clip = data.getClipData();
                if (clip != null) {
                    for (int i = 0; i < clip.getItemCount(); i++) importMedia(clip.getItemAt(i).getUri());
                } else if (data.getData() != null) importMedia(data.getData());
            } else if (requestCode == PICK_LRC && data.getData() != null) {
                importLrc(data.getData());
            } else if (requestCode == PICK_PROJECT && data.getData() != null) {
                takePermission(data, data.getData());
                project = ProjectStore.read(this, data.getData());
                preview.clearCache();
                preview.setProject(project);
                updateLabels();
                if (!project.audioUri.trim().isEmpty()) playback.load(project.audioUri);
                scheduleAutosave();
            } else if (requestCode == SAVE_PROJECT && data.getData() != null) {
                ProjectStore.write(this, data.getData(), project);
                toast("Project saved.");
            } else if (requestCode == CREATE_VIDEO && data.getData() != null) {
                NativeExportService.start(this, project, data.getData());
                toast("Native export started. It will continue in the foreground.");
            }
        } catch (Throwable error) {
            toast("Import/export error: " + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
        }
    }

    private void importMedia(Uri uri) throws Exception {
        takePersistable(uri);
        String name = displayName(uri);
        String lower = name.toLowerCase(Locale.US);
        String mime = getContentResolver().getType(uri);
        mime = mime == null ? "" : mime;
        if (lower.endsWith(".lrc")) {
            importLrc(uri);
            return;
        }
        if (lower.endsWith(".json") || lower.endsWith(".viznative")) {
            project = ProjectStore.read(this, uri);
            preview.setProject(project);
            updateLabels();
            scheduleAutosave();
            return;
        }
        if (mime.startsWith("audio/") || lower.matches(".*\\.(mp3|wav|flac|m4a|aac|ogg|opus)$")) {
            project.audioUri = uri.toString();
            playback.load(project.audioUri);
            project.name = project.name.equals("Untitled project") ? stripExtension(name) : project.name;
            toast("Audio loaded: " + name);
        } else if (mime.startsWith("image/") || lower.matches(".*\\.(png|jpe?g|webp|gif|bmp|heic|heif)$")) {
            ProjectModel.Layer layer = ProjectModel.Layer.create(ProjectModel.LayerType.IMAGE);
            layer.name = stripExtension(name);
            layer.uri = uri.toString();
            project.layers.add(layer);
            preview.setSelectedLayer(layer);
        } else if (mime.startsWith("video/") || lower.matches(".*\\.(mp4|mkv|webm|mov|avi|m4v)$")) {
            ProjectModel.Layer layer = ProjectModel.Layer.create(ProjectModel.LayerType.VIDEO);
            layer.name = stripExtension(name);
            layer.uri = uri.toString();
            project.layers.add(layer);
            preview.setSelectedLayer(layer);
        } else {
            throw new IllegalArgumentException("Unsupported file: " + name);
        }
        preview.invalidate();
        updateLabels();
        scheduleAutosave();
    }

    private void importLrc(Uri uri) throws Exception {
        takePersistable(uri);
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IllegalArgumentException("Unable to open the LRC file.");
            LrcParser.Result result = LrcParser.parse(input);
            project.lyrics.clear();
            project.lyrics.addAll(result.lines);
            boolean hasLyricsLayer = false;
            for (ProjectModel.Layer layer : project.layers) if (layer.type == ProjectModel.LayerType.LYRICS) hasLyricsLayer = true;
            if (!hasLyricsLayer) {
                ProjectModel.Layer layer = ProjectModel.Layer.create(ProjectModel.LayerType.LYRICS);
                layer.name = "Timed lyrics";
                layer.y = 0.78f;
                layer.textSize = 0.07f;
                layer.color = Color.WHITE;
                layer.color2 = Color.rgb(205, 91, 255);
                project.layers.add(layer);
                preview.setSelectedLayer(layer);
            }
            if (!result.title.trim().isEmpty() && project.name.equals("Untitled project")) project.name = result.title;
            toast("Imported " + result.lines.size() + " timed lyric lines.");
            preview.invalidate();
            updateLabels();
            scheduleAutosave();
        }
    }

    private void takePermission(Intent data, Uri uri) {
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try { getContentResolver().takePersistableUriPermission(uri, flags); }
        catch (SecurityException ignored) { }
    }

    private void takePersistable(Uri uri) {
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (SecurityException ignored) { }
    }

    @Override public void onPrepared(long durationMs) {
        if (durationMs > 0L) project.durationMs = durationMs;
        updateLabels();
        scheduleAutosave();
    }

    @Override public void onProgress(long positionMs, boolean playing) {
        if (!userSeeking) {
            int progress = project.durationMs <= 0L ? 0 : (int) Math.min(10_000L, positionMs * 10_000L / project.durationMs);
            seekBar.setProgress(progress);
            preview.setCurrentTimeMs(positionMs);
            timeLabel.setText(formatTime(positionMs) + " / " + formatTime(project.durationMs));
        }
        playButton.setText(playing ? "❚❚" : "▶");
    }

    @Override public void onSpectrum(float[] bands) {
        preview.setSpectrum(bands);
    }

    @Override public void onError(String message) {
        toast(message);
    }

    @Override public void onLayerTransformChanged(ProjectModel.Layer layer) {
        updateLabels();
        scheduleAutosave();
    }

    @Override public void onCanvasTapped() {
        boolean show = topBar.getVisibility() != View.VISIBLE;
        topBar.setVisibility(show ? View.VISIBLE : View.GONE);
        bottomBar.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void updateLabels() {
        if (project == null) return;
        projectLabel.setText(project.name + "  ·  " + project.canvasWidth + "×" + project.canvasHeight);
        ProjectModel.Layer layer = preview.getSelectedLayer();
        layerLabel.setText(layer == null ? "No layer selected" : layer.name + "  ·  " + layer.type.label()
                + "  ·  " + project.layers.size() + " layers  ·  " + project.lyrics.size() + " lyrics");
        timeLabel.setText(formatTime(playback == null ? 0L : playback.position()) + " / " + formatTime(project.durationMs));
    }

    private void scheduleAutosave() {
        main.removeCallbacks(autosave);
        main.postDelayed(autosave, 700L);
    }

    private void performAutosave() {
        try { ProjectStore.autosave(this, project); }
        catch (Exception error) { toast("Autosave failed: " + error.getMessage()); }
    }

    private LinearLayout form() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(8), dp(18), dp(8));
        return form;
    }

    private EditText field(LinearLayout form, String hint, String value, boolean number) {
        TextView title = label(hint, 12, true);
        title.setTextColor(Color.LTGRAY);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(8);
        form.addView(title, titleParams);
        EditText input = new EditText(this);
        input.setText(value);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.GRAY);
        input.setSingleLine(!hint.toLowerCase(Locale.US).contains("text"));
        if (number) input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        form.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return input;
    }

    private View wrapScroll(View content) {
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(content);
        scroll.setFillViewport(true);
        scroll.setMinimumHeight(dp(240));
        return scroll;
    }

    private void immersive(AlertDialog dialog) {
        dialog.setOnDismissListener(d -> ImmersiveMode.apply(this));
        dialog.show();
        ImmersiveMode.apply(dialog.getWindow());
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (RuntimeException ignored) { }
        String segment = uri.getLastPathSegment();
        return segment == null ? "Imported file" : segment;
    }

    private static String stripExtension(String value) {
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }

    private static String safeFileName(String value) {
        String out = value == null ? "Vizzy-Native" : value.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return out.isEmpty() ? "Vizzy-Native" : out;
    }

    private static String nonBlank(String value, String fallback) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static String pretty(String value) {
        String[] parts = value.toLowerCase(Locale.US).split("_");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }

    private static String colorText(int color) {
        return String.format(Locale.US, "#%08X", color);
    }

    private static int parseColor(String value, int fallback) {
        try {
            String normalized = value.trim();
            if (normalized.matches("#[0-9a-fA-F]{6}")) normalized = "#FF" + normalized.substring(1);
            return Color.parseColor(normalized);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static float parseFloat(EditText input, float fallback) {
        String value = input.getText().toString().trim();
        return value.isEmpty() ? fallback : Float.parseFloat(value);
    }

    private static String floatText(float value) {
        return String.format(Locale.US, "%.4f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int makeEven(int value) {
        return (value & 1) == 0 ? value : value + 1;
    }

    private static String formatTime(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long secs = seconds % 60L;
        return hours > 0 ? String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
                : String.format(Locale.US, "%02d:%02d", minutes, secs);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onResume() {
        super.onResume();
        ImmersiveMode.apply(this);
    }

    @Override protected void onPause() {
        super.onPause();
        scheduleAutosave();
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (playback != null) playback.release();
        if (preview != null) preview.clearCache();
        super.onDestroy();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) ImmersiveMode.apply(this);
    }
}
