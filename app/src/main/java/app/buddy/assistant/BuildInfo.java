package app.buddy.assistant;

/** Single source of the app version; read by build.sh and app/build.gradle.kts. */
final class BuildInfo {
    static final String VERSION = "1.0.0";
    static final int VERSION_CODE = 1;
    /** The installed app's package (applicationId); the Java package stays app.buddy.assistant. */
    static final String APP_ID = "app.piggy.assistant";

    private BuildInfo() {
    }
}
