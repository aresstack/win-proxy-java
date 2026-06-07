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

    /**
     * Converts to {@link java.net.Proxy}, honouring the {@link Kind}:
     * <ul>
     *   <li>{@link Kind#PROXY} → an HTTP {@link Proxy} for {@code host:port},</li>
     *   <li>{@link Kind#DIRECT} → {@link Proxy#NO_PROXY},</li>
     *   <li>{@link Kind#ERROR} / {@link Kind#NOT_IMPLEMENTED} → {@link IllegalStateException}.</li>
     * </ul>
     * Errors and not-implemented results deliberately throw instead of silently
     * degrading to {@link Proxy#NO_PROXY}, so a failed resolution can never be
     * mistaken for a genuine DIRECT route. Callers that explicitly want a best-effort
     * NO_PROXY for those kinds must use {@link #toJavaProxyOrNoProxy()}.
     *
     * @throws IllegalStateException if this result is an {@link Kind#ERROR} or
     *         {@link Kind#NOT_IMPLEMENTED}.
     */
    public Proxy toJavaProxy() {
        switch (kind) {
            case PROXY:
                return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
            case DIRECT:
                return Proxy.NO_PROXY;
            default:
                throw new IllegalStateException(
                        "Cannot convert a " + kind + " ProxyResult to java.net.Proxy: " + this);
        }
    }

    /**
     * Best-effort variant of {@link #toJavaProxy()} that never throws:
     * <ul>
     *   <li>{@link Kind#PROXY} → an HTTP {@link Proxy} for {@code host:port},</li>
     *   <li>{@link Kind#DIRECT}, {@link Kind#ERROR}, {@link Kind#NOT_IMPLEMENTED}
     *       → {@link Proxy#NO_PROXY}.</li>
     * </ul>
     * Only use this when the caller has <em>explicitly</em> decided that a failed or
     * not-implemented resolution should fall back to a direct connection. Diagnostics
     * must keep using the original {@link ProxyResult} (and {@link #toJavaProxy()}),
     * so an {@link Kind#ERROR} is never masked as a successful DIRECT.
     */
    public Proxy toJavaProxyOrNoProxy() {
        if (kind == Kind.PROXY) {
            return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
        }
        return Proxy.NO_PROXY;
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
