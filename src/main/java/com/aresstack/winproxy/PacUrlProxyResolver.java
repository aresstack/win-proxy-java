package com.aresstack.winproxy;

/**
 * Resolves proxies through the PAC_URL pipeline.
 */
public final class PacUrlProxyResolver {

    private final PacUrlResolver pacUrlResolver;
    private final PacScriptLoader pacScriptLoader;
    private final PacEvaluator pacEvaluator;
    private final StaticProxySettingsResolver fallbackResolver;

    public PacUrlProxyResolver(
            PacUrlResolver pacUrlResolver,
            PacScriptLoader pacScriptLoader,
            PacEvaluator pacEvaluator,
            StaticProxySettingsResolver fallbackResolver
    ) {
        this.pacUrlResolver = pacUrlResolver;
        this.pacScriptLoader = pacScriptLoader;
        this.pacEvaluator = pacEvaluator;
        this.fallbackResolver = fallbackResolver;
    }

    public ProxyResult resolve(String targetUrl) {
        PacUrlResolution pacUrlResolution = pacUrlResolver.resolve();
        if (!pacUrlResolution.isPresent()) {
            return fallbackResolver.resolve(targetUrl);
        }
        try {
            String pacScript = pacScriptLoader.load(pacUrlResolution.getPacUrl());
            return pacEvaluator.evaluate(pacScript, targetUrl);
        } catch (ProxyResolutionException e) {
            return fallbackResolver.resolve(targetUrl);
        }
    }
}
