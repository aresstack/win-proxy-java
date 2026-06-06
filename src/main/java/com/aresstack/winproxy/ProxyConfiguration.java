package com.aresstack.winproxy;

/**
 * Immutable configuration for proxy resolution.
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
        this.mode = builder.mode == null ? ProxyMode.PAC_URL : builder.mode;
        this.testUrl = defaultIfBlank(builder.testUrl, ProxyDefaults.DEFAULT_TEST_URL);
        this.pacUrl = trimToNull(builder.pacUrl);
        this.pacUrlDiscoveryScript = defaultIfBlank(builder.pacUrlDiscoveryScript,
                ProxyDefaults.DEFAULT_PAC_URL_DISCOVERY_SCRIPT);
        this.windowsPacScript = defaultIfBlank(builder.windowsPacScript, ProxyDefaults.DEFAULT_WINDOWS_PAC_SCRIPT);
        this.manualProxyHost = trimToNull(builder.manualProxyHost);
        this.manualProxyPort = builder.manualProxyPort;
        this.debugEnabled = builder.debugEnabled;
    }

    /**
     * Create default configuration.
     *
     * @return default configuration
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
