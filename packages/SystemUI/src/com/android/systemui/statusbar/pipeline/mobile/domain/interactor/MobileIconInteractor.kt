/*
 * Copyright (C) 2022 The Android Open Source Project
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

/*
 * Changes from Qualcomm Innovation Center are provided under the following license:
 * Copyright (c) 2023 Qualcomm Innovation Center, Inc. All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.android.systemui.statusbar.pipeline.mobile.domain.interactor

import android.content.Context
import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import com.android.settingslib.SignalIcon.MobileIconGroup
import com.android.settingslib.graph.SignalDrawable
import com.android.settingslib.mobile.MobileIconCarrierIdOverrides
import com.android.settingslib.mobile.MobileIconCarrierIdOverridesImpl
import com.android.settingslib.mobile.MobileMappings
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.statusbar.pipeline.mobile.data.model.DataConnectionState.Connected
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileIconCustomizationMode
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType.DefaultNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType.OverrideNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository
import com.android.systemui.statusbar.pipeline.mobile.domain.model.NetworkTypeIconModel
import com.android.systemui.statusbar.pipeline.mobile.domain.model.NetworkTypeIconModel.DefaultIcon
import com.android.systemui.statusbar.pipeline.mobile.domain.model.NetworkTypeIconModel.OverriddenIcon
import com.android.systemui.statusbar.pipeline.mobile.domain.model.SignalIconModel
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

interface MobileIconInteractor {
    /** The table log created for this connection */
    val tableLogBuffer: TableLogBuffer

    /** The current mobile data activity */
    val activity: Flow<DataActivityModel>

    /** See [MobileConnectionsRepository.mobileIsDefault]. */
    val mobileIsDefault: Flow<Boolean>

    /**
     * True when telephony tells us that the data state is CONNECTED. See
     * [android.telephony.TelephonyCallback.DataConnectionStateListener] for more details. We
     * consider this connection to be serving data, and thus want to show a network type icon, when
     * data is connected. Other data connection states would typically cause us not to show the icon
     */
    val isDataConnected: StateFlow<Boolean>

    /** Only true if mobile is the cellular transport but is not validated, otherwise false */
    val isConnectionFailed: StateFlow<Boolean>

    /** True if we consider this connection to be in service, i.e. can make calls */
    val isInService: StateFlow<Boolean>

    /** Observable for the data enabled state of this connection */
    val isDataEnabled: StateFlow<Boolean>

    /** True if the RAT icon should always be displayed and false otherwise. */
    val alwaysShowDataRatIcon: StateFlow<Boolean>

    /** Canonical representation of the current mobile signal strength as a triangle. */
    val signalLevelIcon: StateFlow<SignalIconModel>

    /** Observable for RAT type (network type) indicator */
    val networkTypeIconGroup: StateFlow<NetworkTypeIconModel>

    /**
     * Provider name for this network connection. The name can be one of 3 values:
     * 1. The default network name, if one is configured
     * 2. A derived name based off of the intent [ACTION_SERVICE_PROVIDERS_UPDATED]
     * 3. Or, in the case where the repository sends us the default network name, we check for an
     *    override in [connectionInfo.operatorAlphaShort], a value that is derived from
     *    [ServiceState]
     */
    val networkName: StateFlow<NetworkNameModel>

    /**
     * Provider name for this network connection. The name can be one of 3 values:
     * 1. The default network name, if one is configured
     * 2. A name provided by the [SubscriptionModel] of this network connection
     * 3. Or, in the case where the repository sends us the default network name, we check for an
     *    override in [connectionInfo.operatorAlphaShort], a value that is derived from
     *    [ServiceState]
     *
     * TODO(b/296600321): De-duplicate this field with [networkName] after determining the data
     *   provided is identical
     */
    val carrierName: StateFlow<String>

    /** True if there is only one active subscription. */
    val isSingleCarrier: StateFlow<Boolean>

    /**
     * True if this connection is considered roaming. The roaming bit can come from [ServiceState],
     * or directly from the telephony manager's CDMA ERI number value. Note that we don't consider a
     * connection to be roaming while carrier network change is active
     */
    val isRoaming: StateFlow<Boolean>

    /** See [MobileIconsInteractor.isRoamingForceHidden]. */
    val isRoamingForceHidden: Flow<Boolean>

    /** See [MobileIconsInteractor.isForceHidden]. */
    val isForceHidden: Flow<Boolean>

    /** See [MobileConnectionRepository.isAllowedDuringAirplaneMode]. */
    val isAllowedDuringAirplaneMode: StateFlow<Boolean>

    /** True when in carrier network change mode */
    val carrierNetworkChangeActive: StateFlow<Boolean>

    /** True if the rsrp level should be preferred over the primary level for LTE. */
    val alwaysUseRsrpLevelForLte: StateFlow<Boolean>

    /** True if the no internet icon should be hidden.  */
    val hideNoInternetState: StateFlow<Boolean>

    val networkTypeIconCustomization: StateFlow<MobileIconCustomizationMode>

    val imsInfo: StateFlow<MobileIconCustomizationMode>

    val showVolteIcon: StateFlow<Boolean>

    val showVowifiIcon: StateFlow<Boolean>

    val voWifiAvailable: StateFlow<Boolean>

    /** True when VoLTE/VONR available */
    val isMobileHd: StateFlow<Boolean>

    /** See [MobileIconsInteractor.isMobileHdForceHidden]. */
    val isMobileHdForceHidden: Flow<Boolean>

    /** True when VoWifi available */
    val isVoWifi: StateFlow<Boolean>

    /** See [MobileIconsInteractor.isVoWifiForceHidden]. */
    val isVoWifiForceHidden: Flow<Boolean>
}

