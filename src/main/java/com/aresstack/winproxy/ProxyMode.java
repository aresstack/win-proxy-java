package com.aresstack.winproxy;

/**
 * Selects the proxy resolution strategy.
 * <p>
 * The modes are cut along clear functional lines. In particular, the way the
 * <em>PAC URL</em> is discovered (PowerShell vs. Windows settings vs. manual)
 * is an explicit choice and is never silently mixed with static-proxy or
 * registry reading. No {@code PAC_URL_*} mode ever falls back to
 * {@link #WINDOWS_STATIC_PROXY}, {@link #MANUAL_PROXY} or {@link #DISABLED}.
 */
public enum ProxyMode {

    /**
     * Forces DIRECT.
     * <ul>
     *   <li>No Windows discovery.</li>
     *   <li>No PAC evaluation.</li>
     *   <li>No fallback.</li>
     * </ul>
     */
    DISABLED,

    /**
     * Uses a manually configured proxy {@code host:port}.
     * <ul>
     *   <li>No PAC URL.</li>
     *   <li>No Windows discovery.</li>
     *   <li>No PAC evaluation.</li>
     * </ul>
     */
    MANUAL_PROXY,

    /**
     * Reads the classic static Windows proxy from
     * {@code ProxyEnable} / {@code ProxyServer} / {@code ProxyOverride}.
     * <p>
     * This is the former {@code REGISTRY} mode. It is no PAC mode, reads no
     * {@code AutoConfigURL}, and never determines a route via PowerShell. It is
     * only ever used when explicitly selected and must not be a silent fallback
     * of any PAC mode.
     */
    WINDOWS_STATIC_PROXY,

    /**
     * Uses a PAC URL that the user configured explicitly, downloads the PAC
     * file and evaluates {@code FindProxyForURL} via GraalVM/JavaScript.
     * <ul>
     *   <li>No PowerShell.</li>
     *   <li>No registry fallback.</li>
     *   <li>No static-proxy fallback.</li>
     * </ul>
     */
    PAC_URL_MANUAL,

    /**
     * Discovers the PAC URL <em>exclusively</em> through PowerShell
     * (the {@code AutoConfigURL} one-liner, executed inline via {@code -Command}).
     * PowerShell may only deliver the PAC URL here, never the final route.
     * Afterwards the PAC file is downloaded and evaluated via GraalVM/JavaScript.
     * <p>
     * This is the important test path on hardened machines.
     */
    PAC_URL_POWERSHELL,

    /**
     * Discovers the PAC URL from Windows settings <em>without</em> PowerShell,
     * via {@link WindowsPacUrlResolver} ({@code reg.exe} across all relevant
     * hives/policies, the {@code DefaultConnectionSettings} blob and the
     * WPAD auto-detect flag). Afterwards the PAC file is downloaded and
     * evaluated via GraalVM/JavaScript.
     */
    PAC_URL_WINDOWS_SETTINGS,

    /**
     * Legacy mode: runs a PowerShell/.NET script that uses
     * {@code System.Net.WebRequest.GetSystemWebProxy()} to determine the final
     * route directly. No GraalVM, no own PAC evaluation. Kept only for
     * diagnostics/backward compatibility and not offered in normal UIs.
     *
     * @deprecated Use a {@code PAC_URL_*} mode (GraalVM PAC evaluation) instead.
     */
    @Deprecated
    POWERSHELL_ROUTE_RESOLVER_LEGACY,

    /**
     * Reserved future Java 21 / FFM mode that will read proxy settings /
     * {@code AutoConfigURL} / auto-detect through native Windows APIs and then
     * use the Java/GraalVM PAC evaluation. Currently returns a
     * {@code NOT_IMPLEMENTED} result — never DIRECT or a fallback.
     */
    WINDOWS_NATIVE_PROXY_SETTINGS,

    /**
     * Reserved future Java 21 / FFM mode that will determine the final route for
     * a concrete URL through WinHTTP / native Windows APIs (the native successor
     * of {@link #POWERSHELL_ROUTE_RESOLVER_LEGACY}). Currently returns a
     * {@code NOT_IMPLEMENTED} result — never DIRECT or a fallback.
     */
    WINDOWS_NATIVE_ROUTE_RESOLVER
}
