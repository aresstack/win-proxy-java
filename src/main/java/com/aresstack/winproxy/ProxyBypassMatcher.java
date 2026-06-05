package com.aresstack.winproxy;

import java.net.URI;
import java.util.Locale;

/**
 * Matches target URLs against Windows proxy bypass patterns.
 */
public final class ProxyBypassMatcher {

    public boolean isBypassed(String targetUrl, String bypassList) {
        if (bypassList == null || bypassList.trim().length() == 0) {
            return false;
        }
        String host = extractHost(targetUrl);
        if (host == null || host.trim().length() == 0) {
            return false;
        }
        String normalizedHost = host.toLowerCase(Locale.ENGLISH);
        String[] entries = bypassList.split(";");
        for (int i = 0; i < entries.length; i++) {
            if (matches(normalizedHost, entries[i].trim().toLowerCase(Locale.ENGLISH))) {
                return true;
            }
        }
        return false;
    }

    private String extractHost(String targetUrl) {
        if (targetUrl == null || targetUrl.trim().length() == 0) {
            return null;
        }
        try {
            URI uri = URI.create(targetUrl);
            if (uri.getHost() != null) {
                return uri.getHost();
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    private boolean matches(String host, String pattern) {
        if (pattern.length() == 0) {
            return false;
        }
        if ("<local>".equals(pattern) && host.indexOf('.') < 0) {
            return true;
        }
        String regex = pattern.replace(".", "\\.").replace("*", ".*");
        return host.matches(regex);
    }
}
