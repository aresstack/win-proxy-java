package com.aresstack.winproxy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsProxyResolverTest {

    @Test
    void defaultsToPowerShellPacMode() {
        ProxyConfiguration configuration = ProxyConfiguration.defaults();

        assertEquals(ProxyMode.PAC_URL_POWERSHELL, configuration.getMode());
        assertEquals(ProxyDefaults.DEFAULT_TEST_URL, configuration.getTestUrl());
        assertEquals(ProxyDefaults.DEFAULT_PAC_URL_DISCOVERY_SCRIPT, configuration.getPacUrlDiscoveryScript());
        assertEquals(ProxyDefaults.DEFAULT_WINDOWS_PAC_SCRIPT, configuration.getWindowsPacScript());
    }

    @Test
    void blankValuesFallBackToDefaultConfigurationValues() {
        ProxyConfiguration configuration = ProxyConfiguration.builder()
                .testUrl(" ")
                .pacUrl(" ")
                .pacUrlDiscoveryScript(" ")
                .windowsPacScript(" ")
                .manualProxyHost(" ")
                .build();

        assertEquals(ProxyDefaults.DEFAULT_TEST_URL, configuration.getTestUrl());
        assertEquals(null, configuration.getPacUrl());
        assertEquals(ProxyDefaults.DEFAULT_PAC_URL_DISCOVERY_SCRIPT, configuration.getPacUrlDiscoveryScript());
        assertEquals(ProxyDefaults.DEFAULT_WINDOWS_PAC_SCRIPT, configuration.getWindowsPacScript());
        assertEquals(null, configuration.getManualProxyHost());
    }

    @Test
    void customPacUrlDiscoveryScriptOverridesDefaultScript() {
        ProxyConfiguration configuration = ProxyConfiguration.builder()
                .pacUrlDiscoveryScript("Write-Output 'http://example.com/wpad.dat'")
                .build();

        assertEquals("Write-Output 'http://example.com/wpad.dat'", configuration.getPacUrlDiscoveryScript());
    }

    @Test
    void keepsDefaultPowerShellAutoConfigUrlQuery() {
        assertEquals(
                "(Get-ItemProperty -Path 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings').AutoConfigURL",
                ProxyDefaults.DEFAULT_PAC_URL_DISCOVERY_SCRIPT
        );
    }

    // ── route parser ──

    @Test
    void parsesPacProxyResult() {
        ProxyResult result = new PacProxyRouteParser().parse("PROXY proxy.example.com:8080; DIRECT");

        assertTrue(result.isProxy());
        assertEquals("proxy.example.com", result.getHost());
        assertEquals(8080, result.getPort());
    }

    @Test
    void parsesDirectPacProxyResult() {
        ProxyResult result = new PacProxyRouteParser().parse("DIRECT");

        assertTrue(result.isDirect());
    }

    @Test
    void rejectsUnsupportedSocksResult() {
        ProxyResult result = new PacProxyRouteParser().parse("SOCKS proxy.example.com:1080");

        assertTrue(result.isError(), "SOCKS-only result must be ERROR, never a masked DIRECT");
        assertFalse(result.isDirect());
        assertEquals("unsupported-pac-entry", result.getReason());
    }

    @Test
    void explicitDirectFallbackAfterUnsupportedEntryWins() {
        // DIRECT may only come from an explicit PAC DIRECT, never from a parser fallback.
        ProxyResult result = new PacProxyRouteParser().parse("SOCKS proxy.example.com:1080; DIRECT");

        assertTrue(result.isDirect(), "explicit trailing DIRECT must win over an earlier unsupported entry");
        assertEquals("pac-direct", result.getReason());
    }

    @Test
    void parsesHostPortResult() {
        ProxyResult result = new PacProxyRouteParser().parse("proxy.example.com:8080");

        assertTrue(result.isProxy());
        assertEquals("proxy.example.com", result.getHost());
        assertEquals(8080, result.getPort());
    }

    @Test
    void rejectsInvalidProxyPorts() {
        ProxyResult zero = new PacProxyRouteParser().parse("proxy.example.com:0");
        ProxyResult tooLarge = new PacProxyRouteParser().parse("proxy.example.com:65536");
        ProxyResult notANumber = new PacProxyRouteParser().parse("proxy.example.com:not-a-port");

        assertTrue(zero.isError());
        assertFalse(zero.isDirect());
        assertEquals("invalid-proxy-port", zero.getReason());

        assertTrue(tooLarge.isError());
        assertFalse(tooLarge.isDirect());
        assertEquals("invalid-proxy-port", tooLarge.getReason());

        assertTrue(notANumber.isError());
        assertFalse(notANumber.isDirect());
        assertEquals("invalid-proxy-port", notANumber.getReason());
    }

    @Test
    void rejectsInvalidProxyPortInPrefixedEntry() {
        ProxyResult result = new PacProxyRouteParser().parse("PROXY proxy.example.com:not-a-port");

        assertTrue(result.isError());
        assertFalse(result.isDirect());
        assertEquals("invalid-proxy-port", result.getReason());
    }

    @Test
    void rejectsEmptyPacResult() {
        assertTrue(new PacProxyRouteParser().parse("").isError());
        assertTrue(new PacProxyRouteParser().parse(null).isError());
        assertEquals("empty-pac-result", new PacProxyRouteParser().parse("").getReason());
        assertFalse(new PacProxyRouteParser().parse("").isDirect());
    }

    @Test
    void fallsBackToHttpProxyForProtocolMap() {
        ProxyResult result = new ProxyServerParser().parse("http=proxy.example.com:8080", "https://example.com");

        assertTrue(result.isProxy());
        assertEquals("proxy.example.com", result.getHost());
        assertEquals(8080, result.getPort());
    }

    @Test
    void selectsSchemeSpecificProxyForProtocolMap() {
        ProxyResult result = new ProxyServerParser().parse("http=http.example.com:8080;https=https.example.com:8443", "https://example.com");

        assertTrue(result.isProxy());
        assertEquals("https.example.com", result.getHost());
        assertEquals(8443, result.getPort());
    }

    @Test
    void matchesBypassCaseInsensitive() {
        assertTrue(new ProxyBypassMatcher().isBypassed("https://SERVICE.LOCAL/path", "*.local"));
        assertTrue(new ProxyBypassMatcher().isBypassed("https://service.local/path", "*.LOCAL"));
    }

    @Test
    void treatsInvalidBypassUrlAsNotBypassed() {
        assertFalse(new ProxyBypassMatcher().isBypassed("not a url", "*.local"));
    }

    // ── modes ──

    @Test
    void disabledModeReturnsDirect() {
        ProxyConfiguration configuration = ProxyConfiguration.builder()
                .mode(ProxyMode.DISABLED)
                .build();

        ProxyResult result = new WindowsProxyResolver(configuration).resolve("https://plugins.gradle.org/m2/");

        assertTrue(result.isDirect());
    }

    @Test
    void resolvesManualProxy() {
        ProxyConfiguration configuration = ProxyConfiguration.builder()
                .mode(ProxyMode.MANUAL_PROXY)
                .manualProxyHost("proxy.example.com")
                .manualProxyPort(8080)
                .build();

        ProxyResult result = new WindowsProxyResolver(configuration).resolve("https://plugins.gradle.org/m2/");

        assertTrue(result.isProxy());
        assertEquals("proxy.example.com", result.getHost());
        assertEquals(8080, result.getPort());
    }

    @Test
    void rejectsInvalidManualProxyPort() {
        ProxyConfiguration configuration = ProxyConfiguration.builder()
                .mode(ProxyMode.MANUAL_PROXY)
                .manualProxyHost("proxy.example.com")
                .manualProxyPort(70000)
                .build();

        ProxyResult result = new WindowsProxyResolver(configuration).resolve("https://plugins.gradle.org/m2/");

        assertTrue(result.isError());
        assertFalse(result.isDirect());
        assertEquals("invalid-manual-proxy", result.getReason());
    }

    @Test
    void pacUrlManualWithoutConfiguredUrlReturnsError() {
        ProxyConfiguration configuration = ProxyConfiguration.builder()
                .mode(ProxyMode.PAC_URL_MANUAL)
                .build();

        ProxyResult result = new WindowsProxyResolver(configuration).resolve("https://plugins.gradle.org/m2/");

        assertTrue(result.isError());
        assertFalse(result.isDirect());
        assertEquals("pac-url-not-found", result.getReason());
    }

    @Test
    void reservedNativeModesReturnNotImplemented() {
        ProxyResult settings = new WindowsProxyResolver(ProxyConfiguration.builder()
                .mode(ProxyMode.WINDOWS_NATIVE_PROXY_SETTINGS).build())
                .resolve("https://plugins.gradle.org/m2/");
        ProxyResult route = new WindowsProxyResolver(ProxyConfiguration.builder()
                .mode(ProxyMode.WINDOWS_NATIVE_ROUTE_RESOLVER).build())
                .resolve("https://plugins.gradle.org/m2/");

        assertTrue(settings.isNotImplemented());
        assertFalse(settings.isDirect());
        assertTrue(route.isNotImplemented());
        assertFalse(route.isDirect());
    }

    // ── PAC evaluation ──

    @Test
    void evaluatesSimplePacScript() {
        ProxyResult result = PacEvaluator.createDefault().evaluate(
                "function FindProxyForURL(url, host) { return 'PROXY proxy.example.com:8080; DIRECT'; }",
                "https://plugins.gradle.org/m2/"
        );

        assertTrue(result.isProxy());
        assertEquals("proxy.example.com", result.getHost());
        assertEquals(8080, result.getPort());
    }

    @Test
    void evaluatesCaseInsensitivePacHelpers() {
        ProxyResult result = PacEvaluator.createDefault().evaluate(
                "function FindProxyForURL(url, host) { if (dnsDomainIs(host, '.LOCAL')) { return 'DIRECT'; } return 'PROXY proxy.example.com:8080'; }",
                "https://SERVICE.local/path"
        );

        assertTrue(result.isDirect());
    }

    @Test
    void respectsDirectPacResult() {
        ProxyResult result = PacEvaluator.createDefault().evaluate(
                "function FindProxyForURL(url, host) { return 'DIRECT'; }",
                "https://plugins.gradle.org/m2/"
        );

        assertTrue(result.isDirect());
    }

    @Test
    void exposesWindowsPacDefaultScript() {
        assertNotNull(ProxyDefaults.DEFAULT_WINDOWS_PAC_SCRIPT);
        assertTrue(ProxyDefaults.DEFAULT_WINDOWS_PAC_SCRIPT.contains("GetSystemWebProxy"));
        assertTrue(ProxyDefaults.DEFAULT_WINDOWS_PAC_SCRIPT.contains("ProxyServer"));
    }
}
