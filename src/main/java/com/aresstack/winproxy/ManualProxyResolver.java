package com.aresstack.winproxy;

/**
 * Resolves a manually configured proxy.
 */
public final class ManualProxyResolver {

    public ProxyResult resolve(ProxyConfiguration configuration) {
        String host = configuration.getManualProxyHost();
        int port = configuration.getManualProxyPort();
        if (host == null || host.trim().length() == 0 || port <= 0) {
            return ProxyResult.direct();
        }
        return ProxyResult.of(host.trim(), port);
    }
}
