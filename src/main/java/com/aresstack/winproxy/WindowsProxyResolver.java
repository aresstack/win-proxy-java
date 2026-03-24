package com.aresstack.winproxy;

import java.net.URI;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Detects proxy settings on Windows by reading the Windows Registry and
 * evaluating PAC/WPAD auto-configuration scripts via GraalJS.
 * <p>
 * <b>No PowerShell required.</b> Works on hardened systems where PowerShell
 * Constrained Language Mode (CLM) blocks .NET method calls.
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>Searches <b>all four registry hives</b> (GPO first!) for {@code AutoConfigURL}</li>
 *   <li>Checks the {@code DefaultConnectionSettings} binary blob for an embedded PAC URL</li>
 *   <li>Tries WPAD auto-detect ({@code http://wpad/wpad.dat}) if the flag is set</li>
 *   <li>Falls back to static proxy settings across all four registry hives</li>
 *   <li>Respects the {@code ProxyOverride} bypass list</li>
 * </ol>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // Full auto-detection:
 * ProxyResult result = WindowsProxyResolver.resolve("https://example.com");
 *
 * // Choose PAC URL source explicitly:
 * ProxyResult r1 = WindowsProxyResolver.resolve(target, PacUrlSource.DIRECT,
 *     "http://wpad.corp.local/wpad.dat");
 * ProxyResult r2 = WindowsProxyResolver.resolve(target, PacUrlSource.REGISTRY, null);
 * ProxyResult r3 = WindowsProxyResolver.resolve(target, PacUrlSource.POWERSHELL, null);
 *
 * if (result.isDirect()) {
 *     connection = url.openConnection();
 * } else {
 *     connection = url.openConnection(result.toJavaProxy());
 * }
 * }</pre>
 *
 * @see ProxyResult
 * @see PacUrlSource
 */
public final class WindowsProxyResolver {

    private static final Logger LOG = Logger.getLogger(WindowsProxyResolver.class.getName());

    /** The Windows Registry key containing user-level Internet/proxy settings. */
    public static final String INTERNET_SETTINGS_KEY =
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings";

    /**
     * Default PowerShell command to discover the PAC URL from the Windows Registry.
     * Works even under PowerShell Constrained Language Mode (CLM).
     */
    public static final String DEFAULT_PAC_DISCOVERY_SCRIPT =
            "(Get-ItemProperty -Path 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings').AutoConfigURL";

    private WindowsProxyResolver() {}

    // ── Public API ───────────────────────────────────────────────

    /**
     * Resolves the proxy using the full Windows auto-detection chain (registry-based).
     * Equivalent to {@code resolve(url, PacUrlSource.REGISTRY, null)}.
     */
    public static ProxyResult resolve(String url) {
        try {
            String autoConfigUrl = RegistryReader.queryValueFromAllHives("AutoConfigURL");
            if (autoConfigUrl != null && !autoConfigUrl.isEmpty()) {
                LOG.fine("[WinProxy] AutoConfigURL = " + autoConfigUrl);
                ProxyResult pacResult = evaluatePacInternal(autoConfigUrl, url);
                if (pacResult != null) return pacResult;
            }

            String blobPacUrl = RegistryReader.queryAutoConfigUrlFromBlob();
            if (blobPacUrl != null && !blobPacUrl.equals(autoConfigUrl)) {
                ProxyResult blobResult = evaluatePacInternal(blobPacUrl, url);
                if (blobResult != null) return blobResult;
            }

            if (isWpadAutoDetectEnabled()) {
                ProxyResult wpadResult = resolveViaWpad(url);
                if (wpadResult != null) return wpadResult;
            }

            return resolveStatic(url);
        } catch (Exception e) {
            LOG.log(Level.FINE, "[WinProxy] Resolution failed", e);
            return ProxyResult.direct("error: " + e.getMessage());
        }
    }

    /**
     * Resolves the proxy using the specified {@link PacUrlSource} strategy.
     * This is the <b>primary facade method</b>.
     *
     * @param targetUrl      the URL to resolve the proxy for
     * @param source         how to obtain the PAC URL
     * @param pacUrlOrScript PAC URL, PowerShell command, or {@code null} (depending on source)
     * @return a {@link ProxyResult} — never {@code null}
     */
    public static ProxyResult resolve(String targetUrl, PacUrlSource source, String pacUrlOrScript) {
        if (source == null) source = PacUrlSource.REGISTRY;

        switch (source) {
            case DIRECT:
                if (pacUrlOrScript == null || pacUrlOrScript.trim().isEmpty()) {
                    return ProxyResult.direct("pac-url-empty");
                }
                return evaluatePac(pacUrlOrScript.trim(), targetUrl);

            case POWERSHELL:
                return resolveViaPowerShellScript(targetUrl, pacUrlOrScript);

            case REGISTRY:
            default:
                return resolve(targetUrl);
        }
    }

    /** Resolves using only static registry settings (ProxyEnable + ProxyServer). */
    public static ProxyResult resolveStatic(String url) {
        try {
            for (String key : RegistryReader.SETTINGS_KEYS) {
                String proxyEnable = RegistryReader.queryValue(key, "ProxyEnable");
                if (!"0x1".equals(proxyEnable != null ? proxyEnable.trim() : "")) continue;

                String proxyServer = RegistryReader.queryValue(key, "ProxyServer");
                if (proxyServer == null || proxyServer.trim().isEmpty()) continue;

                String proxyOverride = RegistryReader.queryValue(key, "ProxyOverride");
                if (proxyOverride == null || proxyOverride.trim().isEmpty()) {
                    proxyOverride = RegistryReader.queryValueFromAllHives("ProxyOverride");
                }

                if (proxyOverride != null && !proxyOverride.trim().isEmpty() && url != null) {
                    try {
                        String targetHost = URI.create(url).getHost();
                        if (targetHost != null && isBypassed(targetHost, proxyOverride)) {
                            return ProxyResult.direct("bypass-match");
                        }
                    } catch (Exception ignore) { }
                }

                String server = proxyServer.trim();
                if (server.contains("=")) {
                    String extracted = extractProxyForProtocol(server, url);
                    if (extracted != null) server = extracted;
                    else continue;
                }

                return parseHostPort(server, "static");
            }
            return ProxyResult.direct("proxy-disabled");
        } catch (Exception e) {
            return ProxyResult.direct("error: " + e.getMessage());
        }
    }

    /** Evaluates a PAC auto-configuration script from the given URL via GraalJS. */
    public static ProxyResult evaluatePac(String pacUrl, String targetUrl) {
        ProxyResult result = evaluatePacInternal(pacUrl, targetUrl);
        return result != null ? result : ProxyResult.direct("pac-evaluation-failed");
    }

    /** Evaluates a PAC script string (not a URL) against the given target URL. */
    public static ProxyResult evaluatePacScript(String pacScript, String targetUrl) {
        try {
            String pacResult = PacEvaluator.evaluateScript(pacScript, targetUrl);
            if (pacResult == null) return ProxyResult.direct("pac-script-null");
            return parsePacResult(pacResult.trim());
        } catch (Exception e) {
            return ProxyResult.direct("pac-script-error: " + e.getMessage());
        }
    }

    /** Reads a single value from the Windows Registry via {@code reg.exe}. */
    public static String readRegistryValue(String key, String valueName) {
        return RegistryReader.queryValue(key, valueName);
    }

    /** Searches all four registry hives for the given value name (GPO first). */
    public static String readRegistryValueFromAllHives(String valueName) {
        return RegistryReader.queryValueFromAllHives(valueName);
    }

    /** Checks if a host matches the Windows proxy bypass pattern list. */
    public static boolean isBypassed(String host, String proxyOverride) {
        if (host == null || proxyOverride == null) return false;
        String lowerHost = host.toLowerCase(Locale.ROOT);
        for (String pattern : proxyOverride.split(";")) {
            String p = pattern.trim().toLowerCase(Locale.ROOT);
            if (p.isEmpty()) continue;
            if ("<local>".equals(p)) { if (!lowerHost.contains(".")) return true; continue; }
            if (p.startsWith("*")) { if (lowerHost.endsWith(p.substring(1))) return true; }
            else if (p.endsWith("*")) { if (lowerHost.startsWith(p.substring(0, p.length() - 1))) return true; }
            else if (lowerHost.equals(p)) return true;
        }
        return false;
    }

    /** Returns {@code true} if WPAD auto-detect is enabled in connection settings. */
    public static boolean isWpadAutoDetectEnabled() {
        int flags = RegistryReader.queryConnectionFlags();
        if (flags < 0) return false;
        return (flags & RegistryReader.FLAG_AUTO_DETECT) != 0;
    }

    /** Reads the raw connection flags byte. Returns -1 if unreadable. */
    public static int readConnectionFlags() {
        return RegistryReader.queryConnectionFlags();
    }

    // ── Internal ─────────────────────────────────────────────────

    private static ProxyResult resolveViaWpad(String targetUrl) {
        try {
            String wpadResult = PacEvaluator.evaluate("http://wpad/wpad.dat", targetUrl);
            if (wpadResult == null) return null;
            ProxyResult result = parsePacResult(wpadResult.trim());
            return result.isDirect() ? ProxyResult.direct("wpad-direct") : ProxyResult.proxy(result.getHost(), result.getPort(), "wpad");
        } catch (Exception e) {
            return null;
        }
    }

    private static ProxyResult evaluatePacInternal(String pacUrl, String targetUrl) {
        try {
            String pacResult = PacEvaluator.evaluate(pacUrl, targetUrl);
            if (pacResult == null) return null;
            return parsePacResult(pacResult.trim());
        } catch (Exception e) {
            return null;
        }
    }

    static ProxyResult parsePacResult(String pacResult) {
        if (pacResult == null || pacResult.isEmpty() || "DIRECT".equalsIgnoreCase(pacResult)) {
            return ProxyResult.direct("pac-direct");
        }
        for (String entry : pacResult.split(";")) {
            String trimmed = entry.trim();
            if (trimmed.toUpperCase(Locale.ROOT).startsWith("PROXY ")) {
                ProxyResult parsed = parseHostPort(trimmed.substring(6).trim(), "pac");
                if (!parsed.isDirect()) return parsed;
            }
        }
        return ProxyResult.direct("pac-direct");
    }

    private static ProxyResult parseHostPort(String hostPort, String source) {
        int idx = hostPort.lastIndexOf(':');
        if (idx > 0 && idx < hostPort.length() - 1) {
            String host = hostPort.substring(0, idx).trim();
            String portStr = hostPort.substring(idx + 1).trim().replaceAll("[^0-9]", "");
            if (!portStr.isEmpty()) {
                try {
                    int port = Integer.parseInt(portStr);
                    if (port > 0 && port <= 65535) return ProxyResult.proxy(host, port, source);
                } catch (NumberFormatException ignore) { }
            }
        }
        return ProxyResult.direct(source + "-invalid: " + hostPort);
    }

    private static ProxyResult resolveViaPowerShellScript(String targetUrl, String script) {
        String effectiveScript = (script != null && !script.trim().isEmpty()) ? script.trim() : DEFAULT_PAC_DISCOVERY_SCRIPT;
        String pacUrl = ScriptRunner.executePowerShell(effectiveScript);
        if (pacUrl == null || pacUrl.trim().isEmpty()) return ProxyResult.direct("pac-script-empty");
        return evaluatePac(pacUrl.trim(), targetUrl);
    }

    static String extractProxyForProtocol(String proxyServer, String url) {
        String protocol = "http";
        if (url != null) { try { protocol = URI.create(url).getScheme(); } catch (Exception ignore) { } }
        if (protocol == null) protocol = "http";

        for (String entry : proxyServer.split(";")) {
            String[] kv = entry.split("=", 2);
            if (kv.length == 2 && protocol.equalsIgnoreCase(kv[0].trim())) return kv[1].trim();
        }
        if (!"http".equalsIgnoreCase(protocol)) {
            for (String entry : proxyServer.split(";")) {
                String[] kv = entry.split("=", 2);
                if (kv.length == 2 && "http".equalsIgnoreCase(kv[0].trim())) return kv[1].trim();
            }
        }
        return null;
    }
}

