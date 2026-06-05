package com.aresstack.winproxy;

import java.net.URI;

/**
 * Matches target URLs against Windows proxy bypass patterns.
 */
public final class ProxyBypassMatcher {

    public boolean isBypassed(String targetUrl, String bypassList) {
        if (bypassList == null || bypassList.trim().length() == 0) {
            return false;
        }
        URI uri = URI.create(targetUrl);
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        String[] entries = bypassList.split(";");
        for (int i = 0; i < entries.length; i++) {
            if (matches(host, entries[i].trim())) {
                return true;
            }
        }
        return false;
    }

    private boolean matches(String host, String pattern) {
        if (pattern.length() == 0) {
            return false;
        }
        if ("<local>".equalsIgnoreCase(pattern) && host.indexOf('.') < 0) {
            return true;
        }
        String regex = pattern.replace(".", "\\.").replace("*", ".*");
        return host.matches(regex);
    }
}
