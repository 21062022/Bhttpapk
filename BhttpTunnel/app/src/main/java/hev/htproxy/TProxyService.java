package hev.htproxy;

/**
 * JNI de hev-socks5-tunnel (tun2socks).
 * El nombre del paquete/clase debe ser exactamente este para coincidir con la firma C.
 */
public class TProxyService {
    private static boolean loaded = false;

    static {
        try {
            System.loadLibrary("hev-socks5-tunnel");
            loaded = true;
        } catch (Throwable e) {
            loaded = false;
        }
    }

    public static boolean isLoaded() {
        return loaded;
    }

    public static native void TProxyStartService(String configPath, int fd);
    public static native void TProxyStopService();
    public static native long[] TProxyGetStats();
}
