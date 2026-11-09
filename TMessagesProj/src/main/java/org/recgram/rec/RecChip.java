package org.recgram.rec;

import static org.recgram.RLocale.s;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.voip.VoIPService;

import java.util.Locale;

/** Отметка записи на экране звонка: «● REC 01:23» во время записи, «● Записать» — когда можно начать. */
@SuppressLint("ViewConstructor")
public class RecChip extends TextView {

    private final GradientDrawable bg = new GradientDrawable();
    private final Runnable tick = this::refresh;

    public RecChip(Context context) {
        super(context);
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        setTextColor(0xFFFFFFFF);
        setGravity(Gravity.CENTER);
        int h = AndroidUtilities.dp(12), v = AndroidUtilities.dp(5);
        setPadding(h, v, h, v);
        bg.setCornerRadius(AndroidUtilities.dp(14));
        setBackground(bg);
        setOnClickListener(x -> {
            if (CallRecordController.isRecording()) {
                CallRecordController.stop();
            } else if (CallRecordController.canStart()) {
                CallRecordController.startWithNotice(VoIPService.getSharedInstance());
            }
            refresh();
        });
        refresh();
    }

    private void refresh() {
        removeCallbacks(tick);
        if (CallRecordController.isRecording() && CallRecordController.isNoticePlaying()) {
            setText(s("🔊  REC  Предупреждение…", "🔊  REC  Notice…"));
            bg.setColor(0xE6FF3B30);
            setVisibility(View.VISIBLE);
        } else if (CallRecordController.isRecording()) {
            long sec = CallRecordController.elapsedMs() / 1000;
            setText(String.format(Locale.US, "●  REC  %02d:%02d", sec / 60, sec % 60));
            bg.setColor(0xE6FF3B30);
            setVisibility(View.VISIBLE);
        } else if (CallRecordController.isNoticePlaying()) {
            setText(s("🔊  Предупреждение о записи…", "🔊  Recording notice…"));
            bg.setColor(0xE6FF9500);
            setVisibility(View.VISIBLE);
        } else if (CallRecordController.canStart()) {
            setText(s("●  Записать", "●  Record"));
            bg.setColor(0x66000000);
            setVisibility(View.VISIBLE);
        } else {
            setVisibility(View.GONE);
        }
        if (isAttachedToWindow()) {
            postDelayed(tick, 500);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refresh();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(tick);
    }
}
