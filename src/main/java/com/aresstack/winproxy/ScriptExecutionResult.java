package com.aresstack.winproxy;

/**
 * Result of a script execution.
 */
public final class ScriptExecutionResult {

    private final int exitCode;
    private final String output;

    public ScriptExecutionResult(int exitCode, String output) {
        this.exitCode = exitCode;
        this.output = output;
    }

    public int getExitCode() {
        return exitCode;
    }

    public String getOutput() {
        return output;
    }
}
