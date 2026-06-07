package com.aresstack.winproxy;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Executes PowerShell either inline via {@code -Command} for PAC URL discovery
 * ({@link #runInlineCommand()} / {@link #buildInlineCommand()}) or through a temporary
 * script file for the deprecated legacy route resolver ({@link #runWithArguments(String...)}).
 * <p>
 * The inline {@code -Command} path is the important one on hardened machines: it never writes
 * a {@code .ps1} file to {@code %TEMP%}, so it keeps working where GPO execution policy or
 * AppLocker block unsigned script files.
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
        ExecutorService outputExecutor = Executors.newSingleThreadExecutor();
        try {
            file = File.createTempFile("win-proxy-java-", ".ps1");
            Files.write(file.toPath(), script.getBytes(StandardCharsets.UTF_8));

            List<String> command = createCommand(file, arguments);
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

            Future<String> outputFuture = outputExecutor.submit(new OutputReader(process));
            int exitCode = waitFor(process);
            String output = readOutput(outputFuture);
            return new ScriptExecutionResult(exitCode, output.trim());
        } catch (IOException e) {
            throw new ProxyResolutionException("Could not run PowerShell script.", e);
        } finally {
            outputExecutor.shutdownNow();
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            if (file != null && file.exists() && !file.delete()) {
                file.deleteOnExit();
            }
        }
    }

    /**
     * Executes the script <em>inline</em> via {@code powershell.exe -Command} —
     * no temporary {@code .ps1} file is written.
     * <p>
     * This is required on hardened machines where an unsigned {@code .ps1} from
     * {@code %TEMP%} would be blocked by GPO execution policy or AppLocker, while
     * an inline {@code -Command} is still allowed. {@code stderr} is drained on a
     * daemon thread and discarded so it cannot corrupt the parsed output.
     */
    ScriptExecutionResult runInlineCommand() {
        Process process = null;
        ExecutorService outputExecutor = Executors.newSingleThreadExecutor();
        try {
            process = new ProcessBuilder(buildInlineCommand()).start();
            final Process startedProcess = process;
            Thread stderrDrainer = new Thread(new Runnable() {
                public void run() {
                    BufferedReader reader = null;
                    try {
                        reader = new BufferedReader(new InputStreamReader(startedProcess.getErrorStream(), StandardCharsets.UTF_8));
                        while (reader.readLine() != null) {
                            // discard
                        }
                    } catch (Exception ignored) {
                        // ignore
                    } finally {
                        if (reader != null) {
                            try {
                                reader.close();
                            } catch (IOException ignored) {
                                // ignore
                            }
                        }
                    }
                }
            }, "win-proxy-java-stderr-drain");
            stderrDrainer.setDaemon(true);
            stderrDrainer.start();

            Future<String> outputFuture = outputExecutor.submit(new OutputReader(process));
            int exitCode = waitFor(process);
            String output = readOutput(outputFuture);
            return new ScriptExecutionResult(exitCode, output.trim());
        } catch (IOException e) {
            throw new ProxyResolutionException("Could not run PowerShell command.", e);
        } finally {
            outputExecutor.shutdownNow();
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * Builds the exact command line used by {@link #runInlineCommand()}:
     * {@code powershell.exe -NoProfile -ExecutionPolicy Bypass -Command <script>}.
     * <p>
     * It deliberately uses {@code -Command} (inline) and never {@code -File}, so no
     * temporary {@code .ps1} is written to {@code %TEMP%}. Writing a temp script was
     * the hardening bug: an unsigned {@code .ps1} from {@code %TEMP%} is blocked by
     * GPO execution policy / AppLocker, while an inline {@code -Command} is allowed.
     * Package-private so it can be asserted in a unit test without spawning a process.
     */
    List<String> buildInlineCommand() {
        List<String> command = new ArrayList<String>();
        command.add("powershell.exe");
        command.add("-NoProfile");
        command.add("-ExecutionPolicy");
        command.add("Bypass");
        command.add("-Command");
        command.add(script);
        return command;
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

    private String readOutput(Future<String> outputFuture) {
        try {
            return outputFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProxyResolutionException("PowerShell output reading was interrupted.", e);
        } catch (ExecutionException e) {
            throw new ProxyResolutionException("Could not read PowerShell output.", e);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new ProxyResolutionException("PowerShell output reader did not finish.", e);
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

    private static final class OutputReader implements Callable<String> {
        private final Process process;

        private OutputReader(Process process) {
            this.process = process;
        }

        public String call() throws Exception {
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
    }
}
