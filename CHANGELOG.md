# Changelog

All notable changes to `win-proxy-java` are documented here. Versions follow
[Semantic Versioning](https://semver.org/); the artifact targets Java 8.

## 0.3.0

### Fixed

- **The PAC script is no longer downloaded on every `resolve()`.** In the `PAC_URL_*` modes
  `WindowsProxyResolver` ran the PAC URL discovery (PowerShell/WScript process or registry
  read) and downloaded the PAC file for each call. Callers resolving many URLs hit the WPAD
  server constantly; once it throttled or timed out, routes that had worked failed with
  `pac-download-failed`.

### Added

- **PAC cache with lifetime.** Each resolver remembers PAC URL and script and reloads them
  lazily during a resolution (no timer, no polling): after the lifetime the PAC server announces
  (`Cache-Control: max-age`, `no-cache`/`no-store`, `Expires`), clamped to 5 min … 24 h, or after
  12 h (like Chromium) when it announces none. Reloads revalidate with `If-None-Match` /
  `If-Modified-Since`; an HTTP 304 keeps the script without downloading it again.
- **Last good script on failure.** If a reload fails (discovery, download, evaluation, also for a
  newly discovered PAC URL), the last good PAC URL and script stay in use and the next attempt
  waits 1 min, doubling up to 1 h. While one resolution reloads, others keep using the last good
  script instead of waiting. A script is only remembered after it was evaluated successfully;
  a load that started before an invalidation cannot undo it. A discovery that finds no PAC URL
  at all forgets the script.
- **`WindowsProxyResolver.invalidatePacCache()`** — reload at the next `resolve()`, e.g. after a
  network change or when a connection over a resolved proxy failed (Windows/WinHTTP reloads on
  an IP address change).
- **`WindowsProxyResolver.pacCacheStatus()` / `PacCacheStatus`** — when the script was loaded,
  until when it is valid, and since when refreshing fails (`isStale()`, `describe()`), for a
  status line or log.
- `diagnose(url)` still discovers and downloads afresh and appends the cache state; a successful
  diagnosis refreshes the remembered script.

## 0.2.0 — first non-beta release

### Added

- **`ProxyMode.PAC_URL_WSCRIPT`** — PAC URL discovery through a VBScript run by the Windows
  Script Host (`cscript.exe //NoLogo //T:20`), backed by the new `WScriptPacUrlResolver`.
  The script only delivers the PAC URL; download and `FindProxyForURL` evaluation are the
  same shared pipeline as every other `PAC_URL_*` mode. Compatibility mode for workstations
  where `powershell.exe` is blocked but `cscript.exe` is not. The default script
  (`ProxyDefaults.DEFAULT_PAC_URL_DISCOVERY_WSCRIPT`) probes `AutoConfigURL` in the same
  GPO-first hive order as the `reg.exe` discovery. The temporary `.vbs` has a unique name
  and is deleted right after the run.
- **`ProxyDiagnostics`** and **`WindowsProxyResolver.diagnose(url)`** /
  **`PacUrlProxyResolver.diagnose(url)`** — the same resolution as `resolve(url)`, plus a
  step-by-step record: discovered PAC URL and its source, PAC script size, evaluation
  result, duration and the underlying failure message. `describe()` renders a report for
  a "resolve test" button or a log.
- **`ProxyResult.getDetail()`** — ERROR and NOT_IMPLEMENTED results now carry the message
  chain of the underlying exception (e.g. the GraalJS `PolyglotException` behind
  `pac-evaluation-failed`, such as `No language for id regex found`). `toString()` appends
  it (`ERROR (pac-evaluation-failed): ...`). New factories `ProxyResult.error(reason, detail)`
  and `ProxyResult.notImplemented(reason, detail)`.
- **`ProxyConfiguration.validate()` / `validationProblems()` / `isValid()`** and
  **`ProxyConfigurationException`** — report incomplete settings (`MANUAL_PROXY` without
  host/port, `PAC_URL_MANUAL` without a valid URL) and not-implemented modes before any
  process is spawned or any network access happens.
- **`ProxyDefaults.defaultPacUrlDiscoveryScript(mode)`** — the discovery script default now
  follows the mode (VBScript for `PAC_URL_WSCRIPT`, the PowerShell one-liner otherwise).
- GitHub Actions CI (`mvn verify` on JDK 8 and 21) for pushes and pull requests.

### Changed

- `WINDOWS_NATIVE_PROXY_SETTINGS` and `WINDOWS_NATIVE_ROUTE_RESOLVER` remain reserved and
  **not implemented**. `resolve()` still returns `NOT_IMPLEMENTED` (never DIRECT), now with
  a detail text, and `ProxyConfiguration.validate()` rejects both modes so a UI can tell
  the user up front. They are kept so persisted configurations keep parsing.
- `POWERSHELL_ROUTE_RESOLVER_LEGACY` stays deprecated; its failure result now carries the
  underlying message as detail.
- Enum order: `PAC_URL_WSCRIPT` is appended at the end of `ProxyMode`, so the ordinals of
  all existing constants are unchanged.

### Compatibility

- Source and binary compatible with 0.1.0-beta.4 for all existing public API. Code that
  switches over `ProxyMode` without a `default` branch should add the new constant.
- Java 8 source/target, GraalJS 21.2.0 unchanged.

## 0.1.0-beta.4

- Last beta. Inline PowerShell `-Command` discovery, `reg.exe` based
  `PAC_URL_WINDOWS_SETTINGS`, hardened PAC route parsing (no masked DIRECT),
  `ProxyResult.toJavaProxy()` / `toJavaProxyOrNoProxy()`, fat-jar `regex` provider
  workaround documented.
