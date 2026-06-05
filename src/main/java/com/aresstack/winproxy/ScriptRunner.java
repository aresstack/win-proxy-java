package com.aresstack.winproxy;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Executes PowerShell scripts with a temporary script file.
 */
final class ScriptRunner {

    private static final int TIMEOUT_SECONDS = 15;

    private final String script;

    ScriptRunner(String script) {
        this.script = script;
    }

    String run() {
        return runWithArguments(new String[0]).getOutput();
    }

    ScriptExecutionResult runWithArguments(String... arguments) {
        File file = null;
        Process process = null;
        try {
            file = File.createTempFile("win-proxy-java-", ".ps1");
            Files.write(file.toPath(), script.getBytes(StandardCharsets.UTF_8));

            List<String> command = createCommand(file, arguments);
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

            int exitCode = waitFor(process);
            String output = readOutput(process);
            return new ScriptExecutionResult(exitCode, output.trim());
        } catch (IOException e) {
            throw new ProxyResolutionException("Could not run PowerShell script.", e);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            if (file != null && file.exists() && !file.delete()) {
                file.deleteOnExit();
            }
        }
    }

    private List<String> createCommand(File file, String[] arguments) {
        List<String> command = new ArrayList<String>();
        command.add("powershell.exe");
        command.add("-NoProfile");
        command.add("-ExecutionPolicy");
        command.add("Bypass");
        command.add("-File");
        command.add(file.getAbsolutePath());
        for (int i = 0; i < arguments.length; i++) {
            if (arguments[i] != null && arguments[i].trim().length() > 0) {
                command.add(arguments[i]);
            }
        }
        return command;
    }

    private String readOutput(Process process) throws IOException {
        StringBuilder builder = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (builder.length() > 0) {
                    builder.append(System.lineSeparator());
                }
                builder.append(line);
            }
            return builder.toString();
        } finally {
            reader.close();
        }
    }

    private int waitFor(Process process) {
        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new ProxyResolutionException("PowerShell script timed out after " + TIMEOUT_SECONDS + " seconds.");
            }
            return process.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProxyResolutionException("PowerShell script was interrupted.", e);
        }
    }

    static String escapePowerShellSingleQuoted(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("'", "''");
    }
}
