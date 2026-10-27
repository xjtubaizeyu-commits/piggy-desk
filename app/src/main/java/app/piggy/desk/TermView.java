package app.piggy.desk;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.OverScroller;

/**
 * Draws a {@link TermSession} and turns touches and keys into terminal input: tap for the keyboard,
 * drag to scroll back, pinch to zoom, long-press for the menu. Box-drawing lines are drawn directly so
 * borders always connect.
 */
final class TermView extends View {
    interface Callbacks {
        void onLongPress();

        /** "Paste" from the selection toolbar. */
        void onPaste();

        /** Called after a key used the sticky Ctrl/Alt from the extra keys, so they can reset. */
        void onModifiersUsed();
    }

    private TermSession session;
    private Callbacks callbacks;
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint fill = new Paint();
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface regular, bold;
    private float fontSp, cellW, cellH, ascent;
    private final float padX, padY;
    private int scrollBack; // rows scrolled back into history; 0 = live
    private float scrollRemainder;
    private boolean cursorOn = true;
    boolean ctrl, alt; // sticky modifiers from the extra keys
    private final GestureDetector gestures;
    private final ScaleGestureDetector scaler;
    private final OverScroller flinger;
    private final RectF r = new RectF();
    private final StringBuilder run = new StringBuilder();

    // selection, in stable line numbers (TermEmulator.scrolled + row) so it stays on its text
    private boolean selecting;
    private long selA, selB;  // start / end line
    private int colA, colB;   // start / end column, inclusive
    private int dragging;     // 0 none, 1 start handle, 2 end handle
    /** The finger that long-pressed is still down and extends the selection from the pressed word. */
    private boolean fromLongPress;
    private long wordLn;
    private int wordA, wordB;
    private android.view.ActionMode actionMode;
    private final float handleR;

    private final Runnable blink = new Runnable() {
        @Override
        public void run() {
            cursorOn = !cursorOn;
            invalidate();
            postDelayed(this, 600);
        }
    };

    TermView(Context c) {
        super(c);
        setFocusable(true);
        setFocusableInTouchMode(true);
        regular = Ui.mono(c, 400);
        bold = Ui.mono(c, 700);
        padX = Ui.dp(c, 12);
        padY = Ui.dp(c, 8);
        handleR = Ui.dp(c, 9);
        setFontSize(Prefs.termFont(c));
        flinger = new OverScroller(c);
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                flinger.forceFinished(true);
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (selecting) clearSelection();
                else showKeyboard();
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                scrollBy(dy);
                return true;
            }

