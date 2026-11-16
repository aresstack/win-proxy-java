package com.aresstack.winproxy;

/**
 * A downloaded PAC script with the HTTP caching information of its response.
 */
final class PacScript {

    /** No lifetime announced by the server. */
    static final long NO_MAX_AGE = -1L;

    private final String content;
    private final long maxAgeMillis;
    private final String etag;
    private final String lastModified;
    private final boolean notModified;

    PacScript(String content, long maxAgeMillis, String etag, String lastModified, boolean notModified) {
        this.content = content == null ? "" : content;
        this.maxAgeMillis = maxAgeMillis;
        this.etag = etag;
        this.lastModified = lastModified;
        this.notModified = notModified;
    }

    static PacScript of(String content) {
        return new PacScript(content, NO_MAX_AGE, null, null, false);
    }

    String getContent() {
        return content;
    }

    /** Lifetime from {@code Cache-Control: max-age} or {@code Expires}, or {@link #NO_MAX_AGE}. */
    long getMaxAgeMillis() {
        return maxAgeMillis;
    }

    String getEtag() {
        return etag;
    }

    String getLastModified() {
        return lastModified;
    }

    /** {@code true} when the server answered 304 and {@link #getContent()} is the previous script. */
    boolean isNotModified() {
        return notModified;
    }
}
