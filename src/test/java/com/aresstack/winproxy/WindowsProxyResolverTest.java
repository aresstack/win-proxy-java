package com.aresstack.winproxy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WindowsProxyResolverTest {

    @Test void resolveDoesNotCrash() {
        ProxyResult res = WindowsProxyResolver.resolve("https://example.com");
        assertNotNull(res);
        assertNotNull(res.getReason());
    }

    @Test void resolveStaticDoesNotCrash() {
        ProxyResult res = WindowsProxyResolver.resolveStatic("https://example.com");
        assertNotNull(res);
    }

    @Test void readRegistryValueReturnsNullForMissingKey() {
        assertNull(WindowsProxyResolver.readRegistryValue("HKCU\\Software\\WinProxyTest_NonExistent", "Bogus"));
    }

    @Test void readRegistryValueFromAllHivesDoesNotCrash() {
        WindowsProxyResolver.readRegistryValueFromAllHives("AutoConfigURL");
    }

    @Test void readRegistryValueFromAllHivesReturnsNullForBogus() {
        assertNull(WindowsProxyResolver.readRegistryValueFromAllHives("WinProxyTest_Bogus_99999"));
    }

    @Test void settingsKeysContainPolicyAndUserHives() {
        assertEquals(4, RegistryReader.SETTINGS_KEYS.length);
        assertTrue(RegistryReader.SETTINGS_KEYS[0].contains("Policies"));
        assertTrue(RegistryReader.SETTINGS_KEYS[0].startsWith("HKCU"));
        assertTrue(RegistryReader.SETTINGS_KEYS[1].contains("Policies"));
        assertTrue(RegistryReader.SETTINGS_KEYS[1].startsWith("HKLM"));
        assertFalse(RegistryReader.SETTINGS_KEYS[2].contains("Policies"));
        assertFalse(RegistryReader.SETTINGS_KEYS[3].contains("Policies"));
    }

    @Test void connectionFlagsConstants() {
        assertEquals(0x08, RegistryReader.FLAG_AUTO_DETECT);
        assertEquals(0x04, RegistryReader.FLAG_AUTO_CONFIG);
        assertEquals(0x02, RegistryReader.FLAG_PROXY);
    }

    // ── isBypassed ──

    @Test void isBypassedExact() {
        assertTrue(WindowsProxyResolver.isBypassed("localhost", "localhost"));
        assertFalse(WindowsProxyResolver.isBypassed("example.com", "localhost"));
    }

    @Test void isBypassedWildcardPrefix() {
        assertTrue(WindowsProxyResolver.isBypassed("intranet.corp.local", "*.corp.local"));
    }

    @Test void isBypassedWildcardSuffix() {
        assertTrue(WindowsProxyResolver.isBypassed("10.130.165.20", "10.*"));
    }

    @Test void isBypassedLocal() {
        assertTrue(WindowsProxyResolver.isBypassed("intranet", "<local>"));
        assertFalse(WindowsProxyResolver.isBypassed("www.example.com", "<local>"));
    }

    @Test void isBypassedMultiple() {
        String p = "localhost;*.local;10.*;192.168.*;<local>";
        assertTrue(WindowsProxyResolver.isBypassed("localhost", p));
        assertTrue(WindowsProxyResolver.isBypassed("myhost.local", p));
        assertTrue(WindowsProxyResolver.isBypassed("10.0.0.1", p));
        assertTrue(WindowsProxyResolver.isBypassed("intranet", p));
        assertFalse(WindowsProxyResolver.isBypassed("www.google.com", p));
    }

    @Test void isBypassedNullSafe() {
        assertFalse(WindowsProxyResolver.isBypassed(null, "localhost"));
        assertFalse(WindowsProxyResolver.isBypassed("localhost", null));
    }

    // ── parsePacResult ──

    @Test void parsePacResultDirect() { assertTrue(WindowsProxyResolver.parsePacResult("DIRECT").isDirect()); }

    @Test void parsePacResultProxy() {
        ProxyResult res = WindowsProxyResolver.parsePacResult("PROXY 10.0.0.1:3128");
        assertFalse(res.isDirect());
        assertEquals("10.0.0.1", res.getHost());
        assertEquals(3128, res.getPort());
    }

    @Test void parsePacResultMultiple() {
        ProxyResult res = WindowsProxyResolver.parsePacResult("PROXY 10.0.0.1:3128; DIRECT");
        assertFalse(res.isDirect());
    }

    @Test void parsePacResultEmpty() {
        assertTrue(WindowsProxyResolver.parsePacResult("").isDirect());
        assertTrue(WindowsProxyResolver.parsePacResult(null).isDirect());
    }

    // ── extractProxyForProtocol ──

    @Test void extractsHttps() {
        assertEquals("10.0.0.2:3129", WindowsProxyResolver.extractProxyForProtocol(
                "http=10.0.0.1:3128;https=10.0.0.2:3129", "https://example.com"));
    }

    @Test void fallsBackToHttp() {
        assertEquals("10.0.0.1:3128", WindowsProxyResolver.extractProxyForProtocol(
                "http=10.0.0.1:3128", "https://example.com"));
    }

    @Test void returnsNullForNoMatch() {
        assertNull(WindowsProxyResolver.extractProxyForProtocol("ftp=10.0.0.3:21", "https://example.com"));
    }

    // ── PAC script evaluation ──

    @Test void evaluatePacScriptProxy() {
        ProxyResult res = WindowsProxyResolver.evaluatePacScript(
                "function FindProxyForURL(url, host) { return 'PROXY 10.0.0.1:3128'; }", "https://example.com");
        assertFalse(res.isDirect());
        assertEquals("10.0.0.1", res.getHost());
    }

    @Test void evaluatePacScriptDirect() {
        assertTrue(WindowsProxyResolver.evaluatePacScript(
                "function FindProxyForURL(url, host) { return 'DIRECT'; }", "https://example.com").isDirect());
    }

    @Test void evaluatePacScriptWithHelpers() {
        String script = "function FindProxyForURL(url, host) {\n" +
                "  if (isPlainHostName(host)) return 'DIRECT';\n" +
                "  if (dnsDomainIs(host, '.corp.local')) return 'DIRECT';\n" +
                "  return 'PROXY proxy.corp.local:8080';\n" +
                "}";
        assertTrue(WindowsProxyResolver.evaluatePacScript(script, "http://intranet").isDirect());
        assertTrue(WindowsProxyResolver.evaluatePacScript(script, "http://app.corp.local/x").isDirect());
        ProxyResult ext = WindowsProxyResolver.evaluatePacScript(script, "https://www.google.com");
        assertFalse(ext.isDirect());
        assertEquals("proxy.corp.local", ext.getHost());
        assertEquals(8080, ext.getPort());
    }

    @Test void evaluatePacScriptNullSafe() {
        assertTrue(WindowsProxyResolver.evaluatePacScript(null, "https://example.com").isDirect());
    }

    // ── WPAD ──

    @Test void isWpadAutoDetectDoesNotCrash() { WindowsProxyResolver.isWpadAutoDetectEnabled(); }

    @Test void readConnectionFlagsRange() {
        int f = WindowsProxyResolver.readConnectionFlags();
        assertTrue(f >= -1 && f <= 255);
    }

    // ── ProxyResult ──

    @Test void proxyResultToString() {
        assertEquals("DIRECT (x)", ProxyResult.direct("x").toString());
        assertEquals("PROXY h:80 (y)", ProxyResult.proxy("h", 80, "y").toString());
    }

    @Test void proxyResultToJavaProxy() {
        assertEquals(java.net.Proxy.NO_PROXY, ProxyResult.direct("x").toJavaProxy());
        assertEquals(java.net.Proxy.Type.HTTP, ProxyResult.proxy("h", 80, "x").toJavaProxy().type());
    }

    // ── PacUrlSource facade ──

    @Test void defaultPacDiscoveryScript() {
        assertNotNull(WindowsProxyResolver.DEFAULT_PAC_DISCOVERY_SCRIPT);
        assertTrue(WindowsProxyResolver.DEFAULT_PAC_DISCOVERY_SCRIPT.contains("AutoConfigURL"));
    }

    @Test void resolveDirectEmpty() {
        assertTrue(WindowsProxyResolver.resolve("https://x.com", PacUrlSource.DIRECT, "").isDirect());
        assertTrue(WindowsProxyResolver.resolve("https://x.com", PacUrlSource.DIRECT, null).isDirect());
    }

    @Test void resolveRegistrySource() {
        assertNotNull(WindowsProxyResolver.resolve("https://x.com", PacUrlSource.REGISTRY, null));
    }

    @Test void resolveNullSourceFallback() {
        assertNotNull(WindowsProxyResolver.resolve("https://x.com", null, null));
    }

    @Test void pacUrlSourceValues() {
        assertEquals(3, PacUrlSource.values().length);
    }
}

