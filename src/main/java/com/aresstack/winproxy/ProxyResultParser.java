package com.aresstack.winproxy;

/**
 * Parses PAC proxy result strings.
 */
public final class ProxyResultParser {

    public ProxyResult parse(String pacResult) {
        if (pacResult == null || pacResult.trim().length() == 0) {
            return ProxyResult.direct("empty-pac-result");
        }

        String[] entries = pacResult.split(";");
        for (int i = 0; i < entries.length; i++) {
            ProxyResult result = parseEntry(entries[i]);
            if (!result.isDirect()) {
                return result;
            }
        }
        return ProxyResult.direct("pac-direct");
    }

    private ProxyResult parseEntry(String entry) {
        if (entry == null) {
            return ProxyResult.direct("null-pac-entry");
        }
        String trimmed = entry.trim();
        if (trimmed.length() == 0 || "DIRECT".equalsIgnoreCase(trimmed)) {
            return ProxyResult.direct("pac-direct");
        }
        String upper = trimmed.toUpperCase();
        if (upper.startsWith("PROXY ")) {
            return parseHostPort(trimmed.substring(6).trim());
        }
        if (upper.startsWith("HTTPS ")) {
            return parseHostPort(trimmed.substring(6).trim());
        }
        if (trimmed.indexOf(':') > 0 && upper.indexOf(' ') < 0) {
            return parseHostPort(trimmed);
        }
        return ProxyResult.direct("unsupported-pac-entry");
    }

    private ProxyResult parseHostPort(String value) {
        if (value == null) {
            return ProxyResult.direct("invalid-proxy-address");
        }
        String[] parts = value.split(":", 2);
        if (parts.length != 2) {
            return ProxyResult.direct("invalid-proxy-address");
        }
        String host = parts[0] == null ? "" : parts[0].trim();
        String portText = parts[1] == null ? "" : parts[1].trim();
        if (host.length() == 0 || portText.length() == 0) {
            return ProxyResult.direct("invalid-proxy-address");
        }
        try {
            int port = Integer.parseInt(portText);
            if (port < 1 || port > 65535) {
                return ProxyResult.direct("invalid-proxy-port");
            }
            return ProxyResult.of(host, port);
        } catch (NumberFormatException e) {
            return ProxyResult.direct("invalid-proxy-port");
        }
    }
}
