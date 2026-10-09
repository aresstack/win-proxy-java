package com.aresstack.winproxy;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable configuration for proxy resolution.
 * <p>
 * {@link #validate()} / {@link #validationProblems()} check whether the configuration
 * is complete for its {@link ProxyMode} <em>before</em> any process is spawned or any
 * network access happens, so UIs can report incomplete settings up front.
 */
public final class ProxyConfiguration {

    private final ProxyMode mode;
    private final String testUrl;
    private final String pacUrl;
    private final String pacUrlDiscoveryScript;
    private final String windowsPacScript;
    private final String manualProxyHost;
    private final int manualProxyPort;
    private final boolean debugEnabled;

    private ProxyConfiguration(Builder builder) {
        this.mode = builder.mode == null ? ProxyMode.PAC_URL_POWERSHELL : builder.mode;
        this.testUrl = defaultIfBlank(builder.testUrl, ProxyDefaults.DEFAULT_TEST_URL);
        this.pacUrl = trimToNull(builder.pacUrl);
        this.pacUrlDiscoveryScript = defaultIfBlank(builder.pacUrlDiscoveryScript,
                ProxyDefaults.defaultPacUrlDiscoveryScript(this.mode));
        this.windowsPacScript = defaultIfBlank(builder.windowsPacScript, ProxyDefaults.DEFAULT_WINDOWS_PAC_SCRIPT);
        this.manualProxyHost = trimToNull(builder.manualProxyHost);
        this.manualProxyPort = builder.manualProxyPort;
        this.debugEnabled = builder.debugEnabled;
    }

    /**
     * Create default configuration.
     * <p>
     * <b>Important — the default mode is {@link ProxyMode#PAC_URL_POWERSHELL}.</b>
     * This is a deliberate choice: it mirrors the proven user path on managed,
     * hardened Windows machines, where the PAC URL ({@code AutoConfigURL}) is
     * discovered by an inline {@code powershell.exe -Command} one-liner. As a
     * consequence, calling {@link WindowsProxyResolver#resolve(String)} on the
     * default configuration <em>will spawn {@code powershell.exe}</em> for the
     * discovery step (PowerShell only delivers the PAC URL, never the final
     * route). Callers that must not start PowerShell should select an explicit
     * mode such as {@link ProxyMode#DISABLED},
     * {@link ProxyMode#PAC_URL_WINDOWS_SETTINGS} or {@link ProxyMode#PAC_URL_MANUAL}
     * via {@link Builder#mode(ProxyMode)}.
     *
     * @return default configuration ({@link ProxyMode#PAC_URL_POWERSHELL})
     */
    public static ProxyConfiguration defaults() {
        return builder().build();
    }

    /**
     * Create a configuration builder.
     *
     * @return builder
     */
    public static Builder builder() {
        return new Builder();
    }

    public ProxyMode getMode() {
        return mode;
    }

    public String getTestUrl() {
        return testUrl;
    }

    public String getPacUrl() {
        return pacUrl;
    }

    /**
     * Returns the PAC URL discovery script. Its language depends on the mode: PowerShell for
     * {@link ProxyMode#PAC_URL_POWERSHELL} (default {@link ProxyDefaults#DEFAULT_PAC_URL_DISCOVERY_SCRIPT}),
     * VBScript for {@link ProxyMode#PAC_URL_WSCRIPT} (default
     * {@link ProxyDefaults#DEFAULT_PAC_URL_DISCOVERY_WSCRIPT}). Other modes ignore it.
     *
     * @return the discovery script, never blank
     */
    public String getPacUrlDiscoveryScript() {
        return pacUrlDiscoveryScript;
    }

    public String getWindowsPacScript() {
        return windowsPacScript;
    }

    public String getManualProxyHost() {
        return manualProxyHost;
    }

    public int getManualProxyPort() {
        return manualProxyPort;
    }

    public boolean isDebugEnabled() {
        return debugEnabled;
    }

    /**
     * Lists everything that makes this configuration unusable for its mode, without
     * spawning a process or touching the network:
     * <ul>
     *   <li>{@link ProxyMode#MANUAL_PROXY} needs a host and a port in {@code 1..65535},</li>
     *   <li>{@link ProxyMode#PAC_URL_MANUAL} needs a syntactically valid PAC URL,</li>
     *   <li>{@link ProxyMode#WINDOWS_NATIVE_PROXY_SETTINGS} and
     *       {@link ProxyMode#WINDOWS_NATIVE_ROUTE_RESOLVER} are not implemented in this version.</li>
     * </ul>
     *
     * @return the problems found, empty when the configuration is usable; never {@code null}
     * @since 0.2.0
     */
    public List<String> validationProblems() {
        List<String> problems = new ArrayList<String>();
        switch (mode) {
            case MANUAL_PROXY:
                if (manualProxyHost == null) {
                    problems.add("MANUAL_PROXY requires manualProxyHost.");
                }
                if (manualProxyPort < 1 || manualProxyPort > 65535) {
                    problems.add("MANUAL_PROXY requires manualProxyPort in 1..65535 (was " + manualProxyPort + ").");
                }
                break;
            case PAC_URL_MANUAL:
                if (pacUrl == null) {
                    problems.add("PAC_URL_MANUAL requires pacUrl (the PAC/WPAD URL).");
                } else {
                    try {
                        new URL(pacUrl);
                    } catch (MalformedURLException e) {
                        problems.add("PAC_URL_MANUAL pacUrl is not a valid URL: " + pacUrl);
                    }
                }
                break;
            case WINDOWS_NATIVE_PROXY_SETTINGS:
            case WINDOWS_NATIVE_ROUTE_RESOLVER:
                problems.add(mode + " is not implemented in this version of win-proxy-java"
                        + " (resolve() returns NOT_IMPLEMENTED); choose another mode.");
                break;
            default:
                break;
        }
        return Collections.unmodifiableList(problems);
    }

    /**
     * Returns {@code true} when {@link #validationProblems()} is empty.
     *
     * @return whether the configuration is usable for its mode
     * @since 0.2.0
     */
    public boolean isValid() {
        return validationProblems().isEmpty();
    }

    /**
     * Throws when the configuration is unusable for its mode; see {@link #validationProblems()}.
     *
     * @throws ProxyConfigurationException listing every problem found, one per line
     * @since 0.2.0
     */
    public void validate() {
        List<String> problems = validationProblems();
        if (problems.isEmpty()) {
            return;
        }
        StringBuilder message = new StringBuilder();
        for (int i = 0; i < problems.size(); i++) {
            if (i > 0) {
                message.append('\n');
            }
            message.append(problems.get(i));
        }
        throw new ProxyConfigurationException(message.toString());
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        String trimmed = trimToNull(value);
        return trimmed == null ? defaultValue : trimmed;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() == 0 ? null : trimmed;
    }

    /**
     * Builder for {@link ProxyConfiguration}.
     */
    public static final class Builder {
        private ProxyMode mode;
        private String testUrl;
        private String pacUrl;
        private String pacUrlDiscoveryScript;
        private String windowsPacScript;
        private String manualProxyHost;
        private int manualProxyPort;
        private boolean debugEnabled;

        private Builder() {
        }

        public Builder mode(ProxyMode mode) {
            this.mode = mode;
            return this;
        }

        public Builder testUrl(String testUrl) {
            this.testUrl = testUrl;
            return this;
        }

        public Builder pacUrl(String pacUrl) {
            this.pacUrl = pacUrl;
            return this;
        }

        /**
         * Sets the PAC URL discovery script: PowerShell for {@link ProxyMode#PAC_URL_POWERSHELL},
         * VBScript for {@link ProxyMode#PAC_URL_WSCRIPT}. Blank selects the mode's default.
         *
         * @param pacUrlDiscoveryScript the script
         * @return this builder
         */
        public Builder pacUrlDiscoveryScript(String pacUrlDiscoveryScript) {
            this.pacUrlDiscoveryScript = pacUrlDiscoveryScript;
            return this;
        }

        public Builder windowsPacScript(String windowsPacScript) {
            this.windowsPacScript = windowsPacScript;
            return this;
        }

        public Builder manualProxyHost(String manualProxyHost) {
            this.manualProxyHost = manualProxyHost;
            return this;
        }

        public Builder manualProxyPort(int manualProxyPort) {
            this.manualProxyPort = manualProxyPort;
            return this;
        }

        public Builder debugEnabled(boolean debugEnabled) {
            this.debugEnabled = debugEnabled;
            return this;
        }

        public ProxyConfiguration build() {
            return new ProxyConfiguration(this);
        }
    }
}
