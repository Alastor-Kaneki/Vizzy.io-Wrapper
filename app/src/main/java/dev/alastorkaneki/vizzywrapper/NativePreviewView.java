package dev.alastorkaneki.vizzywrapper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.Arrays;

/** Native interactive project canvas with selection, drag, pinch-scale and rotation gestures. */
public final class NativePreviewView extends View {
    public interface Listener {
        void onLayerTransformChanged(ProjectModel.Layer layer);
        void onCanvasTapped();
    }

    private final NativeRenderer.AssetCache cache = new NativeRenderer.AssetCache(12);
    private final Paint mattePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF viewport = new RectF();
    private final GestureDetector gestures;
    private final ScaleGestureDetector scaleGestures;

    private ProjectModel project;
    private ProjectModel.Layer selectedLayer;
    private Listener listener;
    private long currentTimeMs;
    private float[] spectrum = new float[64];
    private float lastX;
    private float lastY;
    private boolean dragging;
    private float rotationStart;
    private float rotationBase;

    public NativePreviewView(Context context) {
        this(context, null);
    }

    public NativePreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        mattePaint.setColor(Color.rgb(8, 8, 8));
        selectionPaint.setColor(Color.rgb(211, 120, 255));
        selectionPaint.setStyle(Paint.Style.STROKE);
        selectionPaint.setStrokeWidth(dp(2));
        selectionPaint.setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(7), dp(5)}, 0f));

        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) {
                lastX = e.getX();
                lastY = e.getY();
                dragging = selectedLayer != null && !selectedLayer.locked && viewport.contains(lastX, lastY);
                return true;
            }

            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (listener != null) listener.onCanvasTapped();
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent e) {
                ProjectModel.Layer layer = selectedLayer;
                if (layer != null && !layer.locked) {
                    layer.rotation = Math.round(layer.rotation / 45f) * 45f;
                    notifyTransform(layer);
                }
                return true;
            }
        });

        scaleGestures = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private float baseWidth;
            private float baseHeight;

            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                if (selectedLayer == null || selectedLayer.locked) return false;
                baseWidth = selectedLayer.width;
                baseHeight = selectedLayer.height;
                return true;
            }

            @Override public boolean onScale(ScaleGestureDetector detector) {
                if (selectedLayer == null) return false;
                float factor = detector.getScaleFactor();
                selectedLayer.width = clamp(baseWidth * factor, 0.01f, 4f);
                selectedLayer.height = clamp(baseHeight * factor, 0.01f, 4f);
                notifyTransform(selectedLayer);
                return true;
            }
        });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setProject(ProjectModel project) {
        this.project = project;
        selectedLayer = project == null || project.layers.isEmpty() ? null : project.layers.get(project.layers.size() - 1);
        invalidate();
    }

    public ProjectModel getProject() {
        return project;
    }

    public void setSelectedLayer(ProjectModel.Layer layer) {
        selectedLayer = layer;
        invalidate();
    }

    public ProjectModel.Layer getSelectedLayer() {
        return selectedLayer;
    }

    public void setCurrentTimeMs(long currentTimeMs) {
        this.currentTimeMs = Math.max(0L, currentTimeMs);
        invalidate();
    }

    public void setSpectrum(float[] values) {
        spectrum = values == null ? new float[64] : Arrays.copyOf(values, values.length);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.BLACK);
        ProjectModel current = project;
        if (current == null) return;

        float availableWidth = getWidth();
        float availableHeight = getHeight();
        float aspect = current.canvasWidth / (float) Math.max(1, current.canvasHeight);
        float width = availableWidth;
        float height = width / aspect;
        if (height > availableHeight) {
            height = availableHeight;
            width = height * aspect;
        }
        float left = (availableWidth - width) / 2f;
        float top = (availableHeight - height) / 2f;
        viewport.set(left, top, left + width, top + height);

        canvas.drawRect(0, 0, getWidth(), getHeight(), mattePaint);
        NativeRenderer.render(canvas, viewport, current, currentTimeMs, spectrum, getContext().getContentResolver(), cache, false);
        drawSelection(canvas);
    }

    private void drawSelection(Canvas canvas) {
        ProjectModel.Layer layer = selectedLayer;
        if (layer == null || !layer.activeAt(currentTimeMs)) return;
        float x = viewport.left + layer.x * viewport.width();
        float y = viewport.top + layer.y * viewport.height();
        float w = layer.width * viewport.width();
        float h = layer.height * viewport.height();
        int save = canvas.save();
        canvas.translate(x, y);
        canvas.rotate(layer.rotation);
        canvas.drawRect(-w / 2f, -h / 2f, w / 2f, h / 2f, selectionPaint);
        canvas.drawCircle(w / 2f, h / 2f, dp(5), selectionPaint);
        canvas.restoreToCount(save);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        scaleGestures.onTouchEvent(event);
        gestures.onTouchEvent(event);
        if (event.getPointerCount() == 2 && selectedLayer != null && !selectedLayer.locked) {
            float dx = event.getX(1) - event.getX(0);
            float dy = event.getY(1) - event.getY(0);
            float angle = (float) Math.toDegrees(Math.atan2(dy, dx));
            if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
                rotationStart = angle;
                rotationBase = selectedLayer.rotation;
            } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                selectedLayer.rotation = rotationBase + angle - rotationStart;
                notifyTransform(selectedLayer);
            }
            return true;
        }
        if (dragging && selectedLayer != null && event.getActionMasked() == MotionEvent.ACTION_MOVE && !scaleGestures.isInProgress()) {
            float dx = event.getX() - lastX;
            float dy = event.getY() - lastY;
            selectedLayer.x = clamp(selectedLayer.x + dx / Math.max(1f, viewport.width()), -1f, 2f);
            selectedLayer.y = clamp(selectedLayer.y + dy / Math.max(1f, viewport.height()), -1f, 2f);
            lastX = event.getX();
            lastY = event.getY();
            notifyTransform(selectedLayer);
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            dragging = false;
        }
        return true;
    }

    private void notifyTransform(ProjectModel.Layer layer) {
        invalidate();
        if (listener != null) listener.onLayerTransformChanged(layer);
    }

    public void clearCache() {
        cache.clear();
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
