# win-proxy-java

Detects **Windows proxy settings** (PAC/WPAD/Registry/GPO) from **pure Java** — no browser, no PowerShell, no .NET.  
Works on **hardened enterprise systems** where PowerShell Constrained Language Mode (CLM) blocks `.NET` method calls.

## Installation

### Maven

```xml
<dependency>
    <groupId>com.aresstack</groupId>
    <artifactId>win-proxy-java</artifactId>
    <version>0.1.0-beta.1</version>
</dependency>
```

### Gradle

```groovy
implementation 'com.aresstack:win-proxy-java:0.1.0-beta.1'
```

## Quick Start

```java
import com.aresstack.winproxy.WindowsProxyResolver;
import com.aresstack.winproxy.ProxyResult;

ProxyResult result = WindowsProxyResolver.resolve("https://example.com");

if (result.isDirect()) {
    connection = url.openConnection();
} else {
    connection = url.openConnection(result.toJavaProxy());
}
```

## Choose How the PAC URL Is Obtained

```java
import com.aresstack.winproxy.*;

// Option 1: User provides the PAC URL directly
ProxyResult r = WindowsProxyResolver.resolve(
    "https://example.com", PacUrlSource.DIRECT,
    "http://wpad.corp.local/wpad.dat");

// Option 2: Auto-detect from Windows Registry (all 4 hives, GPO first)
ProxyResult r = WindowsProxyResolver.resolve(
    "https://example.com", PacUrlSource.REGISTRY, null);

// Option 3: Run PowerShell command to discover PAC URL
ProxyResult r = WindowsProxyResolver.resolve(
    "https://example.com", PacUrlSource.POWERSHELL, null);
// uses DEFAULT_PAC_DISCOVERY_SCRIPT:
// (Get-ItemProperty -Path 'HKCU:\...\Internet Settings').AutoConfigURL

// Option 3b: Custom PowerShell command
ProxyResult r = WindowsProxyResolver.resolve(
    "https://example.com", PacUrlSource.POWERSHELL,
    "my-custom-script.ps1");
```

## The Problem

In enterprise environments, proxy configuration is managed via Group Policy (WPAD/PAC or static proxy). Java applications need to detect these at runtime, but standard approaches fail:

| Approach | Problem |
|---|---|
| `java.net.useSystemProxies=true` | Only works as JVM startup arg |
| PowerShell `.NET` calls | Blocked by CLM on hardened systems |
| Only reading `HKCU\...\Internet Settings` | Misses GPO settings on hardened machines |

**win-proxy-java** solves this by:
1. Searching **all four registry hives** in correct priority order (GPO first)
2. Reading `AutoConfigURL`, `ProxyEnable`, `ProxyServer`, `ProxyOverride` via `reg.exe`
3. Parsing the `DefaultConnectionSettings` binary blob for embedded PAC URLs
4. Evaluating PAC scripts via **GraalJS** (pure Java, no native deps)
5. Optionally running a **PowerShell command** to discover the PAC URL

## API

### `WindowsProxyResolver` (Facade)

| Method | Description |
|---|---|
| `resolve(url)` | Full auto-detection: GPO PAC → blob PAC → WPAD → static → DIRECT |
| `resolve(url, PacUrlSource, script)` | **Primary facade**: choose how PAC URL is obtained |
| `resolveStatic(url)` | Static proxy only (skips PAC/WPAD) |
| `evaluatePac(pacUrl, targetUrl)` | Download + evaluate a PAC file |
| `evaluatePacScript(script, targetUrl)` | Evaluate a PAC script string |
| `DEFAULT_PAC_DISCOVERY_SCRIPT` | Default PowerShell command for PAC URL discovery |
| `readRegistryValueFromAllHives(name)` | Search all 4 hives (GPO first) |
| `isBypassed(host, overrideList)` | Check proxy bypass rules |

### `PacUrlSource` (Enum)

| Value | Description |
|---|---|
| `DIRECT` | PAC URL provided directly by the caller |
| `REGISTRY` | Auto-detect from Windows Registry (all 4 hives, GPO first) |
| `POWERSHELL` | Run a PowerShell command to obtain the PAC URL |

### `ProxyResult` (Value Object)

| Method | Description |
|---|---|
| `isDirect()` | `true` if no proxy needed |
| `getHost()` / `getPort()` | Proxy host and port |
| `getReason()` | Diagnostic string for logging |
| `toJavaProxy()` | Converts to `java.net.Proxy` |

## Why GPO Keys Matter

On hardened enterprise machines (e.g. Windows 11 with Group Policy), the PAC URL is stored in `HKCU\Software\Policies\...` — **not** the normal user-level key. If you only check the user-level key (which most Java proxy libraries do), you get a false `DIRECT` result.

Registry search order:
1. `HKCU\Software\Policies\...\Internet Settings` (User GPO)
2. `HKLM\Software\Policies\...\Internet Settings` (Machine GPO)
3. `HKCU\Software\Microsoft\...\Internet Settings` (User settings)
4. `HKLM\Software\Microsoft\...\Internet Settings` (Machine settings)

## Dependencies

- **Java 8+**
- **GraalJS 21.2.0** — PAC script evaluation
- **`reg.exe`** — ships with every Windows installation

## License

MIT

