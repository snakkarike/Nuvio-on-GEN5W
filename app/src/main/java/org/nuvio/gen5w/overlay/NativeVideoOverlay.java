package org.nuvio.gen5w.overlay;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.ui.PlayerView;

import org.nuvio.gen5w.R;

/**
 * NativeVideoOverlay hosts ExoPlayer's hardware-accelerated PlayerView
 * layered on top of the WebView, along with an automotive safety interlock screen
 * that blocks video display whenever the vehicle is unparked.
 */
public class NativeVideoOverlay extends FrameLayout {

    public interface CloseListener {
        void onClose();
    }

    public enum AspectRatioMode {
        FIT,
        FILL,
        STRETCH
    }

    private PlayerView playerView;
    private LinearLayout safetyWarningContainer;
    private TextView safetyTitleText;
    private TextView safetyDescText;
    private Button backButton;
    private CloseListener closeListener;

    private boolean isParked = true;
    private AspectRatioMode aspectRatioMode = AspectRatioMode.FIT;

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

    public void setCloseListener(CloseListener listener) {
        this.closeListener = listener;
    }

    private void init(Context context) {
        setBackgroundColor(Color.BLACK);
        setClickable(true);
        setFocusable(true);

        // 1. ExoPlayer PlayerView (handles Surface lifecycle, aspect ratio, buffering spinner & controls)
        playerView = new PlayerView(context);
        playerView.setUseController(true);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);
        playerView.setKeepScreenOn(true);
        LayoutParams playerParams = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
        );
        addView(playerView, playerParams);

        // 2. "Back to Browse" Button pinned to top-left
        backButton = new Button(context);
        backButton.setText("← Back to Browse");
        backButton.setTextColor(Color.WHITE);
        backButton.setTextSize(14);
        backButton.setBackgroundColor(Color.parseColor("#CC12131A"));
        backButton.setPadding(dp(14), dp(8), dp(14), dp(8));
        backButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (closeListener != null) {
                    closeListener.onClose();
                }
            }
        });
        LayoutParams backParams = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(44),
                Gravity.TOP | Gravity.START
        );
        backParams.setMargins(dp(16), dp(16), 0, 0);
        addView(backButton, backParams);

        // 3. Safety Warning Screen
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    public PlayerView getPlayerView() {
        return playerView;
    }

    public void setVehicleParked(boolean parked) {
        this.isParked = parked;
        post(new Runnable() {
            @Override
            public void run() {
                if (isParked) {
                    safetyWarningContainer.setVisibility(View.GONE);
                    playerView.setVisibility(View.VISIBLE);
                } else {
                    safetyWarningContainer.setVisibility(View.VISIBLE);
                    playerView.setVisibility(View.INVISIBLE);
                }
            }
        });
    }

    public boolean isVehicleParked() {
        return isParked;
    }

    public void setVideoSize(int width, int height) {
        // Video sizing is managed automatically by PlayerView and AspectRatioFrameLayout
    }

    public void setAspectRatioMode(AspectRatioMode mode) {
        this.aspectRatioMode = mode;
        post(new Runnable() {
            @Override
            public void run() {
                if (playerView == null) return;
                switch (aspectRatioMode) {
                    case FILL:
                        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
                        break;
                    case STRETCH:
                        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
                        break;
                    case FIT:
                    default:
                        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
                        break;
                }
            }
        });
    }
}
