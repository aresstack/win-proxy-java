package com.aresstack.winproxy;

/**
 * The single, shared PAC pipeline used by all {@code PAC_URL_*} modes:
 * <pre>
 *   PacUrlResolver -&gt; PacScriptLoader -&gt; PacEvaluator (GraalVM) -&gt; PacProxyRouteParser
 * </pre>
 * Only the {@link PacUrlResolver} differs per mode (manual / PowerShell / WScript / Windows
 * settings).
 * <p>
 * This pipeline never falls back to static-proxy, registry, manual or DIRECT. Every
 * failure surfaces as a {@link ProxyResult#error(String, String) ProxyResult ERROR} carrying
 * the technical cause (and the underlying message as {@link ProxyResult#getDetail() detail}),
 * so diagnostics are never masked:
 * <ul>
 *   <li>{@code pac-url-discovery-failed} — the discovery step threw.</li>
 *   <li>{@code pac-url-not-found} — the discovery step produced no URL.</li>
 *   <li>{@code pac-download-failed} — the PAC file could not be downloaded.</li>
 *   <li>{@code pac-evaluation-failed} — GraalVM/PAC evaluation failed.</li>
 * </ul>
 * A genuine {@code DIRECT} is only returned when the PAC script itself yields DIRECT.
 * {@link #diagnose(String)} runs the very same steps and additionally records them.
 */
public final class PacUrlProxyResolver {

    private final PacUrlResolver pacUrlResolver;
    private final PacScriptLoader pacScriptLoader;
    private final PacEvaluator pacEvaluator;
    private final PacCache cache;
    private final boolean fresh;

    public PacUrlProxyResolver(
            PacUrlResolver pacUrlResolver,
            PacScriptLoader pacScriptLoader,
            PacEvaluator pacEvaluator
    ) {
        this(pacUrlResolver, pacScriptLoader, pacEvaluator, null, true);
    }

    /**
     * @param cache remembered PAC URL and script, or {@code null} to load on every call
     * @param fresh {@code true}: always discover and download (diagnostics) and only remember a
     *              success; {@code false}: use the remembered pair and fall back to it on failure
     */
    PacUrlProxyResolver(
            PacUrlResolver pacUrlResolver,
            PacScriptLoader pacScriptLoader,
            PacEvaluator pacEvaluator,
            PacCache cache,
            boolean fresh
    ) {
        this.pacUrlResolver = pacUrlResolver;
        this.pacScriptLoader = pacScriptLoader;
        this.pacEvaluator = pacEvaluator;
        this.cache = cache;
        this.fresh = fresh;
    }

    public ProxyResult resolve(String targetUrl) {
        return diagnose(null, targetUrl).getResult();
    }

    /**
     * Runs the pipeline for the target URL and records every step (discovered PAC URL and its
     * source, PAC script size, evaluation result, failure messages).
     *
     * @param targetUrl target URL
     * @return the diagnostics; {@link ProxyDiagnostics#getResult()} equals {@link #resolve(String)}
     * @since 0.2.0
     */
    public ProxyDiagnostics diagnose(String targetUrl) {
        return diagnose(null, targetUrl);
    }

    ProxyDiagnostics diagnose(ProxyMode mode, String targetUrl) {
        ProxyDiagnostics.Builder diagnostics = ProxyDiagnostics.builder(mode, targetUrl);
        if (cache == null) {
            return load(diagnostics, targetUrl, -1L, null);
        }
        if (fresh) {
            ProxyDiagnostics loaded = load(diagnostics, targetUrl, cache.generation(), null);
            diagnostics.step("Remembered: " + cache.status().describe());
            return loaded;
        }
        PacCache.Entry lastGood = cache.lastGood();
        if (lastGood != null && !cache.needsReload()) {
            return evaluate(diagnostics, lastGood, targetUrl, "remembered");
        }
        if (lastGood == null) {
            // Nothing to fall back to: wait for the single first load.
            cache.loadLock().lock();
        } else if (!cache.loadLock().tryLock()) {
            // Another resolution is reloading right now: keep using the last good script.
            return evaluate(diagnostics, lastGood, targetUrl, "remembered, reload in progress");
        }
        try {
            lastGood = cache.lastGood();
            if (lastGood != null && !cache.needsReload()) {
                return evaluate(diagnostics, lastGood, targetUrl, "remembered");
            }
            return reload(diagnostics, targetUrl, lastGood);
        } finally {
            cache.loadLock().unlock();
        }
    }

