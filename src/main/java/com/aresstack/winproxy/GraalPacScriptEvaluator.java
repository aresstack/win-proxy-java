package com.aresstack.winproxy;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

import java.net.InetAddress;
import java.net.URL;

/**
 * Evaluates PAC scripts with GraalJS.
 */
public final class GraalPacScriptEvaluator implements PacEvaluator {

    private final PacProxyRouteParser proxyResultParser;

    public GraalPacScriptEvaluator(PacProxyRouteParser proxyResultParser) {
        this.proxyResultParser = proxyResultParser;
    }

    public ProxyResult evaluate(String pacScript, String targetUrl) {
        if (pacScript == null || pacScript.trim().length() == 0) {
            throw new ProxyResolutionException("PAC script must not be empty.");
        }
        Context context = null;
        try {
            String host = extractHost(targetUrl);
            context = Context.newBuilder("js")
                    .allowAllAccess(false)
                    .option("engine.WarnInterpreterOnly", "false")
                    .build();
            String script = createPacHelperScript() + "\n\n" + pacScript + "\n\n"
                    + "FindProxyForURL(" + jsStringLiteral(targetUrl) + ", " + jsStringLiteral(host) + ");";
            Value value = context.eval("js", script);
            if (value == null || value.isNull()) {
                throw new ProxyResolutionException("PAC script returned no result for " + targetUrl + ".");
            }
            return proxyResultParser.parse(value.asString());
        } catch (ProxyResolutionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ProxyResolutionException("Could not evaluate PAC script for " + targetUrl + ".", e);
        } finally {
            if (context != null) {
                context.close();
            }
        }
    }

    private static String extractHost(String targetUrl) {
        try {
            return new URL(targetUrl).getHost();
        } catch (Exception e) {
            return targetUrl;
        }
    }

    private static String jsStringLiteral(String value) {
        if (value == null) {
            return "\"\"";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String createPacHelperScript() {
        String myIp;
        try {
            myIp = InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            myIp = "127.0.0.1";
        }
        return "function isPlainHostName(host) { return host.indexOf('.') === -1; }\n" +
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
