package com.noah.spotifycontroller.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Snapshot of the active Spotify playback state returned by {@code GET /v1/me/player}.
 */
public record TrackState(
        String trackId,
        String trackName,
        String artistName,
        String albumName,
        String albumArtUrl,
        long progressMs,
        long durationMs,
        boolean isPlaying,
        int volumePercent,
        boolean shuffleState,
        String repeatState,
        long fetchedAtMillis,
        String spotifyUrl
) {
    /** Minecraft's chat message length limit; longer messages are rejected by the server. */
    public static final int MAX_CHAT_LENGTH = 256;
    private static final String SPOTIFY_WEB_PREFIX = "https://open.spotify.com/";
    private static final int MAX_QUEUE_ITEMS = 10;

    /** One upcoming entry from {@code GET /v1/me/player/queue}. */
    public record QueueItem(String name, String artist) {
    }

    /**
     * Fits {@code text + " " + url} into one chat message: the URL is kept whole, the text is trimmed,
     * and characters Minecraft servers reject (control chars, the section sign) are removed.
     */
    public static String fitChatMessage(String text, String url) {
        StringBuilder clean = new StringBuilder();
        for (char c : (text == null ? "" : text).toCharArray()) {
            if (c >= 32 && c != 127 && c != '§') {
                clean.append(c);
            }
        }
        String suffix = (url == null || url.isEmpty()) ? "" : " " + url;
        int room = MAX_CHAT_LENGTH - suffix.length();
        String body = clean.toString();
        if (body.length() > room) {
            body = body.substring(0, Math.max(0, room - 1)) + "…";
        }
        return body + suffix;
    }

    /**
     * Parses {@code GET /v1/me/player/queue} into at most 10 upcoming entries (empty on bad input).
     */
    public static List<QueueItem> parseQueue(String json) {
        List<QueueItem> items = new ArrayList<>();
        try {
            JsonElement root = JsonParser.parseString(json == null ? "" : json);
            if (!root.isJsonObject() || !root.getAsJsonObject().has("queue")
                    || !root.getAsJsonObject().get("queue").isJsonArray()) {
                return items;
            }
            for (JsonElement element : root.getAsJsonObject().getAsJsonArray("queue")) {
                if (items.size() >= MAX_QUEUE_ITEMS) {
                    break;
                }
                if (element.isJsonObject()) {
                    JsonObject item = element.getAsJsonObject();
                    items.add(new QueueItem(getStringOrDefault(item, "name", "Unknown Track"), extractArtistName(item)));
                }
            }
        } catch (JsonSyntaxException | IllegalStateException | UnsupportedOperationException e) {
            items.clear();
        }
        return items;
    }

    /**
     * Estimates current track playback position by advancing {@link #progressMs}
     * with elapsed wall-clock time while playing.
     */
    public long getInterpolatedProgressMs() {
        if (!isPlaying) {
            return Math.max(0L, progressMs);
        }
        long elapsed = Math.max(0L, System.currentTimeMillis() - fetchedAtMillis);
        long estimated = Math.max(0L, progressMs) + elapsed;
        return durationMs > 0 ? Math.min(durationMs, estimated) : estimated;
    }

    /**
     * Formats milliseconds as {@code m:ss} (or {@code h:mm:ss} for tracks >= 1 hour).
     */
    public static String formatDuration(long ms) {
        long totalSeconds = Math.max(0L, ms) / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%d:%02d", minutes, seconds);
    }

    /**
     * Parses a Spotify {@code /v1/me/player} JSON payload into a {@link TrackState}.
     * Returns empty if there is no playable item (e.g. ad break or idle player).
     */
    public static Optional<TrackState> fromPlayerJson(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonElement rootElement = JsonParser.parseString(json);
            if (!rootElement.isJsonObject()) {
                return Optional.empty();
            }
            JsonObject root = rootElement.getAsJsonObject();
            if (!root.has("item") || root.get("item").isJsonNull() || !root.get("item").isJsonObject()) {
                return Optional.empty();
            }

            JsonObject item = root.getAsJsonObject("item");
            String trackId = getStringOrDefault(item, "id", "");
            String trackName = getStringOrDefault(item, "name", "Unknown Track");
            long durationMs = getLongOrDefault(item, "duration_ms", 0L);

            String artistName = extractArtistName(item);
            String albumName = "";
            String albumArtUrl = "";
            if (item.has("album") && item.get("album").isJsonObject()) {
                JsonObject album = item.getAsJsonObject("album");
                albumName = getStringOrDefault(album, "name", "");
                albumArtUrl = extractBestImageUrl(album);
            } else {
                // Podcast episodes place images directly on the item or show object.
                albumArtUrl = extractBestImageUrl(item);
                if (albumArtUrl.isEmpty() && item.has("show") && item.get("show").isJsonObject()) {
                    JsonObject show = item.getAsJsonObject("show");
                    albumName = getStringOrDefault(show, "name", "");
                    albumArtUrl = extractBestImageUrl(show);
                }
            }

            long progressMs = getLongOrDefault(root, "progress_ms", 0L);
            boolean isPlaying = getBooleanOrDefault(root, "is_playing", false);
            boolean shuffleState = getBooleanOrDefault(root, "shuffle_state", false);
            String repeatState = getStringOrDefault(root, "repeat_state", "off");

            int volumePercent = -1;
            if (root.has("device") && root.get("device").isJsonObject()) {
                JsonObject device = root.getAsJsonObject("device");
                if (device.has("volume_percent") && !device.get("volume_percent").isJsonNull()) {
                    volumePercent = device.get("volume_percent").getAsInt();
                }
            }

            return Optional.of(new TrackState(
                    trackId,
                    trackName,
                    artistName,
                    albumName,
                    albumArtUrl,
                    progressMs,
                    durationMs,
                    isPlaying,
                    volumePercent,
                    shuffleState,
                    repeatState,
                    System.currentTimeMillis(),
                    extractSpotifyUrl(item)
            ));
        } catch (JsonSyntaxException | IllegalStateException | UnsupportedOperationException e) {
            return Optional.empty();
        }
    }

    /** Only links on open.spotify.com are shared, so a malformed response can't put another URL in chat. */
    private static String extractSpotifyUrl(JsonObject item) {
        if (item.has("external_urls") && item.get("external_urls").isJsonObject()) {
            String url = getStringOrDefault(item.getAsJsonObject("external_urls"), "spotify", "");
            if (url.startsWith(SPOTIFY_WEB_PREFIX) && url.indexOf(' ') < 0) {
                return url;
            }
        }
        return "";
    }

    private static String extractArtistName(JsonObject item) {
        if (item.has("artists") && item.get("artists").isJsonArray()) {
            JsonArray artists = item.getAsJsonArray("artists");
            List<String> names = new ArrayList<>();
            for (JsonElement element : artists) {
                if (element.isJsonObject()) {
                    String name = getStringOrDefault(element.getAsJsonObject(), "name", "");
                    if (!name.isEmpty()) {
                        names.add(name);
                    }
                }
            }
            if (!names.isEmpty()) {
                return String.join(", ", names);
            }
        }
        if (item.has("show") && item.get("show").isJsonObject()) {
            String publisher = getStringOrDefault(item.getAsJsonObject("show"), "publisher", "");
            if (!publisher.isEmpty()) {
                return publisher;
            }
            String showName = getStringOrDefault(item.getAsJsonObject("show"), "name", "");
            if (!showName.isEmpty()) {
                return showName;
            }
        }
        return "Unknown Artist";
    }

    private static String extractBestImageUrl(JsonObject container) {
        if (!container.has("images") || !container.get("images").isJsonArray()) {
            return "";
        }
        JsonArray images = container.getAsJsonArray("images");
        String bestUrl = "";
        int bestDelta = Integer.MAX_VALUE;
        for (JsonElement element : images) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject image = element.getAsJsonObject();
            String url = getStringOrDefault(image, "url", "");
            if (url.isEmpty()) {
                continue;
            }
            int width = (image.has("width") && !image.get("width").isJsonNull())
                    ? image.get("width").getAsInt()
                    : 0;
            // Prefer ~300px album art for crisp HUD/GUI downscaling without huge downloads.
            int delta = width > 0 ? Math.abs(width - 300) : 1000;
            if (bestUrl.isEmpty() || delta < bestDelta) {
                bestUrl = url;
                bestDelta = delta;
            }
        }
        return bestUrl;
    }

    private static String getStringOrDefault(JsonObject obj, String key, String fallback) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return fallback;
    }

    private static long getLongOrDefault(JsonObject obj, String key, long fallback) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsLong();
        }
        return fallback;
    }

    private static boolean getBooleanOrDefault(JsonObject obj, String key, boolean fallback) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsBoolean();
        }
        return fallback;
    }
}
