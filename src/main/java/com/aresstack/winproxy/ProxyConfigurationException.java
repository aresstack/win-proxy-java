package com.aresstack.winproxy;

/**
 * Signals that a {@link ProxyConfiguration} cannot be used for resolution as configured,
 * e.g. {@link ProxyMode#MANUAL_PROXY} without a host, {@link ProxyMode#PAC_URL_MANUAL}
 * without a PAC URL, or a mode that is not implemented in this version.
 * The message lists every problem found, one per line.
 *
 * @see ProxyConfiguration#validate()
 * @since 0.2.0
 */
public final class ProxyConfigurationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ProxyConfigurationException(String message) {
        super(message);
    }
}
