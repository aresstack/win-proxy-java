package com.aresstack.winproxy;

/**
 * Parses the route string returned by {@code FindProxyForURL} (or a Windows
 * {@code ProxyServer} value) into a {@link ProxyResult}.
 * <p>
 * The route string is a {@code ;}-separated preference list evaluated left to right;
 * the first usable entry wins:
 * <ul>
 *   <li>an explicit {@code DIRECT} entry yields {@code DIRECT pac-direct},</li>
 *   <li>a valid {@code PROXY host:port} / {@code HTTPS host:port} / bare {@code host:port}
 *       entry yields a {@link ProxyResult.Kind#PROXY} result,</li>
 *   <li>an unsupported entry (e.g. {@code SOCKS}) or a malformed {@code host:port} is an
 *       error candidate.</li>
 * </ul>
 * Unsupported or malformed entries are <b>never</b> silently masked as {@code DIRECT}:
 * they only become {@code DIRECT} when a <em>later</em> entry is an explicit {@code DIRECT}
 * (or a usable proxy). Otherwise the parser returns an {@link ProxyResult.Kind#ERROR} with a
 * technical reason ({@code unsupported-pac-entry}, {@code invalid-proxy-port},
 * {@code invalid-proxy-address}, {@code empty-pac-result}). {@code DIRECT} is therefore only
 * returned when the PAC result itself explicitly contains {@code DIRECT}.
 */
public final class PacProxyRouteParser {

    public ProxyResult parse(String pacResult) {
        if (pacResult == null || pacResult.trim().length() == 0) {
            return ProxyResult.error("empty-pac-result");
        }

        ProxyResult firstError = null;
        String[] entries = pacResult.split(";");
        for (int i = 0; i < entries.length; i++) {
            ProxyResult result = parseEntry(entries[i]);
            if (result == null) {
                // blank entry — neither explicit DIRECT nor proxy nor error; skip it
                continue;
            }
            if (result.isProxy() || result.isDirect()) {
                // first usable entry (explicit proxy or explicit DIRECT) wins
                return result;
            }
            // error candidate (unsupported / malformed): remember the first one but keep
            // scanning — a later explicit DIRECT or usable proxy still takes precedence
            if (firstError == null) {
                firstError = result;
            }
        }
        if (firstError != null) {
            return firstError;
        }
        // non-empty string that contained only blank entries (e.g. ";")
        return ProxyResult.error("empty-pac-result");
    }

    /** Returns {@code null} for a blank entry. */
    private ProxyResult parseEntry(String entry) {
        if (entry == null) {
            return null;
        }
        String trimmed = entry.trim();
        if (trimmed.length() == 0) {
            return null;
        }
        if ("DIRECT".equalsIgnoreCase(trimmed)) {
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
        return ProxyResult.error("unsupported-pac-entry");
    }

    private ProxyResult parseHostPort(String value) {
        if (value == null) {
            return ProxyResult.error("invalid-proxy-address");
        }
        String[] parts = value.split(":", 2);
        if (parts.length != 2) {
            return ProxyResult.error("invalid-proxy-address");
        }
        String host = parts[0] == null ? "" : parts[0].trim();
        String portText = parts[1] == null ? "" : parts[1].trim();
        if (host.length() == 0 || portText.length() == 0) {
            return ProxyResult.error("invalid-proxy-address");
        }
        try {
            int port = Integer.parseInt(portText);
            if (port < 1 || port > 65535) {
                return ProxyResult.error("invalid-proxy-port");
            }
            return ProxyResult.of(host, port);
        } catch (NumberFormatException e) {
            return ProxyResult.error("invalid-proxy-port");
        }
    }
}
