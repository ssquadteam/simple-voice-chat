package de.maxhenkel.voicechat.integration.litebans;

import de.maxhenkel.voicechat.Voicechat;
import litebans.api.Database;
import litebans.api.Entry;
import litebans.api.Events;
import litebans.api.exception.MissingImplementationException;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class LiteBansMuteIntegration {

    private static final long CACHE_TTL_MILLIS = 10_000L;

    private final Map<UUID, CacheEntry> muteCache;
    @Nullable
    private Events.Listener listener;
    private boolean eventsUnavailable;
    private boolean databaseWarningLogged;

    public LiteBansMuteIntegration() {
        muteCache = new ConcurrentHashMap<>();
    }

    public boolean isMuted(UUID uuid, @Nullable String ipAddress) {
        registerListener();

        long now = System.currentTimeMillis();
        CacheEntry cached = muteCache.get(uuid);
        if (cached != null && cached.expiresAt() > now) {
            return cached.muted();
        }

        boolean muted = queryMuted(uuid, ipAddress);
        muteCache.put(uuid, new CacheEntry(muted, now + CACHE_TTL_MILLIS));
        return muted;
    }

    private boolean queryMuted(UUID uuid, @Nullable String ipAddress) {
        try {
            if (ipAddress == null || ipAddress.isBlank()) {
                return Database.get().isPlayerMuted(uuid, Database.ANY_SERVER_SCOPE);
            }
            return Database.get().isPlayerMuted(uuid, ipAddress, Database.ANY_SERVER_SCOPE);
        } catch (MissingImplementationException e) {
            logDatabaseWarning("LiteBans is enabled, but its database API is not ready", e);
        } catch (Throwable t) {
            logDatabaseWarning("Failed to query LiteBans mute state", t);
        }
        return false;
    }

    private void registerListener() {
        if (listener != null || eventsUnavailable) {
            return;
        }

        try {
            listener = new Events.Listener() {
                @Override
                public void entryAdded(Entry entry) {
                    handleEntryChanged(entry);
                }

                @Override
                public void entryRemoved(Entry entry) {
                    handleEntryChanged(entry);
                }
            };
            Events.get().register(listener);
            Voicechat.LOGGER.info("Hooked LiteBans chat mutes into voice chat");
        } catch (MissingImplementationException e) {
            eventsUnavailable = true;
        } catch (Throwable t) {
            eventsUnavailable = true;
            Voicechat.LOGGER.warn("Failed to register LiteBans mute listener", t);
        }
    }

    private void handleEntryChanged(Entry entry) {
        if (!isMuteEntry(entry)) {
            return;
        }

        UUID uuid = parseUuid(entry.getUuid());
        if (uuid != null) {
            muteCache.remove(uuid);
        } else {
            muteCache.clear();
        }
    }

    private void logDatabaseWarning(String message, Throwable throwable) {
        if (databaseWarningLogged) {
            return;
        }
        databaseWarningLogged = true;
        Voicechat.LOGGER.warn(message, throwable);
    }

    private static boolean isMuteEntry(@Nullable Entry entry) {
        return entry != null && "mute".equalsIgnoreCase(entry.getType());
    }

    @Nullable
    private static UUID parseUuid(@Nullable String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return null;
        }

        try {
            return UUID.fromString(uuid);
        } catch (IllegalArgumentException ignored) {
        }

        String normalized = uuid.replace("-", "");
        if (normalized.length() != 32) {
            return null;
        }

        try {
            return UUID.fromString("%s-%s-%s-%s-%s".formatted(
                    normalized.substring(0, 8),
                    normalized.substring(8, 12),
                    normalized.substring(12, 16),
                    normalized.substring(16, 20),
                    normalized.substring(20)
            ));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private record CacheEntry(boolean muted, long expiresAt) {
    }

}
