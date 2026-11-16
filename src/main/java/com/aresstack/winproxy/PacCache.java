package com.aresstack.winproxy;

/**
 * Remembers the discovered PAC URL and the downloaded PAC script of one
 * {@link WindowsProxyResolver}, so that the PAC URL discovery (PowerShell, WScript, registry)
 * and the PAC download run once and not on every {@code resolve()}.
 * <p>
 * Rules:
 * <ul>
 *   <li>The first resolution discovers the PAC URL and downloads the script; every later one
 *       reuses both. Concurrent first resolutions wait for that single load.</li>
 *   <li>{@link #invalidate()} marks both as outdated; the next resolution loads them again.</li>
 *   <li>If that reload fails, the last good PAC URL and script stay in use (until the next
 *       {@link #invalidate()}), so a briefly unreachable WPAD server never breaks routes
 *       that worked before. Without a last good value the failure is reported as before.</li>
 *   <li>A discovery that now finds no PAC URL at all is taken as a configuration change and
 *       forgets the remembered values.</li>
 * </ul>
 * Instances are thread-safe.
 */
final class PacCache {

    private final Object lock = new Object();

    private PacUrlResolution resolution;
    private boolean resolutionOutdated;
    private String scriptUrl;
    private String script;
    private boolean scriptOutdated;

    /** The remembered PAC URL, discovered through {@code discovery} only when needed. */
    PacUrlResolution resolve(PacUrlResolver discovery) {
        synchronized (lock) {
            if (resolution != null && !resolutionOutdated) {
                return resolution;
            }
            try {
                return storeResolution(discovery.resolve());
            } catch (ProxyResolutionException e) {
                if (resolution == null) {
                    throw e;
                }
                resolutionOutdated = false;
                return resolution;
            }
        }
    }

    /** The remembered PAC script of {@code pacUrl}, downloaded through {@code loader} only when needed. */
    String load(PacScriptLoader loader, String pacUrl) {
        synchronized (lock) {
            if (script != null && !scriptOutdated && pacUrl.equals(scriptUrl)) {
                return script;
            }
            try {
                return storeScript(pacUrl, loader.load(pacUrl));
            } catch (ProxyResolutionException e) {
                if (script == null || !pacUrl.equals(scriptUrl)) {
                    throw e;
                }
                scriptOutdated = false;
                return script;
            }
        }
    }

    /** Discovers the PAC URL now, without falling back; a found URL is remembered. For diagnostics. */
    PacUrlResolution resolveFresh(PacUrlResolver discovery) {
        PacUrlResolution fresh = discovery.resolve();
        synchronized (lock) {
            return storeResolution(fresh);
        }
    }

    /** Downloads the PAC script now, without falling back; the script is remembered. For diagnostics. */
    String loadFresh(PacScriptLoader loader, String pacUrl) {
        String fresh = loader.load(pacUrl);
        synchronized (lock) {
            return storeScript(pacUrl, fresh);
        }
    }

    /** Marks PAC URL and script as outdated; the next resolution loads them again. */
    void invalidate() {
        synchronized (lock) {
            resolutionOutdated = true;
            scriptOutdated = true;
        }
    }

    private PacUrlResolution storeResolution(PacUrlResolution fresh) {
        if (fresh == null || !fresh.isPresent()) {
            resolution = null;
            script = null;
            scriptUrl = null;
        } else {
            resolution = fresh;
        }
        resolutionOutdated = false;
        return fresh;
    }

    private String storeScript(String pacUrl, String fresh) {
        script = fresh;
        scriptUrl = pacUrl;
        scriptOutdated = false;
        return fresh;
    }
}
