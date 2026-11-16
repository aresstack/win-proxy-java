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
        PacUrlProxyResolver pipeline = new PacUrlProxyResolver(discovery, loader, evaluator, cache, false);

        for (int i = 0; i < 10; i++) {
            assertTrue(pipeline.resolve("https://confluence.example.com/").isProxy());
        }

        assertEquals(1, discoveries.get());
        assertEquals(1, downloads.get());
    }

    @Test
    void failedRediscoveryKeepsTheLastGoodScript() {
        PacCache cache = new PacCache();
        AtomicBoolean blocked = new AtomicBoolean();
        PacUrlResolver discovery = () -> {
            if (blocked.get()) {
                throw new ProxyResolutionException("powershell blocked");
            }
            return PacUrlResolution.found(PAC_URL, "test");
        };
        PacUrlProxyResolver pipeline = new PacUrlProxyResolver(discovery, loader, evaluator, cache, false);
        pipeline.resolve("https://confluence.example.com/");

        blocked.set(true);
        cache.invalidate();

        assertTrue(pipeline.resolve("https://confluence.example.com/").isProxy());
        assertEquals(1, downloads.get());
    }

    @Test
    void unreachableNewPacUrlKeepsTheLastGoodPair() {
        PacCache cache = new PacCache();
        AtomicBoolean moved = new AtomicBoolean();
        PacUrlResolver discovery = () -> PacUrlResolution.found(
                moved.get() ? "http://other.example.com/proxy.pac" : PAC_URL, "test");
        PacScriptLoader byUrl = pacUrl -> {
            downloads.incrementAndGet();
            if (!PAC_URL.equals(pacUrl)) {
                throw new ProxyResolutionException("Could not load PAC script from " + pacUrl + ".");
            }
            return SCRIPT;
        };
        PacUrlProxyResolver pipeline = new PacUrlProxyResolver(discovery, byUrl, evaluator, cache, false);
        pipeline.resolve("https://confluence.example.com/");

        moved.set(true);
        cache.invalidate();
        ProxyResult afterMove = pipeline.resolve("https://confluence.example.com/");
        ProxyResult later = pipeline.resolve("https://confluence.example.com/");

        assertTrue(afterMove.isProxy(), afterMove.toString());
        assertTrue(later.isProxy(), later.toString());
        assertEquals(2, downloads.get(), "one failed attempt for the new URL, then the last good pair");
    }

    @Test
    void brokenDownloadNeverReplacesAWorkingScript() {
        PacCache cache = new PacCache();
        AtomicBoolean broken = new AtomicBoolean();
        PacScriptLoader content = pacUrl -> {
            downloads.incrementAndGet();
            return broken.get() ? "" : SCRIPT;
        };
        PacEvaluator strict = (script, url) -> {
            if (script.trim().isEmpty()) {
                throw new ProxyResolutionException("FindProxyForURL is not defined");
            }
            return ProxyResult.of("proxy.example.com", 3128);
        };
        PacUrlResolver discovery = () -> PacUrlResolution.found(PAC_URL, "test");
        PacUrlProxyResolver resolving = new PacUrlProxyResolver(discovery, content, strict, cache, false);
        PacUrlProxyResolver diagnosing = new PacUrlProxyResolver(discovery, content, strict, cache, true);
        resolving.resolve("https://confluence.example.com/");

        broken.set(true);
        assertEquals("pac-evaluation-failed",
                diagnosing.diagnose("https://confluence.example.com/").getResult().getReason());
        assertTrue(resolving.resolve("https://confluence.example.com/").isProxy());

        cache.invalidate();
        assertTrue(resolving.resolve("https://confluence.example.com/").isProxy());
        assertTrue(resolving.resolve("https://confluence.example.com/").isProxy());
        assertEquals(3, downloads.get());
    }

    @Test
    void aDiagnosisStartedBeforeAnInvalidationDoesNotUndoIt() {
        PacCache cache = new PacCache();
        long before = cache.generation();
        cache.invalidate();

        cache.store(before, PacUrlResolution.found(PAC_URL, "test"), SCRIPT);

        assertEquals(null, cache.current());
        assertEquals(null, cache.lastGood());
    }
}
