package com.aresstack.winproxy;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
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
 * Executes a VBScript through the Windows Script Host console runner ({@code cscript.exe}).
 * Used by {@link WScriptPacUrlResolver} for {@link ProxyMode#PAC_URL_WSCRIPT}.
 * <p>
 * Unlike PowerShell, {@code cscript.exe} has no inline command mode, so the script has to be
 * written to a file. It goes to a <em>uniquely named</em> temporary file (never a fixed name in
 * a shared directory) that is deleted as soon as the process has finished. The run is bounded
 * twice: by the WSH timeout switch ({@code //T:20}, which terminates the script host) and by a
 * Java-side wait with {@link Process#destroyForcibly()}. stderr is merged into stdout so that a
 * script error ends up in the diagnostic output; {@link WScriptPacUrlResolver} only accepts
 * lines that look like a URL, so error text can never be mistaken for a PAC URL.
 */
class CScriptRunner {

    /** Seconds the script host may run (passed as {@code //T:}). */
    static final int TIMEOUT_SECONDS = 20;

    /** Extra seconds Java waits beyond the WSH timeout before killing the process. */
    private static final int GRACE_SECONDS = 5;

    ScriptExecutionResult run(String script) {
        File file = null;
        Process process = null;
        ExecutorService outputExecutor = Executors.newSingleThreadExecutor();
        try {
            file = File.createTempFile("win-proxy-java-", ".vbs");
            // WSH reads .vbs files in the ANSI code page (unless they carry a UTF-16 BOM), which is
            // what the platform default charset is on a Windows JDK; the default scripts are ASCII.
            Files.write(file.toPath(), (script == null ? "" : script).getBytes(Charset.defaultCharset()));

            process = new ProcessBuilder(buildCommand(file))
                    .redirectErrorStream(true)
                    .start();

            Future<String> outputFuture = outputExecutor.submit(new OutputReader(process));
            int exitCode = waitFor(process);
            String output = readOutput(outputFuture);
            return new ScriptExecutionResult(exitCode, output.trim());
        } catch (IOException e) {
            throw new ProxyResolutionException("Could not run cscript.exe: " + e.getMessage(), e);
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
     * Builds the exact command line: {@code cscript.exe //NoLogo //T:20 <script>.vbs}.
     * Package-private so it can be asserted in a unit test without spawning a process.
     */
    List<String> buildCommand(File file) {
        List<String> command = new ArrayList<String>();
        command.add("cscript.exe");
        command.add("//NoLogo");
        command.add("//T:" + TIMEOUT_SECONDS);
        command.add(file.getAbsolutePath());
        return command;
    }

    private String readOutput(Future<String> outputFuture) {
        try {
            return outputFuture.get(TIMEOUT_SECONDS + GRACE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProxyResolutionException("cscript.exe output reading was interrupted.", e);
        } catch (ExecutionException e) {
            throw new ProxyResolutionException("Could not read cscript.exe output.", e);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new ProxyResolutionException("cscript.exe output reader did not finish.", e);
        }
    }

    private int waitFor(Process process) {
        try {
            if (!process.waitFor(TIMEOUT_SECONDS + GRACE_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new ProxyResolutionException("cscript.exe timed out after "
                        + (TIMEOUT_SECONDS + GRACE_SECONDS) + " seconds.");
            }
            return process.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProxyResolutionException("cscript.exe was interrupted.", e);
        }
    }

    private static final class OutputReader implements Callable<String> {
        private final Process process;

        private OutputReader(Process process) {
            this.process = process;
        }

        public String call() throws Exception {
            StringBuilder builder = new StringBuilder();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), Charset.defaultCharset()));
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
