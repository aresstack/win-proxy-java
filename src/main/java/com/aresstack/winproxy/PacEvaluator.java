package com.aresstack.winproxy;

/**
 * Evaluates a PAC script for one target URL.
 */
public interface PacEvaluator {

    /**
     * Evaluate the PAC script for the given target URL.
     *
     * @param pacScript PAC JavaScript content
     * @param targetUrl target URL
     * @return proxy result
     */
    ProxyResult evaluate(String pacScript, String targetUrl);

    /**
     * Evaluate a PAC URL for the given target URL.
     *
     * @param pacUrl URL of the PAC script
     * @param targetUrl target URL
     * @return proxy result
     * @deprecated Use {@link PacScriptLoader} together with {@link #evaluate(String, String)}.
     */
    @Deprecated
    ProxyResult evaluateFromPacUrl(String pacUrl, String targetUrl);

    /**
     * Create the default GraalJS based evaluator.
     *
     * @return default evaluator
     */
    static PacEvaluator createDefault() {
        return new GraalPacScriptEvaluator(new ProxyResultParser());
    }
}
