package com.aresstack.winproxy;

/**
 * A {@link PacScriptLoader} that also reports the HTTP caching information of the response
 * and can revalidate a previously downloaded script ({@code If-None-Match},
 * {@code If-Modified-Since}).
 */
interface ConditionalPacScriptLoader extends PacScriptLoader {

    /**
     * @param pacUrl   PAC URL
     * @param previous script previously downloaded from the same URL, or {@code null}
     * @return the script; when the server answered 304, the previous content with
     *         {@link PacScript#isNotModified()} set
     */
    PacScript load(String pacUrl, PacScript previous);
}
