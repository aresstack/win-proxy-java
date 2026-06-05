package com.aresstack.winproxy;

/**
 * Result of PAC/WPAD URL discovery.
 */
public final class PacUrlResolution {

    private final String pacUrl;
    private final String source;

    private PacUrlResolution(String pacUrl, String source) {
        this.pacUrl = pacUrl;
        this.source = source;
    }

    public static PacUrlResolution found(String pacUrl, String source) {
        return new PacUrlResolution(pacUrl, source);
    }

    public static PacUrlResolution notFound() {
        return new PacUrlResolution(null, null);
    }

    public boolean isPresent() {
        return pacUrl != null && pacUrl.trim().length() > 0;
    }

    public String getPacUrl() {
        return pacUrl;
    }

    public String getSource() {
        return source;
    }
}
