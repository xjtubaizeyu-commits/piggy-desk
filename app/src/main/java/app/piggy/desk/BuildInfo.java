package app.piggy.desk;

/** Single source of the app version; read by build.sh and app/build.gradle.kts. */
final class BuildInfo {
    static final String VERSION = "0.1.2";
    static final int VERSION_CODE = 3;
    /** The installed app's package (applicationId), the same as the Java package. */
    static final String APP_ID = "app.piggy.desk";

    private BuildInfo() {
    }
}
