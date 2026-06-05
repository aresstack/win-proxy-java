package com.aresstack.winproxy;

import java.net.URI;

/**
 * Parses Windows ProxyServer registry values.
 */
public final class ProxyServerParser {

    public ProxyResult parse(String proxyServer, String targetUrl) {
        if (proxyServer == null || proxyServer.trim().length() == 0) {
            return ProxyResult.direct();
        }
        String selected = selectServer(proxyServer, targetUrl);
        return new ProxyResultParser().parse(selected);
    }

    private String selectServer(String proxyServer, String targetUrl) {
        if (proxyServer.indexOf('=') < 0) {
            return proxyServer.trim();
        }
        String scheme = URI.create(targetUrl).getScheme();
        String[] entries = proxyServer.split(";");
        for (int i = 0; i < entries.length; i++) {
            String[] keyValue = entries[i].split("=", 2);
            if (keyValue.length == 2 && keyValue[0].trim().equalsIgnoreCase(scheme)) {
                return keyValue[1].trim();
            }
        }
        return "DIRECT";
    }
}
