package com.aresstack.winproxy;

/**
 * Signals that proxy resolution failed unexpectedly.
 */
public final class ProxyResolutionException extends RuntimeException {

    public ProxyResolutionException(String message) {
        super(message);
    }

    public ProxyResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
