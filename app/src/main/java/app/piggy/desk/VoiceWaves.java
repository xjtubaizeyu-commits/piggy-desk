package app.piggy.desk;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * Glowing voice waves under the bubble while you talk to it with the chat closed (Tap the bubble to talk):
 * three bright sine waves that swell with your voice. Draws itself every frame while shown.
 */
final class VoiceWaves extends View {
    private static final int[] COLORS = {Ui.ACCENT, 0xFF7FD4FF, 0xFFB48CFF};
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private float target, level, phase;
    /** false: starting or transcribing, the waves settle into a calm shimmer. */
    private boolean live;

    VoiceWaves(Context c) {
        super(c);
        setLayerType(LAYER_TYPE_SOFTWARE, null); // for the blur
        float d = c.getResources().getDisplayMetrics().density;
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth(4 * d);
        glow.setMaskFilter(new BlurMaskFilter(5 * d, BlurMaskFilter.Blur.NORMAL));
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.6f * d);
        line.setStrokeCap(Paint.Cap.ROUND);
    }

    /** Microphone loudness, 0..1. */
    void level(float v) {
        target = Math.max(target, v);
    }

    void setLive(boolean on) {
        live = on;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), mid = h / 2f;
        // quick to rise, slower to fall, like a VU meter
        level += ((live ? target : 0.12f) - level) * (target > level ? 0.45f : 0.12f);
        target *= 0.85f;
        phase += live ? 0.16f : 0.07f;
        float amp = (0.12f + 0.88f * Math.min(1f, level * 1.4f)) * (h / 2f - 4);
        for (int k = 0; k < COLORS.length; k++) {
            float freq = 1.6f + k * 0.55f, shift = phase * (1f + k * 0.35f) + k * 2.1f;
            float a = amp * (1f - k * 0.22f);
            path.reset();
            for (int i = 0; i <= 48; i++) {
                float x = w * i / 48f, t = x / w;
                float envelope = (float) Math.pow(Math.sin(Math.PI * t), 2); // pinched at both ends
                float y = mid + a * envelope * (float) Math.sin(2 * Math.PI * freq * t + shift);
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            int col = COLORS[k];
            glow.setColor((col & 0x00FFFFFF) | 0x99000000);
            line.setColor(col);
            c.drawPath(path, glow);
            c.drawPath(path, line);
        }
        if (getVisibility() == VISIBLE) postInvalidateOnAnimation();
    }
}
