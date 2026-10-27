package app.piggy.desk;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import static app.piggy.desk.Ui.*;

/**
 * "Waiting for you": the replies the notification rules wrote that the user didn't approve in time (see Waiting), to
 * send, change or skip. Off-limits to agents, even when they may tap Buddy's screens: they'd approve their own.
 */
public class WaitingActivity extends Screen {
    private LinearLayout body;
    private final Runnable changed = this::render;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        LinearLayout top = row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPaddingRelative(dp(this, 4), dp(this, 4), dp(this, 16), dp(this, 4));
        ImageButton back = iconButton(this, R.drawable.ms_arrow_back, getString(R.string.perms_back));
        back.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)));
        back.setOnClickListener(v -> finish());
        top.addView(back);
        TextView t = title(this, getString(R.string.waiting_title), 18);
        t.setPaddingRelative(dp(this, 6), 0, 0, 0);
        top.addView(t, weight1());
        root.addView(top, full());
        ScrollView sv = new ScrollView(this);
        body = column(this);
        body.setPaddingRelative(dp(this, 16), dp(this, 4), dp(this, 16), dp(this, 32));
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(BG);
        frame.setFitsSystemWindows(true);
        frame.addView(root);
        setContentView(protect(frame));
    }

    @Override
    protected void onResume() {
        super.onResume();
        Waiting.listener = changed;
        render();
    }

    @Override
    protected void onPause() {
        if (Waiting.listener == changed) Waiting.listener = null;
        super.onPause();
    }

    private void render() {
        body.removeAllViews();
        List<Waiting.Item> items = Waiting.all(this);
        if (items.isEmpty()) {
            TextView e = text(this, getString(R.string.waiting_empty), 14, MUTED);
            e.setPaddingRelative(dp(this, 4), dp(this, 16), dp(this, 4), 0);
            body.addView(e, full());
            return;
        }
        for (int i = items.size() - 1; i >= 0; i--) body.addView(card(items.get(i)), gap(i == items.size() - 1 ? 8 : 14));
    }

    private View card(final Waiting.Item it) {
        return card(this, it, false);
    }

    /**
     * One waiting reply: where it stands, their messages, the reply to edit, and Skip / Edit / Send. inChat: inside
     * its own chat (ChatActivity), whose messages are already on screen, under a "Waiting for you" label.
     */
    static View card(final android.app.Activity a, final Waiting.Item it, boolean inChat) {
        LinearLayout card = column(a);
        card.setPaddingRelative(dp(a, 16), dp(a, 14), dp(a, 16), dp(a, 14));
        card.setBackground(inChat ? outlined(a, CARD, 18, Theme.withAlpha(ACCENT, 0x73), 1) : rounded(a, CARD, 16));

        LinearLayout head = row(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        if (inChat) {
            head.addView(icon(a, R.drawable.ms_chat_bubble, ACCENT_TEXT, 18));
            TextView lab = text(a, a.getString(R.string.waiting_title), 12, ACCENT_TEXT);
            lab.setTypeface(sans(a, 600));
            lab.setLetterSpacing(0.06f);
            lab.setPaddingRelative(dp(a, 8), 0, 0, 0);
            head.addView(lab, weight1());
        } else {
            ImageView ic = new ImageView(a);
            try {
                ic.setImageDrawable(a.getPackageManager().getApplicationIcon(it.pkg));
            } catch (PackageManager.NameNotFoundException e) {
                ic.setImageResource(R.drawable.ms_chat_bubble);
                ic.setImageTintList(android.content.res.ColorStateList.valueOf(MUTED));
            }
            head.addView(ic, new LinearLayout.LayoutParams(dp(a, 28), dp(a, 28)));
            TextView who = single(title(a, it.who + " · " + it.app, 15));
            who.setPaddingRelative(dp(a, 10), 0, dp(a, 8), 0);
            head.addView(who, weight1());
        }
        head.addView(monoText(a, android.text.format.DateUtils.formatSameDayTime(it.at, System.currentTimeMillis(),
                java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).toString(), 12, MUTED));
        card.addView(head, full());

        Waiting.State state = Waiting.state(it);
        String note = state == Waiting.State.WROTE_AGAIN ? a.getString(R.string.waiting_state_wrote_again, it.who)
                : state == Waiting.State.ANSWERED ? a.getString(R.string.waiting_state_answered, it.app)
                : state == Waiting.State.GONE ? a.getString(R.string.waiting_state_gone, it.app) : null;
        if (note != null) {
            TextView n = text(a, note, 13, state == Waiting.State.GONE ? MUTED : ERR);
            n.setPaddingRelative(0, dp(a, 10), 0, 0);
            card.addView(n, full());
        }

        if (!inChat) {
            card.addView(small(a, a.getString(R.string.waiting_their_messages)), gap(a, 12));
            TextView msgs = text(a, it.messages, 14, TEXT);
            msgs.setMaxLines(6);
            msgs.setEllipsize(android.text.TextUtils.TruncateAt.END);
            card.addView(msgs, gap(a, 2));
        }
        if (!it.why.isEmpty()) {
            TextView why = text(a, it.why, 13, MUTED);
            why.setPaddingRelative(0, dp(a, 8), 0, 0);
            card.addView(why, full());
        }

        card.addView(small(a, a.getString(R.string.waiting_reply)), gap(a, 12));
        final EditText draft = new EditText(a);
        draft.setText(it.draft);
        draft.setTextColor(TEXT);
        draft.setTextSize(15);
        draft.setTypeface(sans(a, 400));
        draft.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        draft.setMaxLines(inChat ? 5 : 8);
        draft.setGravity(Gravity.TOP | Gravity.START);
        draft.setBackground(outlined(a, BG, 12, OUTLINE, 1));
        draft.setPaddingRelative(dp(a, 12), dp(a, 10), dp(a, 12), dp(a, 10));
        card.addView(draft, gap(a, 4));

        LinearLayout actions = row(a);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        TextView open = text(a, a.getString(R.string.waiting_open_chat), 13, ACCENT_TEXT);
        open.setPaddingRelative(0, dp(a, 8), dp(a, 8), dp(a, 8));
        open.setOnClickListener(v -> a.startActivity(new Intent(a, ChatActivity.class).putExtra("id", it.chat)));
        if (inChat || ChatHub.get(a).sessions.find(it.chat) == null) open.setVisibility(View.INVISIBLE);
        actions.addView(open, weight1());
        Button skip = outlinedButton(a, a.getString(R.string.waiting_skip), MUTED);
        skip.setOnClickListener(v -> Waiting.remove(a, it.key));
        actions.addView(skip);
        Button edit = button(a, a.getString(R.string.waiting_edit), false);
        edit.setOnClickListener(v -> {
            draft.requestFocus();
            draft.setSelection(draft.getText().length());
            InputMethodManager im = a.getSystemService(InputMethodManager.class);
            if (im != null) im.showSoftInput(draft, InputMethodManager.SHOW_IMPLICIT);
        });
        actions.addView(edit, spaced(a));
        Button send = button(a, Waiting.canReply(it) ? a.getString(R.string.waiting_send)
                : a.getString(R.string.waiting_copy_open, it.app), true);
        send.setOnClickListener(v -> {
            String text = draft.getText().toString().trim();
            if (text.isEmpty()) return;
            Waiting.Sent how = Waiting.send(a, it, text);
            Toast.makeText(a, how == Waiting.Sent.SENT ? a.getString(R.string.waiting_sent, it.who)
                    : a.getString(R.string.waiting_copied, it.app), Toast.LENGTH_LONG).show();
        });
        actions.addView(send, spaced(a));
        card.addView(actions, gap(a, 12));
        return card;
    }

    private static TextView small(android.app.Activity a, String s) {
        return single(monoText(a, s, 12, MUTED));
    }

    private static LinearLayout.LayoutParams spaced(android.app.Activity a) {
        LinearLayout.LayoutParams p = wrap();
        p.setMarginStart(dp(a, 8));
        return p;
    }

    private static LinearLayout.LayoutParams gap(android.app.Activity a, int dpv) {
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(a, dpv);
        return p;
    }

    private LinearLayout.LayoutParams gap(int dpv) {
        return gap(this, dpv);
    }
}
