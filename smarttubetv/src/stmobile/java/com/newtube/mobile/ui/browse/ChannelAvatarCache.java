package com.newtube.mobile.ui.browse;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.ContentService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * NEWTUBE(channel-avatars): feed videos carry no avatar image, and often not even a channel id, so
 * the round disc under each card showed just the channel's first letter. Two sources fill this map,
 * keyed by channel id AND by channel name (the feed usually has only the name):
 * <ol>
 *   <li>the account's subscription list (one read, all pages): every channel's avatar at once;</li>
 *   <li>for any other channel, the video's own metadata (the same call the player uses for its
 *       channel icon), fetched lazily for the cards actually shown, two at a time, once per channel.</li>
 * </ol>
 * A channel neither source knows keeps the letter.
 */
final class ChannelAvatarCache {
    private static final String TAG = ChannelAvatarCache.class.getSimpleName();
    private static final int MAX_PAGES = 15;
    private static final long RETRY_AFTER_MS = 60_000;
    private static final long NOTIFY_DEBOUNCE_MS = 250;

    interface Listener {
        void onAvatarsLoaded();
    }

    private static final Map<String, String> URLS = new ConcurrentHashMap<>();
    private static final Set<String> REQUESTED = ConcurrentHashMap.newKeySet();
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService METADATA_POOL = Executors.newFixedThreadPool(2);
    private static final Runnable NOTIFY = () -> {
        for (Listener listener : LISTENERS) {
            listener.onAvatarsLoaded();
        }
    };

    private static boolean sLoading;
    private static boolean sLoaded;
    private static long sLastAttemptMs;

    private ChannelAvatarCache() {
    }

    @Nullable
    static String get(@Nullable String channelId, @Nullable String author) {
        String url = channelId != null ? URLS.get("id:" + channelId) : null;
        if (url == null) {
            String name = normalize(author);
            if (name != null) {
                url = URLS.get("n:" + name);
            }
        }
        return url;
    }

    static void addListener(Listener listener) {
        if (!LISTENERS.contains(listener)) {
            LISTENERS.add(listener);
        }
    }

    static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    /** Main thread. Loads once; an empty/failed load (signed out, offline) retries after a minute. */
    static void ensureLoaded() {
        if (sLoading || sLoaded) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (sLastAttemptMs != 0 && now - sLastAttemptMs < RETRY_AFTER_MS) {
            return;
        }
        sLastAttemptMs = now;
        sLoading = true;

        Schedulers.io().scheduleDirect(() -> {
            boolean any = false;
            try {
                ContentService service = YouTubeServiceManager.instance().getContentService();
                MediaGroup group = service.getSubscribedChannelsObserve().blockingFirst();
                for (int page = 0; group != null && page < MAX_PAGES; page++) {
                    any |= collect(group);
                    if (group.getNextPageKey() == null) {
                        break;
                    }
                    group = service.continueGroupObserve(group).blockingFirst();
                }
            } catch (Throwable e) {
                Log.e(TAG, "subscriptions load error: %s", e.getMessage());
            }
            final boolean loaded = any;
            MAIN.post(() -> {
                sLoading = false;
                sLoaded = loaded;
                scheduleNotify();
            });
        });
    }

    /**
     * Main thread. A card whose channel is not known yet: look the picture up from the video's own
     * metadata, once per channel. The result arrives through the listener.
     */
    static void request(@Nullable Video video) {
        if (video == null || video.videoId == null) {
            return;
        }
        String name = normalize(video.getAuthor());
        String key = video.channelId != null ? "id:" + video.channelId : name != null ? "n:" + name : null;
        if (key == null || URLS.containsKey(key) || !REQUESTED.add(key)) {
            return;
        }
        final String videoId = video.videoId;
        final String channelId = video.channelId;
        final String feedName = name;
        METADATA_POOL.execute(() -> {
            try {
                MediaItemMetadata metadata = YouTubeServiceManager.instance().getMediaItemService()
                        .getMetadataObserve(videoId, null, 0, null).blockingFirst();
                String url = fix(metadata.getAuthorImageUrl());
                if (url == null) {
                    return;
                }
                if (channelId != null) {
                    URLS.put("id:" + channelId, url);
                }
                if (metadata.getChannelId() != null) {
                    URLS.put("id:" + metadata.getChannelId(), url);
                }
                if (feedName != null) {
                    URLS.put("n:" + feedName, url);
                }
                String metaName = normalize(metadata.getAuthor());
                if (metaName != null) {
                    URLS.put("n:" + metaName, url);
                }
                MAIN.post(ChannelAvatarCache::scheduleNotify);
            } catch (Throwable e) {
                Log.e(TAG, "metadata avatar error: %s", e.getMessage());
            }
        });
    }

    private static void scheduleNotify() {
        MAIN.removeCallbacks(NOTIFY);
        MAIN.postDelayed(NOTIFY, NOTIFY_DEBOUNCE_MS);
    }

    private static boolean collect(MediaGroup group) {
        List<MediaItem> items = group.getMediaItems();
        if (items == null) {
            return false;
        }
        boolean any = false;
        for (MediaItem item : items) {
            String url = fix(item.getCardImageUrl());
            if (url == null) {
                continue;
            }
            if (item.getChannelId() != null) {
                URLS.put("id:" + item.getChannelId(), url);
                any = true;
            }
            String title = normalize(item.getTitle());
            if (title != null) {
                URLS.put("n:" + title, url);
                any = true;
            }
            String author = normalize(item.getAuthor());
            if (author != null) {
                URLS.put("n:" + author, url);
                any = true;
            }
        }
        return any;
    }

    @Nullable
    private static String fix(@Nullable String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        return url.startsWith("//") ? "https:" + url : url;
    }

    /** "@Luis  Gabriel " and "luis gabriel" are the same channel name. */
    @Nullable
    private static String normalize(@Nullable String name) {
        if (name == null) {
            return null;
        }
        String n = name.trim();
        if (n.startsWith("@")) {
            n = n.substring(1);
        }
        n = n.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return n.isEmpty() ? null : n;
    }
}
