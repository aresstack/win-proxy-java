package com.aresstack.winproxy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hardening regression tests for the shared PAC pipeline used by every
 * {@code PAC_URL_*} mode. They lock down the non-negotiable guarantees that
 * came out of the review:
 *
 * <ul>
 *   <li>A failed or empty PAC-URL discovery always surfaces as an {@code ERROR}
 *       {@link ProxyResult} — never as a masked {@code DIRECT} and never as a
 *       static-proxy / registry / manual fallback. In particular it never
 *       becomes {@code DIRECT no-static-proxy-settings}.</li>
 *   <li>{@code PAC_URL_MANUAL} uses only the configured PAC URL — no PowerShell,
 *       no {@link WindowsPacUrlResolver}, no {@link StaticProxySettingsResolver}.</li>
 *   <li>The inline PowerShell discovery uses {@code -Command}, never a temporary
 *       {@code -File}.ps1 — that was the acute hardening bug.</li>
 * </ul>
 *
 * <p>The {@code PAC_URL_POWERSHELL} and {@code PAC_URL_WINDOWS_SETTINGS} modes are
 * exercised at the {@link PacUrlProxyResolver} layer with injected fakes so the
 * guarantees are verified deterministically on any OS (no real {@code powershell.exe}
 * / {@code reg.exe}). {@link WindowsProxyResolver} wires the exact same pipeline.
 */
class PacUrlDiscoveryHardeningTest {

    private static final String TARGET = "https://plugins.gradle.org/m2/";

    /** Loader that must never be reached once discovery has already failed. */
    private static final PacScriptLoader FAIL_LOADER = pacUrl -> {
        throw new AssertionError("PAC script loader must not run when discovery failed: " + pacUrl);
    };

    /** Evaluator that must never be reached once discovery has already failed. */
    private static final PacEvaluator FAIL_EVALUATOR = (script, url) -> {
        throw new AssertionError("PAC evaluator must not run when discovery failed");
    };

    // ── PAC_URL_POWERSHELL / PAC_URL_WINDOWS_SETTINGS: discovery empty ──

    @Test
    void discoveryEmptyYieldsErrorNotDirect() {
        // Simulates AutoConfigURL not set / reg.exe empty for the shared pipeline.
        PacUrlProxyResolver pipeline =
                new PacUrlProxyResolver(PacUrlResolution::notFound, FAIL_LOADER, FAIL_EVALUATOR);

        ProxyResult result = pipeline.resolve(TARGET);

        assertTrue(result.isError(), "empty discovery must be ERROR");
        assertFalse(result.isDirect(), "empty discovery must never be DIRECT");
        assertEquals("pac-url-not-found", result.getReason());
    }

    // ── PAC_URL_POWERSHELL / PAC_URL_WINDOWS_SETTINGS: discovery fails ──

    @Test
    void discoveryFailureYieldsErrorNotDirect() {
        PacUrlResolver throwing = () -> {
            throw new ProxyResolutionException("discovery boom");
        };
        PacUrlProxyResolver pipeline =
                new PacUrlProxyResolver(throwing, FAIL_LOADER, FAIL_EVALUATOR);

        ProxyResult result = pipeline.resolve(TARGET);

        assertTrue(result.isError(), "failed discovery must be ERROR");
        assertFalse(result.isDirect(), "failed discovery must never be DIRECT");
        assertEquals("pac-url-discovery-failed", result.getReason());
    }

    // ── PAC_URL_MANUAL: only the configured PAC URL, no auto discovery ──

    @Test
    void manualPacUrlUsesOnlyConfiguredUrl() {
        PacUrlResolution present = new FixedPacUrlResolver("http://proxy.example/wpad.dat").resolve();
        assertTrue(present.isPresent());
        assertEquals("http://proxy.example/wpad.dat", present.getPacUrl());
        assertEquals("configuration", present.getSource(), "manual PAC URL comes from configuration only");

        assertFalse(new FixedPacUrlResolver("   ").resolve().isPresent(), "blank PAC URL is not present");
        assertFalse(new FixedPacUrlResolver(null).resolve().isPresent(), "null PAC URL is not present");
    }

    @Test
    void manualPacUrlBlankYieldsErrorNotDirect() {
        ProxyResult result = new WindowsProxyResolver(ProxyConfiguration.builder()
                .mode(ProxyMode.PAC_URL_MANUAL)
                .build())
                .resolve(TARGET);

        assertTrue(result.isError());
        assertFalse(result.isDirect(), "missing manual PAC URL must never be DIRECT");
        assertEquals("pac-url-not-found", result.getReason());
    }

    // ── PowerShell discovery must run inline, never via a temp .ps1 ──

    @Test
    void inlinePowerShellCommandNeverWritesTempFile() {
        List<String> command = new ScriptRunner("Write-Output 'x'").buildInlineCommand();

        assertEquals("powershell.exe", command.get(0));
        assertTrue(command.contains("-Command"), "inline discovery must use -Command");
        assertFalse(command.contains("-File"), "inline discovery must never use -File (no temp .ps1)");
        // The script is passed inline as the last argument, not as a file path.
        assertEquals("Write-Output 'x'", command.get(command.size() - 1));
    }
}
