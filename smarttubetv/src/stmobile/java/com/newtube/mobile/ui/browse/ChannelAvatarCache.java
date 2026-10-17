package com.newtube.mobile.ui.browse;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.ContentService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * NEWTUBE(channel-avatars): feed videos carry only a channel id, no avatar image, so the round disc
 * under each card showed just the channel's first letter. The account's subscription list does
 * carry every channel's avatar (a channel item's card image IS its avatar), so it is read once,
 * all pages, into a channelId -> url map the cards look up. A channel the user is not subscribed to
 * (or a signed-out session) simply has no entry and keeps the letter.
 */
final class ChannelAvatarCache {
    private static final String TAG = ChannelAvatarCache.class.getSimpleName();
    private static final int MAX_PAGES = 15;
    private static final long RETRY_AFTER_MS = 60_000;

    interface Listener {
        void onAvatarsLoaded();
    }

    private static final Map<String, String> URLS = new ConcurrentHashMap<>();
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static boolean sLoading;
    private static boolean sLoaded;
    private static long sLastAttemptMs;

    private ChannelAvatarCache() {
    }

    @Nullable
    static String get(@Nullable String channelId) {
        return channelId == null ? null : URLS.get(channelId);
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
                Log.e(TAG, "load error: %s", e.getMessage());
            }
            final boolean loaded = any || !URLS.isEmpty();
            MAIN.post(() -> {
                sLoading = false;
                sLoaded = loaded;
                if (loaded) {
                    for (Listener listener : LISTENERS) {
                        listener.onAvatarsLoaded();
                    }
                }
            });
        });
    }

    private static boolean collect(MediaGroup group) {
        List<MediaItem> items = group.getMediaItems();
        if (items == null) {
            return false;
        }
        boolean any = false;
        for (MediaItem item : items) {
            String id = item.getChannelId();
            String url = item.getCardImageUrl();
            if (id == null || url == null || url.isEmpty()) {
                continue;
            }
            if (url.startsWith("//")) {
                url = "https:" + url;
            }
            URLS.put(id, url);
            any = true;
        }
        return any;
    }
}
