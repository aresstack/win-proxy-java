package com.aresstack.winproxy;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads values from the Windows Registry via {@code reg.exe}.
 * <p>
 * Works on all Windows machines regardless of PowerShell CLM restrictions.
 * Group Policy keys are checked first (GPO always overrides user-level settings).
 */
final class RegistryReader {

    private static final Logger LOG = Logger.getLogger(RegistryReader.class.getName());
    private static final int TIMEOUT_SECONDS = 5;

    static final String CONNECTIONS_KEY =
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\\Connections";

    static final int FLAG_AUTO_DETECT = 0x08;
    static final int FLAG_AUTO_CONFIG = 0x04;
    static final int FLAG_PROXY = 0x02;

    /** All registry keys to search, in priority order (GPO first). */
    static final String[] SETTINGS_KEYS = {
            "HKCU\\Software\\Policies\\Microsoft\\Windows\\CurrentVersion\\Internet Settings",
            "HKLM\\Software\\Policies\\Microsoft\\Windows\\CurrentVersion\\Internet Settings",
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings",
            "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings",
    };

    private RegistryReader() {}

    static String queryValue(String key, String valueName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("reg", "query", key, "/v", valueName);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            for (String line : out.toString().split("\\r?\\n")) {
                String trimmed = line.trim();
                if (trimmed.contains(valueName)) {
                    String[] parts = trimmed.split("\\s{2,}");
                    if (parts.length >= 3) {
                        return parts[parts.length - 1].trim();
                    }
                }
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "[WinProxy] reg query failed for " + valueName, e);
        }
        return null;
    }

    static String queryValueFromAllHives(String valueName) {
        for (String key : SETTINGS_KEYS) {
            String value = queryValue(key, valueName);
            if (value != null && !value.trim().isEmpty()) {
                LOG.fine("[WinProxy] Found " + valueName + " in " + key + " = " + value);
                return value.trim();
            }
        }
        return null;
    }

    static int queryConnectionFlags() {
        String hex = readConnectionSettingsHex();
        if (hex == null || hex.length() < 18) return -1;
        try {
            return Integer.parseInt(hex.substring(16, 18), 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static String queryAutoConfigUrlFromBlob() {
        String hex = readConnectionSettingsHex();
        if (hex == null) return null;

        try {
            if (hex.length() < 18) return null;
            int flags = Integer.parseInt(hex.substring(16, 18), 16);
            if ((flags & FLAG_AUTO_CONFIG) == 0) return null;

            int pos = 24;
            if (hex.length() < pos + 8) return null;

            int proxyLen = readDwordLE(hex, pos);
            pos += 8 + (proxyLen * 2);

            if (hex.length() < pos + 8) return null;
            int overrideLen = readDwordLE(hex, pos);
            pos += 8 + (overrideLen * 2);

            if (hex.length() < pos + 8) return null;
            int pacLen = readDwordLE(hex, pos);
            pos += 8;

            if (pacLen <= 0 || pacLen > 4096) return null;
            if (hex.length() < pos + (pacLen * 2)) return null;

            StringBuilder pacUrl = new StringBuilder(pacLen);
            for (int i = 0; i < pacLen; i++) {
                int byteVal = Integer.parseInt(hex.substring(pos + (i * 2), pos + (i * 2) + 2), 16);
                pacUrl.append((char) byteVal);
            }

            String result = pacUrl.toString().trim();
            if (result.isEmpty()) return null;
            LOG.fine("[WinProxy] Found PAC URL embedded in DefaultConnectionSettings: " + result);
            return result;
        } catch (Exception e) {
            LOG.log(Level.FINE, "[WinProxy] Failed to parse PAC URL from blob", e);
            return null;
        }
    }

    private static int readDwordLE(String hex, int hexPos) {
        if (hex.length() < hexPos + 8) return 0;
        int b0 = Integer.parseInt(hex.substring(hexPos, hexPos + 2), 16);
        int b1 = Integer.parseInt(hex.substring(hexPos + 2, hexPos + 4), 16);
        int b2 = Integer.parseInt(hex.substring(hexPos + 4, hexPos + 6), 16);
        int b3 = Integer.parseInt(hex.substring(hexPos + 6, hexPos + 8), 16);
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private static String readConnectionSettingsHex() {
        String[] connectionKeys = {
                CONNECTIONS_KEY,
                "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\\Connections",
        };
        for (String connKey : connectionKeys) {
            String hex = readBlobHex(connKey, "DefaultConnectionSettings");
            if (hex != null && hex.length() >= 18) return hex;
        }
        return null;
    }

    private static String readBlobHex(String key, String valueName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("reg", "query", key, "/v", valueName);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            for (String line : out.toString().split("\\r?\\n")) {
                String trimmed = line.trim();
                if (trimmed.contains(valueName) && trimmed.contains("REG_BINARY")) {
                    String[] parts = trimmed.split("\\s{2,}");
                    if (parts.length >= 3) {
                        return parts[parts.length - 1].trim().replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
                    }
                }
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "[WinProxy] Failed to read " + valueName + " from " + key, e);
        }
        return null;
    }
}

