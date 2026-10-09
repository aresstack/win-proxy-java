package com.aresstack.winproxy;

/**
 * Selects the proxy resolution strategy.
 * <p>
 * <b>Central axis:</b> {@code PAC_URL_*} modes only differ in how the PAC URL is
 * discovered. All {@code PAC_URL_*} modes then download the PAC file and evaluate
 * {@code FindProxyForURL} through the JavaScript (GraalVM) PAC evaluator. The
 * discovery step never determines the final route by itself.
 * <p>
 * The modes are cut along clear functional lines. In particular, the way the
 * <em>PAC URL</em> is discovered (PowerShell vs. Windows Script Host vs. Windows
 * settings vs. manual) is an explicit choice and is never silently mixed with
 * static-proxy or registry reading. No {@code PAC_URL_*} mode ever falls back to
 * {@link #WINDOWS_STATIC_PROXY}, {@link #MANUAL_PROXY} or {@link #DISABLED}.
 * <p>
 * Modes that are not implemented in this version return a {@code NOT_IMPLEMENTED}
 * {@link ProxyResult} and are rejected by {@link ProxyConfiguration#validate()};
 * they never silently become DIRECT.
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
     * <p>
     * Pipeline: {@code configured pacUrl -> download -> GraalVM FindProxyForURL
     * -> parse}. It performs <em>no</em> automatic discovery:
     * <ul>
     *   <li>No PowerShell.</li>
     *   <li>No {@link WindowsPacUrlResolver} (no {@code reg.exe}/registry).</li>
     *   <li>No {@link StaticProxySettingsResolver}.</li>
     *   <li>No static-proxy or DIRECT fallback — a missing/blank PAC URL is an ERROR.</li>
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
     * Reserved future mode that will read proxy settings / {@code AutoConfigURL} /
     * auto-detect through native Windows APIs and then use the Java/GraalVM PAC
     * evaluation.
     * <p>
     * <b>Not implemented as of 0.2.0.</b> {@link WindowsProxyResolver#resolve(String)}
     * returns a {@code NOT_IMPLEMENTED} result (never DIRECT, never a fallback) and
     * {@link ProxyConfiguration#validate()} rejects the mode, so a UI can tell the
     * user up front instead of failing at request time.
     */
    WINDOWS_NATIVE_PROXY_SETTINGS,

    /**
     * Reserved future mode that will determine the final route for a concrete URL
     * through WinHTTP / native Windows APIs (the native successor of
     * {@link #POWERSHELL_ROUTE_RESOLVER_LEGACY}).
     * <p>
     * <b>Not implemented as of 0.2.0.</b> {@link WindowsProxyResolver#resolve(String)}
     * returns a {@code NOT_IMPLEMENTED} result (never DIRECT, never a fallback) and
     * {@link ProxyConfiguration#validate()} rejects the mode.
     */
    WINDOWS_NATIVE_ROUTE_RESOLVER,

    /**
     * Discovers the PAC URL through a VBScript executed by the Windows Script Host
     * console runner ({@code cscript.exe}). The script is taken from
     * {@link ProxyConfiguration#getPacUrlDiscoveryScript()}, which in this mode holds
     * VBScript (default: {@link ProxyDefaults#DEFAULT_PAC_URL_DISCOVERY_WSCRIPT}), and
     * must print the PAC URL on a line of its own; empty output means "no PAC URL
     * configured". Afterwards the PAC file is downloaded and evaluated via
     * GraalVM/JavaScript exactly like every other {@code PAC_URL_*} mode — the script
     * only delivers the PAC URL, never the final route.
     * <p>
     * Compatibility mode for workstations where {@code powershell.exe} is blocked but
     * {@code cscript.exe} is still allowed. Unlike PowerShell, {@code cscript.exe} has no
     * inline command mode, so the script is written to a uniquely named temporary
     * {@code .vbs} file for the duration of the call; AppLocker/WDAC script rules may
     * block that. Microsoft has deprecated VBScript (a removable feature on demand since
     * Windows 11 24H2), so prefer {@link #PAC_URL_WINDOWS_SETTINGS} where it works.
     *
     * @since 0.2.0
     */
    PAC_URL_WSCRIPT
}
