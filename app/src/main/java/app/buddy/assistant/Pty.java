package app.buddy.assistant;

/**
 * The terminal's pseudo-terminal, implemented in native/pty.c (libbuddypty.so). It stays in Buddy's package because
 * the prebuilt library's JNI names (Java_app_buddy_assistant_Pty_*) are bound to it; the rest of the app is app.piggy.desk.
 */
public final class Pty {
    static {
        System.loadLibrary("buddypty");
    }

    private Pty() {
    }

    /** Starts {@code path} on a new pty; returns {masterFd, pid}. */
    public static native int[] start(String path, String[] args, String[] env, String cwd, int rows, int cols)
            throws java.io.IOException;

    public static native void resize(int fd, int rows, int cols);

    /** Blocks until the program exits; returns its exit code. */
    public static native int waitFor(int pid);

    public static native void hangup(int pid);

    public static native void close(int fd);
}
