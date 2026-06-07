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
 *
 * @see ProxyMode
 */
public final class WindowsProxyResolver {

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
                return pacPipeline(new FixedPacUrlResolver(configuration.getPacUrl()))
                        .resolve(effectiveTargetUrl(targetUrl));

            case PAC_URL_POWERSHELL:
                return pacPipeline(new PowerShellPacUrlResolver(configuration.getPacUrlDiscoveryScript()))
                        .resolve(effectiveTargetUrl(targetUrl));

            case PAC_URL_WINDOWS_SETTINGS:
                return pacPipeline(new WindowsPacUrlResolver())
                        .resolve(effectiveTargetUrl(targetUrl));

            case POWERSHELL_ROUTE_RESOLVER_LEGACY:
                return resolveLegacyPowerShellRoute(targetUrl);

            case WINDOWS_NATIVE_PROXY_SETTINGS:
                return new NotImplementedProxyResolver("windows-native-proxy-settings-not-implemented").resolve();

            case WINDOWS_NATIVE_ROUTE_RESOLVER:
                return new NotImplementedProxyResolver("windows-native-route-resolver-not-implemented").resolve();

            default:
                return ProxyResult.error("unknown-proxy-mode");
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
            return ProxyResult.error("legacy-route-resolver-failed");
        }
    }

    private String effectiveTargetUrl(String targetUrl) {
        if (targetUrl == null || targetUrl.trim().length() == 0) {
            return configuration.getTestUrl();
        }
        return targetUrl;
    }
}
