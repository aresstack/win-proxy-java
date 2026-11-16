package com.aresstack.winproxy;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        cache.store(before, PacUrlResolution.found(PAC_URL, "test"), PacScript.of(SCRIPT));

        assertEquals(null, cache.lastGood());
        assertTrue(cache.needsReload());
    }

    // ── lifetime, revalidation, retry delay, status ──

    private final AtomicLong now = new AtomicLong(1_000_000L);

    private PacUrlProxyResolver timed(PacCache cache, PacScriptLoader scripts) {
        return new PacUrlProxyResolver(() -> PacUrlResolution.found(PAC_URL, "test"), scripts, evaluator, cache, false);
    }

    @Test
    void withoutCacheHeadersTheScriptIsReloadedAfterTwelveHours() {
        PacCache cache = new PacCache(now::get);
        PacUrlProxyResolver pipeline = timed(cache, loader);
        pipeline.resolve("https://confluence.example.com/");

        now.addAndGet(PacCache.DEFAULT_MAX_AGE_MILLIS - 1);
        pipeline.resolve("https://confluence.example.com/");
        assertEquals(1, downloads.get());

        now.addAndGet(1);
        pipeline.resolve("https://confluence.example.com/");
        assertEquals(2, downloads.get());
    }

    @Test
    void announcedLifetimeIsHonouredWithinBounds() {
        assertEquals(PacCache.MIN_MAX_AGE_MILLIS, PacCache.lifetime(0L), "no-cache must not mean: every request");
        assertEquals(30L * 60 * 1000, PacCache.lifetime(30L * 60 * 1000));
        assertEquals(PacCache.MAX_MAX_AGE_MILLIS, PacCache.lifetime(7L * 24 * 60 * 60 * 1000));
        assertEquals(PacCache.DEFAULT_MAX_AGE_MILLIS, PacCache.lifetime(PacScript.NO_MAX_AGE));
    }

    @Test
    void cacheHeadersAreParsed() {
        assertEquals(3_600_000L, UrlConnectionPacScriptLoader.maxAgeMillis("public, max-age=3600", -1L, -1L, 0L));
        assertEquals(0L, UrlConnectionPacScriptLoader.maxAgeMillis("no-cache", -1L, -1L, 0L));
        assertEquals(0L, UrlConnectionPacScriptLoader.maxAgeMillis("no-store, max-age=60", -1L, -1L, 0L));
        assertEquals(600_000L, UrlConnectionPacScriptLoader.maxAgeMillis(null, 1_600_000L, 1_000_000L, 0L));
        assertEquals(PacScript.NO_MAX_AGE, UrlConnectionPacScriptLoader.maxAgeMillis(null, -1L, -1L, 0L));
    }

    @Test
    void reloadRevalidatesWithTheEtagAndKeepsTheScriptOn304() {
        PacCache cache = new PacCache(now::get);
        java.util.List<String> sentEtags = new java.util.ArrayList<String>();
        ConditionalPacScriptLoader conditional = new ConditionalPacScriptLoader() {
            @Override
            public PacScript load(String pacUrl, PacScript previous) {
                downloads.incrementAndGet();
                sentEtags.add(previous == null ? null : previous.getEtag());
                return previous == null
                        ? new PacScript(SCRIPT, 60L * 60 * 1000, "\"v1\"", null, false)
                        : new PacScript(previous.getContent(), 60L * 60 * 1000, previous.getEtag(), null, true);
            }

            @Override
            public String load(String pacUrl) {
                return load(pacUrl, null).getContent();
            }
        };
        PacUrlProxyResolver pipeline = timed(cache, conditional);
        pipeline.resolve("https://confluence.example.com/");

        now.addAndGet(60L * 60 * 1000);
        ProxyDiagnostics revalidated = pipeline.diagnose(null, "https://confluence.example.com/");

        assertTrue(revalidated.getResult().isProxy());
        assertTrue(revalidated.getSteps().toString().contains("HTTP 304"), revalidated.getSteps().toString());
        assertEquals(java.util.Arrays.asList(null, "\"v1\""), sentEtags);
        assertEquals(now.get() + 60L * 60 * 1000, cache.status().getValidUntil());
    }

    @Test
    void failedRefreshKeepsTheScriptRetriesWithGrowingDelayAndShowsItInTheStatus() {
        PacCache cache = new PacCache(now::get);
        PacUrlProxyResolver pipeline = timed(cache, loader);
        pipeline.resolve("https://confluence.example.com/");
        long loadedAt = now.get();

        wpadDown.set(true);
        now.addAndGet(PacCache.DEFAULT_MAX_AGE_MILLIS);
        long failedAt = now.get();
        assertTrue(pipeline.resolve("https://confluence.example.com/").isProxy());
        assertEquals(2, downloads.get());

        now.addAndGet(PacCache.FIRST_RETRY_MILLIS - 1);
        assertTrue(pipeline.resolve("https://confluence.example.com/").isProxy());
        assertEquals(2, downloads.get(), "no retry before the delay");
        now.addAndGet(1);
        assertTrue(pipeline.resolve("https://confluence.example.com/").isProxy());
        assertEquals(3, downloads.get());
        now.addAndGet(PacCache.FIRST_RETRY_MILLIS);
        pipeline.resolve("https://confluence.example.com/");
        assertEquals(3, downloads.get(), "second delay is twice as long");

        PacCacheStatus status = cache.status();
        assertTrue(status.isStale());
        assertEquals(loadedAt, status.getLoadedAt());
        assertEquals(failedAt, status.getRefreshFailingSince());
        assertEquals(2, status.getConsecutiveFailures());
        assertTrue(status.describe().contains("Connect timed out"), status.describe());

        wpadDown.set(false);
        now.addAndGet(PacCache.FIRST_RETRY_MILLIS);
        pipeline.resolve("https://confluence.example.com/");
        assertEquals(4, downloads.get());
        assertFalse(cache.status().isStale());
    }

    @Test
    void retryDelayDoublesUpToOneHour() {
        assertEquals(60_000L, PacCache.retryDelay(1));
        assertEquals(120_000L, PacCache.retryDelay(2));
        assertEquals(240_000L, PacCache.retryDelay(3));
        assertEquals(PacCache.MAX_RETRY_MILLIS, PacCache.retryDelay(20));
    }
}
