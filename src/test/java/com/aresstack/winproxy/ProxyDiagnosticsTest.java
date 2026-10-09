package com.aresstack.winproxy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProxyDiagnostics} records the PAC URL and its source, the PAC script size, the
 * evaluation result and the underlying failure message, and its result is exactly what
 * {@code resolve()} returns.
 */
class ProxyDiagnosticsTest {

    private static final String TARGET = "https://plugins.gradle.org/m2/";
    private static final String PAC_URL = "http://proxy.example.com/wpad.dat";
    private static final String PAC_SCRIPT =
            "function FindProxyForURL(url, host) { return 'PROXY proxy.example.com:3128'; }";

    private static final PacUrlResolver FOUND = () -> PacUrlResolution.found(PAC_URL, "test");
    private static final PacScriptLoader LOADS = pacUrl -> PAC_SCRIPT;
    private static final PacEvaluator EVALUATES = (script, url) -> ProxyResult.of("proxy.example.com", 3128);

    @Test
    void recordsEveryStepOfASuccessfulResolution() {
        ProxyDiagnostics diagnostics = new PacUrlProxyResolver(FOUND, LOADS, EVALUATES).diagnose(TARGET);

        assertEquals(TARGET, diagnostics.getTargetUrl());
        assertEquals(PAC_URL, diagnostics.getPacUrl());
        assertEquals("test", diagnostics.getPacUrlSource());
        assertEquals(PAC_SCRIPT.length(), diagnostics.getPacScriptLength());
        assertTrue(diagnostics.getResult().isProxy());
        assertNull(diagnostics.getFailureDetail());
        assertEquals(3, diagnostics.getSteps().size(), diagnostics.getSteps().toString());
        assertTrue(diagnostics.getDurationMillis() >= 0);

        String report = diagnostics.describe();
        assertTrue(report.contains("PAC URL: " + PAC_URL + " (source: test)"), report);
        assertTrue(report.contains("PAC script downloaded (" + PAC_SCRIPT.length() + " characters)"), report);
        assertTrue(report.contains("Result: PROXY proxy.example.com:3128 (resolved)"), report);
    }

    @Test
    void downloadFailureKeepsPacUrlAndCarriesDetail() {
        PacScriptLoader failing = pacUrl -> {
            throw new ProxyResolutionException("Could not load PAC script from " + pacUrl + ".",
                    new java.net.ConnectException("Connection refused"));
        };

        ProxyDiagnostics diagnostics = new PacUrlProxyResolver(FOUND, failing, EVALUATES).diagnose(TARGET);

        assertEquals(PAC_URL, diagnostics.getPacUrl());
        assertEquals(-1, diagnostics.getPacScriptLength());
        assertTrue(diagnostics.getResult().isError());
        assertFalse(diagnostics.getResult().isDirect());
        assertEquals("pac-download-failed", diagnostics.getResult().getReason());
        assertNotNull(diagnostics.getFailureDetail());
        assertTrue(diagnostics.getFailureDetail().contains("Connection refused"), diagnostics.getFailureDetail());
        assertTrue(diagnostics.getResult().toString().contains("Connection refused"));
    }

    @Test
    void evaluationFailureExposesTheRootCause() {
        PacEvaluator failing = (script, url) -> {
            throw new ProxyResolutionException("Could not evaluate PAC script for " + url + ".",
                    new RuntimeException("SyntaxError: No language for id regex found."));
        };

        ProxyDiagnostics diagnostics = new PacUrlProxyResolver(FOUND, LOADS, failing).diagnose(TARGET);

        assertEquals("pac-evaluation-failed", diagnostics.getResult().getReason());
        assertTrue(diagnostics.getResult().getDetail().contains("No language for id regex found"),
                diagnostics.getResult().getDetail());
        assertEquals(PAC_SCRIPT.length(), diagnostics.getPacScriptLength());
    }

