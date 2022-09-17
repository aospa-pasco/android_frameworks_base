/*
 * Copyright (C) 2021 The Android Open Source Project
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

/* Changes from Qualcomm Innovation Center are provided under the following license:
 * Copyright (c) 2022-2023 Qualcomm Innovation Center, Inc. All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */
package com.android.systemui.qs.tiles.dialog;

import static android.telephony.AccessNetworkConstants.TRANSPORT_TYPE_WWAN;
import static android.telephony.NetworkRegistrationInfo.DOMAIN_PS;
import static android.telephony.ims.feature.ImsFeature.FEATURE_MMTEL;
import static android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_CROSS_SIM;

import static com.android.systemui.Prefs.Key.QS_HAS_TURNED_OFF_MOBILE_DATA;
import static com.android.systemui.qs.tiles.dialog.InternetDialogController.MAX_WIFI_ENTRY_COUNT;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.telephony.ims.aidl.IImsRegistration;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;
import android.text.Html;
import android.text.Layout;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.settingslib.wifi.WifiEnterpriseRestrictionUtils;
import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.accessibility.floatingmenu.AnnotationLinkSpan;
import com.android.systemui.animation.DialogLaunchAnimator;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.wifitrackerlib.WifiEntry;

import com.qti.extphone.CiwlanConfig;
import com.qti.extphone.ExtTelephonyManager;
import com.qti.extphone.ServiceCallback;

import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Dialog for showing mobile network, connected Wi-Fi network and Wi-Fi networks.
 */
