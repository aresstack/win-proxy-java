package com.aresstack.winproxy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Loads PAC script content through {@link java.net.URLConnection}. For HTTP(S) it evaluates
 * {@code Cache-Control: max-age}/{@code no-cache}/{@code no-store} and {@code Expires}, and
 * revalidates a previous download with {@code If-None-Match}/{@code If-Modified-Since}.
 */
public final class UrlConnectionPacScriptLoader implements ConditionalPacScriptLoader {

    private static final int CONNECT_TIMEOUT_MILLIS = 5000;
    private static final int READ_TIMEOUT_MILLIS = 10000;

    public String load(String pacUrl) {
        return load(pacUrl, null).getContent();
    }

    @Override
    public PacScript load(String pacUrl, PacScript previous) {
        if (pacUrl == null || pacUrl.trim().length() == 0) {
            throw new ProxyResolutionException("PAC URL must not be empty.");
        }
        try {
            URL url = new URL(pacUrl);
            URLConnection connection = url.openConnection(Proxy.NO_PROXY);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            boolean conditional = previous != null && connection instanceof HttpURLConnection
                    && (previous.getEtag() != null || previous.getLastModified() != null);
            if (conditional) {
                if (previous.getEtag() != null) {
                    connection.setRequestProperty("If-None-Match", previous.getEtag());
                }
                if (previous.getLastModified() != null) {
                    connection.setRequestProperty("If-Modified-Since", previous.getLastModified());
                }
            }
            if (connection instanceof HttpURLConnection) {
                int status = ((HttpURLConnection) connection).getResponseCode();
                if (status == HttpURLConnection.HTTP_NOT_MODIFIED && conditional) {
                    return new PacScript(previous.getContent(), maxAgeMillis(connection), previous.getEtag(),
                            previous.getLastModified(), true);
                }
                if (status < 200 || status >= 300) {
                    throw new IOException("HTTP " + status);
                }
            }
            String content = read(connection);
            return new PacScript(content, maxAgeMillis(connection), connection.getHeaderField("ETag"),
                    connection.getHeaderField("Last-Modified"), false);
        } catch (IOException e) {
            throw new ProxyResolutionException("Could not load PAC script from " + pacUrl + ".", e);
        }
    }

    private static String read(URLConnection connection) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
        try {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
            return builder.toString();
        } finally {
            reader.close();
        }
    }

    private static long maxAgeMillis(URLConnection connection) {
        return maxAgeMillis(connection.getHeaderField("Cache-Control"), connection.getHeaderFieldDate("Expires", -1L),
                connection.getHeaderFieldDate("Date", -1L), System.currentTimeMillis());
    }

    /**
     * Lifetime announced by the response: {@code no-cache}/{@code no-store} → 0,
     * {@code max-age=N} → N seconds, otherwise {@code Expires} relative to {@code Date} (or now),
     * otherwise {@link PacScript#NO_MAX_AGE}.
     */
    static long maxAgeMillis(String cacheControl, long expires, long date, long now) {
        if (cacheControl != null) {
            for (String part : cacheControl.split(",")) {
                String directive = part.trim().toLowerCase(Locale.ROOT);
                if (directive.equals("no-cache") || directive.equals("no-store")) {
                    return 0L;
                }
                if (directive.startsWith("max-age=")) {
                    try {
                        return Math.max(0L, Long.parseLong(directive.substring(8).replace("\"", "").trim()) * 1000L);
                    } catch (NumberFormatException ignored) {
                        // fall through to Expires
                    }
                }
            }
        }
        if (expires > 0) {
            return Math.max(0L, expires - (date > 0 ? date : now));
        }
        return PacScript.NO_MAX_AGE;
    }
}