    /** Loads again (first time, lifetime over or invalidated); on failure falls back to the last good pair. */
    private ProxyDiagnostics reload(ProxyDiagnostics.Builder diagnostics, String targetUrl, PacCache.Entry lastGood) {
        long generation = cache.generation();
        ProxyDiagnostics loaded = load(diagnostics, targetUrl, generation, lastGood);
        ProxyResult result = loaded.getResult();
        if (!isPacFailure(result) || "pac-url-not-found".equals(result.getReason())) {
            return loaded;
        }
        cache.reloadFailed(generation, result.getDetail() != null ? result.getDetail() : result.getReason());
        if (lastGood == null) {
            return loaded;
        }
        diagnostics.step("Reload failed, using the last good PAC script.");
        return evaluate(diagnostics, lastGood, targetUrl, "last good");
    }

    /** Discovery, download and evaluation; remembers a success when a cache is present. */
    private ProxyDiagnostics load(ProxyDiagnostics.Builder diagnostics, String targetUrl, long generation,
                                  PacCache.Entry previous) {
        PacUrlResolution resolution;
        try {
            resolution = pacUrlResolver.resolve();
        } catch (ProxyResolutionException e) {
            String detail = describe(e);
            diagnostics.step("PAC URL discovery failed: " + detail);
            return diagnostics.finish(ProxyResult.error("pac-url-discovery-failed", detail));
        }
        if (resolution == null || !resolution.isPresent()) {
            diagnostics.step("No PAC/WPAD URL was discovered.");
            if (cache != null && !fresh) {
                cache.forget(generation);
            }
            return diagnostics.finish(ProxyResult.error("pac-url-not-found", "no PAC/WPAD URL was discovered"));
        }
        String pacUrl = resolution.getPacUrl();
        diagnostics.pacUrl(pacUrl, resolution.getSource());
        diagnostics.step("PAC URL: " + pacUrl + " (source: " + resolution.getSource() + ")");

        PacScript pacScript;
        try {
            PacScript before = previous != null && pacUrl.equals(previous.resolution.getPacUrl())
                    ? previous.script : null;
            pacScript = pacScriptLoader instanceof ConditionalPacScriptLoader
                    ? ((ConditionalPacScriptLoader) pacScriptLoader).load(pacUrl, before)
                    : PacScript.of(pacScriptLoader.load(pacUrl));
        } catch (ProxyResolutionException e) {
            String detail = describe(e);
            diagnostics.step("PAC script NOT reachable: " + detail);
            return diagnostics.finish(ProxyResult.error("pac-download-failed", detail));
        }
        int length = pacScript.getContent().length();
        diagnostics.pacScriptLength(length);
        diagnostics.step(pacScript.isNotModified()
                ? "PAC script unchanged (HTTP 304, " + length + " characters)."
                : "PAC script downloaded (" + length + " characters).");

        try {
            ProxyResult result = pacEvaluator.evaluate(pacScript.getContent(), targetUrl);
            diagnostics.step("FindProxyForURL -> " + result);
            if (cache != null) {
                cache.store(generation, resolution, pacScript);
            }
            return diagnostics.finish(result);
        } catch (ProxyResolutionException e) {
            String detail = describe(e);
            diagnostics.step("PAC evaluation failed: " + detail);
            return diagnostics.finish(ProxyResult.error("pac-evaluation-failed", detail));
        }
    }

    private ProxyDiagnostics evaluate(ProxyDiagnostics.Builder diagnostics, PacCache.Entry entry, String targetUrl,
                                      String which) {
        String content = entry.script.getContent();
        diagnostics.pacUrl(entry.resolution.getPacUrl(), entry.resolution.getSource());
        diagnostics.pacScriptLength(content.length());
        diagnostics.step("PAC script (" + which + ") from " + entry.resolution.getPacUrl());
        try {
            ProxyResult result = pacEvaluator.evaluate(content, targetUrl);
            diagnostics.step("FindProxyForURL -> " + result);
            return diagnostics.finish(result);
        } catch (ProxyResolutionException e) {
            String detail = describe(e);
            diagnostics.step("PAC evaluation failed: " + detail);
            return diagnostics.finish(ProxyResult.error("pac-evaluation-failed", detail));
        }
    }

    private static boolean isPacFailure(ProxyResult result) {
        String reason = result.getReason();
        return reason != null && (reason.equals("pac-url-discovery-failed") || reason.equals("pac-url-not-found")
                || reason.equals("pac-download-failed") || reason.equals("pac-evaluation-failed"));
    }

    /** Message of the exception followed by the messages of its causes. */
    static String describe(Throwable throwable) {
        StringBuilder builder = new StringBuilder();
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 5) {
            String message = current.getMessage();
            String text = message == null || message.trim().length() == 0
                    ? current.getClass().getName()
                    : (depth == 0 ? message.trim() : current.getClass().getName() + ": " + message.trim());
            if (builder.length() > 0) {
                builder.append(" <- ");
            }
            builder.append(text);
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
            depth++;
        }
        return builder.toString();
    }
}
