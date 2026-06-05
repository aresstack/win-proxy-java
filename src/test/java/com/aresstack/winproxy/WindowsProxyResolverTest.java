package com.aresstack.winproxy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsProxyResolverTest {

    @Test
    void defaultsToPacUrlMode() {
        ProxyConfiguration configuration = ProxyConfiguration.defaults();

        assertEquals(ProxyMode.PAC_URL, configuration.getMode());
        assertEquals(ProxyDefaults.DEFAULT_TEST_URL, configuration.getTestUrl());
    }

    @Test
    void keepsDefaultPowerShellAutoConfigUrlQuery() {
        assertEquals(
                "(Get-ItemProperty -Path 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings').AutoConfigURL",
                ProxyDefaults.DEFAULT_PAC_URL_DISCOVERY_SCRIPT
        );
    }

    @Test
    void parsesPacProxyResult() {
        ProxyResult result = new ProxyResultParser().parse("PROXY proxy.example.com:8080; DIRECT");

        assertFalse(result.isDirect());
        assertEquals("proxy.example.com", result.getHost());
        assertEquals(8080, result.getPort());
    }

    @Test
    void parsesDirectPacProxyResult() {
        ProxyResult result = new ProxyResultParser().parse("DIRECT");

        assertTrue(result.isDirect());
    }

    @Test
    void ignoresUnsupportedSocksResult() {
        ProxyResult result = new ProxyResultParser().parse("SOCKS proxy.example.com:1080");

        assertTrue(result.isDirect());
    }

    @Test
    void parsesHostPortResult() {
        ProxyResult result = new ProxyResultParser().parse("proxy.example.com:8080");

        assertFalse(result.isDirect());
        assertEquals("proxy.example.com", result.getHost());
        assertEquals(8080, result.getPort());
    }

    @Test
    void rejectsInvalidProxyPorts() {
        assertTrue(new ProxyResultParser().parse("proxy.example.com:0").isDirect());
        assertTrue(new ProxyResultParser().parse("proxy.example.com:65536").isDirect());
        assertTrue(new ProxyResultParser().parse("proxy.example.com:not-a-port").isDirect());
    }

    @Test
    void fallsBackToHttpProxyForProtocolMap() {
        ProxyResult result = new ProxyServerParser().parse("http=proxy.example.com:8080", "https://example.com");

        assertFalse(result.isDirect());
        assertEquals("proxy.example.com", result.getHost());
        assertEquals(8080, result.getPort());
    }

    @Test
    void selectsSchemeSpecificProxyForProtocolMap() {
        ProxyResult result = new ProxyServerParser().parse("http=http.example.com:8080;https=https.example.com:8443", "https://example.com");

        assertFalse(result.isDirect());
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

    @Test
    void resolvesManualProxy() {
        ProxyConfiguration configuration = ProxyConfiguration.builder()
                .mode(ProxyMode.MANUAL)
                .manualProxyHost("proxy.example.com")
                .manualProxyPort(8080)
                .build();

        ProxyResult result = new WindowsProxyResolver(configuration).resolve("https://plugins.gradle.org/m2/");

        assertEquals("proxy.example.com", result.getHost());
        assertEquals(8080, result.getPort());
    }

    @Test
    void rejectsInvalidManualProxyPort() {
        ProxyConfiguration configuration = ProxyConfiguration.builder()
                .mode(ProxyMode.MANUAL)
                .manualProxyHost("proxy.example.com")
                .manualProxyPort(70000)
                .build();

        ProxyResult result = new WindowsProxyResolver(configuration).resolve("https://plugins.gradle.org/m2/");

        assertTrue(result.isDirect());
    }

    @Test
    void discoversConfiguredPacUrl() {
        ProxyConfiguration configuration = ProxyConfiguration.builder()
                .pacUrl("http://proxy.example.com/wpad.dat")
                .build();

        PacUrlResolution resolution = new WindowsProxyResolver(configuration).discoverPacUrl();

        assertTrue(resolution.isPresent());
        assertEquals("http://proxy.example.com/wpad.dat", resolution.getPacUrl());
    }

    @Test
    void returnsNullWhenPowerShellPacUrlDiscoveryFails() {
        String pacUrl = new WindowsProxyResolver().discoverPacUrlWithPowerShell("exit 1");

        assertEquals(null, pacUrl);
    }

    @Test
    void evaluatesSimplePacScript() {
        ProxyResult result = PacEvaluator.createDefault().evaluate(
                "function FindProxyForURL(url, host) { return 'PROXY proxy.example.com:8080; DIRECT'; }",
                "https://plugins.gradle.org/m2/"
        );

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
