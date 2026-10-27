package app.piggy.desk;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Piggy's long-term memory: short notes the agents keep themselves (memory_save / memory_delete), about the user,
 * the people around them and how they like things done. Every run gets them all (Helper.writePermissions), whatever
 * the agent. Kept in the app's own storage as JSON, so they survive updates and a reinstall of Linux.
 */
final class Memory {
    /** Most notes kept, and the longest one: the whole memory goes into every run's instructions. */
    static final int MAX_NOTES = 200, MAX_CHARS = 500;

    static final class Note {
        final String id, text;
        final long at;

        Note(String id, String text, long at) {
            this.id = id;
            this.text = text;
            this.at = at;
        }
    }

    private Memory() {
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "memory.json");
    }

    static synchronized List<Note> all(Context c) {
        List<Note> out = new ArrayList<>();
        File f = file(c);
        if (!f.isFile()) return out;
        try {
            JSONArray a = new JSONArray(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Note(o.getString("id"), o.getString("text"), o.optLong("at")));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void write(Context c, List<Note> notes) throws Exception {
        JSONArray a = new JSONArray();
        for (Note n : notes) a.put(new JSONObject().put("id", n.id).put("text", n.text).put("at", n.at));
        File f = file(c), tmp = new File(f.getPath() + ".tmp");
        try (OutputStream o = new FileOutputStream(tmp)) {
            o.write(a.toString(1).getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new java.io.IOException("memory not saved");
    }

    /** Adds a note, or replaces the one with this id (null: a new note). Returns its id. */
    static synchronized String save(Context c, String id, String text) throws Exception {
        text = text.trim();
        if (text.length() > MAX_CHARS) text = text.substring(0, MAX_CHARS);
        List<Note> notes = all(c);
        long now = System.currentTimeMillis();
        if (id != null && !id.isEmpty()) {
            for (int i = 0; i < notes.size(); i++) {
                if (notes.get(i).id.equals(id)) {
                    notes.set(i, new Note(id, text, now));
                    write(c, notes);
                    return id;
                }
            }
        }
        int max = 0;
        for (Note n : notes) {
            try {
                max = Math.max(max, Integer.parseInt(n.id.substring(1)));
            } catch (Exception ignored) {
            }
        }
        String fresh = "m" + (max + 1);
        notes.add(new Note(fresh, text, now));
        while (notes.size() > MAX_NOTES) notes.remove(0); // the oldest go first
        write(c, notes);
        return fresh;
    }

    /** Removes the note with this id; false when there's none. */
    static synchronized boolean delete(Context c, String id) throws Exception {
        List<Note> notes = all(c);
        for (int i = 0; i < notes.size(); i++) {
            if (notes.get(i).id.equals(id)) {
                notes.remove(i);
                write(c, notes);
                return true;
            }
        }
        return false;
    }

    /** The notes as one block, for the agent: "[m3] text" lines. */
    static String list(Context c) {
        List<Note> notes = all(c);
        if (notes.isEmpty()) return "(no notes yet)\n";
        StringBuilder sb = new StringBuilder();
        for (Note n : notes) sb.append("- [").append(n.id).append("] ").append(n.text.replace("\n", " ")).append('\n');
        return sb.toString();
    }

    /** What every run's instructions end with. */
    static String prompt(Context c) {
        return "YOUR MEMORY (your notes from earlier chats; change them with memory_save and memory_delete):\n" + list(c);
    }
}
