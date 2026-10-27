package app.piggy.desk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The "Waiting for you" list: replies a notification rule's run wrote that the user didn't approve in time (Ask me
 * while they weren't on the phone, or a second check that objected). The run ends at once instead of waiting on
 * them, and they send, change or skip each reply later. One per conversation: a newer run's reply replaces it.
 * Kept in Prefs; the notifications' reply fields only in memory (they don't survive a restart, and then Send
 * copies the reply and opens the app instead).
 */
final class Waiting {
    static final class Item {
        /** The conversation: the rule's job key (rule id, app, who). */
        String key = "";
        String pkg = "", app = "", who = "", chat = "";
        /** Their messages the reply answers, one per line. */
        String messages = "";
        String draft = "";
        /** Why it waits: the second check's objection, or "" (Ask me, and the user wasn't there). */
        String why = "";
        /** The messaging app's time of the newest message it answers (0: not a messaging app). */
        long stamp;
        long at;

        JSONObject json() throws org.json.JSONException {
            return new JSONObject().put("key", key).put("pkg", pkg).put("app", app).put("who", who).put("chat", chat)
                    .put("messages", messages).put("draft", draft).put("why", why).put("stamp", stamp).put("at", at);
        }

        static Item of(JSONObject o) {
            Item it = new Item();
            it.key = o.optString("key");
            it.pkg = o.optString("pkg");
            it.app = o.optString("app");
            it.who = o.optString("who");
            it.chat = o.optString("chat");
            it.messages = o.optString("messages");
            it.draft = o.optString("draft");
            it.why = o.optString("why");
            it.stamp = o.optLong("stamp");
            it.at = o.optLong("at");
            return it;
        }
    }

    /** Where an item stands now, read from its conversation's notification. */
    enum State { OK, WROTE_AGAIN, ANSWERED, GONE }

    /** How Send went. */
    enum Sent { SENT, COPIED }

    private static final String CHANNEL = "waiting", TAG = "waiting";
    /** The notifications' reply fields, by conversation (they can't be saved). */
    private static final Map<String, Notification.Action> replies = new ConcurrentHashMap<>();
    /** The list or the chats screen, while open: told when the list changes (main thread). */
    static Runnable listener;

    private Waiting() {
    }

    /** Oldest first. */
    static synchronized List<Item> all(Context c) {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.waiting(c));
            for (int i = 0; i < a.length(); i++) out.add(Item.of(a.getJSONObject(i)));
        } catch (Exception ignored) {
        }
        return out;
    }

    static Item find(Context c, String key) {
        for (Item it : all(c)) if (it.key.equals(key)) return it;
        return null;
    }

    /** Adds the reply, replacing the conversation's earlier one. */
    static synchronized void put(Context c, Item item, Notification.Action reply) {
        List<Item> items = all(c);
        for (int i = items.size() - 1; i >= 0; i--) if (items.get(i).key.equals(item.key)) items.remove(i);
        items.add(item);
        if (reply != null) replies.put(item.key, reply);
        else replies.remove(item.key);
        save(c, items);
    }

    static synchronized void remove(Context c, String key) {
        List<Item> items = all(c);
        boolean found = false;
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).key.equals(key)) {
                items.remove(i);
                found = true;
            }
        }
        replies.remove(key);
        if (found) save(c, items);
    }

    private static void save(Context c, List<Item> items) {
        JSONArray a = new JSONArray();
        try {
            for (Item it : items) a.put(it.json());
        } catch (Exception ignored) {
        }
        Prefs.setWaiting(c, a.toString());
        notifyWaiting(c, items);
        Runnable l = listener;
        if (l != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(l);
    }

    /** Has the conversation moved on since the reply was written? Read from its notification as it is now. */
    static State state(Item it) {
        Triggers.Note now = NotificationWatcher.current(it.pkg, it.who);
        if (now == null) return replies.containsKey(it.key) ? State.OK : State.GONE;
        boolean mine = false, theirs = false;
        if (it.stamp > 0) {
            for (Triggers.Line l : now.messages) {
                if (l.at <= it.stamp) continue;
                if (l.mine) mine = true;
                else theirs = true;
            }
        }
        return mine ? State.ANSWERED : theirs ? State.WROTE_AGAIN : State.OK;
    }

    /** Can Send reply from the notification? Otherwise it copies the reply and opens the app. */
    static boolean canReply(Item it) {
        Triggers.Note now = NotificationWatcher.current(it.pkg, it.who);
        return now != null && now.reply != null || replies.containsKey(it.key);
    }

    /**
     * Sends the reply through the conversation's notification (the current one, or the one it was written for);
     * when there's none left, copies it and opens the app. Either way it leaves the list.
     */
    static Sent send(Context c, Item it, String text) {
        Triggers.Note now = NotificationWatcher.current(it.pkg, it.who);
        Notification.Action a = now != null && now.reply != null ? now.reply : replies.get(it.key);
        remove(c, it.key);
        if (a != null && Triggers.sendReply(c, a, text)) {
            Triggers.sentFromList(c, it, text);
            return Sent.SENT;
        }
        ClipboardManager cm = c.getSystemService(ClipboardManager.class);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(it.who, text));
        Intent open = c.getPackageManager().getLaunchIntentForPackage(it.pkg);
        if (open != null) {
            try {
                c.startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
            }
        }
        return Sent.COPIED;
    }

    /** One quiet notification for the whole list, gone when it's empty. */
    private static void notifyWaiting(Context c, List<Item> items) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (items.isEmpty()) {
            nm.cancel(TAG, 1);
            return;
        }
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, c.getString(R.string.waiting_channel),
                NotificationManager.IMPORTANCE_LOW));
        List<String> names = new ArrayList<>();
        for (Item it : items) if (!names.contains(it.who)) names.add(it.who);
        String text = c.getString(R.string.waiting_banner_text, android.text.TextUtils.join(", ", names));
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(c.getString(R.string.waiting_title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setNumber(items.size())
                .setOnlyAlertOnce(true)
                .setContentIntent(PendingIntent.getActivity(c, 7, new Intent(c, WaitingActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE))
                .build();
        try {
            nm.notify(TAG, 1, n);
        } catch (SecurityException ignored) {
        }
    }
}
