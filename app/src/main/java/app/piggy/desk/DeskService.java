package app.piggy.desk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Keeps the app's process alive while the workspace is in use: Claude Code sessions keep running when the user
 * switches apps, and the tablet's exit (EgressProxy) stays up for the PC. Shows one quiet ongoing notification.
 */
public class DeskService extends Service {
    private static final String CHANNEL = "desk";
    private static final int ID = 7101;
    private static volatile boolean running;

    static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, DeskService.class));
        } catch (Exception e) { // not allowed from the background right now; the next time the app opens
            android.util.Log.w("PiggyDesk", "service not started", e);
        }
    }

    /** Refreshes the notification text (the exit was switched on or off). */
    static void update(Context c) {
        if (!running) return;
        c.getSystemService(NotificationManager.class).notify(ID, build(c));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Piggy Desk 运行中", NotificationManager.IMPORTANCE_LOW));
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, build(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(ID, build(this));
        running = true;
        Desk.get(this); // network guard and the exit start with it
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static Notification build(Context c) {
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, DeskActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        boolean exit = DeskStore.get(c).bool("egressOn", false);
        String text = exit ? "平板出口开着（端口 " + DeskStore.get(c).num("egressPort", 8899) + "）· Claude 在平板上运行"
                : "Claude 在平板上运行";
        return new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("Piggy Desk")
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(open)
                .build();
    }
}
