package com.aresstack.winproxy;

/**
 * Legacy PAC URL source selection.
 *
 * @deprecated Use {@link ProxyMode} and {@link ProxyConfiguration} instead.
 */
@Deprecated
public enum PacUrlSource {
    /**
     * Do not discover a PAC URL.
     */
    DIRECT,

    /**
     * Discover the PAC URL from the Windows registry.
     */
    REGISTRY,

    /**
     * Discover the PAC URL with PowerShell.
     */
    POWERSHELL
}
