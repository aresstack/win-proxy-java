# win-proxy-java

[![Maven Central](https://img.shields.io/maven-central/v/com.aresstack/win-proxy-java.svg)](https://central.sonatype.com/artifact/com.aresstack/win-proxy-java)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

`win-proxy-java` is a lightweight Java 8 library for resolving Windows proxy settings in
corporate workstation environments. It is designed for tools that must run behind managed
Windows proxy infrastructure without forcing every application to duplicate PowerShell,
registry, WPAD, and PAC handling.

The default resolution mode is **PAC_URL_POWERSHELL**. This mirrors the most common managed
Windows setup: Windows stores the address of a PAC/WPAD file, the library discovers that
address (by default via an inline PowerShell `-Command` one-liner), downloads the file, and
evaluates `FindProxyForURL(url, host)` locally. `PAC_URL_WINDOWS_SETTINGS` is the explicit
alternative that discovers the same PAC URL through `reg.exe`/Windows settings without ever
starting PowerShell.

## Installation

```xml
<dependency>
  <groupId>com.aresstack</groupId>
  <artifactId>win-proxy-java</artifactId>
  <version>0.1.0-beta.4</version>
</dependency>
```

Gradle:

```groovy
implementation 'com.aresstack:win-proxy-java:0.1.0-beta.4'
```

## Why this library exists

Many enterprise Windows workstations do not use a single static proxy. Instead, they use
one of these configurations:

- a PAC file configured in Windows Internet Settings,
- WPAD auto-discovery,
- a static registry proxy,
- or a locked-down PowerShell/.NET proxy path that already knows how to evaluate Windows
  proxy rules.

Java applications often need the final proxy for a concrete target URL, for example Maven
Central, Gradle Plugin Portal, an internal artifact repository, or an update endpoint.
`win-proxy-java` provides this as a small reusable library.

## Default architecture

Proxy resolution is intentionally split into three independent stages:

```text
Target URL
   |
   v
1. Resolve PAC URL from Windows
   |
   v
2. Load PAC/WPAD script
   |
   v
3. Evaluate FindProxyForURL(url, host) with GraalJS
   |
   v
ProxyResult
```

This keeps Windows integration, IO, and JavaScript evaluation separated and testable.

### Stage 1: Resolve the PAC URL from Windows

Stage 1 discovers the Windows PAC URL. The default mode `PAC_URL_POWERSHELL` reads the same
value commonly checked with PowerShell, inline via `powershell.exe -Command` (never a
temporary `.ps1`):

```powershell
(Get-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings').AutoConfigURL
```

The explicit alternative `PAC_URL_WINDOWS_SETTINGS` performs the same discovery through
`reg.exe`/Windows settings without starting PowerShell. It checks:

1. `AutoConfigURL` from user and policy hives,
2. binary connection settings that can contain auto-config metadata,
3. WPAD auto-detection flags,
4. `http://wpad/wpad.dat` as the conventional WPAD endpoint when auto-detection is enabled.

`PAC_URL_MANUAL` skips discovery entirely and uses only the PAC URL configured by the caller.
Whichever discovery strategy is selected, stages 2 and 3 are identical.

### Stage 2: Load the PAC/WPAD script

Once a PAC URL is known, the script is downloaded by `PacScriptLoader`.

The default implementation uses `URLConnection` with `Proxy.NO_PROXY` and bounded connect/read
timeouts to avoid recursive proxy resolution while the PAC file itself is being loaded.

### Stage 3: Evaluate the PAC script with GraalJS

The PAC content is evaluated by `GraalPacScriptEvaluator`. It calls:

```javascript
FindProxyForURL(url, host)
```

The result is parsed into a `ProxyResult` by `PacProxyRouteParser`.

Supported PAC result forms include:

```text
DIRECT
PROXY proxy.example.com:8080
HTTPS proxy.example.com:8443
```

The route string is a `;`-separated preference list evaluated left to right; the first usable
entry wins. Unsupported entries (e.g. `SOCKS`) and malformed `host:port` values return an
`ERROR` `ProxyResult` (`unsupported-pac-entry`, `invalid-proxy-port`, `invalid-proxy-address`)
**unless** the PAC result contains an explicit `DIRECT` fallback later in the route list. In
other words, `DIRECT` is only returned when the PAC itself explicitly says `DIRECT` — it is
never silently synthesized from an unsupported or malformed entry.

## Quick start

```java
import com.aresstack.winproxy.ProxyResult;
import com.aresstack.winproxy.WindowsProxyResolver;

public final class ProxyExample {
    public static void main(String[] args) {
        WindowsProxyResolver resolver = new WindowsProxyResolver();

        ProxyResult proxy = resolver.resolve("https://plugins.gradle.org/m2/");

        if (proxy.isDirect()) {
            System.out.println("DIRECT");
            return;
        }

        System.out.println(proxy.getHost() + ":" + proxy.getPort());
    }
}
```

`resolve(url)` uses `ProxyMode.PAC_URL_POWERSHELL` by default.

## Explicit configuration

```java
import com.aresstack.winproxy.ProxyConfiguration;
import com.aresstack.winproxy.ProxyMode;
import com.aresstack.winproxy.ProxyResult;
import com.aresstack.winproxy.WindowsProxyResolver;

public final class ExplicitPacUrlExample {
    public static void main(String[] args) {
        ProxyConfiguration configuration = ProxyConfiguration.builder()
                .mode(ProxyMode.PAC_URL_POWERSHELL)
                .testUrl("https://plugins.gradle.org/m2/")
                .build();

        WindowsProxyResolver resolver = new WindowsProxyResolver(configuration);
        ProxyResult proxy = resolver.resolve(configuration.getTestUrl());

        System.out.println(proxy);
    }
}
```

## Proxy modes

Every `PAC_URL_*` mode runs the same pipeline — discover PAC URL → download PAC →
evaluate `FindProxyForURL` via GraalVM/JavaScript → parse the route. Only the PAC-URL
discovery differs. No `PAC_URL_*` mode ever falls back to a static/registry/manual/direct
result; failures surface as an `ERROR` `ProxyResult` with a technical reason.

| Mode | Purpose |
| --- | --- |
| `DISABLED` | Force DIRECT. No discovery, no PAC, no fallback. |
| `MANUAL_PROXY` | Use a manually configured proxy host:port. |
| `WINDOWS_STATIC_PROXY` | Read the classic static Windows proxy (`ProxyEnable`/`ProxyServer`/`ProxyOverride`). Explicit only — never a silent fallback. |
| `PAC_URL_MANUAL` | Use a user-configured PAC URL, then download + GraalVM PAC evaluation. |
| `PAC_URL_POWERSHELL` | Discover the PAC URL via PowerShell (`AutoConfigURL` one-liner, inline `-Command`), then download + GraalVM. **Important path on hardened machines.** |
| `PAC_URL_WINDOWS_SETTINGS` | Discover the PAC URL via `reg.exe`/Windows settings (all hives/policies, `DefaultConnectionSettings` blob, WPAD auto-detect flag) — no PowerShell — then download + GraalVM. |
| `POWERSHELL_ROUTE_RESOLVER_LEGACY` | **Deprecated.** Legacy `GetSystemWebProxy()` PowerShell/.NET route resolution (no GraalVM). Not for normal use. |
| `WINDOWS_NATIVE_PROXY_SETTINGS` | Reserved Java 21/FFM mode. Returns `NOT_IMPLEMENTED`. |
| `WINDOWS_NATIVE_ROUTE_RESOLVER` | Reserved Java 21/FFM mode. Returns `NOT_IMPLEMENTED`. |

### Result kinds

`ProxyResult` is exactly one of `PROXY`, `DIRECT`, `ERROR` or `NOT_IMPLEMENTED`
(`isProxy()` / `isDirect()` / `isError()` / `isNotImplemented()`). `DIRECT` is only returned
when the mode genuinely yields direct (e.g. `DISABLED`, or a PAC script returning `DIRECT`).
Discovery/download/evaluation failures return `ERROR` with reasons such as
`pac-url-not-found`, `pac-url-discovery-failed`, `pac-download-failed` or `pac-evaluation-failed`.

## PAC discovery on hardened machines

`PAC_URL_POWERSHELL` runs the discovery one-liner inline via
`powershell.exe -Command` (never a temporary `.ps1` in `%TEMP%`), so it keeps working where
GPO execution policy or AppLocker block unsigned script files. If PowerShell itself is locked
down, `PAC_URL_WINDOWS_SETTINGS` reads the PAC URL through `reg.exe` without any PowerShell.

```java
ProxyConfiguration configuration = ProxyConfiguration.builder()
        .mode(ProxyMode.PAC_URL_POWERSHELL)
        .build();

ProxyResult proxy = new WindowsProxyResolver(configuration)
        .resolve("https://repo.maven.apache.org/maven2/");
```

## Manual PAC URL

```java
ProxyConfiguration configuration = ProxyConfiguration.builder()
        .mode(ProxyMode.PAC_URL_MANUAL)
        .pacUrl("http://proxy.example.com/wpad.dat")
        .build();

ProxyResult proxy = new WindowsProxyResolver(configuration)
        .resolve("https://repo.maven.apache.org/maven2/");
```

## Default mode spawns PowerShell

`ProxyConfiguration.defaults()` deliberately selects `PAC_URL_POWERSHELL`, because that
mirrors the proven user path on managed, hardened Windows machines. As a consequence,
`new WindowsProxyResolver().resolve(url)` (or any resolver built from `defaults()`)
**will spawn `powershell.exe`** for the PAC-URL discovery step — PowerShell only delivers
the PAC URL, never the final route. Callers that must not start PowerShell should select an
explicit mode (`DISABLED`, `PAC_URL_WINDOWS_SETTINGS`, `PAC_URL_MANUAL`, …) via
`ProxyConfiguration.builder().mode(...)`.

## Sentinel test (proving the PAC pipeline really ran)

To verify end-to-end that a real PAC file was downloaded and evaluated — and that the
result is not a silently masked `DIRECT` — use a PAC file that returns a clearly bogus,
reserved-range sentinel proxy for a well-known URL. For `plugins.gradle.org` the test PAC
intentionally returns:

```text
PROXY 192.0.2.123:18080
```

`192.0.2.0/24` is the [RFC 5737](https://datatracker.ietf.org/doc/html/rfc5737)
documentation range and is never routable, so the value can only come from the PAC file.

```java
ProxyConfiguration configuration = ProxyConfiguration.builder()
        .mode(ProxyMode.PAC_URL_MANUAL)
        .pacUrl("file:///C:/path/to/sentinel.pac") // serves the PAC above
        .build();

ProxyResult proxy = new WindowsProxyResolver(configuration)
        .resolve("https://plugins.gradle.org/m2/");

// Expected: PROXY 192.0.2.123:18080
// If this comes back DIRECT, the PAC pipeline was NOT used correctly.
assert proxy.isProxy() && "192.0.2.123".equals(proxy.getHost()) && proxy.getPort() == 18080;
```

If `plugins.gradle.org` resolves to `DIRECT` while the sentinel PAC is configured, the PAC
file was not loaded/evaluated — that is exactly the regression this sentinel guards against.


## Apply the result to JVM system properties

```java
ProxyResult proxy = new WindowsProxyResolver()
        .resolve("https://plugins.gradle.org/m2/");

if (proxy.isProxy()) {
    System.setProperty("https.proxyHost", proxy.getHost());
    System.setProperty("https.proxyPort", String.valueOf(proxy.getPort()));
    System.setProperty("http.proxyHost", proxy.getHost());
    System.setProperty("http.proxyPort", String.valueOf(proxy.getPort()));
}
```

## Main public types

| Type | Responsibility |
| --- | --- |
| `WindowsProxyResolver` | Public facade for proxy resolution. |
| `ProxyConfiguration` | Immutable configuration object with builder. |
| `ProxyMode` | Selects the resolution strategy (see Proxy modes). |
| `ProxyResult` | PROXY / DIRECT / ERROR / NOT_IMPLEMENTED result. |
| `PacUrlProxyResolver` | Shared PAC pipeline (discover → load → evaluate → parse). |
| `PacUrlResolver` | Port for discovering a PAC/WPAD URL. |
| `FixedPacUrlResolver` | PAC URL from explicit configuration (`PAC_URL_MANUAL`). |
| `PowerShellPacUrlResolver` | PAC URL via inline PowerShell `-Command` (`PAC_URL_POWERSHELL`). |
| `WindowsPacUrlResolver` | PAC URL via `reg.exe`/Windows settings (`PAC_URL_WINDOWS_SETTINGS`). |
| `PacScriptLoader` | Port for loading PAC script content. |
| `PacEvaluator` / `GraalPacScriptEvaluator` | GraalJS based PAC evaluator. |
| `PacProxyRouteParser` | Parses the `FindProxyForURL` route string. |
| `StaticProxySettingsResolver` | Static Windows proxy (`WINDOWS_STATIC_PROXY`). |
| `ManualProxyResolver` | Manual host:port proxy (`MANUAL_PROXY`). |
| `WindowsPacScriptProxyResolver` | Deprecated PowerShell/.NET legacy route resolver. |

## Runtime requirements

- Java 8 or newer.
- Windows for automatic registry-based discovery.
- GraalJS on the runtime classpath for PAC script evaluation.
- PowerShell only when using `PAC_URL_POWERSHELL` or `POWERSHELL_ROUTE_RESOLVER_LEGACY`.

## Limitations

- PAC evaluation depends on the PAC helper functions currently provided by the evaluator.
  Complex enterprise PAC files may require additional helper functions.
- Unsupported PAC entries (e.g. `SOCKS`) and malformed `host:port` values return `ERROR`
  unless the PAC result contains an explicit `DIRECT` fallback later in the route list;
  `ProxyResult` intentionally models HTTP-style Java proxies only.
- `POWERSHELL_ROUTE_RESOLVER_LEGACY` is deprecated and depends on PowerShell availability and local execution policy.
- Registry-based PAC URL discovery is Windows-specific.
- Maven Central versions are immutable. Publish fixes with a new version.
- Consumers should depend on a published coordinate (e.g.
  `com.aresstack:win-proxy-java:0.1.0-beta.4`). `mavenLocal()` is only acceptable as a
  temporary local-test path and must be removed before a downstream merge/release. If a
  required version is not yet published remotely, treat publishing it as an explicit release
  step (TODO) rather than relying on `mavenLocal()`.

## License

MIT
