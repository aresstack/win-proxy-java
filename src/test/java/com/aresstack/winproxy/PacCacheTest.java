package com.aresstack.winproxy;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The PAC URL discovery and the PAC download run once per {@link WindowsProxyResolver}, not on
 * every {@code resolve()}; after {@link WindowsProxyResolver#invalidatePacCache()} a failed reload
 * keeps the last good script.
 */
class PacCacheTest {

    private static final String PAC_URL = "http://wpad.example.com/wpad.dat";
    private static final String SCRIPT =
            "function FindProxyForURL(url, host) { return 'PROXY proxy.example.com:3128'; }";

    private final AtomicInteger downloads = new AtomicInteger();
    private final AtomicBoolean wpadDown = new AtomicBoolean();
    private final PacScriptLoader loader = pacUrl -> {
        downloads.incrementAndGet();
        if (wpadDown.get()) {
            throw new ProxyResolutionException("Could not load PAC script from " + pacUrl + ".",
                    new java.net.SocketTimeoutException("Connect timed out"));
        }
        return SCRIPT;
    };
    private final AtomicInteger evaluations = new AtomicInteger();
    private final PacEvaluator evaluator = (script, url) -> {
        evaluations.incrementAndGet();
        return ProxyResult.of("proxy.example.com", 3128);
    };

    private WindowsProxyResolver resolver() {
        return new WindowsProxyResolver(
                ProxyConfiguration.builder().mode(ProxyMode.PAC_URL_MANUAL).pacUrl(PAC_URL).build(),
                loader, evaluator,
                new StaticProxySettingsResolver(), new ManualProxyResolver(), new DirectProxyResolver());
    }

    @Test
    void downloadsThePacScriptOnlyOnceForManyResolutions() {
        WindowsProxyResolver resolver = resolver();

        for (int i = 0; i < 50; i++) {
            assertTrue(resolver.resolve("https://confluence.example.com/rest/api/space?start=" + i).isProxy());
        }

        assertEquals(1, downloads.get());
        assertEquals(50, evaluations.get());
    }

    @Test
    void unreachableWpadAfterInvalidationKeepsTheLastGoodScript() {
        WindowsProxyResolver resolver = resolver();
        assertTrue(resolver.resolve("https://confluence.example.com/").isProxy());

        wpadDown.set(true);
        resolver.invalidatePacCache();
        ProxyResult afterFailedReload = resolver.resolve("https://confluence.example.com/");
        ProxyResult later = resolver.resolve("https://confluence.example.com/");

        assertTrue(afterFailedReload.isProxy(), afterFailedReload.toString());
        assertTrue(later.isProxy(), later.toString());
        assertEquals(2, downloads.get(), "one reload attempt, then the last good script without retrying");
    }

    @Test
    void invalidationLoadsTheScriptAgain() {
        WindowsProxyResolver resolver = resolver();
        resolver.resolve("https://confluence.example.com/");

        resolver.invalidatePacCache();
        resolver.resolve("https://confluence.example.com/");
        resolver.resolve("https://confluence.example.com/");

        assertEquals(2, downloads.get());
    }

    @Test
    void withoutAGoodScriptTheDownloadFailureIsReportedAndRetriedNextTime() {
        wpadDown.set(true);
        WindowsProxyResolver resolver = resolver();

        ProxyResult first = resolver.resolve("https://confluence.example.com/");
        assertEquals("pac-download-failed", first.getReason());

        wpadDown.set(false);
        assertTrue(resolver.resolve("https://confluence.example.com/").isProxy());
        assertEquals(2, downloads.get());
    }

    @Test
    void diagnoseDownloadsAfreshAndReportsAnUnreachableWpad() {
        WindowsProxyResolver resolver = resolver();
        resolver.resolve("https://confluence.example.com/");

        wpadDown.set(true);
        ProxyDiagnostics diagnostics = resolver.diagnose("https://confluence.example.com/");

        assertEquals("pac-download-failed", diagnostics.getResult().getReason());
        assertTrue(resolver.resolve("https://confluence.example.com/").isProxy(),
                "a failed diagnosis does not drop the remembered script");
        assertEquals(2, downloads.get());
    }

    @Test
    void discoveryRunsOnlyOnce() {
        AtomicInteger discoveries = new AtomicInteger();
        PacCache cache = new PacCache();
        PacUrlResolver discovery = () -> {
            discoveries.incrementAndGet();
            return PacUrlResolution.found(PAC_URL, "test");
        };

        for (int i = 0; i < 10; i++) {
            assertEquals(PAC_URL, cache.resolve(discovery).getPacUrl());
        }

        assertEquals(1, discoveries.get());
    }

    @Test
    void failedRediscoveryKeepsTheLastGoodPacUrl() {
        PacCache cache = new PacCache();
        cache.resolve(() -> PacUrlResolution.found(PAC_URL, "test"));

        cache.invalidate();
        PacUrlResolution kept = cache.resolve(() -> {
            throw new ProxyResolutionException("powershell blocked");
        });

        assertEquals(PAC_URL, kept.getPacUrl());
    }
}
