package com.newtube.mobile.ui.browse;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * Browse's top bar. On a bottom-nav tab it shows the logo; a section opened from a You row
 * (Sports, Playlists, ...) gets a back arrow and the section's name instead.
 */
final class BrowseTopBar {
    private static final float SUBSCREEN_TITLE_SP = 20f;
    private static final float TAB_TITLE_SP = 22f;

    private final TextView mTitle;
    private final View mBack;
    private final int mWordmarkInset;
    private final int mBesideBackInset;

    BrowseTopBar(Activity activity, Runnable onBack) {
        mTitle = activity.findViewById(R.id.mobile_title_bar);
        mBack = activity.findViewById(R.id.mobile_title_back);
        mBack.setOnClickListener(v -> onBack.run());
        mWordmarkInset = mTitle.getPaddingStart();
        mBesideBackInset = activity.getResources()
                .getDimensionPixelSize(R.dimen.mobile_dialog_title_inset_with_back);
    }

    void show(boolean subScreen, CharSequence title) {
        mBack.setVisibility(subScreen ? View.VISIBLE : View.GONE);

        boolean showLogo = !subScreen;
        CharSequence appName = mTitle.getContext().getString(R.string.app_name);

        if (showLogo) {
            Drawable logo = ContextCompat.getDrawable(mTitle.getContext(), R.drawable.ic_mobile_logo);
            mTitle.setText("");
            mTitle.setCompoundDrawablesRelativeWithIntrinsicBounds(logo, null, null, null);
            mTitle.setContentDescription(appName);
        } else {
            mTitle.setText(title != null && title.length() > 0 ? title : appName);
            mTitle.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null);
            mTitle.setContentDescription(null);
        }

        mTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, subScreen ? SUBSCREEN_TITLE_SP : TAB_TITLE_SP);
        mTitle.setPaddingRelative(subScreen ? mBesideBackInset : mWordmarkInset,
                mTitle.getPaddingTop(), mTitle.getPaddingEnd(), mTitle.getPaddingBottom());
    }
}
