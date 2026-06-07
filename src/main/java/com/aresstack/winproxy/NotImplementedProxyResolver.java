package com.aresstack.winproxy;

/**
 * Returns a {@code NOT_IMPLEMENTED} result for reserved future modes.
 * Never returns DIRECT and never falls back.
 */
public final class NotImplementedProxyResolver {

    private final String reason;

    public NotImplementedProxyResolver(String reason) {
        this.reason = reason == null || reason.trim().length() == 0 ? "not-implemented" : reason.trim();
    }

    public ProxyResult resolve() {
        return ProxyResult.notImplemented(reason);
    }
}
