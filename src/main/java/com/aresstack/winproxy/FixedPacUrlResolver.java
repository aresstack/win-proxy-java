package com.aresstack.winproxy;

/**
 * Returns a configured PAC URL.
 */
public final class FixedPacUrlResolver implements PacUrlResolver {

    private final String pacUrl;

    public FixedPacUrlResolver(String pacUrl) {
        this.pacUrl = pacUrl;
    }

    public PacUrlResolution resolve() {
        if (pacUrl == null || pacUrl.trim().length() == 0) {
            return PacUrlResolution.notFound();
        }
        return PacUrlResolution.found(pacUrl.trim(), "configuration");
    }
}
