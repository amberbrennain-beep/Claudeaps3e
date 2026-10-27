package com.usbapp.lyrics;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

// The floating bubble shown over USB Audio Player PRO. Collapsed it is a small
// note icon whose colour says whether the playing song has lyrics; tapped it
// opens a card with the status and buttons to retry or choose another match.
// Built in code because it lives in a service window, not an activity.
final class FloatingPanel {

    interface Actions {
        void retry();

        void loadMatches();

        void choose(LrclibClient.Result r);
    }

    private static final int C_CARD = 0xF0212329;
    private static final int C_TEXT = 0xFFE2E2E6;
    private static final int C_TEXT2 = 0xFF9DA3AE;
    private static final int C_PRIMARY = 0xFFA8C7FA;
    private static final int C_ON_PRIMARY = 0xFF062E6F;
    private static final int C_OK = 0xFF66BB6A;
    private static final int C_WARN = 0xFFFFB74D;
    private static final int C_BAD = 0xFFE57373;
    private static final int C_IDLE = 0xFF5C6370;

    private final Context ctx;
    private final Actions actions;
    private final WindowManager wm;
    private WindowManager.LayoutParams lp;
    private LinearLayout root;
    private TextView bubble;
    private LinearLayout card;
    private TextView tvTrack, tvStatus;
    private LinearLayout matches;
    private ScrollView matchesScroll;
    private boolean shown, expanded;

    FloatingPanel(Context ctx, Actions actions) {
        this.ctx = ctx;
        this.actions = actions;
        this.wm = ctx.getSystemService(WindowManager.class);
    }

    void show() {
        if (shown || !Settings.canDrawOverlays(ctx)) return;
        if (root == null) build();
        try {
            wm.addView(root, lp);
            shown = true;
        } catch (Exception ignored) {
            // overlay permission revoked
        }
    }

    void hide() {
        if (!shown) return;
        try {
            wm.removeView(root);
        } catch (Exception ignored) {
        }
        shown = false;
    }

    void update(String trackLabel, LyricsService.State state, String detail) {
        if (root == null) build();
        tvTrack.setText(trackLabel.isEmpty() ? "Nothing playing" : trackLabel);
        tvStatus.setText(detail);
        int color;
        String glyph = "♪";
        switch (state) {
            case SAVED:
            case HAS_LYRICS:
                color = C_OK;
                glyph = "✓";
                break;
            case WORKING:
                color = C_PRIMARY;
                glyph = "…";
                break;
            case NOT_FOUND:
            case NO_FILE:
            case NO_FOLDER:
                color = C_WARN;
                glyph = "!";
                break;
            case ERROR:
                color = C_BAD;
                glyph = "!";
                break;
            default:
                color = C_IDLE;
        }
        ((GradientDrawable) bubble.getBackground()).setColor(color);
        bubble.setText(glyph);
        if (state == LyricsService.State.WORKING) matchesScroll.setVisibility(View.GONE);
    }

    void showMatchesLoading() {
        matches.removeAllViews();
        matches.addView(text("Searching all sources…", 13, C_TEXT2, false));
        matchesScroll.setVisibility(View.VISIBLE);
    }

