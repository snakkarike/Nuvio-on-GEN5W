package org.nuvio.gen5w.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.SoundEffectConstants;
import android.view.View;
import android.widget.Checkable;

/**
 * Native Material Design 3 (M3) Toggle Switch.
 *
 * Implements the official M3 switch specification for Android 4.4 KitKat (API 19):
 * - Stadium pill track (52dp x 32dp).
 * - Morphing thumb: 16dp diameter circle (unchecked) expanding to 24dp diameter circle (checked).
 * - Exact M3 tonal roles: primary/onPrimary when checked, surfaceContainerHighest/outline when unchecked.
 * - Mathematical Canvas rendering eliminating KitKat Switch drawable stretching and clipping.
 */
public class M3Switch extends View implements Checkable {

    public interface OnCheckedChangeListener {
        void onCheckedChanged(M3Switch view, boolean isChecked);
    }

    private boolean isChecked = false;
    private ThemeManager.Palette palette;
    private OnCheckedChangeListener listener;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF trackRect = new RectF();
    private final RectF strokeRect = new RectF();

    public M3Switch(Context context) {
        super(context);
        init();
    }

    public M3Switch(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setFocusable(true);
        setClickable(true);
        setPadding(0, 0, 0, 0);
        strokePaint.setStyle(Paint.Style.STROKE);
        fillPaint.setStyle(Paint.Style.FILL);
    }

    public void setPalette(ThemeManager.Palette palette) {
        this.palette = palette;
        invalidate();
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener listener) {
        this.listener = listener;
    }

    @Override
    public boolean isChecked() {
        return isChecked;
    }

    @Override
    public void setChecked(boolean checked) {
        if (this.isChecked != checked) {
            this.isChecked = checked;
            invalidate();
            if (listener != null) {
                listener.onCheckedChanged(this, checked);
            }
        }
    }

    @Override
    public void toggle() {
        setChecked(!isChecked);
    }

    @Override
    public boolean performClick() {
        playSoundEffect(SoundEffectConstants.CLICK);
        toggle();
        return super.performClick();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float density = getResources().getDisplayMetrics().density;
        int defaultWidth = Math.round(52f * density);
        int defaultHeight = Math.round(32f * density);

        int width = resolveSize(defaultWidth, widthMeasureSpec);
        int height = resolveSize(defaultHeight, heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        ThemeManager.Palette p = (palette != null) ? palette : ThemeManager.getDefaultPalette();
        float density = getResources().getDisplayMetrics().density;
        float cornerRadius = h / 2f;
        float strokeWidth = Math.max(1f, 1.5f * density);

        trackRect.set(0, 0, w, h);

        if (isChecked) {
            // M3 Checked: Filled with primary accent
            fillPaint.setColor(p.primary);
            canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, fillPaint);

            // Thumb: 24dp diameter (12dp radius), onPrimary fill, right side
            float thumbRadius = 12f * density;
            float cx = w - cornerRadius;
            float cy = h / 2f;

            fillPaint.setColor(p.onPrimary);
            canvas.drawCircle(cx, cy, thumbRadius, fillPaint);

            if (isFocused()) {
                strokePaint.setColor(p.onPrimary);
                strokePaint.setStrokeWidth(strokeWidth * 1.5f);
                float halfStroke = strokeWidth * 0.75f;
                strokeRect.set(halfStroke, halfStroke, w - halfStroke, h - halfStroke);
                canvas.drawRoundRect(strokeRect, cornerRadius - halfStroke, cornerRadius - halfStroke, strokePaint);
            }
        } else {
            // M3 Unchecked: surfaceContainerHighest fill + outline border
            fillPaint.setColor(p.surfaceContainerHighest);
            canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, fillPaint);

            boolean focused = isFocused();
            strokePaint.setColor(focused ? p.primary : p.outline);
            strokePaint.setStrokeWidth(focused ? strokeWidth * 2f : strokeWidth);
            float halfStroke = (focused ? strokeWidth : strokeWidth / 2f);
            strokeRect.set(halfStroke, halfStroke, w - halfStroke, h - halfStroke);
            float innerRadius = cornerRadius - halfStroke;
            canvas.drawRoundRect(strokeRect, innerRadius, innerRadius, strokePaint);

            // Thumb: 16dp diameter (8dp radius), outline fill, left side
            float thumbRadius = 8f * density;
            float cx = cornerRadius;
            float cy = h / 2f;

            fillPaint.setColor(focused ? p.primary : p.outline);
            canvas.drawCircle(cx, cy, thumbRadius, fillPaint);
        }
    }
}
