package udpgk.server;

/** Shared protocol and application configuration. */
public final class Constants {
    public static final int SERVER_PORT = 9999;
    public static final String SERVER_HOST = "172.20.10.2";
    public static final String SUBSCRIBE = "SUBSCRIBE";
    public static final String UNSUBSCRIBE = "UNSUBSCRIBE";
    public static final int MAX_TABLE_ROWS = 50;
    public static final int BUFFER_SIZE = 512;

    private Constants() {
    }
}
