package dev.alastorkaneki.vizzywrapper;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

public final class SplashActivity extends Activity {
    private static final long SPLASH_DURATION_MS = 850L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ImmersiveMode.apply(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        ImageView waveform = new ImageView(this);
        waveform.setImageResource(R.drawable.ic_launcher_foreground);
        waveform.setScaleType(ImageView.ScaleType.FIT_CENTER);
        waveform.setContentDescription(getString(R.string.splash_description));
        FrameLayout.LayoutParams waveParams = new FrameLayout.LayoutParams(
                dp(420), dp(280), Gravity.CENTER_VERTICAL | Gravity.START
        );
        waveParams.leftMargin = -dp(64);
        root.addView(waveform, waveParams);

        TextView wordmark = new TextView(this);
        wordmark.setText("VIZZY");
        wordmark.setTextColor(Color.WHITE);
        wordmark.setTextSize(64f);
        wordmark.setLetterSpacing(0.08f);
        wordmark.setGravity(Gravity.CENTER);
        wordmark.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        FrameLayout.LayoutParams textParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        );
        textParams.leftMargin = dp(160);
        root.addView(wordmark, textParams);

        setContentView(root);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            root.animate()
                    .alpha(0f)
                    .setDuration(180L)
                    .withEndAction(() -> {
                        startActivity(new Intent(this, MainActivity.class));
                        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                        finish();
                    })
                    .start();
        }, SPLASH_DURATION_MS);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) ImmersiveMode.apply(this);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
