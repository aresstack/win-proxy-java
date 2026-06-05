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

        String embeddedPacUrl = RegistryReader.queryAutoConfigUrlFromBlob();
        if (hasText(embeddedPacUrl)) {
            return PacUrlResolution.found(embeddedPacUrl.trim(), "registry:DefaultConnectionSettings");
        }

        int flags = RegistryReader.queryConnectionFlags();
        if (flags >= 0 && (flags & RegistryReader.FLAG_AUTO_DETECT) != 0) {
            return PacUrlResolution.found(WPAD_URL, "registry:DefaultConnectionSettings:auto-detect");
        }

        String autoDetect = RegistryReader.queryValueFromAllHives("AutoDetect");
        if ("1".equals(autoDetect) || "0x1".equalsIgnoreCase(autoDetect)) {
            return PacUrlResolution.found(WPAD_URL, "registry:AutoDetect");
        }

        return PacUrlResolution.notFound();
    }

    private boolean hasText(String value) {
        return value != null && value.trim().length() > 0;
    }
}