            @Override
            public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (session == null || session.emulator.onAltScreen()) return false;
                flinger.fling(0, (int) (scrollBack * cellH), 0, (int) vy, 0, 0, 0,
                        (int) (session.emulator.historySize() * cellH));
                postInvalidateOnAnimation();
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                startSelection(e.getX(), e.getY());
            }
        });
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                setFontSize(Math.max(8f, Math.min(30f, fontSp * d.getScaleFactor())));
                Prefs.setTermFont(getContext(), fontSp);
                resizeGrid();
                return true;
            }
        });
    }

    void attach(TermSession s, Callbacks cb) {
        session = s;
        callbacks = cb;
        scrollBack = 0;
        resizeGrid();
        invalidate();
    }

    void setFontSize(float sp) {
        fontSp = sp;
        text.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, getResources().getDisplayMetrics()));
        text.setTypeface(regular);
        cellW = text.measureText("M");
        Paint.FontMetrics fm = text.getFontMetrics();
        cellH = (float) Math.ceil((fm.descent - fm.ascent) * 1.12f);
        ascent = -fm.ascent + (cellH - (fm.descent - fm.ascent)) / 2f;
        invalidate();
    }

    float fontSize() {
        return fontSp;
    }

    // ---------------------------------------------------------------- layout

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        resizeGrid();
    }

    void resizeGrid() {
        if (session == null || getWidth() == 0) return;
        int cols = Math.max(10, (int) ((getWidth() - 2 * padX) / cellW));
        int rows = Math.max(4, (int) ((getHeight() - 2 * padY) / cellH));
        session.resize(rows, cols);
    }

    int[] gridFor(int w, int h) {
        return new int[]{Math.max(4, (int) ((h - 2 * padY) / cellH)), Math.max(10, (int) ((w - 2 * padX) / cellW))};
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(TermColors.DEFAULT_BG);
        if (session == null) return;
        if (flinger.computeScrollOffset()) {
            setScrollBack(Math.round(flinger.getCurrY() / cellH));
            postInvalidateOnAnimation();
        }
        TermEmulator em = session.emulator;
        synchronized (em) {
            int rows = em.rows, cols = em.cols;
            scrollBack = Math.min(scrollBack, em.historySize());
            for (int y = 0; y < rows; y++) {
                TermEmulator.Line l = em.line(y - scrollBack);
                if (l == null) continue;
                float top = padY + y * cellH;
                drawLine(canvas, l, Math.min(cols, l.cp.length), top);
            }
            if (selecting) drawSelection(canvas, em);
            // cursor: a rounded accent block that blinks while focused
            if (scrollBack == 0 && em.cursorVisible && (cursorOn || !hasFocus()) && !session.exited) {
                float x = padX + em.cx * cellW, y = padY + em.cy * cellH;
                TermEmulator.Line l = em.line(em.cy);
                boolean wide = l != null && em.cx + 1 < l.cp.length && l.cp[em.cx + 1] == TermEmulator.WIDE_TAIL;
                r.set(x, y + 1, x + cellW * (wide ? 2 : 1), y + cellH - 1);
                float rad = Ui.dp(getContext(), 2);
                if (hasFocus()) {
                    fill.setColor(TermColors.CURSOR);
                    canvas.drawRoundRect(r, rad, rad, fill);
                    int cp = l == null ? 0 : l.cp[em.cx];
                    if (cp > ' ') {
                        text.setColor(TermColors.DEFAULT_BG);
                        text.setTypeface((l.st[em.cx] & TermEmulator.BOLD) != 0 ? bold : regular);
                        drawCell(canvas, cp, x, y);
                    }
                } else {
                    line.setStyle(Paint.Style.STROKE);
                    line.setStrokeWidth(Ui.dp(getContext(), 1));
                    line.setColor(TermColors.CURSOR);
                    canvas.drawRoundRect(r, rad, rad, line);
                    line.setStyle(Paint.Style.FILL);
                }
            }
        }
        // scrolled back: a small hint showing how far
        if (scrollBack > 0) {
            String s = "↑ " + scrollBack;
            text.setTypeface(regular);
            float w = text.measureText(s) + Ui.dp(getContext(), 16);
            r.set(getWidth() - w - padX, padY, getWidth() - padX, padY + cellH + Ui.dp(getContext(), 6));
            fill.setColor(TermColors.CURSOR);
            canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, fill);
            text.setColor(0xFFFFFFFF);
            canvas.drawText(s, r.left + Ui.dp(getContext(), 8), r.top + Ui.dp(getContext(), 3) + ascent, text);
        }
    }

    private void drawLine(Canvas canvas, TermEmulator.Line l, int cols, float top) {
        // backgrounds (merged runs), then text
        int x = 0;
        while (x < cols) {
            int bg = cellBg(l.st[x]);
            int end = x + 1;
            while (end < cols && cellBg(l.st[end]) == bg) end++;
            if (bg != TermColors.DEFAULT_BG) {
                fill.setColor(bg);
                canvas.drawRect(padX + x * cellW, top, padX + end * cellW, top + cellH, fill);
            }
            x = end;
        }
        x = 0;
        while (x < cols) {
            int cp = l.cp[x];
            long st = l.st[x];
            if (cp == 0 || cp == ' ' || cp == TermEmulator.WIDE_TAIL || (st & TermEmulator.INVISIBLE) != 0) {
                x++;
                continue;
            }
            applyStyle(st);
            float px = padX + x * cellW;
            if (cp >= 0x2500 && cp <= 0x257F && drawBox(canvas, cp, px, top)) {
                x++;
                continue;
            }
            if (cp < 0x7F) { // ASCII: draw a run in one go (monospaced, so it lines up)
                run.setLength(0);
                int end = x;
                while (end < cols && l.cp[end] > ' ' && l.cp[end] < 0x7F && l.st[end] == st) {
                    run.append((char) l.cp[end]);
                    end++;
                }
                if (end == x) end = x + 1;
                canvas.drawText(run, 0, run.length(), px, top + ascent, text);
                decorate(canvas, st, px, (end - x) * cellW, top);
                x = end;
                continue;
            }
            drawCell(canvas, cp, px, top);
            decorate(canvas, st, px, cellW, top);
            x++;
        }
        text.setTextSkewX(0);
    }

    private int cellBg(long st) {
        boolean inv = (st & TermEmulator.INVERSE) != 0;
        return inv ? TermColors.resolve(TermEmulator.fg(st), TermColors.DEFAULT_FG)
                : TermColors.resolve(TermEmulator.bg(st), TermColors.DEFAULT_BG);
    }

    private void applyStyle(long st) {
        boolean inv = (st & TermEmulator.INVERSE) != 0;
        int fgc = TermEmulator.fg(st);
        // bold with a basic colour uses its bright variant, like most terminals
        if ((st & TermEmulator.BOLD) != 0 && fgc >= 1 && fgc <= 8) fgc += 8;
        int color = inv ? TermColors.resolve(TermEmulator.bg(st), TermColors.DEFAULT_BG)
                : TermColors.resolve(fgc, TermColors.DEFAULT_FG);
        if ((st & TermEmulator.DIM) != 0) color = (color & 0x00FFFFFF) | 0x99000000;
        text.setColor(color);
        line.setColor(color);
        text.setTypeface((st & TermEmulator.BOLD) != 0 ? bold : regular);
        text.setTextSkewX((st & TermEmulator.ITALIC) != 0 ? -0.2f : 0);
    }

    private void decorate(Canvas canvas, long st, float x, float w, float top) {
        float t = Math.max(1f, cellH / 16f);
        if ((st & TermEmulator.UNDERLINE) != 0) {
            line.setStrokeWidth(t);
            canvas.drawLine(x, top + cellH - t * 1.5f, x + w, top + cellH - t * 1.5f, line);
        }
        if ((st & TermEmulator.STRIKE) != 0) {
            line.setStrokeWidth(t);
            canvas.drawLine(x, top + cellH / 2, x + w, top + cellH / 2, line);
        }
    }

    /** One non-ASCII character, centred in its cell(s) so fallback glyphs of other widths still line up. */
    private void drawCell(Canvas canvas, int cp, float x, float top) {
        String s = new String(Character.toChars(cp));
        int cells = TermColors.width(cp) == 2 ? 2 : 1;
        float w = text.measureText(s);
        float slot = cells * cellW;
        float dx = w > 0 && w < slot * 1.5f ? (slot - w) / 2 : 0;
        if (w > slot * 1.05f) { // too wide for its cells: shrink to fit
            canvas.save();
            canvas.scale(slot / w, 1f, x, top);
            canvas.drawText(s, x, top + ascent, text);
            canvas.restore();
        } else {
            canvas.drawText(s, x + dx, top + ascent, text);
        }
    }

    /** Light/heavy/double box lines and corners, drawn edge to edge. Returns false if not handled. */
    private boolean drawBox(Canvas c, int cp, float x, float top) {
        // segments: up, right, down, left; 1 = light, 2 = heavy
        int u = 0, rt = 0, d = 0, lf = 0;
        boolean round = false;
        switch (cp) {
            case 0x2500: rt = lf = 1; break;
            case 0x2501: rt = lf = 2; break;
            case 0x2502: u = d = 1; break;
            case 0x2503: u = d = 2; break;
            case 0x250C: rt = d = 1; break;
            case 0x250F: rt = d = 2; break;
            case 0x2510: lf = d = 1; break;
            case 0x2513: lf = d = 2; break;
            case 0x2514: u = rt = 1; break;
            case 0x2517: u = rt = 2; break;
            case 0x2518: u = lf = 1; break;
            case 0x251B: u = lf = 2; break;
            case 0x251C: u = d = rt = 1; break;
            case 0x2523: u = d = rt = 2; break;
            case 0x2524: u = d = lf = 1; break;
            case 0x252B: u = d = lf = 2; break;
            case 0x252C: lf = rt = d = 1; break;
            case 0x2533: lf = rt = d = 2; break;
            case 0x2534: lf = rt = u = 1; break;
            case 0x253B: lf = rt = u = 2; break;
            case 0x253C: u = d = lf = rt = 1; break;
            case 0x254B: u = d = lf = rt = 2; break;
            case 0x2574: lf = 1; break;
            case 0x2575: u = 1; break;
            case 0x2576: rt = 1; break;
            case 0x2577: d = 1; break;
            case 0x256D: rt = d = 1; round = true; break;
            case 0x256E: lf = d = 1; round = true; break;
            case 0x256F: lf = u = 1; round = true; break;
            case 0x2570: rt = u = 1; round = true; break;
            default: return false;
        }
        float cx = x + cellW / 2, cy = top + cellH / 2, x2 = x + cellW, y2 = top + cellH;
        float light = Math.max(1f, cellW / 9f);
        line.setStrokeCap(Paint.Cap.BUTT);
        if (round) {
            float rad = Math.min(cellW, cellH) / 2;
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(light);
            android.graphics.Path p = new android.graphics.Path();
            if (rt == 1 && d == 1) { p.moveTo(x2, cy); p.quadTo(cx, cy, cx, y2); }
            else if (lf == 1 && d == 1) { p.moveTo(x, cy); p.quadTo(cx, cy, cx, y2); }
            else if (lf == 1 && u == 1) { p.moveTo(x, cy); p.quadTo(cx, cy, cx, top); }
            else { p.moveTo(x2, cy); p.quadTo(cx, cy, cx, top); }
            c.drawPath(p, line);
            line.setStyle(Paint.Style.FILL);
            return true;
        }
        if (u > 0) { line.setStrokeWidth(u == 2 ? light * 2 : light); c.drawLine(cx, top, cx, cy + (lf + rt > 0 ? light / 2 : 0), line); }
        if (d > 0) { line.setStrokeWidth(d == 2 ? light * 2 : light); c.drawLine(cx, cy - (lf + rt > 0 ? light / 2 : 0), cx, y2, line); }
        if (lf > 0) { line.setStrokeWidth(lf == 2 ? light * 2 : light); c.drawLine(x, cy, cx + (u + d > 0 ? light / 2 : 0), cy, line); }
        if (rt > 0) { line.setStrokeWidth(rt == 2 ? light * 2 : light); c.drawLine(cx - (u + d > 0 ? light / 2 : 0), cy, x2, cy, line); }
        return true;
    }

    // ---------------------------------------------------------------- scrolling

    private void scrollBy(float dy) {
        if (session == null) return;
        TermEmulator em = session.emulator;
        scrollRemainder += dy;
        int lines = (int) (scrollRemainder / cellH);
        if (lines == 0) return;
        scrollRemainder -= lines * cellH;
        if (em.onAltScreen()) {
            // full-screen apps (vim, less, htop): wheel events if they asked for the mouse, else arrow keys
            for (int i = 0; i < Math.abs(lines); i++) {
                if (em.mouseTracking) session.write(lines < 0 ? "\033[<64;1;1M" : "\033[<65;1;1M");
                else session.write(lines < 0 ? (em.appCursor ? "\033OA" : "\033[A") : (em.appCursor ? "\033OB" : "\033[B"));
            }
            return;
        }
        setScrollBack(scrollBack - lines);
    }

    private void setScrollBack(int v) {
        int max = session == null ? 0 : session.emulator.historySize();
        int nv = Math.max(0, Math.min(max, v));
        if (nv != scrollBack) {
            scrollBack = nv;
            invalidate();
        }
    }

    void scrollToBottom() {
        flinger.forceFinished(true);
        setScrollBack(0);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (selecting) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = handleAt(e.getX(), e.getY());
                    fromLongPress = false;
                    if (dragging != 0) {
                        if (actionMode != null) actionMode.hide(60_000);
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (dragging != 0) {
                        moveHandle(e.getX(), e.getY());
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging != 0) {
                        dragging = 0;
                        if (actionMode != null) {
                            actionMode.invalidateContentRect();
                            actionMode.hide(0);
                        }
                        if (fromLongPress) {
                            gestures.onTouchEvent(e); // it saw this finger go down: let it end the long press
                        } else {
                            // a handle drag the detector never saw start: cancel it, or it takes the lift for a
                            // tap (left over from the long press) and clears the selection
                            MotionEvent c = MotionEvent.obtain(e);
                            c.setAction(MotionEvent.ACTION_CANCEL);
                            gestures.onTouchEvent(c);
                            c.recycle();
                        }
                        fromLongPress = false;
                        return true;
                    }
                    break;
                default:
                    break;
            }
        }
        scaler.onTouchEvent(e);
        if (!scaler.isInProgress()) gestures.onTouchEvent(e);
        return true;
    }

    // ---------------------------------------------------------------- selection

    private long lineFor(float y) {
        TermEmulator em = session.emulator;
        int r = Math.max(0, Math.min(em.rows - 1, (int) ((y - padY) / cellH)));
        return em.scrolled + r - scrollBack;
    }

    private int colFor(float x) {
        return Math.max(0, Math.min(session.emulator.cols - 1, (int) ((x - padX) / cellW)));
    }

    /** Characters that belong to a "word" for long-press: words, paths and links. */
    private static boolean wordChar(int c) {
        return Character.isLetterOrDigit(c) || "-_./~:@%+=?&#!*,;$'()[]".indexOf(c) >= 0 && c != 0;
    }

    private void startSelection(float x, float y) {
        if (session == null) return;
        TermEmulator em = session.emulator;
        synchronized (em) {
            long ln = lineFor(y);
            int col = colFor(x);
            TermEmulator.Line l = em.lineAt(ln);
            int a = col, b = col;
            if (l != null && col < l.cp.length && l.cp[col] > ' ' && wordChar(l.cp[col])) {
                while (a > 0 && (l.cp[a - 1] == TermEmulator.WIDE_TAIL || wordChar(l.cp[a - 1]))) a--;
                while (b < l.cp.length - 1 && (l.cp[b + 1] == TermEmulator.WIDE_TAIL || wordChar(l.cp[b + 1]))) b++;
                // don't drag sentence punctuation into links and paths
                while (b > a && ".,;:!?)'".indexOf(l.cp[b]) >= 0) b--;
            }
            selA = selB = ln;
            colA = a;
            colB = b;
            wordLn = ln;
            wordA = a;
            wordB = b;
        }
        selecting = true;
        dragging = 2; // keep moving the finger to extend
        fromLongPress = true;
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        invalidate();
        showToolbar();
    }

    void selectAll() {
        if (session == null) return;
        TermEmulator em = session.emulator;
        synchronized (em) {
            selA = em.scrolled - em.historySize();
            colA = 0;
            int last = em.rows - 1;
            while (last > 0 && em.line(last).text().isEmpty()) last--;
            selB = em.scrolled + last;
            colB = em.cols - 1;
        }
        selecting = true;
        invalidate();
        showToolbar();
        if (actionMode != null) actionMode.invalidate();
    }

    void clearSelection() {
        selecting = false;
        dragging = 0;
        if (actionMode != null) {
            android.view.ActionMode m = actionMode;
            actionMode = null;
            m.finish();
        }
        invalidate();
    }

    private boolean before(long l1, int c1, long l2, int c2) {
        return l1 < l2 || (l1 == l2 && c1 <= c2);
    }

    /** Selected text: lines joined with newlines, except where a line just wrapped. */
    String selectedText() {
        if (!selecting || session == null) return "";
        TermEmulator em = session.emulator;
        long sA = selA, sB = selB;
        int cA = colA, cB = colB;
        if (!before(sA, cA, sB, cB)) {
            long t = sA; sA = sB; sB = t;
            int u = cA; cA = cB; cB = u;
        }
        StringBuilder out = new StringBuilder();
        synchronized (em) {
            for (long ln = sA; ln <= sB; ln++) {
                TermEmulator.Line l = em.lineAt(ln);
                if (l == null) continue;
                int from = ln == sA ? cA : 0, to = ln == sB ? cB : l.cp.length - 1;
                StringBuilder part = new StringBuilder();
                for (int i = from; i <= Math.min(to, l.cp.length - 1); i++) {
                    int c = l.cp[i];
                    if (c == TermEmulator.WIDE_TAIL) continue;
                    part.appendCodePoint(c == 0 ? ' ' : c);
                }
                int end = part.length();
                if (ln != sB || !l.wrapped) while (end > 0 && part.charAt(end - 1) == ' ') end--;
                out.append(part, 0, end);
                if (ln != sB && !l.wrapped) out.append('\n');
            }
        }
        return out.toString();
    }

    private static final java.util.regex.Pattern URL =
            java.util.regex.Pattern.compile("(https?://|www\\.)[^\\s<>\"']+");

    /** The selection, if it's (or contains) a single web link. */
    String selectedLink() {
        String t = selectedText().trim();
        java.util.regex.Matcher m = URL.matcher(t);
        if (!m.find()) return null;
        String u = m.group().replaceAll("[).,;:!?'\\]]+$", "");
        return u.startsWith("www.") ? "https://" + u : u;
    }

    private void drawSelection(Canvas canvas, TermEmulator em) {
        long sA = selA, sB = selB;
        int cA = colA, cB = colB;
        if (!before(sA, cA, sB, cB)) {
            long t = sA; sA = sB; sB = t;
            int u = cA; cA = cB; cB = u;
        }
        fill.setColor(TermColors.SELECTION);
        for (int y = 0; y < em.rows; y++) {
            long ln = em.scrolled + y - scrollBack;
            if (ln < sA || ln > sB) continue;
            int from = ln == sA ? cA : 0, to = ln == sB ? cB : em.cols - 1;
            float top = padY + y * cellH;
            canvas.drawRect(padX + from * cellW, top, padX + (to + 1) * cellW, top + cellH, fill);
        }
        // round handles under the start and end, like Android's text selection
        fill.setColor(TermColors.CURSOR);
        float[] h1 = handlePos(sA, cA, false), h2 = handlePos(sB, cB, true);
        if (h1 != null) drawHandle(canvas, h1[0], h1[1], false);
        if (h2 != null) drawHandle(canvas, h2[0], h2[1], true);
    }

    private void drawHandle(Canvas c, float x, float y, boolean right) {
        // a drop: circle with a corner pointing at the text
        c.drawCircle(right ? x + handleR : x - handleR, y + handleR, handleR, fill);
        c.drawRect(right ? x : x - handleR, y, right ? x + handleR : x, y + handleR, fill);
    }

    /** Screen point of a selection end (bottom-left of start cell / bottom-right of end cell), or null if off screen. */
    private float[] handlePos(long ln, int col, boolean end) {
        TermEmulator em = session.emulator;
        long y = ln - em.scrolled + scrollBack;
        if (y < 0 || y >= em.rows) return null;
        return new float[]{padX + (end ? col + 1 : col) * cellW, padY + (y + 1) * cellH};
    }

    private int handleAt(float x, float y) {
        float touch = handleR * 2.6f;
        long sA = selA, sB = selB;
        int cA = colA, cB = colB;
        boolean swapped = !before(sA, cA, sB, cB);
        float[] hStart = swapped ? handlePos(sB, cB, false) : handlePos(sA, cA, false);
        float[] hEnd = swapped ? handlePos(sA, cA, true) : handlePos(sB, cB, true);
        if (hEnd != null && Math.hypot(x - (hEnd[0] + handleR), y - (hEnd[1] + handleR)) < touch) return swapped ? 1 : 2;
        if (hStart != null && Math.hypot(x - (hStart[0] - handleR), y - (hStart[1] + handleR)) < touch) return swapped ? 2 : 1;
        return 0;
    }

    private void moveHandle(float x, float y) {
        if (fromLongPress) {
            // the finger is on the text itself: extend from the pressed word, keeping all of it
            long ln = lineFor(y);
            int col = colFor(x);
            if (!before(wordLn, wordA, ln, col)) { // before the word: select from here to the word's end
                selA = wordLn;
                colA = wordB;
                selB = ln;
                colB = col;
            } else {
                selA = wordLn;
                colA = wordA;
                boolean inWord = before(ln, col, wordLn, wordB);
                selB = inWord ? wordLn : ln;
                colB = inWord ? wordB : col;
            }
            invalidate();
            return;
        }
        // dragging a handle: the finger sits below the text, so aim a little above it
        long ln = lineFor(y - handleR * 1.5f);
        int col = colFor(x);
        if (dragging == 1) {
            selA = ln;
            colA = col;
        } else {
            selB = ln;
            colB = col;
        }
        invalidate();
    }

    private void showToolbar() {
        if (actionMode != null) {
            actionMode.invalidate();
            actionMode.invalidateContentRect();
            return;
        }
        actionMode = startActionMode(new android.view.ActionMode.Callback2() {
            @Override
            public boolean onCreateActionMode(android.view.ActionMode m, android.view.Menu menu) {
                return true;
            }

            @Override
            public boolean onPrepareActionMode(android.view.ActionMode m, android.view.Menu menu) {
                menu.clear();
                menu.add(0, 1, 0, R.string.terminal_copy);
                if (selectedLink() != null) menu.add(0, 4, 1, R.string.terminal_open_link); // up front, not in the overflow
                menu.add(0, 2, 2, R.string.terminal_paste);
                menu.add(0, 3, 3, R.string.terminal_select_all);
                menu.add(0, 5, 4, R.string.terminal_share);
                return true;
            }

            @Override
            public boolean onActionItemClicked(android.view.ActionMode m, android.view.MenuItem item) {
                switch (item.getItemId()) {
                    case 1: {
                        String t = selectedText();
                        getContext().getSystemService(android.content.ClipboardManager.class)
                                .setPrimaryClip(android.content.ClipData.newPlainText(
                                        getContext().getString(R.string.terminal_title), t));
                        clearSelection();
                        return true;
                    }
                    case 2:
                        clearSelection();
                        if (callbacks != null) callbacks.onPaste();
                        return true;
                    case 3:
                        selectAll();
                        return true;
                    case 4: {
                        String u = selectedLink();
                        clearSelection();
                        try {
                            getContext().startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(u)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (Exception ignored) {
                        }
                        return true;
                    }
                    case 5: {
                        String t = selectedText();
                        clearSelection();
                        getContext().startActivity(android.content.Intent.createChooser(
                                new android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                                        .putExtra(android.content.Intent.EXTRA_TEXT, t), null)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                        return true;
                    }
                    default:
                        return false;
                }
            }

            @Override
            public void onDestroyActionMode(android.view.ActionMode m) {
                if (actionMode == m) {
                    actionMode = null;
                    selecting = false;
                    invalidate();
                }
            }

            @Override
            public void onGetContentRect(android.view.ActionMode m, View v, android.graphics.Rect out) {
                float[] a = handlePos(Math.min(selA, selB), selA <= selB ? colA : colB, false);
                float[] b = handlePos(Math.max(selA, selB), selA <= selB ? colB : colA, true);
                float top = a != null ? a[1] - cellH : padY;
                float bottom = b != null ? b[1] : getHeight() - padY;
                out.set((int) padX, (int) top, getWidth() - (int) padX, (int) bottom);
            }
        }, android.view.ActionMode.TYPE_FLOATING);
    }

    // ---------------------------------------------------------------- keyboard

    void showKeyboard() {
        requestFocus();
        InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
        if (imm != null) imm.showSoftInput(this, 0);
    }

    void hideKeyboard() {
        InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo o) {
        // plain characters, no autocorrect or suggestions: a terminal needs exactly what was typed
        o.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        o.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_NONE;
        return new BaseInputConnection(this, true) {
            @Override
            public boolean commitText(CharSequence t, int pos) {
                getEditable().clear();
                type(t.toString());
                return true;
            }

            @Override
            public boolean finishComposingText() {
                CharSequence pending = getEditable();
                if (pending.length() > 0) type(pending.toString());
                getEditable().clear();
                return super.finishComposingText();
            }

            @Override
            public boolean deleteSurroundingText(int before, int after) {
                for (int i = 0; i < Math.max(1, before); i++) send("\u007f");
                return true;
            }
        };
    }

    /** Text from the keyboard, with sticky Ctrl/Alt applied to the first character. */
    void type(String s) {
        if (s.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        boolean used = false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\n') cp = '\r';
            String piece = new String(Character.toChars(cp));
            if (ctrl && !used) {
                piece = control(cp);
                used = true;
            }
            if (alt && i <= Character.charCount(cp)) {
                piece = "\033" + piece;
                used = true;
            }
            sb.append(piece);
        }
        send(sb.toString());
        if (used) modifiersUsed();
    }

    private static String control(int cp) {
        if (cp >= 'a' && cp <= 'z') return String.valueOf((char) (cp - 'a' + 1));
        if (cp >= '@' && cp <= '_') return String.valueOf((char) (cp - '@'));
        if (cp == ' ' || cp == '2') return "\u0000";
        if (cp == '/') return "\u001f";
        if (cp == '?' || cp == '8') return "\u007f";
        return new String(Character.toChars(cp));
    }

    private void modifiersUsed() {
        ctrl = alt = false;
        if (callbacks != null) callbacks.onModifiersUsed();
    }

    void send(String s) {
        if (session == null) return;
        scrollToBottom();
        cursorOn = true;
        session.write(s);
    }

    /** Arrow and other special keys, shared with the extra-keys bar. */
    String keyCode(int keyCode, boolean shift, boolean altKey, boolean ctrlKey) {
        TermEmulator em = session == null ? null : session.emulator;
        boolean app = em != null && em.appCursor;
        int mod = 1 + (shift ? 1 : 0) + (altKey ? 2 : 0) + (ctrlKey ? 4 : 0);
        String m = mod > 1 ? "1;" + mod : "";
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: return mod > 1 ? "\033[" + m + "A" : app ? "\033OA" : "\033[A";
            case KeyEvent.KEYCODE_DPAD_DOWN: return mod > 1 ? "\033[" + m + "B" : app ? "\033OB" : "\033[B";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return mod > 1 ? "\033[" + m + "C" : app ? "\033OC" : "\033[C";
            case KeyEvent.KEYCODE_DPAD_LEFT: return mod > 1 ? "\033[" + m + "D" : app ? "\033OD" : "\033[D";
            case KeyEvent.KEYCODE_MOVE_HOME: return mod > 1 ? "\033[" + m + "H" : app ? "\033OH" : "\033[H";
            case KeyEvent.KEYCODE_MOVE_END: return mod > 1 ? "\033[" + m + "F" : app ? "\033OF" : "\033[F";
            case KeyEvent.KEYCODE_PAGE_UP: return "\033[5~";
            case KeyEvent.KEYCODE_PAGE_DOWN: return "\033[6~";
            case KeyEvent.KEYCODE_INSERT: return "\033[2~";
            case KeyEvent.KEYCODE_FORWARD_DEL: return "\033[3~";
            case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: return altKey ? "\033\r" : "\r";
            case KeyEvent.KEYCODE_DEL: return altKey ? "\033\u007f" : ctrlKey ? "\u0008" : "\u007f";
            case KeyEvent.KEYCODE_TAB: return shift ? "\033[Z" : "\t";
            case KeyEvent.KEYCODE_ESCAPE: return "\033";
            case KeyEvent.KEYCODE_F1: return "\033OP";
            case KeyEvent.KEYCODE_F2: return "\033OQ";
            case KeyEvent.KEYCODE_F3: return "\033OR";
            case KeyEvent.KEYCODE_F4: return "\033OS";
            case KeyEvent.KEYCODE_F5: return "\033[15~";
            case KeyEvent.KEYCODE_F6: return "\033[17~";
            case KeyEvent.KEYCODE_F7: return "\033[18~";
            case KeyEvent.KEYCODE_F8: return "\033[19~";
            case KeyEvent.KEYCODE_F9: return "\033[20~";
            case KeyEvent.KEYCODE_F10: return "\033[21~";
            case KeyEvent.KEYCODE_F11: return "\033[23~";
            case KeyEvent.KEYCODE_F12: return "\033[24~";
            default: return null;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, e);
        if (session == null) return false;
        boolean c = ctrl || e.isCtrlPressed(), a = alt || e.isAltPressed();
        String seq = keyCode(keyCode, e.isShiftPressed(), a, c);
        if (seq != null) {
            send(seq);
            if (ctrl || alt) modifiersUsed();
            return true;
        }
        int ch = e.getUnicodeChar(e.getMetaState() & ~(KeyEvent.META_CTRL_MASK | KeyEvent.META_ALT_MASK));
        if (ch == 0) return super.onKeyDown(keyCode, e);
        String s = c ? control(ch) : new String(Character.toChars(ch));
        if (a) s = "\033" + s;
        send(s);
        if (ctrl || alt) modifiersUsed();
        return true;
    }

    @Override
    protected void onFocusChanged(boolean focused, int dir, android.graphics.Rect prev) {
        super.onFocusChanged(focused, dir, prev);
        removeCallbacks(blink);
        cursorOn = true;
        if (focused) postDelayed(blink, 600);
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(blink);
        super.onDetachedFromWindow();
    }

    String screenText() {
        return session == null ? "" : session.emulator.screenText();
    }

    @SuppressWarnings("unused")
    private static boolean empty(CharSequence s) {
        return TextUtils.isEmpty(s);
    }
}
