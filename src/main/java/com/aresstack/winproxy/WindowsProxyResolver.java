package com.aresstack.winproxy;

/**
 * Public facade for resolving Windows proxy settings.
 */
public final class WindowsProxyResolver {

    private final ProxyConfiguration configuration;
    private final PacUrlResolver pacUrlResolver;
    private final PacScriptLoader pacScriptLoader;
    private final PacEvaluator pacEvaluator;
    private final StaticProxySettingsResolver staticProxySettingsResolver;
    private final WindowsPacScriptProxyResolver windowsPacScriptProxyResolver;
    private final ManualProxyResolver manualProxyResolver;

    /**
     * Create a resolver with the default PAC_URL configuration.
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
                new WindowsPacUrlResolver(),
                new UrlConnectionPacScriptLoader(),
                PacEvaluator.createDefault(),
                new StaticProxySettingsResolver(),
                new WindowsPacScriptProxyResolver(),
                new ManualProxyResolver()
        );
    }

    WindowsProxyResolver(
            ProxyConfiguration configuration,
            PacUrlResolver pacUrlResolver,
            PacScriptLoader pacScriptLoader,
            PacEvaluator pacEvaluator,
            StaticProxySettingsResolver staticProxySettingsResolver,
            WindowsPacScriptProxyResolver windowsPacScriptProxyResolver,
            ManualProxyResolver manualProxyResolver
    ) {
        this.configuration = configuration == null ? ProxyConfiguration.defaults() : configuration;
        this.pacUrlResolver = pacUrlResolver;
        this.pacScriptLoader = pacScriptLoader;
        this.pacEvaluator = pacEvaluator;
        this.staticProxySettingsResolver = staticProxySettingsResolver;
        this.windowsPacScriptProxyResolver = windowsPacScriptProxyResolver;
        this.manualProxyResolver = manualProxyResolver;
    }

    /**
     * Resolve the proxy for a target URL using the configured mode.
     *
     * @param targetUrl target URL
     * @return proxy result or direct
     */
    public ProxyResult resolve(String targetUrl) {
        ProxyMode mode = configuration.getMode();
        if (mode == ProxyMode.DISABLED) {
            return ProxyResult.direct("disabled");
        }
        if (mode == ProxyMode.MANUAL) {
            return manualProxyResolver.resolve(configuration);
        }
        if (mode == ProxyMode.WINDOWS_PAC) {
            return resolveWindowsPac(targetUrl);
        }
        if (mode == ProxyMode.REGISTRY) {
            return resolveRegistry(targetUrl);
        }
        return resolvePacUrl(targetUrl);
    }

    /**
     * Resolve a proxy through the default PAC_URL pipeline.
     *
     * @param targetUrl target URL
     * @return proxy result or direct
     */
    public ProxyResult resolvePacUrl(String targetUrl) {
        PacUrlResolution pacUrlResolution = discoverPacUrl();
        if (!pacUrlResolution.isPresent()) {
            return resolveRegistry(targetUrl);
        }
        String pacScript = pacScriptLoader.load(pacUrlResolution.getPacUrl());
        return pacEvaluator.evaluate(pacScript, targetUrl);
    }

    /**
     * Discover the PAC/WPAD URL from explicit configuration or Windows.
     *
     * @return discovered PAC URL
     */
    public PacUrlResolution discoverPacUrl() {
        if (configuration.getPacUrl() != null && configuration.getPacUrl().trim().length() > 0) {
            return PacUrlResolution.found(configuration.getPacUrl().trim(), "configuration");
        }
        return pacUrlResolver.resolve();
    }

    /**
     * Resolve a proxy through Windows/.NET/PowerShell.
     *
     * @param targetUrl target URL
     * @return proxy result or direct
     */
    public ProxyResult resolveWindowsPac(String targetUrl) {
        return windowsPacScriptProxyResolver.resolve(configuration, targetUrl);
    }

    /**
     * Resolve a proxy from static Windows registry proxy settings.
     *
     * @param targetUrl target URL
     * @return proxy result or direct
     */
    public ProxyResult resolveRegistry(String targetUrl) {
        return staticProxySettingsResolver.resolve(targetUrl);
    }

    /**
     * Resolve only the configured or discovered PAC URL.
     *
     * @return PAC URL or {@code null}
     * @deprecated Use {@link #discoverPacUrl()}.
     */
    @Deprecated
    public String resolvePacUrl() {
        PacUrlResolution resolution = discoverPacUrl();
        return resolution.isPresent() ? resolution.getPacUrl() : null;
    }

    /**
     * Resolve a PAC URL with the default PowerShell script.
     *
     * @param script optional script
     * @return PAC URL or {@code null}
     */
    public String discoverPacUrlWithPowerShell(String script) {
        PacUrlResolver resolver = new PowerShellPacUrlResolver(script);
        PacUrlResolution resolution = resolver.resolve();
        return resolution.isPresent() ? resolution.getPacUrl() : null;
    }

    /**
     * Resolve a PAC URL by legacy source.
     *
     * @param source legacy PAC URL source
     * @param powerShellScript optional PowerShell script
     * @return PAC URL or {@code null}
     * @deprecated Use {@link ProxyConfiguration} and {@link ProxyMode}.
     */
    @Deprecated
    public String resolvePacUrl(PacUrlSource source, String powerShellScript) {
        if (source == null || source == PacUrlSource.DIRECT) {
            return null;
        }
        if (source == PacUrlSource.POWERSHELL) {
            return discoverPacUrlWithPowerShell(powerShellScript);
        }
        PacUrlResolution resolution = new WindowsPacUrlResolver().resolve();
        return resolution.isPresent() ? resolution.getPacUrl() : null;
    }

    /**
     * Resolve a proxy for a target URL using the legacy source API.
     *
     * @param targetUrl target URL
     * @param source legacy PAC URL source
     * @param powerShellScript optional PowerShell script
     * @return proxy result or direct
     * @deprecated Use {@link #resolve(String)} with {@link ProxyConfiguration}.
     */
    @Deprecated
    public ProxyResult resolve(String targetUrl, PacUrlSource source, String powerShellScript) {
        if (source == null || source == PacUrlSource.DIRECT) {
            return ProxyResult.direct("legacy-direct-source");
        }
        String pacUrl = resolvePacUrl(source, powerShellScript);
        if (pacUrl == null || pacUrl.trim().length() == 0) {
            return resolveRegistry(targetUrl);
        }
        String script = pacScriptLoader.load(pacUrl);
        return pacEvaluator.evaluate(script, targetUrl);
    }
}
