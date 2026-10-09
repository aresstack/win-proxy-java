package com.aresstack.winproxy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProxyConfiguration#validate()} reports incomplete settings and not-implemented modes
 * before any process is spawned, and the discovery script default follows the mode.
 */
class ProxyConfigurationValidationTest {

    @Test
    void defaultConfigurationIsValid() {
        ProxyConfiguration.defaults().validate();
        assertTrue(ProxyConfiguration.defaults().isValid());
    }

    @Test
    void manualProxyRequiresHostAndPort() {
        ProxyConfiguration configuration = ProxyConfiguration.builder()
                .mode(ProxyMode.MANUAL_PROXY)
                .build();

        List<String> problems = configuration.validationProblems();

        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("manualProxyHost"));
        assertTrue(problems.get(1).contains("manualProxyPort"));
        ProxyConfigurationException ex = assertThrows(ProxyConfigurationException.class, configuration::validate);
        assertTrue(ex.getMessage().contains("manualProxyHost"));
        assertTrue(ex.getMessage().contains("manualProxyPort"));
    }

    @Test
    void manualProxyWithHostAndPortIsValid() {
        ProxyConfiguration configuration = ProxyConfiguration.builder()
                .mode(ProxyMode.MANUAL_PROXY)
                .manualProxyHost("proxy.example.com")
                .manualProxyPort(3128)
                .build();

        configuration.validate();
        assertTrue(configuration.validationProblems().isEmpty());
    }

    @Test
    void manualProxyRejectsPortOutOfRange() {
        ProxyConfiguration configuration = ProxyConfiguration.builder()
                .mode(ProxyMode.MANUAL_PROXY)
                .manualProxyHost("proxy.example.com")
                .manualProxyPort(70000)
                .build();

        assertFalse(configuration.isValid());
        assertTrue(configuration.validationProblems().get(0).contains("70000"));
    }

    @Test
    void pacUrlManualRequiresValidUrl() {
        assertTrue(ProxyConfiguration.builder().mode(ProxyMode.PAC_URL_MANUAL).build()
                .validationProblems().get(0).contains("pacUrl"));
        assertTrue(ProxyConfiguration.builder().mode(ProxyMode.PAC_URL_MANUAL).pacUrl("not a url").build()
                .validationProblems().get(0).contains("not a valid URL"));

        ProxyConfiguration.builder().mode(ProxyMode.PAC_URL_MANUAL).pacUrl("http://proxy.example.com/wpad.dat").build()
                .validate();
    }

    @Test
    void reservedNativeModesAreRejectedUpFront() {
        ProxyConfiguration settings = ProxyConfiguration.builder().mode(ProxyMode.WINDOWS_NATIVE_PROXY_SETTINGS).build();
        ProxyConfiguration route = ProxyConfiguration.builder().mode(ProxyMode.WINDOWS_NATIVE_ROUTE_RESOLVER).build();

        assertFalse(settings.isValid());
        assertFalse(route.isValid());
        assertTrue(settings.validationProblems().get(0).contains("not implemented"));
        assertTrue(route.validationProblems().get(0).contains("not implemented"));
        assertThrows(ProxyConfigurationException.class, settings::validate);
        assertThrows(ProxyConfigurationException.class, route::validate);
    }

    @Test
    void pacModesWithDefaultScriptsAreValid() {
        ProxyConfiguration.builder().mode(ProxyMode.PAC_URL_POWERSHELL).build().validate();
        ProxyConfiguration.builder().mode(ProxyMode.PAC_URL_WSCRIPT).build().validate();
        ProxyConfiguration.builder().mode(ProxyMode.PAC_URL_WINDOWS_SETTINGS).build().validate();
        ProxyConfiguration.builder().mode(ProxyMode.WINDOWS_STATIC_PROXY).build().validate();
        ProxyConfiguration.builder().mode(ProxyMode.DISABLED).build().validate();
    }

    @Test
    void discoveryScriptDefaultFollowsTheMode() {
        assertEquals(ProxyDefaults.DEFAULT_PAC_URL_DISCOVERY_WSCRIPT,
                ProxyConfiguration.builder().mode(ProxyMode.PAC_URL_WSCRIPT).build().getPacUrlDiscoveryScript());
        assertEquals(ProxyDefaults.DEFAULT_PAC_URL_DISCOVERY_WSCRIPT,
                ProxyConfiguration.builder().mode(ProxyMode.PAC_URL_WSCRIPT).pacUrlDiscoveryScript("  ").build()
                        .getPacUrlDiscoveryScript());
        assertEquals(ProxyDefaults.DEFAULT_PAC_URL_DISCOVERY_SCRIPT,
                ProxyConfiguration.builder().mode(ProxyMode.PAC_URL_POWERSHELL).build().getPacUrlDiscoveryScript());
        assertEquals(ProxyDefaults.DEFAULT_PAC_URL_DISCOVERY_SCRIPT,
                ProxyConfiguration.builder().mode(ProxyMode.PAC_URL_WINDOWS_SETTINGS).build().getPacUrlDiscoveryScript());
        assertEquals("WScript.Echo \"http://x/y.dat\"",
                ProxyConfiguration.builder().mode(ProxyMode.PAC_URL_WSCRIPT)
                        .pacUrlDiscoveryScript("WScript.Echo \"http://x/y.dat\"").build().getPacUrlDiscoveryScript());
    }
}
