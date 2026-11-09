package org.recgram.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;

/**
 * Надпись «RecGram» вместо логотипа-надписи Telegram (telegram_logo_2) в заголовке списка чатов.
 * Рисуется белым, как исходный вектор, — цвет темы накладывается через ColorFilter (MULTIPLY).
 * Размер — как у исходного логотипа: 90 × 20.4 dp.
 */
public class Wordmark extends Drawable {

    private static final String TEXT = "RecGram";
    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final int width;
    private final int height;

    public Wordmark() {
        paint.setColor(0xFFFFFFFF);
        paint.setTypeface(AndroidUtilities.bold());
        width = AndroidUtilities.dp(90);
        height = AndroidUtilities.dp(20.4f);
        // подобрать размер шрифта, чтобы надпись заняла ширину логотипа и не превысила его высоту
        float size = AndroidUtilities.dp(24);
        paint.setTextSize(size);
        float w = paint.measureText(TEXT);
        size = size * width / w;
        paint.setTextSize(size);
        Paint.FontMetrics fm = paint.getFontMetrics();
        float capHeight = -fm.ascent * 0.72f;
        if (capHeight > height) {
            paint.setTextSize(size * height / capHeight);
        }
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        Paint.FontMetrics fm = paint.getFontMetrics();
        float capHeight = -fm.ascent * 0.72f;
        float x = b.left + (b.width() - paint.measureText(TEXT)) / 2f;
        float y = b.top + (b.height() + capHeight) / 2f;
        canvas.drawText(TEXT, x, y, paint);
    }

    @Override
    public int getIntrinsicWidth() {
        return width;
    }

    @Override
    public int getIntrinsicHeight() {
        return height;
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
