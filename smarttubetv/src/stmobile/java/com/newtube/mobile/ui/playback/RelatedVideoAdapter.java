package com.newtube.mobile.ui.playback;

import android.annotation.SuppressLint;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.common.utils.ClickbaitRemover;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * "Up next / related" list adapter for the watch page ({@link MobilePlaybackActivity}).
 * Two looks: full-width cards (Up next) or compact rows (queue).
 */
public class RelatedVideoAdapter extends ListAdapter<Video, RelatedVideoAdapter.RelatedViewHolder> {

    public interface OnRelatedClickListener {
        void onRelatedClick(Video video, @androidx.annotation.Nullable ImageView thumbnail);
    }

    /** NEWTUBE(touch-prefetch, experiment): a finger has rested on a row, see PressIntentDetector. */
    public interface OnRelatedPressListener {
        void onRelatedPress(Video video);
    }

    /** The ⋮ button / long-press on a card: opens the video context menu. */
    public interface OnRelatedLongClickListener {
        void onRelatedLongClick(Video video);
    }

    private final OnRelatedClickListener mClickListener;
    @androidx.annotation.Nullable
    private final OnRelatedPressListener mPressListener;
    private final long mPressIntentMs;
    private final boolean mCardStyle;
    @androidx.annotation.Nullable
    private final OnRelatedLongClickListener mLongClickListener;
    private String mCurrentVideoId;

    public RelatedVideoAdapter(OnRelatedClickListener clickListener) {
        this(clickListener, null, 0);
    }

    public RelatedVideoAdapter(OnRelatedClickListener clickListener,
            @androidx.annotation.Nullable OnRelatedPressListener pressListener, long pressIntentMs) {
        this(clickListener, pressListener, pressIntentMs, false, null);
    }

    public RelatedVideoAdapter(OnRelatedClickListener clickListener,
            @androidx.annotation.Nullable OnRelatedPressListener pressListener, long pressIntentMs,
            boolean cardStyle, @androidx.annotation.Nullable OnRelatedLongClickListener longClickListener) {
        super(DIFF_CALLBACK);
        mCardStyle = cardStyle;
        mLongClickListener = longClickListener;
        mClickListener = clickListener;
        mPressListener = pressIntentMs > 0 ? pressListener : null;
        mPressIntentMs = pressIntentMs;
    }

    public void setCurrentVideoId(String videoId) {
        if (android.text.TextUtils.equals(mCurrentVideoId, videoId)) {
            return;
        }

        mCurrentVideoId = videoId;
        notifyDataSetChanged();
    }

    private static final DiffUtil.ItemCallback<Video> DIFF_CALLBACK = new DiffUtil.ItemCallback<Video>() {
        @Override
        public boolean areItemsTheSame(@NonNull Video oldItem, @NonNull Video newItem) {
            return oldItem.equals(newItem);
        }

        @Override
        @SuppressLint("DiffUtilEquals")
        public boolean areContentsTheSame(@NonNull Video oldItem, @NonNull Video newItem) {
            return oldItem == newItem;
        }
    };

