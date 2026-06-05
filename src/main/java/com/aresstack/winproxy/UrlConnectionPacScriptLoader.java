package com.aresstack.winproxy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;

/**
 * Loads PAC script content through {@link java.net.URLConnection}.
 */
public final class UrlConnectionPacScriptLoader implements PacScriptLoader {

    private static final int CONNECT_TIMEOUT_MILLIS = 5000;
    private static final int READ_TIMEOUT_MILLIS = 10000;

    public String load(String pacUrl) {
        if (pacUrl == null || pacUrl.trim().length() == 0) {
            throw new ProxyResolutionException("PAC URL must not be empty.");
        }
        try {
            URL url = new URL(pacUrl);
            URLConnection connection = url.openConnection(Proxy.NO_PROXY);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
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
        } catch (IOException e) {
            throw new ProxyResolutionException("Could not load PAC script from " + pacUrl + ".", e);
        }
    }
}
