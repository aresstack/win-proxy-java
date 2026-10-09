package com.aresstack.winproxy;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FilenameFilter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * {@link ProxyMode#PAC_URL_WSCRIPT}: the VBScript only delivers the PAC URL, output is parsed
 * defensively, a non-zero exit code is a discovery failure, and the temporary script never
 * outlives the call. The process-spawning parts are exercised through a fake runner so the
 * guarantees hold on any OS.
 */
class WScriptPacUrlResolverTest {

    private static final String TARGET = "https://plugins.gradle.org/m2/";

    /** Runner that returns a canned result instead of starting cscript.exe. */
    private static final class FakeRunner extends CScriptRunner {
        private final int exitCode;
        private final String output;
        String lastScript;

        FakeRunner(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        @Override
        ScriptExecutionResult run(String script) {
            lastScript = script;
            return new ScriptExecutionResult(exitCode, output);
        }
    }

    @Test
    void takesFirstUrlLineAndIgnoresNoise() {
        FakeRunner runner = new FakeRunner(0,
                "Microsoft (R) Windows Script Host Version 5.812\n"
                        + "Copyright (C) Microsoft Corporation. All rights reserved.\n"
                        + "\n"
                        + "http://proxy.example.com/wpad.dat\n"
                        + "http://ignored.example.com/second.dat\n");

        PacUrlResolution resolution = new WScriptPacUrlResolver("x", runner).resolve();

        assertTrue(resolution.isPresent());
        assertEquals("http://proxy.example.com/wpad.dat", resolution.getPacUrl());
        assertEquals("wscript", resolution.getSource());
    }

    @Test
    void emptyOutputIsNotFoundNotAnError() {
        PacUrlResolution resolution = new WScriptPacUrlResolver("x", new FakeRunner(0, "")).resolve();

        assertFalse(resolution.isPresent());
    }

    @Test
    void outputWithoutUrlIsNotFound() {
        PacUrlResolution resolution = new WScriptPacUrlResolver("x",
                new FakeRunner(0, "C:\\temp\\x.vbs(3, 1) Microsoft VBScript runtime error: Invalid root in registry key"))
                .resolve();

        assertFalse(resolution.isPresent());
    }

    @Test
    void nonZeroExitCodeIsDiscoveryFailure() {
        WScriptPacUrlResolver resolver = new WScriptPacUrlResolver("x", new FakeRunner(1, "boom"));

        ProxyResolutionException ex = assertThrows(ProxyResolutionException.class, resolver::resolve);
        assertTrue(ex.getMessage().contains("exit code 1"));
        assertTrue(ex.getMessage().contains("boom"));
    }

    @Test
    void blankScriptSelectsDefaultVbScript() {
        FakeRunner runner = new FakeRunner(0, "");

        new WScriptPacUrlResolver("   ", runner).resolve();

        assertEquals(ProxyDefaults.DEFAULT_PAC_URL_DISCOVERY_WSCRIPT, runner.lastScript);
    }

    @Test
    void firstUrlLineAcceptsSchemesAndFileUrls() {
        assertEquals("https://p/x.pac", WScriptPacUrlResolver.firstUrlLine(" https://p/x.pac \r\n"));
        assertEquals("file://C:/pac/x.dat", WScriptPacUrlResolver.firstUrlLine("file://C:/pac/x.dat"));
        assertEquals("file:C:\\pac\\x.dat", WScriptPacUrlResolver.firstUrlLine("file:C:\\pac\\x.dat"));
        assertNull(WScriptPacUrlResolver.firstUrlLine("not a url"));
        assertNull(WScriptPacUrlResolver.firstUrlLine("error: http://x contains a space"));
        assertNull(WScriptPacUrlResolver.firstUrlLine(null));
        assertNull(WScriptPacUrlResolver.firstUrlLine(""));
    }

    @Test
    void defaultScriptProbesPolicyHivesFirstAndExitsZero() {
        String script = ProxyDefaults.DEFAULT_PAC_URL_DISCOVERY_WSCRIPT;
        int hkcuPolicy = script.indexOf("HKCU\\Software\\Policies\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\\AutoConfigURL");
        int hklmPolicy = script.indexOf("HKLM\\Software\\Policies\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\\AutoConfigURL");
        int hkcu = script.indexOf("HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\\AutoConfigURL");
        int hklm = script.indexOf("HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\\AutoConfigURL");

        assertTrue(hkcuPolicy >= 0 && hklmPolicy > hkcuPolicy && hkcu > hklmPolicy && hklm > hkcu,
                "hives must be probed in the same GPO-first order as RegistryReader");
        assertTrue(script.contains("RegRead"));
        assertTrue(script.contains("WScript.Echo"));
        assertFalse(script.contains("WScript.Quit 1"), "not found must exit 0 with no output");
        assertTrue(script.contains("\r\n"), "VBScript is written with CRLF line endings");
    }

    @Test
    void commandRunsCscriptWithTimeoutAndNoLogo() {
        File file = new File("C:\\Temp\\win-proxy-java-123.vbs");

        List<String> command = new CScriptRunner().buildCommand(file);

        assertEquals("cscript.exe", command.get(0));
        assertTrue(command.contains("//NoLogo"));
        assertTrue(command.contains("//T:" + CScriptRunner.TIMEOUT_SECONDS));
        assertEquals(file.getAbsolutePath(), command.get(command.size() - 1));
    }

    @Test
    void temporaryScriptNeverOutlivesTheCall() {
        File tmp = new File(System.getProperty("java.io.tmpdir"));
        int before = countScripts(tmp);

        try {
            new CScriptRunner().run("WScript.Quit 0");
        } catch (ProxyResolutionException expectedWithoutCscript) {
            // non-Windows: cscript.exe is missing — the temp file must still be gone
        }

        assertEquals(before, countScripts(tmp), "temporary .vbs must be deleted after the run");
    }

    @Test
    void wscriptModeWithoutCscriptIsErrorNotDirect() {
        assumeFalse(isWindows(), "on Windows cscript.exe exists and discovery may legitimately succeed");

        ProxyResult result = new WindowsProxyResolver(ProxyConfiguration.builder()
                .mode(ProxyMode.PAC_URL_WSCRIPT)
                .build())
                .resolve(TARGET);

        assertTrue(result.isError());
        assertFalse(result.isDirect(), "a failed WScript discovery must never be DIRECT");
        assertEquals("pac-url-discovery-failed", result.getReason());
        assertTrue(result.getDetail() != null && result.getDetail().contains("cscript.exe"),
                "detail must name the missing script host: " + result.getDetail());
    }

    @Test
    void wscriptModeUsesPipelineWithDiscoveredUrl() {
        PacUrlResolver discovery = new WScriptPacUrlResolver("x",
                new FakeRunner(0, "http://proxy.example.com/wpad.dat"));
        PacScriptLoader loader = pacUrl -> {
            assertEquals("http://proxy.example.com/wpad.dat", pacUrl);
            return "function FindProxyForURL(url, host) { return 'PROXY proxy.example.com:3128'; }";
        };
        PacUrlProxyResolver pipeline = new PacUrlProxyResolver(discovery, loader, PacEvaluator.createDefault());

        ProxyResult result = pipeline.resolve(TARGET);

        assertTrue(result.isProxy());
        assertEquals("proxy.example.com", result.getHost());
        assertEquals(3128, result.getPort());
    }

    private static int countScripts(File directory) {
        String[] names = directory.list(new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.startsWith("win-proxy-java-") && name.endsWith(".vbs");
            }
        });
        return names == null ? 0 : names.length;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
