/*
 * Copyright (C) 2017-2018 The LineageOS project
 *           (C) 2023 Paranoid Android
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar;

import static android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;
import static com.android.systemui.statusbar.StatusBarIconView.STATE_DOT;
import static com.android.systemui.statusbar.StatusBarIconView.STATE_HIDDEN;
import static com.android.systemui.statusbar.StatusBarIconView.STATE_ICON;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Paint.FontMetricsInt;
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.net.TrafficStats;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.text.style.RelativeSizeSpan;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.statusbar.StatusIconDisplayable;

import java.util.ArrayList;

public class StatusBarNetworkTrafficView extends TextView implements StatusIconDisplayable {

    public static final String SLOT = "network_traffic";
    private static final String TAG = "StatusBarNetworkTrafficView";
    private static final int REFRESH_INTERVAL_MS = 1000;

    private boolean mEnabled, mAutoHide, mNetworkConnected, mVisible, mAttached;
    private boolean mScreenOn = true;
    private long mTotalBytes, mLastUpdateTime;
    private int mVisibleState;

    private final ConnectivityManager mConnectivityManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final ContentObserver mSettingsObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
            updateVisibility();
        }
    };

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case Intent.ACTION_SCREEN_OFF:
                    mScreenOn = false;
                    break;
                case Intent.ACTION_SCREEN_ON:
                    mScreenOn = true;
                    break;
                case ConnectivityManager.CONNECTIVITY_ACTION:
                    updateNetworkConnected();
                    break;
            }
            updateVisibility();
        }
    };

    public StatusBarNetworkTrafficView(Context context) {
        this(context, null);
    }

    public StatusBarNetworkTrafficView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public StatusBarNetworkTrafficView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mConnectivityManager = context.getSystemService(ConnectivityManager.class);

        setVisibility(View.GONE);
        setMaxLines(2);
        setLineSpacing(0f, 0.8f);
        setTextAppearance(R.style.TextAppearance_QS_Status);
        setIncludeFontPadding(false);
        setGravity(Gravity.CENTER);

        final int hPadding = context.getResources().getDimensionPixelSize(
                R.dimen.network_traffic_horizontal_padding);
        setPadding(hPadding, 0, hPadding, 0);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mAttached) return;
        mAttached = true;

        IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        mContext.registerReceiver(mIntentReceiver, filter);

        ContentResolver resolver = mContext.getContentResolver();
        resolver.registerContentObserver(Settings.System
                .getUriFor(Settings.System.NETWORK_TRAFFIC_STATE), false,
                mSettingsObserver, UserHandle.USER_CURRENT);
        resolver.registerContentObserver(Settings.System
                .getUriFor(Settings.System.NETWORK_TRAFFIC_AUTOHIDE), false,
                mSettingsObserver, UserHandle.USER_CURRENT);

        updateSettings();
        updateNetworkConnected();
        updateVisibility();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (!mAttached) return;
        mAttached = false;
        mContext.unregisterReceiver(mIntentReceiver);
        mContext.getContentResolver().unregisterContentObserver(mSettingsObserver);
    }

    /**
     * Since TextView adds some unwanted padding above the text, our view wasn't being
     * properly centered vertically. To workaround this problem, offset the canvas
     * vertically by the difference between the font metrics' recommended and maximum values.
     * Ref: https://stackoverflow.com/a/23063015
     */
    @Override
    protected void onDraw(Canvas canvas) {
        FontMetricsInt fmi = getPaint().getFontMetricsInt();
        canvas.translate(0, fmi.top - fmi.ascent - fmi.bottom + fmi.descent);
        super.onDraw(canvas);
    }

    @Override
    public String getSlot() {
        return SLOT;
    }

    @Override
    public boolean isIconVisible() {
        return mEnabled;
    }

    @Override
    public void setVisibleState(int state, boolean animate) {
        if (mVisibleState != state) {
            mVisibleState = state;
            updateVisibility();
        }
    }

    @Override
    public int getVisibleState() {
        return mVisibleState;
    }

    @Override
    public void setStaticDrawableColor(int color) {
        setTextColor(color);
    }

    @Override
    public void setDecorColor(int color) {
        setTextColor(color);
    }

    @Override
    public void onDarkChanged(ArrayList<Rect> areas, float darkIntensity, int tint) {
        setTextColor(DarkIconDispatcher.getTint(areas, this, tint));
    }

    private void updateSettings() {
        final ContentResolver resolver = mContext.getContentResolver();
        mEnabled = Settings.System.getIntForUser(resolver,
                Settings.System.NETWORK_TRAFFIC_STATE, 0, UserHandle.USER_CURRENT) == 1;
        mAutoHide = Settings.System.getIntForUser(resolver,
                Settings.System.NETWORK_TRAFFIC_AUTOHIDE, 1, UserHandle.USER_CURRENT) == 1;
    }

    private void updateNetworkConnected() {
        mNetworkConnected = mConnectivityManager != null
                && mConnectivityManager.getActiveNetworkInfo() != null;
    }

    private void updateVisibility() {
        final boolean show = mEnabled && mScreenOn && mNetworkConnected
                && !(mVisibleState == STATE_DOT || mVisibleState == STATE_HIDDEN);

        if (!mVisible && show) {
            // We weren't showing, so let's start showing now
            startUpdateRun();
        }
        // otherwise the runnable will take care of hiding it.
        mVisible = show;
    }

    private void startUpdateRun() {
        // Fetch the initial values and initialize at 0 KB/s
        mHandler.removeCallbacksAndMessages(null);
        updateView(true);

        // Schedule periodic refresh
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                updateView(false);
                mHandler.postDelayed(this, REFRESH_INTERVAL_MS);
            }
        }, REFRESH_INTERVAL_MS);
    }

    private void updateView(boolean initialRun) {
        if (!mVisible) {
            setVisibility(View.GONE);
            mHandler.removeCallbacksAndMessages(null);
            return;
        }

        final long timeNow = SystemClock.elapsedRealtime();
        final long timeDelta = timeNow - mLastUpdateTime;
        mLastUpdateTime = timeNow;

        final long newTotalBytes =
                TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes();
        long bytes = 0;
        if (timeDelta > 0 && !initialRun) {
            // Calculate the data rate from the change in total bytes and time
            bytes = (long) ((newTotalBytes - mTotalBytes) / (timeDelta / 1000f));
        }
        mTotalBytes = newTotalBytes;

        CharSequence output = "";
        if (initialRun) {
            // Wait for the next run to set visibility
        } else if (mAutoHide && bytes < 1024) {
            setVisibility(View.INVISIBLE);
        } else {
            // Format the bytes to human readable speed
            output = formatOutput(bytes);
            setVisibility(View.VISIBLE);
        }

        // Update view if there's anything new to show
        if (!TextUtils.equals(output, getText())) {
            setText(output);
        }
    }

    private CharSequence formatOutput(long bytes) {
        // Use a threshold of 1 KB, don't show B/s (bytes)
        String size;
        if (bytes < 1024) {
            size = mContext.getString(com.android.internal.R.string.fileSizeSuffix,
                    "0", mContext.getString(com.android.internal.R.string.kilobyteShort));
        } else {
            size = Formatter.formatFileSize(mContext, bytes,
                    Formatter.FLAG_IEC_UNITS | Formatter.FLAG_SHORTER);
        }

        // Size is formatted as 10.25 KB (for example), so we split it into the size and unit
        final String[] sizes = size.split(" ");
        if (sizes.length != 2) {
            Log.e(TAG, "Error in parsing size: " + size);
            return "";
        }

        return new SpannableStringBuilder()
                .append(sizes[0], new RelativeSizeSpan(0.7f), SPAN_EXCLUSIVE_EXCLUSIVE)
                .append("\n")
                .append(sizes[1] + "/s", new RelativeSizeSpan(0.5f), SPAN_EXCLUSIVE_EXCLUSIVE);
    }

}
