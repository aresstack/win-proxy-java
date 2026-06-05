package com.aresstack.winproxy;

/**
 * Resolves the final proxy by delegating to a Windows PowerShell/.NET script.
 */
public final class WindowsPacScriptProxyResolver {

    private final ProxyResultParser parser = new ProxyResultParser();

    public ProxyResult resolve(ProxyConfiguration configuration, String targetUrl) {
        String script = configuration.getWindowsPacScript();
        if (script == null || script.trim().length() == 0) {
            script = ProxyDefaults.DEFAULT_WINDOWS_PAC_SCRIPT;
        }

        ScriptExecutionResult result = new ScriptRunner(script).runWithArguments(
                "-TestUrl",
                targetUrl,
                configuration.isDebugEnabled() ? "-DebugEnabled" : ""
        );
        if (result.getExitCode() != 0) {
            throw new ProxyResolutionException("Windows PAC script failed with exit code " + result.getExitCode() + ".");
        }
        return parser.parse(result.getOutput());
    }
}
