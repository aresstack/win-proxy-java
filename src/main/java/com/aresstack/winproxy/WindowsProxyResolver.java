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
                return pacPipeline(pacUrlResolverFor(mode)).resolve(effectiveTargetUrl(targetUrl));

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
            return pacPipeline(pacUrlResolver).diagnose(mode, url);
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

    private PacUrlProxyResolver pacPipeline(PacUrlResolver pacUrlResolver) {
        return new PacUrlProxyResolver(pacUrlResolver, pacScriptLoader, pacEvaluator);
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
