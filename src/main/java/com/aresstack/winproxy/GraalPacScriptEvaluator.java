package com.aresstack.winproxy;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

import java.net.URI;

/**
 * Evaluates PAC scripts with GraalJS.
 */
public final class GraalPacScriptEvaluator implements PacEvaluator {

    private final ProxyResultParser proxyResultParser;

    public GraalPacScriptEvaluator(ProxyResultParser proxyResultParser) {
        this.proxyResultParser = proxyResultParser;
    }

    public ProxyResult evaluate(String pacScript, String targetUrl) {
        if (pacScript == null || pacScript.trim().length() == 0) {
            return ProxyResult.direct();
        }
        try {
            URI uri = URI.create(targetUrl);
            String host = uri.getHost();
            Context context = Context.newBuilder("js")
                    .allowAllAccess(false)
                    .build();
            try {
                context.eval("js", createPacHelperScript());
                context.eval("js", pacScript);
                Value function = context.getBindings("js").getMember("FindProxyForURL");
                if (function == null || !function.canExecute()) {
                    return ProxyResult.direct();
                }
                Value value = function.execute(targetUrl, host);
                return proxyResultParser.parse(value == null ? null : value.asString());
            } finally {
                context.close();
            }
        } catch (RuntimeException e) {
            throw new ProxyResolutionException("Could not evaluate PAC script for " + targetUrl + ".", e);
        }
    }

    public ProxyResult evaluateFromPacUrl(String pacUrl, String targetUrl) {
        String pacScript = new UrlConnectionPacScriptLoader().load(pacUrl);
        return evaluate(pacScript, targetUrl);
    }

    private String createPacHelperScript() {
        return "function dnsDomainIs(host, domain) { return host.length >= domain.length && host.substring(host.length - domain.length) === domain; }\n" +
                "function shExpMatch(str, pattern) { var re = '^' + pattern.replace(/\\./g, '\\\\.').replace(/\\*/g, '.*').replace(/\\?/g, '.') + '$'; return new RegExp(re).test(str); }\n" +
                "function isPlainHostName(host) { return host.indexOf('.') < 0; }\n" +
                "function localHostOrDomainIs(host, hostdom) { return host === hostdom || (hostdom.indexOf(host + '.') === 0); }\n" +
                "function dnsDomainLevels(host) { return host.split('.').length - 1; }\n" +
                "function isResolvable(host) { return true; }\n" +
                "function isInNet(host, pattern, mask) { return false; }\n" +
                "function myIpAddress() { return '127.0.0.1'; }\n" +
                "function dnsResolve(host) { return host; }\n" +
                "function weekdayRange() { return false; }\n" +
                "function dateRange() { return false; }\n" +
                "function timeRange() { return false; }\n";
    }
}
