package com.aresstack.winproxy;

import java.net.URI;

/**
 * Parses Windows ProxyServer registry values.
 */
public final class ProxyServerParser {

    public ProxyResult parse(String proxyServer, String targetUrl) {
        if (proxyServer == null || proxyServer.trim().length() == 0) {
            return ProxyResult.direct("empty-proxy-server");
        }
        String selected = selectServer(proxyServer, targetUrl);
        return new ProxyResultParser().parse(selected);
    }

    private String selectServer(String proxyServer, String targetUrl) {
        if (proxyServer.indexOf('=') < 0) {
            return proxyServer.trim();
        }

        String scheme = getScheme(targetUrl);
        String httpFallback = null;
        String[] entries = proxyServer.split(";");
        for (int i = 0; i < entries.length; i++) {
            String[] keyValue = entries[i].split("=", 2);
            if (keyValue.length != 2) {
                continue;
            }
            String key = keyValue[0].trim();
            String value = keyValue[1].trim();
            if (key.equalsIgnoreCase(scheme)) {
                return value;
            }
            if (key.equalsIgnoreCase("http")) {
                httpFallback = value;
            }
        }
        return httpFallback == null ? "DIRECT" : httpFallback;
    }

    private String getScheme(String targetUrl) {
        try {
            String scheme = URI.create(targetUrl).getScheme();
            return scheme == null ? "http" : scheme;
        } catch (RuntimeException e) {
            return "http";
        }
    }
}
