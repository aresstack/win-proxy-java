package com.aresstack.winproxy;

/**
 * Public facade for resolving Windows proxy settings.
 * <p>
 * The behaviour is driven entirely by {@link ProxyConfiguration#getMode()}. Each
 * mode has a single, clearly-defined strategy and never silently falls back to a
 * different one. In particular, all {@code PAC_URL_*} modes share the one PAC
 * pipeline ({@link PacUrlProxyResolver}) and only differ in how the PAC URL is
 * discovered. Failures are reported as {@link ProxyResult#error(String) ERROR}
 * results with a technical reason instead of a masked DIRECT.
 * <p>
 * {@link #diagnose(String)} runs exactly the same resolution and additionally
 * records every step for a settings UI or a log.
 * <p>
 * In the {@code PAC_URL_*} modes the PAC URL and the PAC script are remembered per instance
 * and reloaded lazily: after the lifetime announced by the PAC server ({@code Cache-Control:
 * max-age}, {@code Expires}; 5 min to 24 h), otherwise after 12 h, and at once after
 * {@link #invalidatePacCache()}. A failed reload keeps the last good script and retries with a
 * growing delay (1 min up to 1 h); {@link #pacCacheStatus()} shows since when it fails. Nothing
 * runs on a timer. Keep one instance per configuration.
 *
 * @see ProxyMode
 */
public final class WindowsProxyResolver {

    private static final String NATIVE_NOT_IMPLEMENTED_DETAIL =
            "reserved for a future native Windows implementation; not available in this version of win-proxy-java";

    private final ProxyConfiguration configuration;
    private final PacScriptLoader pacScriptLoader;
    private final PacEvaluator pacEvaluator;
    private final StaticProxySettingsResolver staticProxySettingsResolver;
    private final ManualProxyResolver manualProxyResolver;
    private final DirectProxyResolver directProxyResolver;
    private final PacCache pacCache = new PacCache();

    /**
     * Create a resolver with the default configuration.
     */
    public WindowsProxyResolver() {
        this(ProxyConfiguration.defaults());
    }

    /**
     * Create a resolver with the given configuration.
     *
     * @param configuration proxy configuration
     */
    public WindowsProxyResolver(ProxyConfiguration configuration) {
        this(
                configuration,
                new UrlConnectionPacScriptLoader(),
                PacEvaluator.createDefault(),
                new StaticProxySettingsResolver(),
                new ManualProxyResolver(),
                new DirectProxyResolver()
        );
    }

    WindowsProxyResolver(
            ProxyConfiguration configuration,
            PacScriptLoader pacScriptLoader,
            PacEvaluator pacEvaluator,
            StaticProxySettingsResolver staticProxySettingsResolver,
            ManualProxyResolver manualProxyResolver,
            DirectProxyResolver directProxyResolver
    ) {
        this.configuration = configuration == null ? ProxyConfiguration.defaults() : configuration;
        this.pacScriptLoader = pacScriptLoader;
        this.pacEvaluator = pacEvaluator;
        this.staticProxySettingsResolver = staticProxySettingsResolver;
        this.manualProxyResolver = manualProxyResolver;
        this.directProxyResolver = directProxyResolver;
    }

    /**
     * Resolve the proxy for a target URL using the configured mode.
     *
     * @param targetUrl target URL
     * @return proxy result — PROXY, DIRECT, ERROR or NOT_IMPLEMENTED, never {@code null}
     */
    public ProxyResult resolve(String targetUrl) {
        ProxyMode mode = configuration.getMode();
        switch (mode) {
            case DISABLED:
                return directProxyResolver.resolve();

            case MANUAL_PROXY:
                return manualProxyResolver.resolve(configuration);

            case WINDOWS_STATIC_PROXY:
                return staticProxySettingsResolver.resolve(effectiveTargetUrl(targetUrl));

            case PAC_URL_MANUAL:
            case PAC_URL_POWERSHELL:
            case PAC_URL_WINDOWS_SETTINGS:
            case PAC_URL_WSCRIPT:
                return cachedPacPipeline(pacUrlResolverFor(mode)).resolve(effectiveTargetUrl(targetUrl));

            case POWERSHELL_ROUTE_RESOLVER_LEGACY:
                return resolveLegacyPowerShellRoute(targetUrl);

            case WINDOWS_NATIVE_PROXY_SETTINGS:
                return ProxyResult.notImplemented("windows-native-proxy-settings-not-implemented",
                        NATIVE_NOT_IMPLEMENTED_DETAIL);

            case WINDOWS_NATIVE_ROUTE_RESOLVER:
                return ProxyResult.notImplemented("windows-native-route-resolver-not-implemented",
                        NATIVE_NOT_IMPLEMENTED_DETAIL);

            default:
                return ProxyResult.error("unknown-proxy-mode");
        }
    }

