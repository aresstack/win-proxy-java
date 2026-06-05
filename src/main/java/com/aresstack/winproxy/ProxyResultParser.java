package com.aresstack.winproxy;

/**
 * Parses PAC proxy result strings.
 */
public final class ProxyResultParser {

    public ProxyResult parse(String pacResult) {
        if (pacResult == null || pacResult.trim().length() == 0) {
            return ProxyResult.direct();
        }

        String[] entries = pacResult.split(";");
        for (int i = 0; i < entries.length; i++) {
            ProxyResult result = parseEntry(entries[i]);
            if (!result.isDirect()) {
                return result;
            }
        }
        return ProxyResult.direct();
    }

    private ProxyResult parseEntry(String entry) {
        if (entry == null) {
            return ProxyResult.direct();
        }
        String trimmed = entry.trim();
        if (trimmed.length() == 0 || "DIRECT".equalsIgnoreCase(trimmed)) {
            return ProxyResult.direct();
        }
        String upper = trimmed.toUpperCase();
        if (upper.startsWith("PROXY ")) {
            return parseHostPort(trimmed.substring(6).trim());
        }
        if (upper.startsWith("HTTPS ")) {
            return parseHostPort(trimmed.substring(6).trim());
        }
        if (upper.startsWith("SOCKS ")) {
            return parseHostPort(trimmed.substring(6).trim());
        }
        if (trimmed.indexOf(':') > 0) {
            return parseHostPort(trimmed);
        }
        return ProxyResult.direct();
    }

    private ProxyResult parseHostPort(String value) {
        String[] parts = value.split(":", 2);
        if (parts.length != 2) {
            return ProxyResult.direct();
        }
        try {
            int port = Integer.parseInt(parts[1]);
            return ProxyResult.of(parts[0], port);
        } catch (NumberFormatException e) {
            return ProxyResult.direct();
        }
    }
}
