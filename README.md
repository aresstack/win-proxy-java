# win-proxy-java

[![Maven Central](https://img.shields.io/maven-central/v/com.aresstack/win-proxy-java.svg)](https://central.sonatype.com/artifact/com.aresstack/win-proxy-java)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

`win-proxy-java` is a lightweight Java 8 library for resolving Windows proxy settings in
corporate workstation environments. It is designed for tools that must run behind managed
Windows proxy infrastructure without forcing every application to duplicate PowerShell,
registry, WPAD, and PAC handling.

The default resolution mode is **PAC_URL**. This mirrors the most common managed Windows
setup: Windows stores the address of a PAC/WPAD file, the application downloads that file,
and the Java library evaluates `FindProxyForURL(url, host)` locally.

## Installation

```xml
<dependency>
  <groupId>com.aresstack</groupId>
  <artifactId>win-proxy-java</artifactId>
  <version>0.1.0-beta.2</version>
</dependency>
```

Gradle:

```groovy
implementation 'com.aresstack:win-proxy-java:0.1.0-beta.2'
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

The default resolver first looks for the Windows PAC URL. The most important Windows value
is the same one commonly checked with PowerShell:

```powershell
(Get-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings').AutoConfigURL
```

The Java implementation prefers direct Windows registry access through the library's
Windows registry adapter. This avoids making the main path dependent on PowerShell.

The built-in resolver checks:

1. `AutoConfigURL` from user and policy hives,
2. binary connection settings that can contain auto-config metadata,
3. WPAD auto-detection flags,
4. `http://wpad/wpad.dat` as the conventional WPAD endpoint when auto-detection is enabled.

### Stage 2: Load the PAC/WPAD script

Once a PAC URL is known, the script is downloaded by `PacScriptLoader`.

The default implementation uses `URLConnection`, so it works without additional HTTP client
dependencies.

### Stage 3: Evaluate the PAC script with GraalJS

The PAC content is evaluated by `GraalPacScriptEvaluator`. It calls:

```javascript
FindProxyForURL(url, host)
```

The result is parsed into a `ProxyResult`.

Supported PAC result forms include:

```text
DIRECT
PROXY proxy.example.com:8080
HTTPS proxy.example.com:8443
SOCKS proxy.example.com:1080
```

The first supported non-direct proxy entry is returned. `DIRECT` produces an empty proxy
result.

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

`resolve(url)` uses `ProxyMode.PAC_URL` by default.

## Explicit configuration

```java
import com.aresstack.winproxy.ProxyConfiguration;
import com.aresstack.winproxy.ProxyMode;
import com.aresstack.winproxy.ProxyResult;
import com.aresstack.winproxy.WindowsProxyResolver;

public final class ExplicitPacUrlExample {
    public static void main(String[] args) {
        ProxyConfiguration configuration = ProxyConfiguration.builder()
                .mode(ProxyMode.PAC_URL)
                .testUrl("https://plugins.gradle.org/m2/")
                .build();

        WindowsProxyResolver resolver = new WindowsProxyResolver(configuration);
        ProxyResult proxy = resolver.resolve(configuration.getTestUrl());

        System.out.println(proxy);
    }
}
```

## Proxy modes

| Mode | Purpose |
| --- | --- |
| `PAC_URL` | Default. Resolve PAC/WPAD URL from Windows, load the PAC script, evaluate it with GraalJS. |
| `WINDOWS_PAC` | Optional fallback. Ask Windows/.NET/PowerShell for the final proxy directly. |
| `REGISTRY` | Resolve static proxy settings from the Windows registry. |
| `MANUAL` | Use a manually supplied proxy host and port. |
| `DISABLED` | Force direct connections. |

## PAC_URL mode

`PAC_URL` is the preferred production path. It keeps the full resolution pipeline inside the
library and makes each step replaceable.

```java
ProxyConfiguration configuration = ProxyConfiguration.builder()
        .mode(ProxyMode.PAC_URL)
        .pacUrl("http://proxy.example.com/wpad.dat")
        .build();

ProxyResult proxy = new WindowsProxyResolver(configuration)
        .resolve("https://repo.maven.apache.org/maven2/");
```

If `pacUrl` is not set, the resolver discovers it from Windows.

## WINDOWS_PAC fallback mode

`WINDOWS_PAC` is intentionally not the default. It exists for environments where Windows
itself can resolve the proxy correctly but the PAC script cannot be downloaded or evaluated
inside the application process.

Internally, this mode uses a PowerShell script with two strategies:

1. `.NET WebRequest.GetSystemWebProxy()` for full Windows proxy/PAC/WPAD handling,
2. registry fallback for static proxy settings in constrained environments.

The default script returns either:

```text
host:port
```

or no output for `DIRECT`.

Example:

```java
ProxyConfiguration configuration = ProxyConfiguration.builder()
        .mode(ProxyMode.WINDOWS_PAC)
        .testUrl("https://plugins.gradle.org/m2/")
        .build();

ProxyResult proxy = new WindowsProxyResolver(configuration)
        .resolve(configuration.getTestUrl());
```

## Discover only the PAC URL

```java
import com.aresstack.winproxy.PacUrlResolution;
import com.aresstack.winproxy.WindowsProxyResolver;

public final class DiscoverPacUrlExample {
    public static void main(String[] args) {
        WindowsProxyResolver resolver = new WindowsProxyResolver();
        PacUrlResolution resolution = resolver.discoverPacUrl();

        if (resolution.isPresent()) {
            System.out.println(resolution.getPacUrl());
        }
    }
}
```

## Manual proxy

```java
ProxyConfiguration configuration = ProxyConfiguration.builder()
        .mode(ProxyMode.MANUAL)
        .manualProxyHost("proxy.example.com")
        .manualProxyPort(8080)
        .build();

ProxyResult proxy = new WindowsProxyResolver(configuration)
        .resolve("https://repo.maven.apache.org/maven2/");
```

## Apply the result to JVM system properties

```java
ProxyResult proxy = new WindowsProxyResolver()
        .resolve("https://plugins.gradle.org/m2/");

if (!proxy.isDirect()) {
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
| `ProxyMode` | Selects the resolution strategy. |
| `ProxyResult` | Final host/port result or direct connection. |
| `PacUrlResolver` | Port for discovering a PAC/WPAD URL. |
| `PacScriptLoader` | Port for loading PAC script content. |
| `PacEvaluator` | Port for evaluating a PAC script for one target URL. |
| `WindowsPacUrlResolver` | Windows registry based PAC/WPAD discovery adapter. |
| `GraalPacScriptEvaluator` | GraalJS based PAC evaluator. |
| `WindowsPacScriptProxyResolver` | PowerShell/.NET fallback adapter. |

## Runtime requirements

- Java 8 or newer.
- Windows for automatic registry-based discovery.
- GraalJS on the runtime classpath for PAC script evaluation.
- PowerShell only when using `WINDOWS_PAC` or explicit PowerShell PAC URL discovery.

## Limitations

- PAC evaluation depends on the PAC helper functions currently provided by the evaluator.
  Complex enterprise PAC files may require additional helper functions.
- `WINDOWS_PAC` depends on PowerShell availability and local execution policy.
- Registry-based PAC URL discovery is Windows-specific.
- Maven Central versions are immutable. Publish fixes with a new version.

## License

MIT