    @NonNull
    @Override
    public RelatedViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(mCardStyle ? R.layout.item_mobile_related_card : R.layout.item_mobile_related_video,
                        parent, false);
        return new RelatedViewHolder(view, mClickListener, mPressListener, mPressIntentMs,
                mCardStyle, mLongClickListener);
    }

    @Override
    public void onBindViewHolder(@NonNull RelatedViewHolder holder, int position) {
        Video video = getItem(position);
        boolean isCurrent = mCurrentVideoId != null && video != null
                && mCurrentVideoId.equals(video.videoId);
        holder.bind(video, isCurrent);
    }

    @Override
    public void onViewRecycled(@NonNull RelatedViewHolder holder) {
        super.onViewRecycled(holder);
        holder.unbind();
    }

    static class RelatedViewHolder extends RecyclerView.ViewHolder {
        private final ImageView mThumbnail;
        private final TextView mBadge;
        private final ProgressBar mWatchProgress;
        private final TextView mTitle;
        private final TextView mSubtitle;
        @androidx.annotation.Nullable
        private final TextView mAvatar;
        @androidx.annotation.Nullable
        private final View mOverflow;
        private final boolean mCardStyle;
        private Video mVideo;

        @SuppressLint("ClickableViewAccessibility")
        RelatedViewHolder(@NonNull View itemView, OnRelatedClickListener clickListener,
                @androidx.annotation.Nullable OnRelatedPressListener pressListener, long pressIntentMs,
                boolean cardStyle, @androidx.annotation.Nullable OnRelatedLongClickListener longClickListener) {
            super(itemView);
            mCardStyle = cardStyle;
            mAvatar = itemView.findViewById(R.id.related_avatar);
            mOverflow = itemView.findViewById(R.id.related_overflow);

            mThumbnail = itemView.findViewById(R.id.related_thumbnail);
            mBadge = itemView.findViewById(R.id.related_badge);
            mWatchProgress = itemView.findViewById(R.id.related_watch_progress);
            mTitle = itemView.findViewById(R.id.related_title);
            mSubtitle = itemView.findViewById(R.id.related_subtitle);

            itemView.setOnClickListener(v -> {
                if (mVideo != null && clickListener != null) {
                    clickListener.onRelatedClick(mVideo, mThumbnail);
                }
            });

            if (longClickListener != null) {
                itemView.setOnLongClickListener(v -> {
                    if (mVideo == null) {
                        return false;
                    }
                    longClickListener.onRelatedLongClick(mVideo);
                    return true;
                });
            }
            if (mOverflow != null) {
                mOverflow.setVisibility(longClickListener == null ? View.GONE : View.VISIBLE);
                mOverflow.setOnClickListener(v -> {
                    if (mVideo != null && longClickListener != null) {
                        longClickListener.onRelatedLongClick(mVideo);
                    }
                });
            }

            if (pressListener != null) {
                PressIntentDetector detector = new PressIntentDetector(
                        android.view.ViewConfiguration.get(itemView.getContext()).getScaledTouchSlop(),
                        pressIntentMs,
                        new PressIntentDetector.Scheduler() {
                            @Override
                            public void postDelayed(Runnable task, long delayMs) {
                                itemView.postDelayed(task, delayMs);
                            }

                            @Override
                            public void remove(Runnable task) {
                                itemView.removeCallbacks(task);
                            }
                        },
                        () -> {
                            if (mVideo != null) {
                                pressListener.onRelatedPress(mVideo);
                            }
                        });
                itemView.setOnTouchListener((v, event) -> detector.onTouch(event));
            }
        }

        void bind(Video video, boolean isCurrent) {
            mVideo = video;
            Context context = itemView.getContext();

            mTitle.setText(video.getTitle());

            CharSequence subtitle = video.getSecondTitle();
            if (subtitle == null || subtitle.length() == 0) {
                subtitle = video.getAuthor();
            }
            if (subtitle != null && subtitle.length() > 0) {
                mSubtitle.setText(subtitle);
                mSubtitle.setVisibility(View.VISIBLE);
            } else {
                mSubtitle.setVisibility(View.GONE);
            }

            bindAvatar(video);
            bindBadge(video, isCurrent);
            bindProgress(video);
            bindThumbnail(context, video);
        }

        /** First letter of the channel on a round disc (card layout only). */
        private void bindAvatar(Video video) {
            if (mAvatar == null) {
                return;
            }
            String author = video.getAuthor();
            if (author != null) {
                author = author.trim();
            }
            if (author == null || author.isEmpty()) {
                mAvatar.setVisibility(View.INVISIBLE);
                return;
            }
            int cp = author.codePointAt(0);
            if (cp == '@' && author.length() > 1) {
                cp = author.codePointAt(1);
            }
            mAvatar.setText(new String(Character.toChars(cp)).toUpperCase());
            mAvatar.setVisibility(View.VISIBLE);
        }

        private void bindBadge(Video video, boolean isCurrent) {
            if (isCurrent) {
                mBadge.setText(R.string.mobile_watch_queue_now_playing);
                mBadge.setVisibility(View.VISIBLE);
                return;
            }

            String badgeText;
            if (video.isLive) {
                badgeText = itemView.getContext().getString(R.string.badge_live);
            } else {
                badgeText = video.badge;
            }

            if (badgeText == null || badgeText.isEmpty()) {
                mBadge.setVisibility(View.GONE);
            } else {
                mBadge.setText(badgeText);
                mBadge.setVisibility(View.VISIBLE);
            }
        }

        private void bindProgress(Video video) {
            int progress = video.percentWatched > 0 && video.percentWatched < 1 ? 1 : Math.round(video.percentWatched);

            if (progress > 0 && progress <= 100) {
                mWatchProgress.setProgress(progress);
                mWatchProgress.setVisibility(View.VISIBLE);
            } else {
                mWatchProgress.setVisibility(View.GONE);
            }
        }

        private void bindThumbnail(Context context, Video video) {
            int thumbQuality = MainUIData.instance(context).getThumbQuality();
            String thumbnailUrl = ClickbaitRemover.updateThumbnail(video, thumbQuality);

            thumbnailUrl = ClickbaitRemover.fitThumbnail(thumbnailUrl, relatedThumbWidthPx(context));

            com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable> request = Glide.with(context)
                    .load(thumbnailUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .format(DecodeFormat.PREFER_RGB_565)
                    .centerCrop()
                    .transition(com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade(150));

            String fallbackUrl = video.getCardImageUrl();
            if (fallbackUrl != null && !fallbackUrl.equals(thumbnailUrl)) {
                request = request.error(Glide.with(context)
                        .load(fallbackUrl)
                        .format(DecodeFormat.PREFER_RGB_565)
                        .centerCrop());
            }

            request.into(mThumbnail);
        }

        private int relatedThumbWidthPx(Context context) {
            if (mCardStyle) {
                return context.getResources().getDisplayMetrics().widthPixels;
            }
            return context.getResources().getDimensionPixelSize(
                    R.dimen.mobile_watch_related_thumb_width);
        }

        void unbind() {
            mVideo = null;
            Glide.with(itemView.getContext().getApplicationContext()).clear(mThumbnail);
        }
    }
}
