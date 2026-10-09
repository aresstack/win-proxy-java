package com.aresstack.winproxy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Step-by-step record of one proxy resolution, produced by
 * {@link WindowsProxyResolver#diagnose(String)} and {@link PacUrlProxyResolver#diagnose(String)}.
 * <p>
 * It answers the questions a "resolve test" in a settings UI needs to answer without
 * guessing: was a PAC URL discovered (and from where), was the PAC file reachable (and how
 * big is it), what did {@code FindProxyForURL} return, and — when something failed — the
 * underlying message (for example the GraalJS {@code PolyglotException} behind a
 * {@code pac-evaluation-failed}). {@link #getResult()} is exactly what
 * {@link WindowsProxyResolver#resolve(String)} would have returned.
 *
 * @since 0.2.0
 */
public final class ProxyDiagnostics {

    private final ProxyMode mode;
    private final String targetUrl;
    private final String pacUrl;
    private final String pacUrlSource;
    private final int pacScriptLength;
    private final ProxyResult result;
    private final String failureDetail;
    private final List<String> steps;
    private final long durationMillis;

    private ProxyDiagnostics(Builder builder) {
        this.mode = builder.mode;
        this.targetUrl = builder.targetUrl;
        this.pacUrl = builder.pacUrl;
        this.pacUrlSource = builder.pacUrlSource;
        this.pacScriptLength = builder.pacScriptLength;
        this.result = builder.result;
        this.failureDetail = builder.failureDetail;
        this.steps = Collections.unmodifiableList(new ArrayList<String>(builder.steps));
        this.durationMillis = builder.durationMillis;
    }

    static Builder builder(ProxyMode mode, String targetUrl) {
        return new Builder(mode, targetUrl);
    }

    /** @return the mode that was resolved, or {@code null} for a standalone PAC pipeline run */
    public ProxyMode getMode() {
        return mode;
    }

    /** @return the target URL the route was resolved for */
    public String getTargetUrl() {
        return targetUrl;
    }

    /** @return the discovered/configured PAC URL, or {@code null} if none was found or the mode uses no PAC */
    public String getPacUrl() {
        return pacUrl;
    }

    /** @return where the PAC URL came from (e.g. {@code powershell}, {@code wscript}, {@code registry:AutoConfigURL}, {@code configuration}), or {@code null} */
    public String getPacUrlSource() {
        return pacUrlSource;
    }

    /** @return the length of the downloaded PAC script in characters, or {@code -1} if it was not downloaded */
    public int getPacScriptLength() {
        return pacScriptLength;
    }

    /** @return the final result — identical to what {@code resolve(targetUrl)} returns; never {@code null} */
    public ProxyResult getResult() {
        return result;
    }

    /** @return the underlying failure message for an ERROR / NOT_IMPLEMENTED result, or {@code null} */
    public String getFailureDetail() {
        return failureDetail;
    }

    /** @return human-readable steps in execution order; never {@code null} */
    public List<String> getSteps() {
        return steps;
    }

    /** @return wall-clock duration of the resolution in milliseconds */
    public long getDurationMillis() {
        return durationMillis;
    }

    /**
     * Multi-line, human-readable report: mode, target URL, every step and the final result.
     *
     * @return the report
     */
    public String describe() {
        StringBuilder builder = new StringBuilder();
        builder.append("Mode: ").append(mode == null ? "(PAC pipeline)" : mode.name()).append('\n');
        builder.append("Target URL: ").append(targetUrl).append('\n');
        for (int i = 0; i < steps.size(); i++) {
            builder.append("- ").append(steps.get(i)).append('\n');
        }
        builder.append("Result: ").append(result).append(" [").append(durationMillis).append(" ms]");
        return builder.toString();
    }

    @Override
    public String toString() {
        return describe();
    }

    /** Collects the steps of one resolution. */
    static final class Builder {
        private final ProxyMode mode;
        private final String targetUrl;
        private final List<String> steps = new ArrayList<String>();
        private final long startNanos = System.nanoTime();
        private String pacUrl;
        private String pacUrlSource;
        private int pacScriptLength = -1;
        private ProxyResult result;
        private String failureDetail;
        private long durationMillis;

        private Builder(ProxyMode mode, String targetUrl) {
            this.mode = mode;
            this.targetUrl = targetUrl;
        }

        Builder step(String step) {
            steps.add(step);
            return this;
        }

        Builder pacUrl(String pacUrl, String pacUrlSource) {
            this.pacUrl = pacUrl;
            this.pacUrlSource = pacUrlSource;
            return this;
        }

        Builder pacScriptLength(int pacScriptLength) {
            this.pacScriptLength = pacScriptLength;
            return this;
        }

        Builder failureDetail(String failureDetail) {
            this.failureDetail = failureDetail;
            return this;
        }

        /** Records the final result and finishes the report. */
        ProxyDiagnostics finish(ProxyResult result) {
            this.result = result;
            if (failureDetail == null && (result.isError() || result.isNotImplemented())) {
                failureDetail = result.getDetail() != null ? result.getDetail() : result.getReason();
            }
            this.durationMillis = (System.nanoTime() - startNanos) / 1000000L;
            return new ProxyDiagnostics(this);
        }
    }
}
