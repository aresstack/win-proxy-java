package com.aresstack.winproxy;

/**
 * Resolves PAC/WPAD URL settings from Windows.
 */
public final class WindowsPacUrlResolver implements PacUrlResolver {

    private static final String WPAD_URL = "http://wpad/wpad.dat";

    public PacUrlResolution resolve() {
        String autoConfigUrl = RegistryReader.queryValueFromAllHives("AutoConfigURL");
        if (hasText(autoConfigUrl)) {
            return PacUrlResolution.found(autoConfigUrl.trim(), "registry:AutoConfigURL");
        }

        String defaultConnectionSettings = RegistryReader.queryValueFromAllHives("DefaultConnectionSettings");
        if (hasText(defaultConnectionSettings) && containsWpadFlag(defaultConnectionSettings)) {
            return PacUrlResolution.found(WPAD_URL, "registry:DefaultConnectionSettings");
        }

        String autoDetect = RegistryReader.queryValueFromAllHives("AutoDetect");
        if ("1".equals(autoDetect) || "0x1".equalsIgnoreCase(autoDetect)) {
            return PacUrlResolution.found(WPAD_URL, "registry:AutoDetect");
        }

        return PacUrlResolution.notFound();
    }

    private boolean containsWpadFlag(String value) {
        String normalized = value.replace(" ", "").toLowerCase();
        return normalized.contains("09") || normalized.contains("0d") || normalized.contains("05");
    }

    private boolean hasText(String value) {
        return value != null && value.trim().length() > 0;
    }
}