    @Test
    void discoveryFailureAndNotFoundAreErrorsWithDetail() {
        PacUrlResolver throwing = () -> {
            throw new ProxyResolutionException("discovery boom");
        };

        ProxyDiagnostics failed = new PacUrlProxyResolver(throwing, LOADS, EVALUATES).diagnose(TARGET);
        ProxyDiagnostics notFound = new PacUrlProxyResolver(PacUrlResolution::notFound, LOADS, EVALUATES).diagnose(TARGET);

        assertEquals("pac-url-discovery-failed", failed.getResult().getReason());
        assertEquals("discovery boom", failed.getFailureDetail());
        assertNull(failed.getPacUrl());
        assertEquals("pac-url-not-found", notFound.getResult().getReason());
        assertFalse(notFound.getResult().isDirect());
        assertNotNull(notFound.getFailureDetail());
    }

    @Test
    void resolveAndDiagnoseAgree() {
        PacUrlProxyResolver pipeline = new PacUrlProxyResolver(FOUND, LOADS, EVALUATES);

        assertEquals(pipeline.resolve(TARGET).toString(), pipeline.diagnose(TARGET).getResult().toString());
    }

    @Test
    void facadeDiagnosesNonPacModes() {
        ProxyDiagnostics disabled = new WindowsProxyResolver(ProxyConfiguration.builder()
                .mode(ProxyMode.DISABLED).build()).diagnose(TARGET);
        ProxyDiagnostics manual = new WindowsProxyResolver(ProxyConfiguration.builder()
                .mode(ProxyMode.MANUAL_PROXY).manualProxyHost("proxy.example.com").manualProxyPort(8080).build())
                .diagnose(TARGET);
        ProxyDiagnostics reserved = new WindowsProxyResolver(ProxyConfiguration.builder()
                .mode(ProxyMode.WINDOWS_NATIVE_ROUTE_RESOLVER).build()).diagnose(TARGET);

        assertEquals(ProxyMode.DISABLED, disabled.getMode());
        assertTrue(disabled.getResult().isDirect());
        assertNull(disabled.getPacUrl());
        assertTrue(manual.getResult().isProxy());
        assertEquals(1, manual.getSteps().size());
        assertTrue(reserved.getResult().isNotImplemented());
        assertFalse(reserved.getResult().isDirect());
        assertNotNull(reserved.getFailureDetail());
        assertTrue(reserved.describe().contains("NOT_IMPLEMENTED"));
    }

    @Test
    void facadeDiagnosesPacModesWithTheirMode() {
        ProxyDiagnostics diagnostics = new WindowsProxyResolver(ProxyConfiguration.builder()
                .mode(ProxyMode.PAC_URL_MANUAL)
                .build()).diagnose(TARGET);

        assertEquals(ProxyMode.PAC_URL_MANUAL, diagnostics.getMode());
        assertEquals("pac-url-not-found", diagnostics.getResult().getReason());
        assertTrue(diagnostics.describe().startsWith("Mode: PAC_URL_MANUAL"));
    }

    @Test
    void errorToStringCarriesDetailOnlyWhenPresent() {
        assertEquals("ERROR (x)", ProxyResult.error("x").toString());
        assertEquals("ERROR (x): why", ProxyResult.error("x", "why").toString());
        assertEquals("ERROR (x)", ProxyResult.error("x", "  ").toString());
        assertNull(ProxyResult.error("x").getDetail());
        assertEquals("why", ProxyResult.error("x", "why").getDetail());
        assertEquals("NOT_IMPLEMENTED (r): d", ProxyResult.notImplemented("r", "d").toString());
    }

    @Test
    void describeChainsCauses() {
        String text = PacUrlProxyResolver.describe(new ProxyResolutionException("outer",
                new IllegalStateException("inner")));

        assertEquals("outer <- java.lang.IllegalStateException: inner", text);
        assertEquals("java.lang.IllegalStateException", PacUrlProxyResolver.describe(new IllegalStateException()));
    }
}
