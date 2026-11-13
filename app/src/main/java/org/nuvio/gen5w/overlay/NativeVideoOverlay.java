package org.nuvio.gen5w.overlay;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.nuvio.gen5w.R;

/**
 * NativeVideoOverlay hosts the hardware-accelerated SurfaceView for ExoPlayer
 * layered on top of the WebView, along with an automotive safety interlock screen
 * that blocks video display whenever the vehicle is unparked.
 */
public class NativeVideoOverlay extends FrameLayout {

    public enum AspectRatioMode {
        FIT,
        FILL,
        STRETCH
    }

    private SurfaceView surfaceView;
    private FrameLayout videoSurfaceContainer;
    private LinearLayout safetyWarningContainer;
    private TextView safetyTitleText;
    private TextView safetyDescText;

    private boolean isParked = true;
    private AspectRatioMode aspectRatioMode = AspectRatioMode.FIT;
    private int videoWidth = 0;
    private int videoHeight = 0;

    public NativeVideoOverlay(Context context) {
        super(context);
        init(context);
    }

    public NativeVideoOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public NativeVideoOverlay(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setBackgroundColor(Color.BLACK);

        // 1. SurfaceView container for video
        videoSurfaceContainer = new FrameLayout(context);
        LayoutParams surfaceContainerParams = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
        );
        addView(videoSurfaceContainer, surfaceContainerParams);

        surfaceView = new SurfaceView(context);
        LayoutParams surfaceParams = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
        );
        videoSurfaceContainer.addView(surfaceView, surfaceParams);

        // 2. Safety Warning Screen
        safetyWarningContainer = new LinearLayout(context);
        safetyWarningContainer.setOrientation(LinearLayout.VERTICAL);
        safetyWarningContainer.setGravity(Gravity.CENTER);
        safetyWarningContainer.setBackgroundColor(Color.parseColor("#EE0A0A0C"));
        safetyWarningContainer.setPadding(48, 32, 48, 32);

        ImageView warningIcon = new ImageView(context);
        warningIcon.setImageResource(R.drawable.ic_safety_warning);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(120, 120);
        iconParams.bottomMargin = 24;
        safetyWarningContainer.addView(warningIcon, iconParams);

        safetyTitleText = new TextView(context);
        safetyTitleText.setText(R.string.safety_warning_title);
        safetyTitleText.setTextColor(Color.WHITE);
        safetyTitleText.setTextSize(22);
        safetyTitleText.setGravity(Gravity.CENTER);
        safetyTitleText.getPaint().setFakeBoldText(true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        titleParams.bottomMargin = 12;
        safetyWarningContainer.addView(safetyTitleText, titleParams);

        safetyDescText = new TextView(context);
        safetyDescText.setText(R.string.safety_warning_desc);
        safetyDescText.setTextColor(Color.parseColor("#CCCCCC"));
        safetyDescText.setTextSize(15);
        safetyDescText.setGravity(Gravity.CENTER);
        safetyWarningContainer.addView(safetyDescText);

        TextView parkNotice = new TextView(context);
        parkNotice.setText(R.string.park_vehicle_notice);
        parkNotice.setTextColor(Color.parseColor("#FF9500"));
        parkNotice.setTextSize(14);
        parkNotice.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams noticeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        noticeParams.topMargin = 16;
        safetyWarningContainer.addView(parkNotice, noticeParams);

        LayoutParams warningParams = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        addView(safetyWarningContainer, warningParams);

        // Initially parked: safety warning hidden
        safetyWarningContainer.setVisibility(View.GONE);
    }

    public SurfaceView getSurfaceView() {
        return surfaceView;
    }

    public void setVehicleParked(boolean parked) {
        this.isParked = parked;
        post(new Runnable() {
            @Override
            public void run() {
                if (isParked) {
                    safetyWarningContainer.setVisibility(View.GONE);
                    surfaceView.setVisibility(View.VISIBLE);
                } else {
                    safetyWarningContainer.setVisibility(View.VISIBLE);
                    surfaceView.setVisibility(View.INVISIBLE);
                }
            }
        });
    }

    public boolean isVehicleParked() {
        return isParked;
    }

    public void setVideoSize(int width, int height) {
        this.videoWidth = width;
        this.videoHeight = height;
        post(new Runnable() {
            @Override
            public void run() {
                adjustAspectRatio();
            }
        });
    }

    public void setAspectRatioMode(AspectRatioMode mode) {
        this.aspectRatioMode = mode;
        post(new Runnable() {
            @Override
            public void run() {
                adjustAspectRatio();
            }
        });
    }

    private void adjustAspectRatio() {
        if (videoWidth <= 0 || videoHeight <= 0) {
            return;
        }

        int containerWidth = getWidth();
        int containerHeight = getHeight();
        if (containerWidth <= 0 || containerHeight <= 0) {
            return;
        }

        LayoutParams params = (LayoutParams) surfaceView.getLayoutParams();

        switch (aspectRatioMode) {
            case FILL:
                // Scale to fill, cropping outside bounds
                float ratioFill = Math.max((float) containerWidth / videoWidth, (float) containerHeight / videoHeight);
                params.width = (int) (videoWidth * ratioFill);
                params.height = (int) (videoHeight * ratioFill);
                break;

            case STRETCH:
                // Stretch to container
                params.width = ViewGroup.LayoutParams.MATCH_PARENT;
                params.height = ViewGroup.LayoutParams.MATCH_PARENT;
                break;

            case FIT:
            default:
                // Fit inside letterbox/pillarbox
                float ratioFit = Math.min((float) containerWidth / videoWidth, (float) containerHeight / videoHeight);
                params.width = (int) (videoWidth * ratioFit);
                params.height = (int) (videoHeight * ratioFit);
                break;
        }

        params.gravity = Gravity.CENTER;
        surfaceView.setLayoutParams(params);
        surfaceView.requestLayout();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        adjustAspectRatio();
    }
}