    void showMatches(List<LrclibClient.Result> list, int songSec) {
        matches.removeAllViews();
        if (list.isEmpty()) {
            matches.addView(text("No source has lyrics for this title.", 13, C_TEXT2, false));
        }
        for (LrclibClient.Result r : list) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF2B2E35);
            bg.setCornerRadius(dp(10));
            row.setBackground(bg);
            row.addView(text(Lyrics.label(r.artist, r.title), 14, C_TEXT, true));
            StringBuilder sub = new StringBuilder();
            if (!r.album.isEmpty()) sub.append(r.album).append("  ·  ");
            if (r.durationSec > 0) sub.append(Lyrics.mmss(r.durationSec));
            else sub.append("length unknown");
            if (songSec > 0 && r.durationSec > 0 && Math.abs(r.durationSec - songSec) > 3) {
                sub.append(" (song is ").append(Lyrics.mmss(songSec)).append(")");
            }
            sub.append("  ·  ").append(r.synced != null ? "synced" : "unsynced")
                    .append("  ·  ").append(r.source);
            row.addView(text(sub.toString(), 12, C_TEXT2, false));
            row.setOnClickListener(v -> {
                matchesScroll.setVisibility(View.GONE);
                actions.choose(r);
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            p.topMargin = dp(6);
            matches.addView(row, p);
        }
        matchesScroll.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------- building

    private void build() {
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(8);
        lp.y = dp(160);

        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);

        bubble = new TextView(ctx);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(C_IDLE);
        circle.setStroke(dp(2), 0x55FFFFFF);
        bubble.setBackground(circle);
        bubble.setGravity(Gravity.CENTER);
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        bubble.setTypeface(Typeface.DEFAULT_BOLD);
        bubble.setText("♪");
        bubble.setElevation(dp(6));
        root.addView(bubble, new LinearLayout.LayoutParams(dp(52), dp(52)));
        bubble.setOnTouchListener(new DragListener());

        card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(C_CARD);
        cardBg.setCornerRadius(dp(18));
        card.setBackground(cardBg);
        card.setElevation(dp(8));
        card.setVisibility(View.GONE);

        TextView header = text("USB Lyrics", 12, C_PRIMARY, true);
        card.addView(header);
        tvTrack = text("", 16, C_TEXT, true);
        card.addView(tvTrack);
        tvStatus = text("", 13, C_TEXT2, false);
        tvStatus.setPadding(0, dp(4), 0, dp(8));
        card.addView(tvStatus);

        LinearLayout buttons = new LinearLayout(ctx);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(button("Find lyrics", true, v -> actions.retry()), weight());
        buttons.addView(button("Choose match", false, v -> actions.loadMatches()), weight());
        card.addView(buttons);

        matches = new LinearLayout(ctx);
        matches.setOrientation(LinearLayout.VERTICAL);
        matchesScroll = new ScrollView(ctx);
        matchesScroll.addView(matches);
        matchesScroll.setVisibility(View.GONE);
        card.addView(matchesScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(260)));

        LinearLayout bottom = new LinearLayout(ctx);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.END);
        bottom.addView(link("Settings", v -> {
            Intent i = new Intent(ctx, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            setExpanded(false);
        }));
        bottom.addView(link("Close", v -> setExpanded(false)));
        card.addView(bottom);

        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(300), LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(6);
        root.addView(card, cp);
    }

    private void setExpanded(boolean on) {
        expanded = on;
        card.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) matchesScroll.setVisibility(View.GONE);
        if (shown) wm.updateViewLayout(root, lp);
    }

    // Drag the bubble anywhere; a tap without movement toggles the card.
    private final class DragListener implements View.OnTouchListener {
        private int startX, startY;
        private float downX, downY;
        private boolean moved;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = lp.x;
                    startY = lp.y;
                    downX = e.getRawX();
                    downY = e.getRawY();
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true;
                    if (moved) {
                        lp.x = startX + (int) dx;
                        lp.y = Math.max(0, startY + (int) dy);
                        if (shown) wm.updateViewLayout(root, lp);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved) setExpanded(!expanded);
                    return true;
                default:
                    return false;
            }
        }
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String label, boolean primary, View.OnClickListener l) {
        Button b = new Button(ctx);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTextColor(primary ? C_ON_PRIMARY : C_PRIMARY);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        if (primary) bg.setColor(C_PRIMARY);
        else bg.setStroke(dp(1), 0xFF44474F);
        b.setBackground(bg);
        b.setMinHeight(dp(40));
        b.setOnClickListener(l);
        return b;
    }

    private TextView link(String label, View.OnClickListener l) {
        TextView t = text(label, 13, C_PRIMARY, true);
        t.setPadding(dp(12), dp(10), dp(4), dp(2));
        t.setOnClickListener(l);
        return t;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(40), 1f);
        p.setMarginEnd(dp(6));
        return p;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics()));
    }
}
