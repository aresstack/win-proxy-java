package com.aresstack.winproxy;

import java.net.InetSocketAddress;
import java.net.Proxy;

/**
 * Immutable result of a proxy resolution.
 * <p>
 * Provides both structured access ({@link #getHost()}, {@link #getPort()}) and a
 * convenience method {@link #toJavaProxy()} to create a {@link java.net.Proxy} for
 * direct use with {@link java.net.URLConnection} or OkHttp.
 */
public final class ProxyResult {

    private final boolean direct;
    private final String host;
    private final int port;
    private final String reason;

    private ProxyResult(boolean direct, String host, int port, String reason) {
        this.direct = direct;
        this.host = host;
        this.port = port;
        this.reason = reason;
    }

    /** Creates a DIRECT result (no proxy needed). */
    public static ProxyResult direct(String reason) {
        return new ProxyResult(true, null, 0, reason);
    }

    /** Creates a DIRECT result with a generic reason. */
    public static ProxyResult direct() {
        return direct("direct");
    }

    /** Creates a PROXY result. */
    public static ProxyResult proxy(String host, int port, String reason) {
        return new ProxyResult(false, host, port, reason);
    }

    /** Creates a PROXY result with a generic reason. */
    public static ProxyResult of(String host, int port) {
        return proxy(host, port, "resolved");
    }

    /** Returns {@code true} if the target URL should be accessed directly (no proxy). */
    public boolean isDirect() { return direct; }

    /** Returns the proxy host, or {@code null} for DIRECT results. */
    public String getHost() { return host; }

    /** Returns the proxy port, or {@code 0} for DIRECT results. */
    public int getPort() { return port; }

    /** Returns a human-readable diagnostic reason for this result. */
    public String getReason() { return reason; }

    /** Converts to {@link java.net.Proxy}. Returns {@link Proxy#NO_PROXY} for DIRECT results. */
    public Proxy toJavaProxy() {
        if (direct) return Proxy.NO_PROXY;
        return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
    }

    @Override
    public String toString() {
        if (direct) return "DIRECT (" + reason + ")";
        return "PROXY " + host + ":" + port + " (" + reason + ")";
    }
}
