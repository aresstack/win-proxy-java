package com.aresstack.winproxy;

/**
 * Resolves the final proxy by delegating to a Windows PowerShell/.NET script
 * ({@code GetSystemWebProxy()}). Legacy route resolver — no GraalVM, no own PAC
 * evaluation. Backs {@link ProxyMode#POWERSHELL_ROUTE_RESOLVER_LEGACY}.
 *
 * @deprecated Use a {@code PAC_URL_*} mode (GraalVM PAC evaluation) instead.
 */
@Deprecated
public final class WindowsPacScriptProxyResolver {

    private final PacProxyRouteParser parser = new PacProxyRouteParser();

    public ProxyResult resolve(ProxyConfiguration configuration, String targetUrl) {
        String resolvedTargetUrl = targetUrl;
        if (resolvedTargetUrl == null || resolvedTargetUrl.trim().length() == 0) {
            resolvedTargetUrl = configuration.getTestUrl();
        }
        String script = configuration.getWindowsPacScript();
        if (script == null || script.trim().length() == 0) {
            script = ProxyDefaults.DEFAULT_WINDOWS_PAC_SCRIPT;
        }

        ScriptExecutionResult result = new ScriptRunner(script).runWithArguments(
                "-TestUrl",
                quotePowerShellArgument(resolvedTargetUrl),
                configuration.isDebugEnabled() ? "-DebugEnabled" : ""
        );
        if (result.getExitCode() != 0) {
            throw new ProxyResolutionException("Windows PAC script failed with exit code " + result.getExitCode() + ".");
        }
        return parser.parse(result.getOutput());
    }

    private String quotePowerShellArgument(String value) {
        return "'" + ScriptRunner.escapePowerShellSingleQuoted(value) + "'";
    }
}
