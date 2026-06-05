package com.aresstack.winproxy;

/**
 * Resolves the PAC URL through a PowerShell script.
 */
public final class PowerShellPacUrlResolver implements PacUrlResolver {

    private final String script;

    public PowerShellPacUrlResolver(String script) {
        this.script = script == null || script.trim().length() == 0
                ? ProxyDefaults.DEFAULT_PAC_URL_DISCOVERY_SCRIPT
                : script;
    }

    public PacUrlResolution resolve() {
        ScriptExecutionResult result = new ScriptRunner(script).runWithArguments();
        if (result.getExitCode() != 0) {
            throw new ProxyResolutionException("PowerShell PAC URL discovery failed with exit code " + result.getExitCode() + ".");
        }
        String output = result.getOutput();
        if (output == null || output.trim().length() == 0) {
            return PacUrlResolution.notFound();
        }
        return PacUrlResolution.found(output.trim(), "powershell");
    }
}
