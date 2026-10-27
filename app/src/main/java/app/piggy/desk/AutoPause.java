package app.piggy.desk;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Pauses an agent that's operating the phone when the user takes the phone back (see BuddyService.autoPause): they
 * touch the screen, or a call rings or starts. Touches: a 1-pixel window nobody can see or touch hears about every
 * touch elsewhere, but not where it landed. Calls: the phone's audio mode and, with notification access, a call's
 * notification (only its category is looked at). Nothing is kept.
 */
final class AutoPause {
    static final String ACTION_RESUME = "app.piggy.desk.RESUME";
    /** MotionEvent.FLAG_IS_ACCESSIBILITY_EVENT (hidden, Android 12+): an accessibility service made it (Buddy's own taps). */
    private static final int FLAG_FROM_ACCESSIBILITY = 0x800;
    private static final long POLL_MS = 700;
    private static final int CALL_ENDED_ID = 9;
    /** The call notifications showing right now (their keys), from NotificationWatcher. */
    private static final Set<String> calls = Collections.synchronizedSet(new HashSet<>());

    private final BuddyService svc;
    private View watcher;
    private boolean watching, inCall, pausedForCall;
    /** Until then (uptime ms) Buddy's own gesture may be touching the screen. */
    private volatile long ownUntil;
    private final Runnable poll = this::check;

    AutoPause(BuddyService svc) {
        this.svc = svc;
    }

    /** An agent started operating the phone (main thread). */
    void start() {
        if (watching) return;
        watching = true;
        inCall = callNow(); // a call already going on (a task asked for during one) doesn't pause it
        addWatcher();
        schedule();
    }

    /** It stopped operating the phone, or was paused (main thread). */
    void stop() {
        if (!watching) return;
        watching = false;
        if (watcher != null) {
            try {
                svc.getSystemService(WindowManager.class).removeView(watcher);
            } catch (Exception ignored) {
            }
            watcher = null;
        }
        schedule();
    }

    /** The pause ended (resumed or stopped): nothing left to say about a call (main thread). */
    void onPauseEnded() {
        pausedForCall = false;
        cancelCallEnded(svc);
        schedule();
    }

    static void cancelCallEnded(android.content.Context c) {
        c.getSystemService(NotificationManager.class).cancel(CALL_ENDED_ID);
    }

    /** Buddy is about to touch the screen itself for this long (tool thread). */
    void ownGesture(long ms) {
        ownUntil = SystemClock.uptimeMillis() + ms + 600;
    }

    /** Something that happened then (uptime ms) may have been Buddy's own touch. */
    boolean own(long uptime) {
        return uptime <= ownUntil;
    }

    // ---------------------------------------------------------------- touches

    private void addWatcher() {
        if (watcher != null) return;
        View v = new View(svc);
        v.setOnTouchListener((view, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) onOutside(e);
            return false;
        });
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(1, 1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        try {
            svc.getSystemService(WindowManager.class).addView(v, p);
            watcher = v;
        } catch (Exception ignored) { // no touch watching then; calls still pause
        }
    }

    private void onOutside(MotionEvent e) {
        // Buddy's own taps come here too: Android 12+ marks them, and they come while one runs
        if ((e.getFlags() & FLAG_FROM_ACCESSIBILITY) != 0 || e.getEventTime() <= ownUntil) return;
        // Android only says where a touch landed when it's on one of Buddy's own windows (the bubble, a card): that's
        // the user talking to Buddy, not taking the phone. Elsewhere every axis reads 0, the pressure too.
        if (e.getPressure() != 0f || e.getTouchMajor() != 0f) return;
        svc.mainHandler().post(svc::onScreenTouched); // not from inside the watcher's own event: pausing removes it
    }

    // ------------------------------------------------------------------ calls

    /** A notification came or went (NotificationWatcher): only whether it's a call is looked at. */
    static void onNotification(StatusBarNotification s, boolean posted) {
        if (s == null || s.getNotification() == null || BuildInfo.APP_ID.equals(s.getPackageName())) return;
        if (!Notification.CATEGORY_CALL.equals(s.getNotification().category)) return;
        if (!posted) {
            calls.remove(s.getKey());
            return;
        }
        calls.add(s.getKey());
        BuddyService svc = BuddyService.get();
        if (svc != null) svc.runOnMain(() -> svc.autoPauser().check()); // at once: the call screen may cover the app
    }

    /** Notification access (re)connected: the call notifications showing now. */
    static void onNotifications(StatusBarNotification[] active) {
        calls.clear();
        if (active != null) for (StatusBarNotification s : active) onNotification(s, true);
    }

    private boolean callNow() {
        // ringing, in a call, or in a call in an app (MODE_IN_COMMUNICATION), screening one…: any mode but normal
        return svc.getSystemService(AudioManager.class).getMode() > AudioManager.MODE_NORMAL || !calls.isEmpty();
    }

    private void check() {
        boolean call = callNow();
        if (watching && call && !inCall) {
            pausedForCall = true;
            if (!svc.autoPause(R.string.service_autopaused_call)) pausedForCall = false;
        } else if (pausedForCall && inCall && !call && svc.paused()) {
            showCallEnded();
        }
        inCall = call;
        schedule();
    }

    /** Polls while an agent operates the phone, or while a call it was paused for goes on. */
    private void schedule() {
        svc.mainHandler().removeCallbacks(poll);
        if (watching || pausedForCall) svc.mainHandler().postDelayed(poll, POLL_MS);
    }

    /** The call is over and the task still waits: a quiet notification to resume it (the pill says so too). */
    private void showCallEnded() {
        PendingIntent resume = PendingIntent.getBroadcast(svc, 4,
                new Intent(svc, HiddenNotice.class).setAction(ACTION_RESUME),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getBroadcast(svc, 5,
                new Intent(svc, HiddenNotice.class).setAction(ControlIndicator.ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(svc, ControlIndicator.CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(svc.getString(R.string.pause_call_ended_title))
                .setContentText(svc.getString(R.string.pause_call_ended_text))
                .setContentIntent(resume)
                .setAutoCancel(true)
                .addAction(new Notification.Action.Builder(null, svc.getString(R.string.menu_resume), resume).build())
                .addAction(new Notification.Action.Builder(null, svc.getString(R.string.control_stop), stop).build())
                .build();
        try {
            svc.getSystemService(NotificationManager.class).notify(CALL_ENDED_ID, n);
        } catch (SecurityException ignored) { // notifications off: the pill still says how to resume
        }
    }
}
