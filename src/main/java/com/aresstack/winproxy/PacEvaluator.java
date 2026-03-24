package com.aresstack.winproxy;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Downloads a PAC (Proxy Auto-Config) file and evaluates {@code FindProxyForURL(url, host)}
 * using GraalJS — no PowerShell, no .NET, works on CLM-restricted machines.
 */
final class PacEvaluator {

    private static final Logger LOG = Logger.getLogger(PacEvaluator.class.getName());
    private static final int DOWNLOAD_TIMEOUT_MS = 5000;

    private PacEvaluator() {}

    static String evaluate(String pacUrl, String targetUrl) {
        try {
            String pacScript = downloadPac(pacUrl);
            if (pacScript == null || pacScript.trim().isEmpty()) {
                LOG.fine("[PAC] Empty PAC file from " + pacUrl);
                return null;
            }
            String host;
            try { host = new URL(targetUrl).getHost(); } catch (Exception e) { host = targetUrl; }
            return evalFindProxyForURL(pacScript, targetUrl, host);
        } catch (Exception e) {
            LOG.log(Level.FINE, "[PAC] Evaluation failed for " + pacUrl, e);
            return null;
        }
    }

    static String evaluateScript(String pacScript, String targetUrl) {
        try {
            if (pacScript == null || pacScript.trim().isEmpty()) return null;
            String host;
            try { host = new URL(targetUrl).getHost(); } catch (Exception e) { host = targetUrl; }
            return evalFindProxyForURL(pacScript, targetUrl, host);
        } catch (Exception e) {
            LOG.log(Level.FINE, "[PAC] Script evaluation failed", e);
            return null;
        }
    }

    private static String downloadPac(String pacUrl) throws Exception {
        URLConnection conn = new URL(pacUrl).openConnection(java.net.Proxy.NO_PROXY);
        conn.setConnectTimeout(DOWNLOAD_TIMEOUT_MS);
        conn.setReadTimeout(DOWNLOAD_TIMEOUT_MS);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private static String evalFindProxyForURL(String pacScript, String url, String host) {
        Context context = null;
        try {
            context = Context.newBuilder("js")
                    .allowAllAccess(false)
                    .option("engine.WarnInterpreterOnly", "false")
                    .build();

            String fullScript = PAC_HELPERS + "\n\n" + pacScript + "\n\n"
                    + "FindProxyForURL(" + jsStringLiteral(url) + ", " + jsStringLiteral(host) + ");";

            Value result = context.eval("js", fullScript);
            return result.isNull() ? null : result.asString();
        } catch (Exception e) {
            LOG.log(Level.FINE, "[PAC] GraalJS evaluation failed", e);
            return null;
        } finally {
            if (context != null) {
                try { context.close(); } catch (Exception ignore) {}
            }
        }
    }

    private static String jsStringLiteral(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static final String PAC_HELPERS;
    static {
        String myIp;
        try { myIp = InetAddress.getLocalHost().getHostAddress(); } catch (Exception e) { myIp = "127.0.0.1"; }

        PAC_HELPERS =
            "function isPlainHostName(host) { return host.indexOf('.') === -1; }\n" +
            "function dnsDomainIs(host, domain) {\n" +
            "  return host.length >= domain.length &&\n" +
            "         host.substring(host.length - domain.length).toLowerCase() === domain.toLowerCase();\n" +
            "}\n" +
            "function localHostOrDomainIs(host, hostdom) {\n" +
            "  return host.toLowerCase() === hostdom.toLowerCase() ||\n" +
            "         hostdom.toLowerCase().indexOf(host.toLowerCase() + '.') === 0;\n" +
            "}\n" +
            "function isResolvable(host) { try { dnsResolve(host); return true; } catch(e) { return false; } }\n" +
            "function dnsResolve(host) {\n" +
            "  if (/^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$/.test(host)) return host;\n" +
            "  return '0.0.0.0';\n" +
            "}\n" +
            "function myIpAddress() { return '" + myIp + "'; }\n" +
            "function dnsDomainLevels(host) { return host.split('.').length - 1; }\n" +
            "function shExpMatch(str, shexp) {\n" +
            "  var re = shexp.replace(/\\./g, '\\\\.').replace(/\\*/g, '.*').replace(/\\?/g, '.');\n" +
            "  return new RegExp('^' + re + '$', 'i').test(str);\n" +
            "}\n" +
            "function isInNet(host, pattern, mask) {\n" +
            "  function ipToLong(ip) {\n" +
            "    var parts = ip.split('.');\n" +
            "    return ((+parts[0]) << 24 | (+parts[1]) << 16 | (+parts[2]) << 8 | (+parts[3])) >>> 0;\n" +
            "  }\n" +
            "  var ip = /^\\d{1,3}\\./.test(host) ? host : dnsResolve(host);\n" +
            "  if (!ip) return false;\n" +
            "  return (ipToLong(ip) & ipToLong(mask)) === (ipToLong(pattern) & ipToLong(mask));\n" +
            "}\n" +
            "function weekdayRange() { return true; }\n" +
            "function dateRange() { return true; }\n" +
            "function timeRange() { return true; }\n" +
            "function alert(msg) {}\n";
    }
}

