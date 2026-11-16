package com.aresstack.winproxy;

/**
 * Remembers the last good PAC URL and PAC script of one {@link WindowsProxyResolver}, so that
 * the PAC URL discovery (PowerShell, WScript, registry) and the PAC download run once and not
 * on every {@code resolve()}.
 * <p>
 * Rules (applied by {@link PacUrlProxyResolver}):
 * <ul>
 *   <li>A pair of PAC URL and script is only remembered after the script was evaluated
 *       successfully; an empty or broken download never replaces a working script.</li>
 *   <li>{@link #invalidate()} marks the pair as outdated; the next resolution loads again. If
 *       that reload fails at any step (discovery, download, evaluation, also for a newly
 *       discovered PAC URL), the last good pair stays in use until the next invalidation.</li>
 *   <li>A discovery that finds no PAC URL at all is taken as a configuration change and
 *       forgets the pair.</li>
 *   <li>Every write carries the {@link #generation()} read before the load started; a write
 *       from a load that began before the latest {@link #invalidate()} is ignored, so a slow
 *       diagnosis cannot undo an invalidation.</li>
 * </ul>
 * Instances are thread-safe; {@link #loadLock()} lets concurrent first resolutions wait for a
 * single load.
 */
final class PacCache {

    /** A PAC URL with the script downloaded from it. */
    static final class Entry {
        final PacUrlResolution resolution;
        final String script;

        Entry(PacUrlResolution resolution, String script) {
            this.resolution = resolution;
            this.script = script;
        }
    }

    private final Object lock = new Object();
    private final Object loadLock = new Object();

    private Entry entry;
    private boolean outdated;
    private long generation;

    /** The remembered pair if it is current (not outdated), otherwise {@code null}. */
    Entry current() {
        synchronized (lock) {
            return outdated ? null : entry;
        }
    }

    /** The last good pair, current or outdated, or {@code null}. */
    Entry lastGood() {
        synchronized (lock) {
            return entry;
        }
    }

    /** Counter of invalidations; read before a load and passed to the writes. */
    long generation() {
        synchronized (lock) {
            return generation;
        }
    }

    /** Remembers a successfully evaluated pair, unless the cache was invalidated since {@code loadGeneration}. */
    void store(long loadGeneration, PacUrlResolution resolution, String script) {
        synchronized (lock) {
            if (loadGeneration == generation) {
                entry = new Entry(resolution, script);
                outdated = false;
            }
        }
    }

    /** A reload failed: keep using the last good pair until the next invalidation. */
    void keepLastGood(long loadGeneration) {
        synchronized (lock) {
            if (loadGeneration == generation && entry != null) {
                outdated = false;
            }
        }
    }

    /** The discovery found no PAC URL: forget the pair. */
    void forget(long loadGeneration) {
        synchronized (lock) {
            if (loadGeneration == generation) {
                entry = null;
                outdated = false;
            }
        }
    }

    /** Marks the pair as outdated; the next resolution loads again. */
    void invalidate() {
        synchronized (lock) {
            generation++;
            outdated = true;
        }
    }

    /** Serialises loads of the resolution path. */
    Object loadLock() {
        return loadLock;
    }
}
