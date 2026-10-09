package com.aresstack.winproxy;

/**
 * Resolves the PAC URL through a VBScript executed by the Windows Script Host
 * ({@code cscript.exe}). Backs {@link ProxyMode#PAC_URL_WSCRIPT}.
 * <p>
 * Contract for the script:
 * <ul>
 *   <li>It prints the PAC/WPAD URL on a line of its own (e.g. via {@code WScript.Echo}).
 *       The first line that looks like a URL ({@code scheme://...} or {@code file:...}) is
 *       taken; any other output (script host banners, error text) is ignored.</li>
 *   <li>Exit code {@code 0} with no URL line means "no PAC URL configured" and yields
 *       {@link PacUrlResolution#notFound()} — the PAC pipeline then reports
 *       {@code pac-url-not-found}, never DIRECT.</li>
 *   <li>A non-zero exit code is a discovery failure ({@link ProxyResolutionException},
 *       reported by the pipeline as {@code pac-url-discovery-failed}).</li>
 * </ul>
 * The script may only deliver the PAC URL, never the final route; the PAC file is then
 * downloaded and evaluated like in every other {@code PAC_URL_*} mode.
 *
 * @since 0.2.0
 */
public final class WScriptPacUrlResolver implements PacUrlResolver {

    private static final int OUTPUT_EXCERPT_LENGTH = 300;

    private final String script;
    private final CScriptRunner runner;

    /**
     * @param script the VBScript; blank selects {@link ProxyDefaults#DEFAULT_PAC_URL_DISCOVERY_WSCRIPT}
     */
    public WScriptPacUrlResolver(String script) {
        this(script, new CScriptRunner());
    }

    WScriptPacUrlResolver(String script, CScriptRunner runner) {
        this.script = script == null || script.trim().length() == 0
                ? ProxyDefaults.DEFAULT_PAC_URL_DISCOVERY_WSCRIPT
                : script;
        this.runner = runner;
    }

    public PacUrlResolution resolve() {
        ScriptExecutionResult result = runner.run(script);
        if (result.getExitCode() != 0) {
            throw new ProxyResolutionException("WScript PAC URL discovery failed with exit code "
                    + result.getExitCode() + "." + excerpt(result.getOutput()));
        }
        String pacUrl = firstUrlLine(result.getOutput());
        if (pacUrl == null) {
            return PacUrlResolution.notFound();
        }
        return PacUrlResolution.found(pacUrl, "wscript");
    }

    /**
     * Returns the first non-blank line of the output that looks like a URL
     * ({@code scheme://...} or {@code file:...}), trimmed, or {@code null}.
     */
    static String firstUrlLine(String output) {
        if (output == null) {
            return null;
        }
        String[] lines = output.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (looksLikeUrl(line)) {
                return line;
            }
        }
        return null;
    }

    private static boolean looksLikeUrl(String line) {
        if (line.length() == 0 || line.indexOf(' ') >= 0) {
            return false;
        }
        String lower = line.toLowerCase();
        if (lower.startsWith("file:")) {
            return true;
        }
        int separator = lower.indexOf("://");
        if (separator < 1) {
            return false;
        }
        for (int i = 0; i < separator; i++) {
            char c = lower.charAt(i);
            boolean schemeChar = (c >= 'a' && c <= 'z') || (i > 0 && ((c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.'));
            if (!schemeChar) {
                return false;
            }
        }
        return true;
    }

    private static String excerpt(String output) {
        if (output == null || output.trim().length() == 0) {
            return "";
        }
        String trimmed = output.trim();
        if (trimmed.length() > OUTPUT_EXCERPT_LENGTH) {
            trimmed = trimmed.substring(0, OUTPUT_EXCERPT_LENGTH) + "...";
        }
        return " Output: " + trimmed;
    }
}
