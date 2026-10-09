# Changelog

All notable changes to `win-proxy-java` are documented here. Versions follow
[Semantic Versioning](https://semver.org/); the artifact targets Java 8.

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
