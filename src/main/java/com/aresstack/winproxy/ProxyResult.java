package com.aresstack.winproxy;

import java.net.InetSocketAddress;
import java.net.Proxy;

/**
 * Immutable result of a proxy resolution.
 * <p>
 * A result is exactly one of four {@link Kind kinds}:
 * <ul>
 *   <li>{@link Kind#PROXY} — a concrete {@code host:port} proxy.</li>
 *   <li>{@link Kind#DIRECT} — the selected mode genuinely yields DIRECT
 *       (e.g. {@link ProxyMode#DISABLED} or a PAC script returning {@code DIRECT}).</li>
 *   <li>{@link Kind#ERROR} — the selected mode failed; {@link #getReason()} carries
 *       the technical cause (e.g. {@code pac-url-not-found}, {@code pac-download-failed},
 *       {@code pac-evaluation-failed}). This is <b>not</b> DIRECT.</li>
 *   <li>{@link Kind#NOT_IMPLEMENTED} — the selected mode is reserved/not implemented.</li>
 * </ul>
 * Errors are never reported as DIRECT, so callers can distinguish "no proxy needed"
 * from "resolution failed".
 */
public final class ProxyResult {

    /** The kind of a {@link ProxyResult}. */
    public enum Kind {
        PROXY,
        DIRECT,
        ERROR,
        NOT_IMPLEMENTED
    }

    private final Kind kind;
    private final String host;
    private final int port;
    private final String reason;

    private ProxyResult(Kind kind, String host, int port, String reason) {
        this.kind = kind;
        this.host = host;
        this.port = port;
        this.reason = reason;
    }

    /** Creates a DIRECT result (no proxy needed). */
    public static ProxyResult direct(String reason) {
        return new ProxyResult(Kind.DIRECT, null, 0, reason);
    }

    /** Creates a DIRECT result with a generic reason. */
    public static ProxyResult direct() {
        return direct("direct");
    }

    /** Creates a PROXY result. */
    public static ProxyResult proxy(String host, int port, String reason) {
        return new ProxyResult(Kind.PROXY, host, port, reason);
    }

    /** Creates a PROXY result with a generic reason. */
    public static ProxyResult of(String host, int port) {
        return proxy(host, port, "resolved");
    }

    /** Creates an ERROR result carrying the technical cause. Never DIRECT. */
    public static ProxyResult error(String reason) {
        return new ProxyResult(Kind.ERROR, null, 0, reason);
    }

    /** Creates a NOT_IMPLEMENTED result. Never DIRECT, never a fallback. */
    public static ProxyResult notImplemented(String reason) {
        return new ProxyResult(Kind.NOT_IMPLEMENTED, null, 0, reason);
    }

    /** Returns the {@link Kind} of this result. */
    public Kind getKind() { return kind; }

    /** Returns {@code true} for a concrete {@code host:port} proxy. */
    public boolean isProxy() { return kind == Kind.PROXY; }

    /** Returns {@code true} only if the mode genuinely yields DIRECT (not on error). */
    public boolean isDirect() { return kind == Kind.DIRECT; }

    /** Returns {@code true} if resolution failed; see {@link #getReason()}. */
    public boolean isError() { return kind == Kind.ERROR; }

    /** Returns {@code true} if the selected mode is reserved/not implemented. */
    public boolean isNotImplemented() { return kind == Kind.NOT_IMPLEMENTED; }

    /** Returns the proxy host, or {@code null} for non-PROXY results. */
    public String getHost() { return host; }

    /** Returns the proxy port, or {@code 0} for non-PROXY results. */
    public int getPort() { return port; }

    /** Returns a machine-readable diagnostic reason for this result. */
    public String getReason() { return reason; }

    /** Converts to {@link java.net.Proxy}. Returns {@link Proxy#NO_PROXY} for non-PROXY results. */
    public Proxy toJavaProxy() {
        if (kind != Kind.PROXY) return Proxy.NO_PROXY;
        return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
    }

    @Override
    public String toString() {
        switch (kind) {
            case PROXY:
                return "PROXY " + host + ":" + port + " (" + reason + ")";
            case DIRECT:
                return "DIRECT (" + reason + ")";
            case ERROR:
                return "ERROR (" + reason + ")";
            case NOT_IMPLEMENTED:
                return "NOT_IMPLEMENTED (" + reason + ")";
            default:
                return String.valueOf(reason);
        }
    }
}
