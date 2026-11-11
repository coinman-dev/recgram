package org.recgram.ui;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

/**
 * Строка «название слева — действие справа» на всю ширину. TextCell Telegram оставляет справа запас
 * под иконку (71dp), и длинные названия («Не приостанавливать приложение») обрезаются, хотя место есть.
 * Здесь названию достаётся всё, что не заняло действие; не влезает — многоточие в конце.
 */
public class SplitRow extends FrameLayout {

    public final TextView left, right;

    public SplitRow(Context ctx) {
        super(ctx);
        setBackground(Theme.createSelectorWithBackgroundDrawable(Theme.getColor(Theme.key_windowBackgroundWhite), Theme.getColor(Theme.key_listSelector)));
        left = label(ctx, Theme.key_windowBackgroundWhiteBlackText);
        left.setEllipsize(TextUtils.TruncateAt.END);
        right = label(ctx, Theme.key_windowBackgroundWhiteValueText);
        addView(left, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT, Gravity.LEFT | Gravity.CENTER_VERTICAL));
        addView(right, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL));
    }

    public SplitRow set(CharSequence title, CharSequence action) {
        left.setText(title);
        right.setText(action);
        return this;
    }

    private static TextView label(Context ctx, int colorKey) {
        TextView t = new TextView(ctx);
        t.setTextColor(Theme.getColor(colorKey));
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = AndroidUtilities.dp(50);
        int pad = AndroidUtilities.dp(21), gap = AndroidUtilities.dp(12);
        right.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        int leftMax = Math.max(0, width - pad * 2 - gap - right.getMeasuredWidth());
        left.measure(MeasureSpec.makeMeasureSpec(leftMax, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int pad = AndroidUtilities.dp(21);
        int w = r - l, h = b - t;
        left.layout(pad, 0, pad + left.getMeasuredWidth(), h);
        right.layout(w - pad - right.getMeasuredWidth(), 0, w - pad, h);
    }
}
