package com.aresstack.winproxy;

/**
 * Selects the proxy resolution strategy.
 */
public enum ProxyMode {
    /**
     * Resolve PAC/WPAD URL, load the PAC script, and evaluate it in Java.
     */
    PAC_URL,

    /**
     * Ask Windows through PowerShell/.NET for the final proxy.
     */
    WINDOWS_PAC,

    /**
     * Use static Windows registry proxy settings.
     */
    REGISTRY,

    /**
     * Use manually configured proxy host and port.
     */
    MANUAL,

    /**
     * Do not use a proxy.
     */
    DISABLED
}
