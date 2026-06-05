package com.aresstack.winproxy;

/**
 * Resolves static proxy settings from the Windows registry.
 */
public final class StaticProxySettingsResolver {

    private final ProxyServerParser proxyServerParser = new ProxyServerParser();
    private final ProxyBypassMatcher proxyBypassMatcher = new ProxyBypassMatcher();

    public ProxyResult resolve(String targetUrl) {
        String enabled = RegistryReader.queryValueFromAllHives("ProxyEnable");
        if (!isEnabled(enabled)) {
            return ProxyResult.direct();
        }
        String server = RegistryReader.queryValueFromAllHives("ProxyServer");
        if (server == null || server.trim().length() == 0) {
            return ProxyResult.direct();
        }
        String bypass = RegistryReader.queryValueFromAllHives("ProxyOverride");
        if (proxyBypassMatcher.isBypassed(targetUrl, bypass)) {
            return ProxyResult.direct();
        }
        return proxyServerParser.parse(server, targetUrl);
    }

    private boolean isEnabled(String value) {
        return "1".equals(value) || "0x1".equalsIgnoreCase(value);
    }
}
