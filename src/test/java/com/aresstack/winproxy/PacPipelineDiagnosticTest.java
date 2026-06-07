package com.aresstack.winproxy;

import org.junit.jupiter.api.Test;

/**
 * Manual, network-touching diagnostic that exercises the real PAC pipeline against
 * the locally configured Windows proxy settings, once per discovery strategy.
 *
 * <p>It never fails the build (no hard assertions on network/registry state) — it
 * prints a report so you can see EXACTLY what each mode returns and, on failure,
 * the technical reason instead of a masked DIRECT.
 */
class PacPipelineDiagnosticTest {

    private static final String TARGET_URL = "https://plugins.gradle.org/m2/";

    @Test
    void diagnoseAllPacDiscoveryStrategies() {
        System.out.println("==================================================================");
        System.out.println(" win-proxy-java PAC pipeline diagnostic");
        System.out.println(" target URL: " + TARGET_URL);
        System.out.println("==================================================================");

        // PAC URL via PowerShell (inline -Command) — the important hardened-machine path
        runMode(ProxyMode.PAC_URL_POWERSHELL, ProxyConfiguration.builder()
                .mode(ProxyMode.PAC_URL_POWERSHELL));

        // PAC URL via reg.exe / Windows settings (no PowerShell)
        runMode(ProxyMode.PAC_URL_WINDOWS_SETTINGS, ProxyConfiguration.builder()
                .mode(ProxyMode.PAC_URL_WINDOWS_SETTINGS));

        System.out.println("==================================================================");
    }

    private void runMode(ProxyMode mode, ProxyConfiguration.Builder builder) {
        try {
            ProxyResult result = new WindowsProxyResolver(builder.build()).resolve(TARGET_URL);
            System.out.println("[" + mode + "] -> " + result + "  (kind=" + result.getKind() + ")");
        } catch (Throwable t) {
            System.out.println("[" + mode + "] THREW: " + t);
            t.printStackTrace(System.out);
        }
    }
}
