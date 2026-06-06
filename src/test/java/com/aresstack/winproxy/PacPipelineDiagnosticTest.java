package com.aresstack.winproxy;

import org.junit.jupiter.api.Test;

/**
 * Manual, network-touching diagnostic that exercises the real PAC_URL pipeline
 * stage by stage against the locally configured Windows proxy settings.
 *
 * <p>It never fails the build (no hard assertions on network state) — it prints a
 * report so you can see EXACTLY at which stage the resolution turns into DIRECT.
 */
class PacPipelineDiagnosticTest {

    private static final String TARGET_URL = "https://plugins.gradle.org/m2/";

    @Test
    void diagnosePacUrlPipeline() {
        System.out.println("==================================================================");
        System.out.println(" win-proxy-java PAC pipeline diagnostic");
        System.out.println(" target URL: " + TARGET_URL);
        System.out.println("==================================================================");

        WindowsProxyResolver resolver = new WindowsProxyResolver();

        // Stage 1: PAC URL discovery (PowerShell / registry)
        String pacUrl = null;
        try {
            PacUrlResolution resolution = resolver.discoverPacUrl();
            System.out.println("[1] discoverPacUrl -> present=" + resolution.isPresent()
                    + (resolution.isPresent() ? ", url=" + resolution.getPacUrl()
                    + ", source=" + resolution.getSource() : ""));
            if (resolution.isPresent()) {
                pacUrl = resolution.getPacUrl();
            }
        } catch (Throwable t) {
            System.out.println("[1] discoverPacUrl THREW: " + t);
            t.printStackTrace(System.out);
        }

        // Stage 2: load the PAC script over the network
        String pacScript = null;
        if (pacUrl != null) {
            try {
                pacScript = new UrlConnectionPacScriptLoader().load(pacUrl);
                System.out.println("[2] load PAC script -> " + pacScript.length() + " chars");
                System.out.println("----- PAC script start -----");
                System.out.println(pacScript.trim());
                System.out.println("----- PAC script end -------");
            } catch (Throwable t) {
                System.out.println("[2] load PAC script THREW: " + t);
                t.printStackTrace(System.out);
            }
        } else {
            System.out.println("[2] skipped (no PAC URL discovered)");
        }

        // Stage 3: evaluate the PAC script with GraalJS
        if (pacScript != null) {
            try {
                ProxyResult evaluated = PacEvaluator.createDefault().evaluate(pacScript, TARGET_URL);
                System.out.println("[3] evaluate -> " + evaluated);
            } catch (Throwable t) {
                System.out.println("[3] evaluate THREW: " + t);
                t.printStackTrace(System.out);
            }
        } else {
            System.out.println("[3] skipped (no PAC script loaded)");
        }

        // Stage 4: the full public pipeline exactly as a caller would use it
        try {
            ProxyResult result = resolver.resolve(TARGET_URL);
            System.out.println("[4] resolver.resolve(url) -> " + result);
        } catch (Throwable t) {
            System.out.println("[4] resolver.resolve(url) THREW: " + t);
            t.printStackTrace(System.out);
        }

        // Stage 5: the DEPRECATED legacy path that directml-workbench/ProxyPanel actually uses
        try {
            ProxyResult result = resolver.resolve(
                    TARGET_URL, PacUrlSource.POWERSHELL, ProxyDefaults.DEFAULT_PAC_URL_DISCOVERY_SCRIPT);
            System.out.println("[5] resolve(url, POWERSHELL, defaultScript) -> " + result
                    + "   <-- exact workbench path");
        } catch (Throwable t) {
            System.out.println("[5] resolve(url, POWERSHELL, defaultScript) THREW: " + t);
            t.printStackTrace(System.out);
        }

        // Stage 6: legacy POWERSHELL discovery only (URL string)
        try {
            String url = resolver.discoverPacUrlWithPowerShell(ProxyDefaults.DEFAULT_PAC_URL_DISCOVERY_SCRIPT);
            System.out.println("[6] discoverPacUrlWithPowerShell -> " + url);
        } catch (Throwable t) {
            System.out.println("[6] discoverPacUrlWithPowerShell THREW: " + t);
            t.printStackTrace(System.out);
        }

        System.out.println("==================================================================");
    }
}
