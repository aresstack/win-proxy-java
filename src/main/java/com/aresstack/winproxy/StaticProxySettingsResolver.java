package com.aresstack.winproxy;

/**
 * Resolves static proxy settings from the Windows registry.
 */
public final class StaticProxySettingsResolver {

    private final ProxyServerParser proxyServerParser = new ProxyServerParser();
    private final ProxyBypassMatcher proxyBypassMatcher = new ProxyBypassMatcher();

    public ProxyResult resolve(String targetUrl) {
        for (int i = 0; i < RegistryReader.SETTINGS_KEYS.length; i++) {
            String key = RegistryReader.SETTINGS_KEYS[i];
            String enabled = RegistryReader.queryValue(key, "ProxyEnable");
            if (enabled == null || enabled.trim().length() == 0) {
                continue;
            }
            if (!isEnabled(enabled)) {
                return ProxyResult.direct("static-proxy-disabled");
            }

            String server = RegistryReader.queryValue(key, "ProxyServer");
            if (server == null || server.trim().length() == 0) {
                return ProxyResult.direct("static-proxy-enabled-without-server");
            }

            String bypass = RegistryReader.queryValue(key, "ProxyOverride");
            if (bypass == null || bypass.trim().length() == 0) {
                bypass = RegistryReader.queryValueFromAllHives("ProxyOverride");
            }
            if (proxyBypassMatcher.isBypassed(targetUrl, bypass)) {
                return ProxyResult.direct("registry-bypass");
            }
            return proxyServerParser.parse(server, targetUrl);
        }
        return ProxyResult.direct("no-static-proxy-settings");
    }

    private boolean isEnabled(String value) {
        return "1".equals(value) || "0x1".equalsIgnoreCase(value);
    }
}