@SysUISingleton
public class InternetDialog extends SystemUIDialog implements
        InternetDialogController.InternetDialogCallback, Window.Callback {
    private static final String TAG = "InternetDialog";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    static final long PROGRESS_DELAY_MS = 1500L;
    static final int MAX_NETWORK_COUNT = 4;

    private final Handler mHandler;
    private final Executor mBackgroundExecutor;
    private final DialogLaunchAnimator mDialogLaunchAnimator;

    @VisibleForTesting
    protected InternetAdapter mAdapter;
    @VisibleForTesting
    protected View mDialogView;
    @VisibleForTesting
    protected boolean mCanConfigWifi;

    private InternetDialogFactory mInternetDialogFactory;
    private SubscriptionManager mSubscriptionManager;
    private TelephonyManager mTelephonyManager;
    @Nullable
    private AlertDialog mAlertDialog;
    private UiEventLogger mUiEventLogger;
    private Context mContext;
    private InternetDialogController mInternetDialogController;
    private TextView mInternetDialogTitle;
    private TextView mInternetDialogSubTitle;
    private View mDivider;
    private ProgressBar mProgressBar;
    private LinearLayout mInternetDialogLayout;
    private LinearLayout mConnectedWifListLayout;
    private LinearLayout mMobileNetworkLayout;
    private LinearLayout mSecondaryMobileNetworkLayout;
    private LinearLayout mTurnWifiOnLayout;
    private LinearLayout mEthernetLayout;
    private LinearLayout mHotspotLayout;
    private TextView mWifiToggleTitleText;
    private LinearLayout mWifiScanNotifyLayout;
    private TextView mWifiScanNotifyText;
    private LinearLayout mSeeAllLayout;
    private RecyclerView mWifiRecyclerView;
    private ImageView mConnectedWifiIcon;
    private ImageView mWifiSettingsIcon;
    private TextView mConnectedWifiTitleText;
    private TextView mConnectedWifiSummaryText;
    private ImageView mSignalIcon;
    private TextView mMobileTitleText;
    private TextView mMobileSummaryText;
    private TextView mSecondaryMobileTitleText;
    private TextView  mSecondaryMobileSummaryText;
    private TextView mAirplaneModeSummaryText;
    private Switch mMobileDataToggle;
    private Switch mSecondaryMobileDataToggle;
    private View mMobileToggleDivider;
    private View mMobileConnectedSpace;
    private ImageView mHotspotIcon;
    private TextView mHotspotTitleText;
    private TextView mHotspotSummaryText;
    private Switch mHotspotToggle;
    private Switch mWiFiToggle;
    private View mWifiConnectedSpace;
    private Button mDoneButton;
    private Button mAirplaneModeButton;
    private Drawable mBackgroundOn;
    private KeyguardStateController mKeyguard;
    @Nullable
    private Drawable mBackgroundOff = null;
    private int mDefaultDataSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private int mNddsSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private boolean mCanConfigMobileData;
    private boolean mCanChangeWifiState;

    // Wi-Fi entries
    private int mWifiNetworkHeight;
    @Nullable
    @VisibleForTesting
    protected WifiEntry mConnectedWifiEntry;
    @VisibleForTesting
    protected int mWifiEntriesCount;
    @VisibleForTesting
    protected boolean mHasMoreWifiEntries;

    // Wi-Fi scanning progress bar
    protected boolean mIsProgressBarVisible;
    protected boolean mIsSearchingHidden;
    protected final Runnable mHideProgressBarRunnable = () -> {
        setProgressBarVisible(false);
    };
    protected Runnable mHideSearchingRunnable = () -> {
        mIsSearchingHidden = true;
        mInternetDialogSubTitle.setText(getSubtitleText());
    };

    private ExtTelephonyManager mExtTelephonyManager;
    private boolean mExtTelServiceConnected = false;
    private CiwlanConfig mCiwlanConfig = null;
    private CiwlanConfig mNddsCiwlanConfig = null;
    private ServiceCallback mExtTelServiceCallback = new ServiceCallback() {
        @Override
        public void onConnected() {
            Log.d(TAG, "ExtTelephony service connected");
            mExtTelServiceConnected = true;
            // Query the C_IWLAN config
            try {
                mCiwlanConfig = mExtTelephonyManager.getCiwlanConfig(
                        SubscriptionManager.getSlotIndex(mDefaultDataSubId));
                mNddsCiwlanConfig = mExtTelephonyManager.getCiwlanConfig(
                        SubscriptionManager.getSlotIndex(mNddsSubId));
            } catch (RemoteException ex) {
                Log.e(TAG, "getCiwlanConfig exception", ex);
            }
        }

        @Override
        public void onDisconnected() {
            Log.d(TAG, "ExtTelephony service disconnected");
            mExtTelServiceConnected = false;
        }
    };

    @Inject
    public InternetDialog(Context context, InternetDialogFactory internetDialogFactory,
            InternetDialogController internetDialogController, boolean canConfigMobileData,
            boolean canConfigWifi, boolean aboveStatusBar, UiEventLogger uiEventLogger,
            DialogLaunchAnimator dialogLaunchAnimator,
            @Main Handler handler, @Background Executor executor,
            KeyguardStateController keyguardStateController) {
        super(context);
        if (DEBUG) {
            Log.d(TAG, "Init InternetDialog");
        }

        // Save the context that is wrapped with our theme.
        mContext = getContext();
        mHandler = handler;
        mBackgroundExecutor = executor;
        mInternetDialogFactory = internetDialogFactory;
        mInternetDialogController = internetDialogController;
        mSubscriptionManager = mInternetDialogController.getSubscriptionManager();
        mDefaultDataSubId = mInternetDialogController.getDefaultDataSubscriptionId();
        mNddsSubId = getNddsSubId();
        mTelephonyManager = mInternetDialogController.getTelephonyManager();
        mCanConfigMobileData = canConfigMobileData;
        mCanConfigWifi = canConfigWifi;
        mCanChangeWifiState = WifiEnterpriseRestrictionUtils.isChangeWifiStateAllowed(context);
        mKeyguard = keyguardStateController;

        mUiEventLogger = uiEventLogger;
        mDialogLaunchAnimator = dialogLaunchAnimator;
        mAdapter = new InternetAdapter(mInternetDialogController);
        if (!aboveStatusBar) {
            getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        }

        mExtTelephonyManager = ExtTelephonyManager.getInstance(context);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (DEBUG) {
            Log.d(TAG, "onCreate");
        }
        mUiEventLogger.log(InternetDialogEvent.INTERNET_DIALOG_SHOW);
        mDialogView = LayoutInflater.from(mContext).inflate(R.layout.internet_connectivity_dialog,
                null);
        mDialogView.setAccessibilityPaneTitle(
                mContext.getText(R.string.accessibility_desc_quick_settings));
        final Window window = getWindow();
        window.setContentView(mDialogView);

        window.setWindowAnimations(R.style.Animation_InternetDialog);

        mWifiNetworkHeight = mContext.getResources()
                .getDimensionPixelSize(R.dimen.internet_dialog_wifi_network_height);

        mInternetDialogLayout = mDialogView.requireViewById(R.id.internet_connectivity_dialog);
        mInternetDialogTitle = mDialogView.requireViewById(R.id.internet_dialog_title);
        mInternetDialogSubTitle = mDialogView.requireViewById(R.id.internet_dialog_subtitle);
        mDivider = mDialogView.requireViewById(R.id.divider);
        mProgressBar = mDialogView.requireViewById(R.id.wifi_searching_progress);
        mEthernetLayout = mDialogView.requireViewById(R.id.ethernet_layout);
        mMobileNetworkLayout = mDialogView.requireViewById(R.id.mobile_network_layout);
        mHotspotLayout = mDialogView.requireViewById(R.id.hotspot_layout);
        mTurnWifiOnLayout = mDialogView.requireViewById(R.id.turn_on_wifi_layout);
        mWifiToggleTitleText = mDialogView.requireViewById(R.id.wifi_toggle_title);
        mWifiScanNotifyLayout = mDialogView.requireViewById(R.id.wifi_scan_notify_layout);
        mWifiScanNotifyText = mDialogView.requireViewById(R.id.wifi_scan_notify_text);
        mConnectedWifListLayout = mDialogView.requireViewById(R.id.wifi_connected_layout);
        mConnectedWifiIcon = mDialogView.requireViewById(R.id.wifi_connected_icon);
        mConnectedWifiTitleText = mDialogView.requireViewById(R.id.wifi_connected_title);
        mConnectedWifiSummaryText = mDialogView.requireViewById(R.id.wifi_connected_summary);
        mWifiSettingsIcon = mDialogView.requireViewById(R.id.wifi_settings_icon);
        mWifiRecyclerView = mDialogView.requireViewById(R.id.wifi_list_layout);
        mSeeAllLayout = mDialogView.requireViewById(R.id.see_all_layout);
        mDoneButton = mDialogView.requireViewById(R.id.done_button);
        mAirplaneModeButton = mDialogView.requireViewById(R.id.apm_button);
        mSignalIcon = mDialogView.requireViewById(R.id.signal_icon);
        mMobileTitleText = mDialogView.requireViewById(R.id.mobile_title);
        mMobileSummaryText = mDialogView.requireViewById(R.id.mobile_summary);
        mAirplaneModeSummaryText = mDialogView.requireViewById(R.id.airplane_mode_summary);
        mMobileToggleDivider = mDialogView.requireViewById(R.id.mobile_toggle_divider);
        mMobileDataToggle = mDialogView.requireViewById(R.id.mobile_toggle);
        mMobileConnectedSpace = mDialogView.requireViewById(R.id.mobile_connected_space);
        mHotspotIcon = mDialogView.requireViewById(R.id.hotspot_icon);
        mHotspotTitleText = mDialogView.requireViewById(R.id.hotspot_title);
        mHotspotSummaryText = mDialogView.requireViewById(R.id.hotspot_summary);
        mHotspotToggle = mDialogView.requireViewById(R.id.hotspot_toggle);
        mWiFiToggle = mDialogView.requireViewById(R.id.wifi_toggle);
        mWifiConnectedSpace = mDialogView.requireViewById(R.id.wifi_connected_space);
        mBackgroundOn = mContext.getDrawable(R.drawable.settingslib_switch_bar_bg_on);
        mInternetDialogTitle.setText(getDialogTitleText());
        mInternetDialogTitle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        mBackgroundOff = mContext.getDrawable(R.drawable.internet_dialog_selected_effect);
        setOnClickListener();
        setHotspotLayout();
        mTurnWifiOnLayout.setBackground(null);
        mAirplaneModeButton.setVisibility(
                mInternetDialogController.isAirplaneModeEnabled() ? View.VISIBLE : View.GONE);
        mWifiRecyclerView.setLayoutManager(new LinearLayoutManager(mContext));
        mWifiRecyclerView.setAdapter(mAdapter);
    }

    @Override
    public void start() {
        if (DEBUG) {
            Log.d(TAG, "onStart");
        }
        if (!mExtTelServiceConnected) {
            mExtTelephonyManager.connectService(mExtTelServiceCallback);
        }
        mInternetDialogController.onStart(this, mCanConfigWifi);
        if (!mCanConfigWifi) {
            hideWifiViews();
        }
    }

    @VisibleForTesting
    void hideWifiViews() {
        setProgressBarVisible(false);
        mTurnWifiOnLayout.setVisibility(View.GONE);
        mConnectedWifListLayout.setVisibility(View.GONE);
        mWifiRecyclerView.setVisibility(View.GONE);
        mSeeAllLayout.setVisibility(View.GONE);
    }

    @Override
    public void stop() {
        if (DEBUG) {
            Log.d(TAG, "onStop");
        }
        if (mExtTelServiceConnected) {
            mExtTelephonyManager.disconnectService(mExtTelServiceCallback);
        }
        mHandler.removeCallbacks(mHideProgressBarRunnable);
        mHandler.removeCallbacks(mHideSearchingRunnable);
        mMobileNetworkLayout.setOnClickListener(null);
        mHotspotLayout.setOnClickListener(null);
        mHotspotToggle.setOnCheckedChangeListener(null);
        mConnectedWifListLayout.setOnClickListener(null);
        if (mSecondaryMobileNetworkLayout != null) {
            mSecondaryMobileNetworkLayout.setOnClickListener(null);
        }
        mSeeAllLayout.setOnClickListener(null);
        mWiFiToggle.setOnCheckedChangeListener(null);
        mDoneButton.setOnClickListener(null);
        mAirplaneModeButton.setOnClickListener(null);
        mInternetDialogController.onStop();
        mInternetDialogFactory.destroyDialog();
        if (mSecondaryMobileDataToggle != null) {
            mSecondaryMobileDataToggle.setOnCheckedChangeListener(null);
        }
    }

    @Override
    public void dismissDialog() {
        if (DEBUG) {
            Log.d(TAG, "dismissDialog");
        }
        mInternetDialogFactory.destroyDialog();
        dismiss();
    }

    /**
     * Update the internet dialog when receiving the callback.
     *
     * @param shouldUpdateMobileNetwork {@code true} for update the mobile network layout,
     * otherwise {@code false}.
     * @param shouldUpdateHotspot {@code true} for update the hotspot layout,
     * otherwise {@code false}.
     */
    void updateDialog(boolean shouldUpdateMobileNetwork, boolean shouldUpdateHotspot) {
        if (DEBUG) {
            Log.d(TAG, "updateDialog");
        }
        mInternetDialogTitle.setText(getDialogTitleText());
        mInternetDialogSubTitle.setText(getSubtitleText());
        mAirplaneModeButton.setVisibility(
                mInternetDialogController.isAirplaneModeEnabled() ? View.VISIBLE : View.GONE);

        updateEthernet();
        if (shouldUpdateMobileNetwork) {
            setMobileDataLayout(mInternetDialogController.activeNetworkIsCellular(),
                    mInternetDialogController.isCarrierNetworkActive());
        }

        if (shouldUpdateHotspot) {
            setHotspotLayout();
        }

        if (!mCanConfigWifi) {
            return;
        }

        showProgressBar();
        final boolean isDeviceLocked = mInternetDialogController.isDeviceLocked();
        final boolean isWifiEnabled = mInternetDialogController.isWifiEnabled();
        final boolean isWifiScanEnabled = mInternetDialogController.isWifiScanEnabled();
        updateWifiToggle(isWifiEnabled, isDeviceLocked);
        updateConnectedWifi(isWifiEnabled, isDeviceLocked);
        updateWifiListAndSeeAll(isWifiEnabled, isDeviceLocked);
        updateWifiScanNotify(isWifiEnabled, isWifiScanEnabled, isDeviceLocked);
    }

    void updateDialog(boolean shouldUpdateMobileNetwork) {
        updateDialog(shouldUpdateMobileNetwork, false /* shouldUpdateHotspot */);
    }

    private void setOnClickListener() {
        mMobileNetworkLayout.setOnClickListener(v -> {
            // Do not show auto data switch dialog if Smart DDS Switch feature is available
            if (!mInternetDialogController.isSmartDdsSwitchFeatureAvailable()) {
                int autoSwitchNonDdsSubId =
                        mInternetDialogController.getActiveAutoSwitchNonDdsSubId();
                if (autoSwitchNonDdsSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    showTurnOffAutoDataSwitchDialog(autoSwitchNonDdsSubId);
                }
            }
            mInternetDialogController.connectCarrierNetwork();
        });
        mMobileDataToggle.setOnClickListener(v -> {
            boolean isChecked = mMobileDataToggle.isChecked();
            if (!isChecked && shouldShowMobileDialog(mDefaultDataSubId)) {
                mMobileDataToggle.setChecked(true);
                showTurnOffMobileDialog(mDefaultDataSubId);
            } else if (mInternetDialogController.isMobileDataEnabled(mDefaultDataSubId) != isChecked) {
                mInternetDialogController.setMobileDataEnabled(mContext, mDefaultDataSubId,
                        isChecked, false);
            }
        });
        mHotspotLayout.setOnClickListener(mInternetDialogController::launchHotspotSetting);
        mHotspotToggle.setOnCheckedChangeListener(
                (buttonView, isChecked) -> mInternetDialogController.setHotspotEnabled(isChecked));
        mConnectedWifListLayout.setOnClickListener(this::onClickConnectedWifi);
        mSeeAllLayout.setOnClickListener(this::onClickSeeMoreButton);
        mWiFiToggle.setOnCheckedChangeListener(
                (buttonView, isChecked) -> {
                    if (mInternetDialogController.isWifiEnabled() == isChecked) return;
                    mInternetDialogController.setWifiEnabled(isChecked);
                });
        mDoneButton.setOnClickListener(v -> dismiss());
        mAirplaneModeButton.setOnClickListener(v -> {
            mInternetDialogController.setAirplaneModeDisabled();
        });
    }

    @MainThread
    private void updateEthernet() {
        mEthernetLayout.setVisibility(
                mInternetDialogController.hasEthernet() ? View.VISIBLE : View.GONE);
    }

    /**
     * Do not allow the user to disable mobile data of DDS while there is an active
     * call on the nDDS.
     * Whether device works under DSDA or DSDS mode, if temp DDS switch has happened,
     * disabling mobile data won't be allowed.
     */
    private boolean shouldDisallowUserToDisableDdsMobileData() {
        return mInternetDialogController.isMobileDataEnabled(mDefaultDataSubId)
                && !mInternetDialogController.isNonDdsCallStateIdle()
                && mInternetDialogController.isTempDdsHappened();
    }

    private void setMobileDataLayout(boolean activeNetworkIsCellular,
            boolean isCarrierNetworkActive) {
        boolean isNetworkConnected = activeNetworkIsCellular || isCarrierNetworkActive;
        // 1. Mobile network should be gone if airplane mode ON or the list of active
        //    subscriptionId is null.
        // 2. Carrier network should be gone if airplane mode ON and Wi-Fi is OFF.
        if (DEBUG) {
            Log.d(TAG, "setMobileDataLayout, isCarrierNetworkActive = " + isCarrierNetworkActive);
        }

        boolean isWifiEnabled = mInternetDialogController.isWifiEnabled();
        if (!mInternetDialogController.hasActiveSubId()
                && (!isWifiEnabled || !isCarrierNetworkActive)) {
            mMobileNetworkLayout.setVisibility(View.GONE);
            if (mSecondaryMobileNetworkLayout != null) {
                mSecondaryMobileNetworkLayout.setVisibility(View.GONE);
            }
        } else {
            if (shouldDisallowUserToDisableDdsMobileData()) {
                Log.d(TAG, "Do not allow mobile data switch to be turned off");
                mMobileDataToggle.setEnabled(false);
            } else {
                mMobileDataToggle.setEnabled(true);
            }
            mMobileNetworkLayout.setVisibility(View.VISIBLE);
            mMobileDataToggle.setChecked(
                    mInternetDialogController.isMobileDataEnabled(mDefaultDataSubId));
            mMobileTitleText.setText(getMobileNetworkTitle(mDefaultDataSubId));
            String summary = getMobileNetworkSummary(mDefaultDataSubId);
            if (!TextUtils.isEmpty(summary)) {
                mMobileSummaryText.setText(
                        Html.fromHtml(summary, Html.FROM_HTML_MODE_LEGACY));
                mMobileSummaryText.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
                mMobileSummaryText.setVisibility(View.VISIBLE);
            } else {
                mMobileSummaryText.setVisibility(View.GONE);
            }
            mBackgroundExecutor.execute(() -> {
                Drawable drawable = getSignalStrengthDrawable(mDefaultDataSubId);
                mHandler.post(() -> {
                    mSignalIcon.setImageDrawable(drawable);
                });
            });
            mMobileConnectedSpace.setVisibility(
                    isNetworkConnected ? View.VISIBLE : View.GONE);

            mMobileDataToggle.setVisibility(mCanConfigMobileData ? View.VISIBLE : View.INVISIBLE);
            mMobileToggleDivider.setVisibility(
                    mCanConfigMobileData ? View.VISIBLE : View.INVISIBLE);
            Log.d(TAG, "mNddsSubId: " + mNddsSubId + " isDualDataEnabled: " + isDualDataEnabled());
            boolean nonDdsVisibleForDualData = SubscriptionManager
                    .isUsableSubscriptionId(mNddsSubId) && isDualDataEnabled();
            // Display the info for the non-DDS if it's actively being used
            int autoSwitchNonDdsSubId = mInternetDialogController.getActiveAutoSwitchNonDdsSubId();
            int nonDdsVisibility = (autoSwitchNonDdsSubId
                    != SubscriptionManager.INVALID_SUBSCRIPTION_ID || nonDdsVisibleForDualData)
                    ? View.VISIBLE : View.GONE;

            int secondaryRes = isNetworkConnected
                    ? R.style.TextAppearance_InternetDialog_Secondary_Active
                    : R.style.TextAppearance_InternetDialog_Secondary;
            if (nonDdsVisibleForDualData) {
                ViewStub stub = mDialogView.findViewById(R.id.secondary_mobile_network_stub);
                if (stub != null) {
                    stub.setLayoutResource(R.layout.qs_diaglog_secondary_generic_mobile_network);
                    stub.inflate();
                }
                mSecondaryMobileNetworkLayout = findViewById(R.id.secondary_mobile_network_layout);
                mSecondaryMobileNetworkLayout.setBackground(mBackgroundOn);
                mSecondaryMobileDataToggle =
                        mDialogView.requireViewById(R.id.secondary_generic_mobile_toggle);
                mSecondaryMobileDataToggle.setChecked(
                        mInternetDialogController.isMobileDataEnabled(mNddsSubId));
                TextView mobileTitleText =
                        mDialogView.requireViewById(R.id.secondary_generic_mobile_title);
                mobileTitleText.setText(getMobileNetworkTitle(mNddsSubId));

                TextView summaryText =
                        mDialogView.requireViewById(R.id.secondary_generic_mobile_summary);
                String secondarySummary = getMobileNetworkSummary(mNddsSubId);
                if (!TextUtils.isEmpty(secondarySummary)) {
                    summaryText.setText(
                            Html.fromHtml(secondarySummary, Html.FROM_HTML_MODE_LEGACY));
                    summaryText.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
                    summaryText.setVisibility(View.VISIBLE);
                } else {
                    summaryText.setVisibility(View.GONE);
                }

                final ImageView signalIcon =
                        mDialogView.requireViewById(R.id.secondary_generic_signal_icon);
                mBackgroundExecutor.execute(() -> {
                    Drawable drawable = getSignalStrengthDrawable(mNddsSubId);
                    mHandler.post(() -> {
                        signalIcon.setImageDrawable(drawable);
                    });
                });

                View divider = mDialogView.requireViewById(
                        R.id.secondary_generic_mobile_toggle_divider);

                mSecondaryMobileDataToggle.setVisibility(
                        mCanConfigMobileData ? View.VISIBLE : View.INVISIBLE);
                divider.setVisibility(
                        mCanConfigMobileData ? View.VISIBLE : View.INVISIBLE);
                mSecondaryMobileDataToggle.setOnCheckedChangeListener(
                    (buttonView, isChecked) -> {
                        if (!isChecked && shouldShowMobileDialog(mNddsSubId)) {
                            showTurnOffMobileDialog(mNddsSubId);
                        } else if (!shouldShowMobileDialog(mNddsSubId)) {
                            if (mInternetDialogController.isMobileDataEnabled(
                                    mNddsSubId) == isChecked) {
                                return;
                            }
                            mInternetDialogController.setMobileDataEnabled(
                                    mContext, mNddsSubId, isChecked, false);
                        }
                });
                nonDdsVisibility = View.VISIBLE;
            } else if (nonDdsVisibility == View.VISIBLE) {
                // non DDS is the currently active sub, set primary visual for it
                ViewStub stub = mDialogView.findViewById(R.id.secondary_mobile_network_stub);
                if (stub != null) {
                    stub.inflate();
                }
                mSecondaryMobileNetworkLayout = findViewById(R.id.secondary_mobile_network_layout);
                mSecondaryMobileNetworkLayout.setOnClickListener(
                        this::onClickConnectedSecondarySub);
                mSecondaryMobileNetworkLayout.setBackground(mBackgroundOn);

                mSecondaryMobileTitleText = mDialogView.requireViewById(
                        R.id.secondary_mobile_title);
                mSecondaryMobileTitleText.setText(getMobileNetworkTitle(autoSwitchNonDdsSubId));
                mSecondaryMobileTitleText.setTextAppearance(
                        R.style.TextAppearance_InternetDialog_Active);

                mSecondaryMobileSummaryText =
                        mDialogView.requireViewById(R.id.secondary_mobile_summary);
                summary = getMobileNetworkSummary(autoSwitchNonDdsSubId);
                if (!TextUtils.isEmpty(summary)) {
                    mSecondaryMobileSummaryText.setText(
                            Html.fromHtml(summary, Html.FROM_HTML_MODE_LEGACY));
                    mSecondaryMobileSummaryText.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
                    mSecondaryMobileSummaryText.setTextAppearance(
                            R.style.TextAppearance_InternetDialog_Active);
                }

                ImageView mSecondarySignalIcon =
                        mDialogView.requireViewById(R.id.secondary_signal_icon);
                mBackgroundExecutor.execute(() -> {
                    Drawable drawable = getSignalStrengthDrawable(autoSwitchNonDdsSubId);
                    mHandler.post(() -> {
                        mSecondarySignalIcon.setImageDrawable(drawable);
                    });
                });

                ImageView mSecondaryMobileSettingsIcon =
                        mDialogView.requireViewById(R.id.secondary_settings_icon);
                mSecondaryMobileSettingsIcon.setColorFilter(
                        mContext.getColor(R.color.connected_network_primary_color));

                // set secondary visual for default data sub
                mMobileNetworkLayout.setBackground(mBackgroundOff);
                mMobileTitleText.setTextAppearance(R.style.TextAppearance_InternetDialog);
                mMobileSummaryText.setTextAppearance(
                        R.style.TextAppearance_InternetDialog_Secondary);
                mSignalIcon.setColorFilter(
                        mContext.getColor(R.color.connected_network_secondary_color));
            } else {
                mMobileNetworkLayout.setBackground(
                        isNetworkConnected ? mBackgroundOn : mBackgroundOff);
                mMobileTitleText.setTextAppearance(isNetworkConnected
                        ?
                        R.style.TextAppearance_InternetDialog_Active
                        : R.style.TextAppearance_InternetDialog);
                mMobileSummaryText.setTextAppearance(secondaryRes);
            }

            if (mSecondaryMobileNetworkLayout != null) {
                mSecondaryMobileNetworkLayout.setVisibility(nonDdsVisibility);
            }

            // Set airplane mode to the summary for carrier network
            if (mInternetDialogController.isAirplaneModeEnabled()) {
                mAirplaneModeSummaryText.setVisibility(View.VISIBLE);
                mAirplaneModeSummaryText.setText(mContext.getText(R.string.airplane_mode));
                mAirplaneModeSummaryText.setTextAppearance(secondaryRes);
            } else {
                mAirplaneModeSummaryText.setVisibility(View.GONE);
            }
        }
    }

    private void setHotspotLayout() {
        if (!mInternetDialogController.isHotspotAvailable()) {
            mHotspotLayout.setVisibility(View.GONE);
            return;
        }
        mHotspotLayout.setVisibility(View.VISIBLE);
        mHotspotTitleText.setText(getHotspotTitle());
        mHotspotSummaryText.setText(getHotspotSummary());

        boolean enabled = mInternetDialogController.isHotspotEnabled();
        mHotspotIcon.setImageResource(enabled ? R.drawable.ic_internet_hotspot
                : R.drawable.ic_internet_hotspot_disabled);
        mHotspotToggle.setChecked(enabled);

        boolean dataSaver = mInternetDialogController.isDataSaverEnabled();
        mHotspotTitleText.setEnabled(!dataSaver);
        mHotspotSummaryText.setEnabled(!dataSaver);
        mHotspotToggle.setEnabled(!dataSaver);
    }

    @MainThread
    private void updateWifiToggle(boolean isWifiEnabled, boolean isDeviceLocked) {
        if (mWiFiToggle.isChecked() != isWifiEnabled) {
            mWiFiToggle.setChecked(isWifiEnabled);
        }
        if (isDeviceLocked) {
            mWifiToggleTitleText.setTextAppearance((mConnectedWifiEntry != null)
                    ? R.style.TextAppearance_InternetDialog_Active
                    : R.style.TextAppearance_InternetDialog);
        }

        boolean showBackground = isDeviceLocked && mConnectedWifiEntry != null;
        ViewGroup.LayoutParams lp = mTurnWifiOnLayout.getLayoutParams();
        lp.height = mContext.getResources().getDimensionPixelSize(
                showBackground ? R.dimen.internet_dialog_wifi_network_height
                : R.dimen.internet_dialog_wifi_toggle_height);
        mTurnWifiOnLayout.setLayoutParams(lp);
        mTurnWifiOnLayout.setBackground(showBackground ? mBackgroundOn : null);
        mWifiConnectedSpace.setVisibility(showBackground ? View.VISIBLE : View.GONE);

        if (!mCanChangeWifiState && mWiFiToggle.isEnabled()) {
            mWiFiToggle.setEnabled(false);
            mWifiToggleTitleText.setEnabled(false);
            final TextView summaryText = mDialogView.requireViewById(R.id.wifi_toggle_summary);
            summaryText.setEnabled(false);
            summaryText.setVisibility(View.VISIBLE);
        }
    }

    @MainThread
    private void updateConnectedWifi(boolean isWifiEnabled, boolean isDeviceLocked) {
        if (!isWifiEnabled || mConnectedWifiEntry == null || isDeviceLocked) {
            mConnectedWifListLayout.setVisibility(View.GONE);
            return;
        }
        mConnectedWifListLayout.setVisibility(View.VISIBLE);
        mConnectedWifiTitleText.setText(mConnectedWifiEntry.getTitle());
        mConnectedWifiSummaryText.setText(mConnectedWifiEntry.getSummary(false));
        mConnectedWifiIcon.setImageDrawable(
                mInternetDialogController.getInternetWifiDrawable(mConnectedWifiEntry));
        mWifiSettingsIcon.setColorFilter(
                mContext.getColor(R.color.connected_network_primary_color));

        if (mSecondaryMobileNetworkLayout != null) {
            mSecondaryMobileNetworkLayout.setVisibility(View.GONE);
        }
    }

    @MainThread
    private void updateWifiListAndSeeAll(boolean isWifiEnabled, boolean isDeviceLocked) {
        if (!isWifiEnabled || isDeviceLocked) {
            mWifiRecyclerView.setVisibility(View.GONE);
            mSeeAllLayout.setVisibility(View.GONE);
            return;
        }
        final int wifiListMaxCount = getWifiListMaxCount();
        if (mAdapter.getItemCount() > wifiListMaxCount) {
            mHasMoreWifiEntries = true;
        }
        mAdapter.setMaxEntriesCount(wifiListMaxCount);
        final int wifiListMinHeight = mWifiNetworkHeight * wifiListMaxCount;
        if (mWifiRecyclerView.getMinimumHeight() != wifiListMinHeight) {
            mWifiRecyclerView.setMinimumHeight(wifiListMinHeight);
        }
        mWifiRecyclerView.setVisibility(View.VISIBLE);
        mSeeAllLayout.setVisibility(mHasMoreWifiEntries ? View.VISIBLE : View.INVISIBLE);
    }

    @VisibleForTesting
    @MainThread
    int getWifiListMaxCount() {
        // Use the maximum count of networks to calculate the remaining count for Wi-Fi networks.
        int count = MAX_NETWORK_COUNT;
        if (mEthernetLayout.getVisibility() == View.VISIBLE) {
            count -= 1;
        }
        if (mMobileNetworkLayout.getVisibility() == View.VISIBLE) {
            count -= 1;
        }

        // If the remaining count is greater than the maximum count of the Wi-Fi network, the
        // maximum count of the Wi-Fi network is used.
        if (count > MAX_WIFI_ENTRY_COUNT) {
            count = MAX_WIFI_ENTRY_COUNT;
        }
        if (mConnectedWifListLayout.getVisibility() == View.VISIBLE) {
            count -= 1;
        }
        return count;
    }

    @MainThread
    private void updateWifiScanNotify(boolean isWifiEnabled, boolean isWifiScanEnabled,
            boolean isDeviceLocked) {
        if (isWifiEnabled || !isWifiScanEnabled || isDeviceLocked) {
            mWifiScanNotifyLayout.setVisibility(View.GONE);
            return;
        }
        if (TextUtils.isEmpty(mWifiScanNotifyText.getText())) {
            final AnnotationLinkSpan.LinkInfo linkInfo = new AnnotationLinkSpan.LinkInfo(
                    AnnotationLinkSpan.LinkInfo.DEFAULT_ANNOTATION,
                    mInternetDialogController::launchWifiScanningSetting);
            mWifiScanNotifyText.setText(AnnotationLinkSpan.linkify(
                    getContext().getText(R.string.wifi_scan_notify_message), linkInfo));
            mWifiScanNotifyText.setMovementMethod(LinkMovementMethod.getInstance());
        }
        mWifiScanNotifyLayout.setVisibility(View.VISIBLE);
    }

    void onClickConnectedWifi(View view) {
        if (mConnectedWifiEntry == null) {
            return;
        }
        mInternetDialogController.launchWifiDetailsSetting(mConnectedWifiEntry.getKey(), view);
    }

    /** For DSDS auto data switch **/
    void onClickConnectedSecondarySub(View view) {
        mInternetDialogController.launchMobileNetworkSettings(view);
    }

    void onClickSeeMoreButton(View view) {
        mInternetDialogController.launchNetworkSetting(view);
    }

    CharSequence getDialogTitleText() {
        return mInternetDialogController.getDialogTitleText();
    }

    @Nullable
    CharSequence getSubtitleText() {
        return mInternetDialogController.getSubtitleText(
                mIsProgressBarVisible && !mIsSearchingHidden);
    }

    private Drawable getSignalStrengthDrawable(int subId) {
        return mInternetDialogController.getSignalStrengthDrawable(subId);
    }

    CharSequence getMobileNetworkTitle(int subId) {
        return mInternetDialogController.getMobileNetworkTitle(subId);
    }

    String getMobileNetworkSummary(int subId) {
        if (subId == mDefaultDataSubId && shouldDisallowUserToDisableDdsMobileData()) {
            return mContext.getString(R.string.mobile_data_summary_not_allowed_to_disable_data);
        }
        return mInternetDialogController.getMobileNetworkSummary(subId);
    }

    private CharSequence getHotspotTitle() {
        final WifiManager wifiManager = mInternetDialogController.getWifiManager();
        if (wifiManager != null) {
            final SoftApConfiguration softApConfig = wifiManager.getSoftApConfiguration();
            if (softApConfig != null) {
                return softApConfig.getSsid();
            }
        }
        return mContext.getString(R.string.quick_settings_hotspot_label);
    }

    String getHotspotSummary() {
        if (mInternetDialogController.isDataSaverEnabled()) {
            return mContext.getString(
                    R.string.quick_settings_hotspot_secondary_label_data_saver_enabled);
        } else if (mInternetDialogController.isHotspotTransient()) {
            return mContext.getString(R.string.quick_settings_hotspot_secondary_label_transient);
        } else if (mInternetDialogController.isHotspotEnabled()) {
            int numDevices = mInternetDialogController.getHotspotNumDevices();
            if (numDevices > 0) {
                return mContext.getResources().getQuantityString(
                        R.plurals.quick_settings_internet_hotspot_summary_num_devices,
                        numDevices, numDevices);
            }
            return mContext.getString(R.string.switch_bar_on);
        }
        return mContext.getString(R.string.switch_bar_off);
    }

    protected void showProgressBar() {
        if (!mInternetDialogController.isWifiEnabled()
                || mInternetDialogController.isDeviceLocked()) {
            setProgressBarVisible(false);
            return;
        }
        setProgressBarVisible(true);
        if (mConnectedWifiEntry != null || mWifiEntriesCount > 0) {
            mHandler.postDelayed(mHideProgressBarRunnable, PROGRESS_DELAY_MS);
        } else if (!mIsSearchingHidden) {
            mHandler.postDelayed(mHideSearchingRunnable, PROGRESS_DELAY_MS);
        }
    }

    private void setProgressBarVisible(boolean visible) {
        if (mIsProgressBarVisible == visible) {
            return;
        }
        mIsProgressBarVisible = visible;
        mProgressBar.setVisibility(visible ? View.VISIBLE : View.GONE);
        mProgressBar.setIndeterminate(visible);
        mDivider.setVisibility(visible ? View.GONE : View.VISIBLE);
        mInternetDialogSubTitle.setText(getSubtitleText());
    }

    private boolean shouldShowMobileDialog(int subId) {
        if (mInternetDialogController.isMobileDataEnabled(subId)) {
            if (isCiwlanWarningConditionSatisfied(subId)) {
                return true;
            }
            boolean flag = Prefs.getBoolean(mContext, QS_HAS_TURNED_OFF_MOBILE_DATA,
                    false);
            if (!flag) {
                return true;
            }
        }
        return false;
    }

    private boolean isCiwlanWarningConditionSatisfied(int subId) {
        TelephonyManager telephonyManager = mTelephonyManager.createForSubscriptionId(subId);
        boolean isCallIdle = telephonyManager.getCallState() == TelephonyManager.CALL_STATE_IDLE;
        boolean isImsRegisteredOverCiwlan = isImsRegisteredOverCiwlan(subId);
        boolean isCiwlanModeSupported = isCiwlanModeSupported(subId);
        boolean isInCiwlanOnlyMode = isInCiwlanOnlyMode(telephonyManager, subId);
        Log.d(TAG, "isCallIdle = " + isCallIdle + ", isImsRegisteredOverCiwlan = " +
                isImsRegisteredOverCiwlan + ", isInCiwlanOnlyMode = " + isInCiwlanOnlyMode +
                ", isCiwlanModeSupported = " + isCiwlanModeSupported + " on SUB " + subId);
        return !isCallIdle && isImsRegisteredOverCiwlan
                && (!isCiwlanModeSupported || isInCiwlanOnlyMode);
    }

    private void showTurnOffMobileDialog(int subId) {
        CharSequence carrierName = getMobileNetworkTitle(subId);
        boolean isInService = mInternetDialogController.isVoiceStateInService(subId);
        if (TextUtils.isEmpty(carrierName) || !isInService) {
            carrierName = mContext.getString(R.string.mobile_data_disable_message_default_carrier);
        }
        String mobileDataDisableDialogMessage = isDualDataEnabled() ?
                mContext.getString(R.string.mobile_data_disable_message_on_dual_data, carrierName)
                : mContext.getString(R.string.mobile_data_disable_message, carrierName);

        // Adjust the dialog message for CIWLAN
        if (isCiwlanWarningConditionSatisfied(subId)) {
            mobileDataDisableDialogMessage = isCiwlanModeSupported(subId) ?
                    mContext.getString(R.string.data_disable_ciwlan_call_will_drop_message) :
                    mContext.getString(R.string.data_disable_ciwlan_call_might_drop_message);
        }

        final Switch mobileDataToggle = (subId == mDefaultDataSubId)
                ? mMobileDataToggle : mSecondaryMobileDataToggle;
        mAlertDialog = new Builder(mContext)
                .setTitle(R.string.mobile_data_disable_title)
                .setMessage(mobileDataDisableDialogMessage)
                .setNegativeButton(android.R.string.cancel, (d, w) -> {})
                .setPositiveButton(
                        com.android.internal.R.string.alert_windows_notification_turn_off_action,
                        (d, w) -> {
                            mInternetDialogController.setMobileDataEnabled(mContext,
                                    subId, false, false);
                            mobileDataToggle.setChecked(false);
                            Prefs.putBoolean(mContext, QS_HAS_TURNED_OFF_MOBILE_DATA, true);
                        })
                .create();
        mAlertDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
        SystemUIDialog.setShowForAllUsers(mAlertDialog, true);
        SystemUIDialog.registerDismissListener(mAlertDialog);
        SystemUIDialog.setWindowOnTop(mAlertDialog, mKeyguard.isShowing());
        mDialogLaunchAnimator.showFromDialog(mAlertDialog, this, null, false);
    }

    private void showTurnOffAutoDataSwitchDialog(int subId) {
        CharSequence carrierName = getMobileNetworkTitle(mDefaultDataSubId);
        if (TextUtils.isEmpty(carrierName)) {
            carrierName = mContext.getString(R.string.mobile_data_disable_message_default_carrier);
        }
        mAlertDialog = new Builder(mContext)
                .setTitle(mContext.getString(R.string.auto_data_switch_disable_title, carrierName))
                .setMessage(R.string.auto_data_switch_disable_message)
                .setNegativeButton(R.string.auto_data_switch_dialog_negative_button,
                        (d, w) -> {})
                .setPositiveButton(R.string.auto_data_switch_dialog_positive_button,
                        (d, w) -> {
                            mInternetDialogController
                                    .setAutoDataSwitchMobileDataPolicy(subId, false);
                            if (mSecondaryMobileNetworkLayout != null) {
                                mSecondaryMobileNetworkLayout.setVisibility(View.GONE);
                            }
                        })
                .create();
        mAlertDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
        SystemUIDialog.setShowForAllUsers(mAlertDialog, true);
        SystemUIDialog.registerDismissListener(mAlertDialog);
        SystemUIDialog.setWindowOnTop(mAlertDialog, mKeyguard.isShowing());
        mDialogLaunchAnimator.showFromDialog(mAlertDialog, this, null, false);
    }

    private boolean isImsRegisteredOverCiwlan(int subId) {
        IImsRegistration imsRegistrationImpl = mTelephonyManager.getImsRegistration(
                mSubscriptionManager.getSlotIndex(subId), FEATURE_MMTEL);
        if (imsRegistrationImpl != null) {
            try {
                return imsRegistrationImpl.getRegistrationTechnology() ==
                        REGISTRATION_TECH_CROSS_SIM;
            } catch (RemoteException ex) {
                Log.e(TAG, "getRegistrationTechnology failed", ex);
            }
        }
        return false;
    }

    private boolean isInCiwlanOnlyMode(TelephonyManager tm, int subId) {
        CiwlanConfig ciwlanConfig =
                (subId == mDefaultDataSubId) ? mCiwlanConfig : mNddsCiwlanConfig;
        if (ciwlanConfig == null) {
            Log.d(TAG, "isInCiwlanOnlyMode: C_IWLAN config null on SUB " + subId);
            return false;
        }
        if (isRoaming(tm)) {
            return ciwlanConfig.isCiwlanOnlyInRoam();
        }
        return ciwlanConfig.isCiwlanOnlyInHome();
    }

    private boolean isCiwlanModeSupported(int subId) {
        CiwlanConfig ciwlanConfig =
                (subId == mDefaultDataSubId) ? mCiwlanConfig : mNddsCiwlanConfig;
        if (ciwlanConfig == null) {
            Log.d(TAG, "isCiwlanModeSupported: C_IWLAN config null on SUB " + subId);
            return false;
        }
        return ciwlanConfig.isCiwlanModeSupported();
    }

    private boolean isRoaming(TelephonyManager tm) {
        if (tm == null) {
            Log.d(TAG, "isRoaming: TelephonyManager null");
            return false;
        }
        boolean nriRoaming = false;
        ServiceState serviceState = tm.getServiceState();
        if (serviceState != null) {
            NetworkRegistrationInfo nri =
                    serviceState.getNetworkRegistrationInfo(DOMAIN_PS, TRANSPORT_TYPE_WWAN);
            if (nri != null) {
                nriRoaming = nri.isNetworkRoaming();
            } else {
                Log.d(TAG, "isRoaming: network registration info null");
            }
        } else {
            Log.d(TAG, "isRoaming: service state null");
        }
        return nriRoaming;
    }

    @Override
    public void onRefreshCarrierInfo() {
        mHandler.post(() -> updateDialog(true /* shouldUpdateMobileNetwork */));
    }

    @Override
    public void onSimStateChanged() {
        mHandler.post(() -> updateDialog(true /* shouldUpdateMobileNetwork */));
    }

    @Override
    @WorkerThread
    public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
        mHandler.post(() -> updateDialog(true /* shouldUpdateMobileNetwork */));
    }

    @Override
    @WorkerThread
    public void onLost(Network network) {
        mHandler.post(() -> updateDialog(true /* shouldUpdateMobileNetwork */));
    }

    @Override
    public void onSubscriptionsChanged(int defaultDataSubId) {
        mDefaultDataSubId = defaultDataSubId;
        mTelephonyManager = mTelephonyManager.createForSubscriptionId(mDefaultDataSubId);
        mNddsSubId = getNddsSubId();
        updateCiwlanConfigs();
        mHandler.post(() -> updateDialog(true /* shouldUpdateMobileNetwork */));
    }

    @Override
    public void onUserMobileDataStateChanged(boolean enabled) {
        mHandler.post(() -> updateDialog(true /* shouldUpdateMobileNetwork */));
    }

    @Override
    public void onServiceStateChanged(ServiceState serviceState) {
        mHandler.post(() -> updateDialog(true /* shouldUpdateMobileNetwork */));
    }

    @Override
    @WorkerThread
    public void onDataConnectionStateChanged(int state, int networkType) {
        mHandler.post(() -> updateDialog(true /* shouldUpdateMobileNetwork */));
    }

    @Override
    public void onSignalStrengthsChanged(SignalStrength signalStrength) {
        mHandler.post(() -> updateDialog(true /* shouldUpdateMobileNetwork */));
    }

    @Override
    public void onDisplayInfoChanged(TelephonyDisplayInfo telephonyDisplayInfo) {
        mHandler.post(() -> updateDialog(true /* shouldUpdateMobileNetwork */));
    }

    @Override
    public void onCarrierNetworkChange(boolean active) {
        mHandler.post(() -> updateDialog(true /* shouldUpdateMobileNetwork */));
    }

    @Override
    public void onNonDdsCallStateChanged(int callState) {
        mHandler.post(() -> updateDialog(true /* shouldUpdateMobileNetwork */));
    }

    @Override
    public void onTempDdsSwitchHappened() {
        mHandler.post(() -> updateDialog(true /* shouldUpdateMobileNetwork */));
    }

    @Override
    @WorkerThread
    public void onAccessPointsChanged(@Nullable List<WifiEntry> wifiEntries,
            @Nullable WifiEntry connectedEntry, boolean hasMoreWifiEntries) {
        // Should update the carrier network layout when it is connected under airplane mode ON.
        boolean shouldUpdateCarrierNetwork = mMobileNetworkLayout.getVisibility() == View.VISIBLE
                && mInternetDialogController.isAirplaneModeEnabled();
        mHandler.post(() -> {
            mConnectedWifiEntry = connectedEntry;
            mWifiEntriesCount = wifiEntries == null ? 0 : wifiEntries.size();
            mHasMoreWifiEntries = hasMoreWifiEntries;
            updateDialog(shouldUpdateCarrierNetwork /* shouldUpdateMobileNetwork */);
            mAdapter.setWifiEntries(wifiEntries, mWifiEntriesCount);
            mAdapter.notifyDataSetChanged();
        });
    }

    @Override
    public void onHotspotChanged() {
        mHandler.post(() -> updateDialog(false /* shouldUpdateMobileNetwork */,
                true /* shouldUpdateHotspot */));
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (mAlertDialog != null && !mAlertDialog.isShowing()) {
            if (!hasFocus && isShowing()) {
                dismiss();
            }
        }
    }

    private boolean isDualDataEnabled() {
        return mInternetDialogController.isDualDataEnabled();
    }

    @Override
    public void onDualDataEnabledStateChanged() {
        mNddsSubId = getNddsSubId();
        updateCiwlanConfigs();
        mHandler.post(() -> updateDialog(true /* shouldUpdateMobileNetwork */));
    }

    private void updateCiwlanConfigs() {
        if (mExtTelephonyManager != null) {
            try {
                if (SubscriptionManager.isUsableSubscriptionId(mDefaultDataSubId)) {
                    mCiwlanConfig = mExtTelephonyManager.getCiwlanConfig(
                            SubscriptionManager.getSlotIndex(mDefaultDataSubId));
                }
                if (SubscriptionManager.isUsableSubscriptionId(mNddsSubId)) {
                    mNddsCiwlanConfig = mExtTelephonyManager.getCiwlanConfig(
                            SubscriptionManager.getSlotIndex(mNddsSubId));
                }
            } catch (RemoteException ex) {
                Log.e(TAG, "getCiwlanConfig exception", ex);
            }
        }
    }

    private int getNddsSubId() {
        return mInternetDialogController.getNddsSubId();
    }

    public enum InternetDialogEvent implements UiEventLogger.UiEventEnum {
        @UiEvent(doc = "The Internet dialog became visible on the screen.")
        INTERNET_DIALOG_SHOW(843);

        private final int mId;

        InternetDialogEvent(int id) {
            mId = id;
        }

        @Override
        public int getId() {
            return mId;
        }
    }
}
