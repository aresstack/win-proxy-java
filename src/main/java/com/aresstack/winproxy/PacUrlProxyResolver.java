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
            return diagnostics.finish(ProxyResult.error("pac-url-not-found", "no PAC/WPAD URL was discovered"));
        }
        String pacUrl = resolution.getPacUrl();
        diagnostics.pacUrl(pacUrl, resolution.getSource());
        diagnostics.step("PAC URL: " + pacUrl + " (source: " + resolution.getSource() + ")");

        String pacScript;
        try {
            pacScript = pacScriptLoader.load(pacUrl);
        } catch (ProxyResolutionException e) {
            String detail = describe(e);
            diagnostics.step("PAC script NOT reachable: " + detail);
            return diagnostics.finish(ProxyResult.error("pac-download-failed", detail));
        }
        int length = pacScript == null ? 0 : pacScript.length();
        diagnostics.pacScriptLength(length);
        diagnostics.step("PAC script downloaded (" + length + " characters).");

        try {
            ProxyResult result = pacEvaluator.evaluate(pacScript, targetUrl);
            diagnostics.step("FindProxyForURL -> " + result);
            return diagnostics.finish(result);
        } catch (ProxyResolutionException e) {
            String detail = describe(e);
            diagnostics.step("PAC evaluation failed: " + detail);
            return diagnostics.finish(ProxyResult.error("pac-evaluation-failed", detail));
        }
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
