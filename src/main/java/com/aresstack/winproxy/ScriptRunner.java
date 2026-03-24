package com.aresstack.winproxy;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Executes PowerShell commands and returns the trimmed stdout output.
 */
final class ScriptRunner {

    private static final Logger LOG = Logger.getLogger(ScriptRunner.class.getName());
    private static final int TIMEOUT_SECONDS = 10;

    private ScriptRunner() {}

    static String executePowerShell(String command) {
        if (command == null || command.trim().isEmpty()) return null;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-Command", command);
            pb.redirectErrorStream(false);
            Process process = pb.start();

            Thread stderrDrainer = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    while (r.readLine() != null) { /* discard */ }
                } catch (Exception ignored) { }
            }, "pac-url-stderr-drain");
            stderrDrainer.setDaemon(true);
            stderrDrainer.start();

            StringBuilder stdout = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    stdout.append(line).append('\n');
                }
            }

            process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            String result = stdout.toString().trim();
            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[WinProxy] PowerShell command failed: " + e.getMessage(), e);
            return null;
        }
    }
}

