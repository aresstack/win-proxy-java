package com.aresstack.winproxy;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

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
            return ProxyResult.direct("empty-pac-script");
        }
        try {
            URI uri = URI.create(targetUrl);
            String host = uri.getHost();
            Context context = Context.newBuilder("js")
                    .allowAllAccess(false)
                    .build();
            try {
                context.getBindings("js").putMember("pacHostResolver", new PacHostResolver());
                context.eval("js", createPacHelperScript());
                context.eval("js", pacScript);
                Value function = context.getBindings("js").getMember("FindProxyForURL");
                if (function == null || !function.canExecute()) {
                    return ProxyResult.direct("missing-find-proxy-for-url");
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
        return "function pacString(value) { return value === null || value === undefined ? '' : String(value); }\n" +
                "function pacLower(value) { return pacString(value).toLowerCase(); }\n" +
                "function dnsDomainIs(host, domain) { var h = pacLower(host); var d = pacLower(domain); return h.length >= d.length && h.substring(h.length - d.length) === d; }\n" +
                "function shExpMatch(str, pattern) { var escaped = pacString(pattern).replace(/[.+^${}()|[\\]\\\\]/g, '\\\\$&'); var re = '^' + escaped.replace(/\\*/g, '.*').replace(/\\?/g, '.') + '$'; return new RegExp(re, 'i').test(pacString(str)); }\n" +
                "function isPlainHostName(host) { return pacString(host).indexOf('.') < 0; }\n" +
                "function localHostOrDomainIs(host, hostdom) { var h = pacLower(host); var hd = pacLower(hostdom); return h === hd || (hd.indexOf(h + '.') === 0); }\n" +
                "function dnsDomainLevels(host) { return pacString(host).split('.').length - 1; }\n" +
                "function dnsResolve(host) { return pacHostResolver.dnsResolve(pacString(host)); }\n" +
                "function isResolvable(host) { return dnsResolve(host) !== null; }\n" +
                "function isInNet(host, pattern, mask) { var resolved = dnsResolve(host); if (resolved === null) { return false; } return pacHostResolver.isInNet(resolved, pacString(pattern), pacString(mask)); }\n" +
                "function myIpAddress() { return pacHostResolver.myIpAddress(); }\n" +
                "function weekdayRange() { return pacHostResolver.weekdayRange.apply(pacHostResolver, arguments); }\n" +
                "function dateRange() { return pacHostResolver.dateRange.apply(pacHostResolver, arguments); }\n" +
                "function timeRange() { return pacHostResolver.timeRange.apply(pacHostResolver, arguments); }\n";
    }

    /**
     * Provides PAC helper implementations to GraalJS.
     */
    public static final class PacHostResolver {

        public String dnsResolve(String host) {
            if (host == null || host.trim().length() == 0) {
                return null;
            }
            try {
                return InetAddress.getByName(host).getHostAddress();
            } catch (UnknownHostException e) {
                return null;
            }
        }

        public String myIpAddress() {
            try {
                return InetAddress.getLocalHost().getHostAddress();
            } catch (UnknownHostException e) {
                return "127.0.0.1";
            }
        }

        public boolean isInNet(String address, String pattern, String mask) {
            try {
                long addressValue = toIpv4(address);
                long patternValue = toIpv4(pattern);
                long maskValue = toIpv4(mask);
                return (addressValue & maskValue) == (patternValue & maskValue);
            } catch (RuntimeException e) {
                return false;
            }
        }

        public boolean weekdayRange(Object first, Object second, Object third, Object fourth) {
            return true;
        }

        public boolean dateRange(Object first, Object second, Object third, Object fourth, Object fifth, Object sixth, Object seventh) {
            return true;
        }

        public boolean timeRange(Object first, Object second, Object third, Object fourth, Object fifth, Object sixth, Object seventh) {
            return true;
        }

        private long toIpv4(String address) {
            String[] parts = address.split("\\.");
            if (parts.length != 4) {
                throw new IllegalArgumentException("Not an IPv4 address: " + address);
            }
            long value = 0;
            for (int i = 0; i < parts.length; i++) {
                int part = Integer.parseInt(parts[i]);
                if (part < 0 || part > 255) {
                    throw new IllegalArgumentException("Invalid IPv4 segment: " + parts[i]);
                }
                value = (value << 8) | part;
            }
            return value;
        }
    }
}