    /**
     * Resolve the proxy for a target URL exactly like {@link #resolve(String)} and record every
     * step (PAC URL and its source, PAC script size, evaluation result, failure messages).
     * Intended for "resolve test" buttons and logs; {@link ProxyDiagnostics#getResult()} is
     * what {@link #resolve(String)} returns.
     * <p>
     * In the {@code PAC_URL_*} modes the PAC URL is discovered and the PAC script downloaded
     * afresh, so the report shows whether they are reachable right now; on success both
     * replace the remembered ones used by {@link #resolve(String)}.
     *
     * @param targetUrl target URL
     * @return the diagnostics, never {@code null}
     * @since 0.2.0
     */
    public ProxyDiagnostics diagnose(String targetUrl) {
        ProxyMode mode = configuration.getMode();
        String url = effectiveTargetUrl(targetUrl);
        PacUrlResolver pacUrlResolver = pacUrlResolverFor(mode);
        if (pacUrlResolver != null) {
            return freshPacPipeline(pacUrlResolver).diagnose(mode, url);
        }
        ProxyDiagnostics.Builder diagnostics = ProxyDiagnostics.builder(mode, url);
        ProxyResult result = resolve(url);
        diagnostics.step(mode + " -> " + result);
        return diagnostics.finish(result);
    }

    /**
     * Returns the PAC URL discovery strategy of a {@code PAC_URL_*} mode, or {@code null} for
     * every other mode.
     */
    private PacUrlResolver pacUrlResolverFor(ProxyMode mode) {
        switch (mode) {
            case PAC_URL_MANUAL:
                return new FixedPacUrlResolver(configuration.getPacUrl());
            case PAC_URL_POWERSHELL:
                return new PowerShellPacUrlResolver(configuration.getPacUrlDiscoveryScript());
            case PAC_URL_WINDOWS_SETTINGS:
                return new WindowsPacUrlResolver();
            case PAC_URL_WSCRIPT:
                return new WScriptPacUrlResolver(configuration.getPacUrlDiscoveryScript());
            default:
                return null;
        }
    }

    /**
     * Marks the remembered PAC URL and PAC script as outdated, e.g. after a network change or
     * when a connection over a resolved proxy failed. The next {@link #resolve(String)} discovers
     * and downloads them again; if that fails, the last good ones stay in use. Without
     * {@code PAC_URL_*} mode this does nothing.
     *
     * @since 0.3.0
     */
    public void invalidatePacCache() {
        pacCache.invalidate();
    }

    /**
     * State of the remembered PAC script: when it was loaded, until when it is valid, and since
     * when refreshing it fails (the last good script is then still in use). For a status line
     * or a log; without {@code PAC_URL_*} mode nothing is remembered.
     *
     * @return the state, never {@code null}
     * @since 0.3.0
     */
    public PacCacheStatus pacCacheStatus() {
        return pacCache.status();
    }

    /** PAC pipeline over the remembered PAC URL and script (loaded only when needed). */
    private PacUrlProxyResolver cachedPacPipeline(PacUrlResolver pacUrlResolver) {
        return new PacUrlProxyResolver(pacUrlResolver, pacScriptLoader, pacEvaluator, pacCache, false);
    }

    /** PAC pipeline that discovers and downloads now and remembers a successful result. */
    private PacUrlProxyResolver freshPacPipeline(PacUrlResolver pacUrlResolver) {
        return new PacUrlProxyResolver(pacUrlResolver, pacScriptLoader, pacEvaluator, pacCache, true);
    }

    /**
     * Legacy PowerShell/.NET route resolution. Does not fall back to any other mode;
     * a failure is reported as an ERROR result.
     *
     * @deprecated Use a {@code PAC_URL_*} mode instead.
     */
    @Deprecated
    private ProxyResult resolveLegacyPowerShellRoute(String targetUrl) {
        try {
            return new WindowsPacScriptProxyResolver().resolve(configuration, effectiveTargetUrl(targetUrl));
        } catch (ProxyResolutionException e) {
            return ProxyResult.error("legacy-route-resolver-failed", PacUrlProxyResolver.describe(e));
        }
    }

    private String effectiveTargetUrl(String targetUrl) {
        if (targetUrl == null || targetUrl.trim().length() == 0) {
            return configuration.getTestUrl();
        }
        return targetUrl;
    }
}
