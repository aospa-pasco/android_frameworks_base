/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar;

import static com.android.systemui.plugins.DarkIconDispatcher.getTint;
import static com.android.systemui.plugins.DarkIconDispatcher.isInAreas;
import static com.android.systemui.statusbar.StatusBarIconView.STATE_DOT;
import static com.android.systemui.statusbar.StatusBarIconView.STATE_HIDDEN;
import static com.android.systemui.statusbar.StatusBarIconView.STATE_ICON;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.drawable.InsetDrawable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import androidx.core.graphics.ColorUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.settingslib.graph.SignalDrawable;
import com.android.systemui.DualToneHandler;
import com.android.systemui.R;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.phone.StatusBarSignalPolicy.MobileIconState;

import java.util.ArrayList;

/**
 * View group for the mobile icon in the status bar
 */
public class StatusBarMobileView extends BaseStatusBarFrameLayout implements DarkReceiver,
        StatusIconDisplayable {
    private static final String TAG = "StatusBarMobileView";

    /// Used to show etc dots
    private StatusBarIconView mDotView;
    /// The main icon view
    private LinearLayout mMobileGroup;
    private String mSlot;
    private MobileIconState mState;
    private SignalDrawable mMobileDrawable;
    private ViewGroup mMobileTypeContainer;
    private ImageView mMobile, mMobileType, mInout;
    private View mMobileTypeSpace, mVolteSpace;
    @StatusBarIconView.VisibleState
    private int mVisibleState = STATE_HIDDEN;
    private DualToneHandler mDualToneHandler;
    private boolean mForceHidden;
    private ImageView mVolte;
    private int mColor, mOffColor;

    /**
     * Designated constructor
     *
     * This view is special, in that it is the only view in SystemUI that allows for a configuration
     * override on a MCC/MNC-basis. This means that for every mobile view inflated, we have to
     * construct a context with that override, since the resource system doesn't have a way to
     * handle this for us.
     *
     * @param context A context with resources configured by MCC/MNC
     * @param slot The string key defining which slot this icon refers to. Always "mobile" for the
     *             mobile icon
     */
    public static StatusBarMobileView fromContext(
            Context context,
            String slot
    ) {
        LayoutInflater inflater = LayoutInflater.from(context);
        StatusBarMobileView v = (StatusBarMobileView)
                inflater.inflate(R.layout.status_bar_mobile_signal_group, null);
        v.setSlot(slot);
        v.init();
        v.setVisibleState(STATE_ICON);
        return v;
    }

    public StatusBarMobileView(Context context) {
        super(context);
    }

    public StatusBarMobileView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public StatusBarMobileView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public void getDrawingRect(Rect outRect) {
        super.getDrawingRect(outRect);
        float translationX = getTranslationX();
        float translationY = getTranslationY();
        outRect.left += translationX;
        outRect.right += translationX;
        outRect.top += translationY;
        outRect.bottom += translationY;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        mMobileGroup.measure(widthMeasureSpec, heightMeasureSpec);
        setMeasuredDimension(mMobileGroup.getMeasuredWidth(), mMobileGroup.getMeasuredHeight());
    }

    private void init() {
        mDualToneHandler = new DualToneHandler(getContext());
        mMobileGroup = findViewById(R.id.mobile_group);
        mMobile = findViewById(R.id.mobile_signal);
        mMobileTypeContainer = findViewById(R.id.mobile_type_container);
        mMobileType = findViewById(R.id.mobile_type);
        mMobileTypeSpace = findViewById(R.id.mobile_type_space);
        mVolteSpace = findViewById(R.id.mobile_volte_space);
        mInout = findViewById(R.id.mobile_inout);
        mVolte = findViewById(R.id.mobile_volte);

        mMobileDrawable = new SignalDrawable(getContext());
        mMobile.setImageDrawable(mMobileDrawable);

        float signalSize = mContext.getResources().getDimensionPixelSize(
                R.dimen.signal_icon_size);
        float viewportSize = mContext.getResources().getDimensionPixelSize(
                R.dimen.signal_icon_viewport_size);
        LayoutParams lp = (LayoutParams) mVolte.getLayoutParams();
        lp.height = Math.round(lp.height * (signalSize / viewportSize));
        lp.width = Math.round(lp.width * (signalSize / viewportSize));
        mVolte.setLayoutParams(lp);

        initDotView();
    }

    private void initDotView() {
        mDotView = new StatusBarIconView(mContext, mSlot, null);
        mDotView.setVisibleState(STATE_DOT);

        int width = mContext.getResources().getDimensionPixelSize(R.dimen.status_bar_icon_size);
        LayoutParams lp = new LayoutParams(width, width);
        lp.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
        addView(mDotView, lp);
    }

    public void applyMobileState(MobileIconState state) {
        boolean requestLayout = false;
        if (state == null) {
            requestLayout = getVisibility() != View.GONE;
            setVisibility(View.GONE);
            mState = null;
        } else if (mState == null) {
            requestLayout = true;
            mState = state.copy();
            initViewState();
        } else if (!mState.equals(state)) {
            requestLayout = updateState(state.copy());
        }

        if (requestLayout) {
            requestLayout();
        }
    }

    private void initViewState() {
        setContentDescription(mState.contentDescription);
        if (!mState.visible || mForceHidden) {
            mMobileGroup.setVisibility(View.GONE);
        } else {
            mMobileGroup.setVisibility(View.VISIBLE);
        }
        if (mState.strengthId >= 0) {
            mMobile.setVisibility(View.VISIBLE);
            mMobileDrawable.setLevel(mState.strengthId);
        }else {
            mMobile.setVisibility(View.GONE);
        }
        mMobileTypeSpace.setVisibility(mState.typeSpacerVisible ? View.VISIBLE : View.GONE);
        mMobile.setVisibility(mState.showTriangle ? View.VISIBLE : View.GONE);
        updateMobileTypeLayout(mState);

        if (mState.volteId > 0 ) {
            mVolte.setImageResource(mState.volteId);
            mVolte.setVisibility(View.VISIBLE);
            mVolteSpace.setVisibility(View.VISIBLE);
        }else {
            mVolte.setVisibility(View.GONE);
            mVolteSpace.setVisibility(View.GONE);
        }
    }

    private boolean updateState(MobileIconState state) {
        boolean needsLayout = false;

        setContentDescription(state.contentDescription);
        int newVisibility = state.visible && !mForceHidden ? View.VISIBLE : View.GONE;
        if (newVisibility != mMobileGroup.getVisibility() && STATE_ICON == mVisibleState) {
            mMobileGroup.setVisibility(newVisibility);
            needsLayout = true;
        }
        if (state.strengthId >= 0) {
            mMobileDrawable.setLevel(state.strengthId);
            mMobile.setVisibility(View.VISIBLE);
        }else {
            mMobile.setVisibility(View.GONE);
        }
        if (mState.typeId != state.typeId) {
            needsLayout |= state.typeId == 0 || mState.typeId == 0;
        }
        mMobileTypeSpace.setVisibility(state.typeSpacerVisible ? View.VISIBLE : View.GONE);
        mMobile.setVisibility(state.showTriangle ? View.VISIBLE : View.GONE);
        updateMobileTypeLayout(state);

        if (mState.volteId != state.volteId) {
            if (state.volteId != 0) {
                mVolte.setImageResource(state.volteId);
                mVolte.setVisibility(View.VISIBLE);
                mVolteSpace.setVisibility(View.VISIBLE);
            } else {
                mVolte.setVisibility(View.GONE);
                mVolteSpace.setVisibility(View.GONE);
            }
        }

        needsLayout |= state.volteId != mState.volteId
                || state.activityIn != mState.activityIn
                || state.activityOut != mState.activityOut
                || state.showTriangle != mState.showTriangle;

        mState = state;
        return needsLayout;
    }

    // Sets the mobile type icon and network activity indicators.
    private void updateMobileTypeLayout(MobileIconState state) {
        int paddingTop = (state.activityEnabled && state.volteId != 0) ? getContext().getResources()
                .getDimensionPixelSize(R.dimen.mobile_signal_icon_padding_top) : 0;
        mMobile.setPadding(0, paddingTop, 0, 0);

        if (state.typeId == 0) {
            // Container is hidden, nothing else to do here.
            mMobileTypeContainer.setVisibility(View.GONE);
            return;
        }

        mMobileTypeContainer.setVisibility(View.VISIBLE);

        int inset = getContext().getResources().getDimensionPixelSize(
                R.dimen.mobile_type_icon_inset_vertical);
        mMobileType.setImageDrawable(new InsetDrawable(
                getContext().getDrawable(state.typeId), 0, inset, 0, inset));
        mMobileType.setContentDescription(state.typeContentDescription);

        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) mMobileType.getLayoutParams();
        lp.height = getContext().getResources().getDimensionPixelSize(state.activityEnabled
                ? R.dimen.mobile_type_icon_height_with_activity
                : R.dimen.mobile_type_icon_height);
        int vMargin = getContext().getResources().getDimensionPixelSize(state.activityEnabled
                ? R.dimen.mobile_type_icon_margin_vertical_with_activity
                : R.dimen.mobile_type_icon_margin_vertical);
        lp.setMargins(0, vMargin, 0, vMargin);
        mMobileType.setLayoutParams(lp);

        if (!state.activityEnabled) {
            // Hide activity indicators, show only mobile type.
            float fontScale = getContext().getResources().getConfiguration().fontScale;
            mMobileType.setScaleX(fontScale);
            mMobileType.setScaleY(fontScale);
            mInout.setVisibility(View.GONE);
            return;
        } else {
            // Scale view to fit vertically
            mMobileType.setScaleX(1f);
            mMobileType.setScaleY(1f);
            mInout.setVisibility(View.VISIBLE);
        }

        int resId = R.drawable.stat_sys_data_no_inout;
        if (state.activityIn && state.activityOut) {
            resId = R.drawable.stat_sys_data_inout;
        } else if (state.activityIn) {
            resId = R.drawable.stat_sys_data_in;
        } else if (state.activityOut) {
            resId = R.drawable.stat_sys_data_out;
        }
        mInout.setImageResource(resId);
    }

    @Override
    public void onDarkChanged(ArrayList<Rect> areas, float darkIntensity, int tint) {
        float intensity = isInAreas(areas, this) ? darkIntensity : 0;
        mMobileDrawable.setTintList(
                ColorStateList.valueOf(mDualToneHandler.getSingleColor(intensity)));
        ColorStateList color = ColorStateList.valueOf(getTint(areas, this, tint));
        mInout.setImageTintList(color);
        mMobileType.setImageTintList(color);
        mVolte.setImageTintList(color);
        mDotView.setDecorColor(tint);
        mDotView.setIconColor(tint, false);
    }

    @Override
    public String getSlot() {
        return mSlot;
    }

    public void setSlot(String slot) {
        mSlot = slot;
    }

    @Override
    public void setStaticDrawableColor(int color) {
        ColorStateList list = ColorStateList.valueOf(color);
        mMobileDrawable.setTintList(list);
        mInout.setImageTintList(list);
        mMobileType.setImageTintList(list);
        mVolte.setImageTintList(list);
        mDotView.setDecorColor(color);
    }

    @Override
    public void setDecorColor(int color) {
        mDotView.setDecorColor(color);
    }

    @Override
    public boolean isIconVisible() {
        return mState.visible && !mForceHidden;
    }

    @Override
    public void setVisibleState(@StatusBarIconView.VisibleState int state, boolean animate) {
        if (state == mVisibleState) {
            return;
        }

        mVisibleState = state;
        switch (state) {
            case STATE_ICON:
                mMobileGroup.setVisibility(View.VISIBLE);
                mDotView.setVisibility(View.GONE);
                break;
            case STATE_DOT:
                mMobileGroup.setVisibility(View.INVISIBLE);
                mDotView.setVisibility(View.VISIBLE);
                break;
            case STATE_HIDDEN:
            default:
                mMobileGroup.setVisibility(View.INVISIBLE);
                mDotView.setVisibility(View.INVISIBLE);
                break;
        }
    }

    /**
     * Forces the state to be hidden (views will be GONE) and if necessary updates the layout.
     *
     * Makes sure that the {@link StatusBarIconController} cannot make it visible while this flag
     * is enabled.
     * @param forceHidden {@code true} if the icon should be GONE in its view regardless of its
     *                                state.
     *               {@code false} if the icon should show as determined by its controller.
     */
    public void forceHidden(boolean forceHidden) {
        if (mForceHidden != forceHidden) {
            mForceHidden = forceHidden;
            updateState(mState);
            requestLayout();
        }
    }

    @Override
    @StatusBarIconView.VisibleState
    public int getVisibleState() {
        return mVisibleState;
    }

    @VisibleForTesting
    public MobileIconState getState() {
        return mState;
    }

    @Override
    public String toString() {
        return "StatusBarMobileView(slot=" + mSlot + " state=" + mState + ")";
    }
}
