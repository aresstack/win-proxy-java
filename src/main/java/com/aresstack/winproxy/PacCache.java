package com.aresstack.winproxy;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Remembers the last good PAC URL and PAC script of one {@link WindowsProxyResolver}, so that
 * the PAC URL discovery (PowerShell, WScript, registry) and the PAC download do not run on
 * every {@code resolve()}.
 * <p>
 * Rules (applied by {@link PacUrlProxyResolver}; nothing runs on a timer, a due reload happens
 * during the next resolution):
 * <ul>
 *   <li>A pair of PAC URL and script is only remembered after the script was evaluated
 *       successfully; an empty or broken download never replaces a working script.</li>
 *   <li>The pair is valid for the lifetime the PAC server announces ({@code Cache-Control:
 *       max-age}, {@code Expires}), clamped to {@value #MIN_MAX_AGE_MILLIS} ms ...
 *       {@value #MAX_MAX_AGE_MILLIS} ms, and for {@value #DEFAULT_MAX_AGE_MILLIS} ms (12 h, like
 *       Chromium) when it announces none. Then the next resolution reloads it, revalidating with
 *       {@code If-None-Match}/{@code If-Modified-Since} where the server supports it.</li>
 *   <li>{@link #invalidate()} (e.g. network change) makes the next resolution reload at once.</li>
 *   <li>If a reload fails at any step (discovery, download, evaluation, also for a newly
 *       discovered PAC URL), the last good pair stays in use and the next attempt waits
 *       {@value #FIRST_RETRY_MILLIS} ms, doubling up to {@value #MAX_RETRY_MILLIS} ms.
 *       {@link #status()} reports since when refreshing fails.</li>
 *   <li>While one resolution reloads, others keep using the last good pair instead of waiting.</li>
 *   <li>A discovery that finds no PAC URL at all is taken as a configuration change and
 *       forgets the pair.</li>
 *   <li>Every write carries the {@link #generation()} read before the load started; a write
 *       from a load that began before the latest {@link #invalidate()} is ignored.</li>
 * </ul>
 * Instances are thread-safe.
 */
final class PacCache {

    static final long DEFAULT_MAX_AGE_MILLIS = 12L * 60 * 60 * 1000;
    static final long MIN_MAX_AGE_MILLIS = 5L * 60 * 1000;
    static final long MAX_MAX_AGE_MILLIS = 24L * 60 * 60 * 1000;
    static final long FIRST_RETRY_MILLIS = 60L * 1000;
    static final long MAX_RETRY_MILLIS = 60L * 60 * 1000;

    /** A PAC URL with the script downloaded from it. */
    static final class Entry {
        final PacUrlResolution resolution;
        final PacScript script;

        Entry(PacUrlResolution resolution, PacScript script) {
            this.resolution = resolution;
            this.script = script;
        }
    }

    private final Object lock = new Object();
    private final ReentrantLock loadLock = new ReentrantLock();
    private final LongSupplier clock;

    private Entry entry;
    private boolean outdated;
    private long generation;
    private long loadedAt;
    private long validUntil;
    private long failingSince;
    private int failures;
    private String lastFailure;
    private long nextAttemptAt;

    PacCache() {
        this(System::currentTimeMillis);
    }

    PacCache(LongSupplier clock) {
        this.clock = clock;
    }

    long now() {
        return clock.getAsLong();
    }

    /** The last good pair, due for reload or not, or {@code null}. */
    Entry lastGood() {
        synchronized (lock) {
            return entry;
        }
    }

    /** {@code true} if there is no pair yet, or it was invalidated, or its lifetime and the retry wait are over. */
    boolean needsReload() {
        long now = now();
        synchronized (lock) {
            return entry == null || outdated || (now >= validUntil && now >= nextAttemptAt);
        }
    }

    /** Counter of invalidations; read before a load and passed to the writes. */
    long generation() {
        synchronized (lock) {
            return generation;
        }
    }

    /** Remembers a successfully evaluated pair, unless the cache was invalidated since {@code loadGeneration}. */
    void store(long loadGeneration, PacUrlResolution resolution, PacScript script) {
        long now = now();
        synchronized (lock) {
            if (loadGeneration != generation) {
                return;
            }
            entry = new Entry(resolution, script);
            outdated = false;
            loadedAt = now;
            validUntil = now + lifetime(script.getMaxAgeMillis());
            failingSince = 0L;
            failures = 0;
            lastFailure = null;
            nextAttemptAt = 0L;
        }
    }

    /** A reload failed: record it and keep using the last good pair until the next attempt is due. */
    void reloadFailed(long loadGeneration, String detail) {
        long now = now();
        synchronized (lock) {
            if (loadGeneration != generation) {
                return;
            }
            if (failures == 0) {
                failingSince = now;
            }
            failures++;
            lastFailure = detail;
            if (entry != null) {
                outdated = false;
                nextAttemptAt = now + retryDelay(failures);
            }
        }
    }

    /** The discovery found no PAC URL: forget the pair. */
    void forget(long loadGeneration) {
        synchronized (lock) {
            if (loadGeneration == generation) {
                entry = null;
                outdated = false;
                loadedAt = 0L;
                validUntil = 0L;
            }
        }
    }

    /** Marks the pair as outdated; the next resolution reloads at once. */
    void invalidate() {
        synchronized (lock) {
            generation++;
            outdated = true;
            nextAttemptAt = 0L;
        }
    }

    ReentrantLock loadLock() {
        return loadLock;
    }

    PacCacheStatus status() {
        synchronized (lock) {
            return new PacCacheStatus(entry == null ? null : entry.resolution.getPacUrl(), loadedAt, validUntil,
                    failingSince, failures, lastFailure);
        }
    }

    static long lifetime(long announced) {
        if (announced == PacScript.NO_MAX_AGE) {
            return DEFAULT_MAX_AGE_MILLIS;
        }
        return Math.min(MAX_MAX_AGE_MILLIS, Math.max(MIN_MAX_AGE_MILLIS, announced));
    }

    static long retryDelay(int failures) {
        long delay = FIRST_RETRY_MILLIS;
        for (int i = 1; i < failures && delay < MAX_RETRY_MILLIS; i++) {
            delay *= 2;
        }
        return Math.min(delay, MAX_RETRY_MILLIS);
    }
}