/** Interactor for a single mobile connection. This connection _should_ have one subscription ID */
@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
class MobileIconInteractorImpl(
    @Application scope: CoroutineScope,
    defaultSubscriptionHasDataEnabled: StateFlow<Boolean>,
    override val alwaysShowDataRatIcon: StateFlow<Boolean>,
    alwaysUseCdmaLevel: StateFlow<Boolean>,
    override val isSingleCarrier: StateFlow<Boolean>,
    override val mobileIsDefault: StateFlow<Boolean>,
    defaultMobileIconMapping: StateFlow<Map<String, MobileIconGroup>>,
    defaultMobileIconGroup: StateFlow<MobileIconGroup>,
    isDefaultConnectionFailed: StateFlow<Boolean>,
    override val isForceHidden: Flow<Boolean>,
    override val isRoamingForceHidden: Flow<Boolean>,
    override val isMobileHdForceHidden: Flow<Boolean>,
    override val isVoWifiForceHidden: Flow<Boolean>,
    connectionRepository: MobileConnectionRepository,
    override val alwaysUseRsrpLevelForLte: StateFlow<Boolean>,
    override val hideNoInternetState: StateFlow<Boolean>,
    networkTypeIconCustomizationFlow: StateFlow<MobileIconCustomizationMode>,
    override val showVolteIcon: StateFlow<Boolean>,
    override val showVowifiIcon: StateFlow<Boolean>,
    private val context: Context,
    private val defaultDataSubId: StateFlow<Int>,
    val carrierIdOverrides: MobileIconCarrierIdOverrides = MobileIconCarrierIdOverridesImpl()
) : MobileIconInteractor {
    override val tableLogBuffer: TableLogBuffer = connectionRepository.tableLogBuffer

    override val activity = connectionRepository.dataActivityDirection

    override val isDataEnabled: StateFlow<Boolean> = connectionRepository.dataEnabled

    override val carrierNetworkChangeActive: StateFlow<Boolean> =
        connectionRepository.carrierNetworkChangeActive

    // True if there exists _any_ icon override for this carrierId. Note that overrides can include
    // any or none of the icon groups defined in MobileMappings, so we still need to check on a
    // per-network-type basis whether or not the given icon group is overridden
    private val carrierIdIconOverrideExists =
        connectionRepository.carrierId
            .map { carrierIdOverrides.carrierIdEntryExists(it) }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val networkName =
        combine(connectionRepository.operatorAlphaShort, connectionRepository.networkName) {
                operatorAlphaShort,
                networkName ->
                if (networkName is NetworkNameModel.Default && operatorAlphaShort != null) {
                    NetworkNameModel.IntentDerived(operatorAlphaShort)
                } else {
                    networkName
                }
            }
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                connectionRepository.networkName.value
            )

    private val signalStrengthCustomization: StateFlow<MobileIconCustomizationMode> =
        combine(
            alwaysUseRsrpLevelForLte,
            connectionRepository.lteRsrpLevel,
            connectionRepository.voiceNetworkType,
            connectionRepository.dataNetworkType,
        ) { alwaysUseRsrpLevelForLte, lteRsrpLevel, voiceNetworkType, dataNetworkType ->
            MobileIconCustomizationMode(
                alwaysUseRsrpLevelForLte = alwaysUseRsrpLevelForLte,
                lteRsrpLevel = lteRsrpLevel,
                voiceNetworkType = voiceNetworkType,
                dataNetworkType = dataNetworkType,
            )
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(), MobileIconCustomizationMode())

    override val isRoaming: StateFlow<Boolean> =
        combine(
            connectionRepository.carrierNetworkChangeActive,
            connectionRepository.isGsm,
            connectionRepository.isRoaming,
            connectionRepository.cdmaRoaming,
        ) { carrierNetworkChangeActive, isGsm, isRoaming, cdmaRoaming ->
            if (carrierNetworkChangeActive) {
                false
            } else if (isGsm) {
                isRoaming
            } else {
                cdmaRoaming
            }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    private val isDefaultDataSub = defaultDataSubId
        .mapLatest { connectionRepository.subId == it }
        .stateIn(
            scope,
            SharingStarted.WhileSubscribed(),
            connectionRepository.subId == defaultDataSubId.value
        )

    override val networkTypeIconCustomization: StateFlow<MobileIconCustomizationMode> =
        combine(
            networkTypeIconCustomizationFlow,
            isDataEnabled,
            connectionRepository.dataRoamingEnabled,
            isRoaming,
            isDefaultDataSub,
        ){ state, mobileDataEnabled, dataRoamingEnabled, isRoaming, isDefaultDataSub ->
            MobileIconCustomizationMode(
                isRatCustomization = state.isRatCustomization,
                alwaysShowNetworkTypeIcon = state.alwaysShowNetworkTypeIcon,
                ddsRatIconEnhancementEnabled = state.ddsRatIconEnhancementEnabled,
                nonDdsRatIconEnhancementEnabled = state.nonDdsRatIconEnhancementEnabled,
                mobileDataEnabled = mobileDataEnabled,
                dataRoamingEnabled = dataRoamingEnabled,
                isDefaultDataSub = isDefaultDataSub,
                isRoaming = isRoaming
            )
        }.stateIn(scope, SharingStarted.WhileSubscribed(), MobileIconCustomizationMode())

    private val mobileIconCustomization: StateFlow<MobileIconCustomizationMode> =
        combine(
            signalStrengthCustomization,
            connectionRepository.nrIconType,
            networkTypeIconCustomization,
        ) { signalStrengthCustomization, nrIconType, networkTypeIconCustomization ->
            MobileIconCustomizationMode(
                dataNetworkType = signalStrengthCustomization.dataNetworkType,
                voiceNetworkType = signalStrengthCustomization.voiceNetworkType,
                isRatCustomization = networkTypeIconCustomization.isRatCustomization,
                alwaysShowNetworkTypeIcon =
                    networkTypeIconCustomization.alwaysShowNetworkTypeIcon,
                ddsRatIconEnhancementEnabled =
                    networkTypeIconCustomization.ddsRatIconEnhancementEnabled,
                nonDdsRatIconEnhancementEnabled =
                    networkTypeIconCustomization.nonDdsRatIconEnhancementEnabled,
                mobileDataEnabled = networkTypeIconCustomization.mobileDataEnabled,
                dataRoamingEnabled = networkTypeIconCustomization.dataRoamingEnabled,
                isDefaultDataSub = networkTypeIconCustomization.isDefaultDataSub,
                isRoaming = networkTypeIconCustomization.isRoaming
            )
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(), MobileIconCustomizationMode())

    override val imsInfo: StateFlow<MobileIconCustomizationMode> =
        combine(
            connectionRepository.voiceNetworkType,
            connectionRepository.originNetworkType,
            connectionRepository.voiceCapable,
            connectionRepository.videoCapable,
            connectionRepository.imsRegistered,
        ) { voiceNetworkType, originNetworkType, voiceCapable, videoCapable, imsRegistered->
            MobileIconCustomizationMode(
                voiceNetworkType = voiceNetworkType,
                originNetworkType = originNetworkType,
                voiceCapable = voiceCapable,
                videoCapable = videoCapable,
                imsRegistered = imsRegistered,
            )
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(), MobileIconCustomizationMode())

    override val voWifiAvailable: StateFlow<Boolean> =
        combine(
            connectionRepository.imsRegistrationTech,
            connectionRepository.voiceCapable,
            showVowifiIcon,
        ) { imsRegistrationTech, voiceCapable, showVowifiIcon ->
            voiceCapable
                    && imsRegistrationTech == REGISTRATION_TECH_IWLAN
                    && showVowifiIcon
        }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val carrierName =
        combine(connectionRepository.operatorAlphaShort, connectionRepository.carrierName) {
                operatorAlphaShort,
                networkName ->
                if (networkName is NetworkNameModel.Default && operatorAlphaShort != null) {
                    operatorAlphaShort
                } else {
                    networkName.name
                }
            }
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                connectionRepository.carrierName.value.name
            )

    /** What the mobile icon would be before carrierId overrides */
    private val defaultNetworkType: StateFlow<MobileIconGroup> =
        combine(
                connectionRepository.resolvedNetworkType,
                defaultMobileIconMapping,
                defaultMobileIconGroup,
                mobileIconCustomization,
            ) { resolvedNetworkType, mapping, defaultGroup, mobileIconCustomization ->
                when (resolvedNetworkType) {
                    is ResolvedNetworkType.CarrierMergedNetworkType ->
                        resolvedNetworkType.iconGroupOverride
                    else -> {
                        mapping[resolvedNetworkType.lookupKey] ?: defaultGroup
                    }
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), defaultMobileIconGroup.value)

    override val networkTypeIconGroup =
        combine(
                defaultNetworkType,
                carrierIdIconOverrideExists,
            ) { networkType, overrideExists ->
                // DefaultIcon comes out of the icongroup lookup, we check for overrides here
                if (overrideExists) {
                    val iconOverride =
                        carrierIdOverrides.getOverrideFor(
                            connectionRepository.carrierId.value,
                            networkType.name,
                            context.resources,
                        )
                    if (iconOverride > 0) {
                        OverriddenIcon(networkType, iconOverride)
                    } else {
                        DefaultIcon(networkType)
                    }
                } else {
                    DefaultIcon(networkType)
                }
            }
            .distinctUntilChanged()
            .logDiffsForTable(
                tableLogBuffer = tableLogBuffer,
                columnPrefix = "",
                initialValue = DefaultIcon(defaultMobileIconGroup.value),
            )
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                DefaultIcon(defaultMobileIconGroup.value),
            )

    private val level: StateFlow<Int> =
        combine(
                connectionRepository.isGsm,
                connectionRepository.primaryLevel,
                connectionRepository.cdmaLevel,
                alwaysUseCdmaLevel,
                signalStrengthCustomization,
            ) { isGsm, primaryLevel, cdmaLevel, alwaysUseCdmaLevel, signalStrengthCustomization ->
                when {
                    signalStrengthCustomization.alwaysUseRsrpLevelForLte -> {
                        if (isLteCamped(signalStrengthCustomization)) {
                            signalStrengthCustomization.lteRsrpLevel
                        } else {
                            primaryLevel
                        }
                    }
                    // GSM connections should never use the CDMA level
                    isGsm -> primaryLevel
                    alwaysUseCdmaLevel -> cdmaLevel
                    else -> primaryLevel
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), 0)

    private val numberOfLevels: StateFlow<Int> =
        connectionRepository.numberOfLevels.stateIn(
            scope,
            SharingStarted.WhileSubscribed(),
            connectionRepository.numberOfLevels.value,
        )

    override val isDataConnected: StateFlow<Boolean> =
        connectionRepository.dataConnectionState
            .map { it == Connected }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val isInService = connectionRepository.isInService

    override val isConnectionFailed: StateFlow<Boolean> = connectionRepository.isConnectionFailed

    private fun isLteCamped(mobileIconCustmization: MobileIconCustomizationMode): Boolean {
        return (mobileIconCustmization.dataNetworkType == TelephonyManager.NETWORK_TYPE_LTE
                || mobileIconCustmization.dataNetworkType == TelephonyManager.NETWORK_TYPE_LTE_CA
                || mobileIconCustmization.voiceNetworkType == TelephonyManager.NETWORK_TYPE_LTE
                || mobileIconCustmization.voiceNetworkType == TelephonyManager.NETWORK_TYPE_LTE_CA)
        }

    override val isAllowedDuringAirplaneMode = connectionRepository.isAllowedDuringAirplaneMode

    /** Whether or not to show the error state of [SignalDrawable] */
    private val showExclamationMark: StateFlow<Boolean> =
        combine(
                isDataEnabled,
                isDataConnected,
                isConnectionFailed,
                isInService,
                hideNoInternetState,
            ) { isDataEnabled, isDataConnected, isConnectionFailed,
                    isInService, hideNoInternetState ->
                if (hideNoInternetState) {
                    false
                } else {
                    !isDataEnabled || (isDataConnected && isConnectionFailed) || !isInService
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), true)

    private val shownLevel: StateFlow<Int> =
        combine(
                level,
                isInService,
            ) { level, isInService ->
                if (isInService) level else 0
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), 0)

    private val showRoaming: StateFlow<Boolean> =
        combine(
                isRoaming,
                isRoamingForceHidden
        ) { roaming, roamingForceHidden ->
            roaming && !roamingForceHidden
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val signalLevelIcon: StateFlow<SignalIconModel> = run {
        val initial =
            SignalIconModel(
                level = shownLevel.value,
                numberOfLevels = numberOfLevels.value,
                showExclamationMark = showExclamationMark.value,
                carrierNetworkChange = carrierNetworkChangeActive.value,
                showRoaming = showRoaming.value
            )
        combine(
                shownLevel,
                numberOfLevels,
                showExclamationMark,
                carrierNetworkChangeActive,
                showRoaming
            ) { shownLevel, numberOfLevels, showExclamationMark, carrierNetworkChange, showRoaming ->
                SignalIconModel(
                    shownLevel,
                    numberOfLevels,
                    showExclamationMark,
                    carrierNetworkChange,
                    showRoaming
                )
            }
            .distinctUntilChanged()
            .logDiffsForTable(
                tableLogBuffer,
                columnPrefix = "icon",
                initialValue = initial,
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), initial)
    }

    override val isMobileHd: StateFlow<Boolean> =
        connectionRepository.imsState
            .map { it.isHdVoiceCapable() }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val isVoWifi: StateFlow<Boolean> =
        connectionRepository.imsState
            .map { it.isVoWifiAvailable() }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)
}
