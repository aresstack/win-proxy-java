package com.aresstack.winproxy;

/**
 * The single, shared PAC pipeline used by all {@code PAC_URL_*} modes:
 * <pre>
 *   PacUrlResolver -&gt; PacScriptLoader -&gt; PacEvaluator (GraalVM) -&gt; PacProxyRouteParser
 * </pre>
 * Only the {@link PacUrlResolver} differs per mode (manual / PowerShell / Windows settings).
 * <p>
 * This pipeline never falls back to static-proxy, registry, manual or DIRECT. Every
 * failure surfaces as a {@link ProxyResult#error(String) ProxyResult ERROR} carrying the
 * technical cause, so diagnostics are never masked:
 * <ul>
 *   <li>{@code pac-url-discovery-failed} — the discovery step threw.</li>
 *   <li>{@code pac-url-not-found} — the discovery step produced no URL.</li>
 *   <li>{@code pac-download-failed} — the PAC file could not be downloaded.</li>
 *   <li>{@code pac-evaluation-failed} — GraalVM/PAC evaluation failed.</li>
 * </ul>
 * A genuine {@code DIRECT} is only returned when the PAC script itself yields DIRECT.
 */
public final class PacUrlProxyResolver {

    private final PacUrlResolver pacUrlResolver;
    private final PacScriptLoader pacScriptLoader;
    private final PacEvaluator pacEvaluator;

    public PacUrlProxyResolver(
            PacUrlResolver pacUrlResolver,
            PacScriptLoader pacScriptLoader,
            PacEvaluator pacEvaluator
    ) {
        this.pacUrlResolver = pacUrlResolver;
        this.pacScriptLoader = pacScriptLoader;
        this.pacEvaluator = pacEvaluator;
    }

    public ProxyResult resolve(String targetUrl) {
        PacUrlResolution resolution;
        try {
            resolution = pacUrlResolver.resolve();
        } catch (ProxyResolutionException e) {
            return ProxyResult.error("pac-url-discovery-failed");
        }
        if (resolution == null || !resolution.isPresent()) {
            return ProxyResult.error("pac-url-not-found");
        }

        String pacScript;
        try {
            pacScript = pacScriptLoader.load(resolution.getPacUrl());
        } catch (ProxyResolutionException e) {
            return ProxyResult.error("pac-download-failed");
        }

        try {
            return pacEvaluator.evaluate(pacScript, targetUrl);
        } catch (ProxyResolutionException e) {
            return ProxyResult.error("pac-evaluation-failed");
        }
    }
}
