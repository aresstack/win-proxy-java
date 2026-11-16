package com.aresstack.winproxy;

import java.time.Instant;

/**
 * State of the remembered PAC script of a {@link WindowsProxyResolver}, for a status line or a
 * log: when it was loaded, until when it is valid, and whether refreshing it currently fails
 * (the last good script is then still in use). Times are epoch milliseconds, {@code 0} = never.
 *
 * @since 0.3.0
 */
public final class PacCacheStatus {

    private final String pacUrl;
    private final long loadedAt;
    private final long validUntil;
    private final long refreshFailingSince;
    private final int consecutiveFailures;
    private final String lastFailure;

    PacCacheStatus(String pacUrl, long loadedAt, long validUntil, long refreshFailingSince,
                   int consecutiveFailures, String lastFailure) {
        this.pacUrl = pacUrl;
        this.loadedAt = loadedAt;
        this.validUntil = validUntil;
        this.refreshFailingSince = refreshFailingSince;
        this.consecutiveFailures = consecutiveFailures;
        this.lastFailure = lastFailure;
    }

    /** @return the PAC URL of the remembered script, or {@code null} if none is remembered */
    public String getPacUrl() {
        return pacUrl;
    }

    /** @return when the remembered script was last loaded or revalidated, {@code 0} = never */
    public long getLoadedAt() {
        return loadedAt;
    }

    /** @return until when the remembered script is used without reloading, {@code 0} = none */
    public long getValidUntil() {
        return validUntil;
    }

    /** @return since when loading or refreshing fails, {@code 0} = not failing */
    public long getRefreshFailingSince() {
        return refreshFailingSince;
    }

    /** @return number of failed attempts in a row */
    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    /** @return message of the last failed attempt, or {@code null} */
    public String getLastFailure() {
        return lastFailure;
    }

    /** @return {@code true} while refreshing fails although a last good script is in use */
    public boolean isStale() {
        return pacUrl != null && refreshFailingSince > 0;
    }

    /** One line, e.g. for a log or a status bar. */
    public String describe() {
        StringBuilder text = new StringBuilder();
        if (pacUrl == null) {
            text.append("No PAC script remembered");
        } else {
            text.append("PAC script from ").append(pacUrl).append(" loaded ").append(Instant.ofEpochMilli(loadedAt))
                    .append(", valid until ").append(Instant.ofEpochMilli(validUntil));
        }
        if (refreshFailingSince > 0) {
            text.append("; refresh failing since ").append(Instant.ofEpochMilli(refreshFailingSince))
                    .append(" (").append(consecutiveFailures).append(consecutiveFailures == 1 ? " attempt" : " attempts")
                    .append(")");
            if (lastFailure != null) {
                text.append(": ").append(lastFailure);
            }
        }
        return text.toString();
    }

    @Override
    public String toString() {
        return describe();
    }
}
