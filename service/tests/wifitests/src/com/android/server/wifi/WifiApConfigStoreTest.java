/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wifi;

import static android.net.wifi.SoftApConfiguration.SECURITY_TYPE_WPA2_PSK;
import static android.net.wifi.SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.net.MacAddress;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.SoftApConfiguration.Builder;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.os.Build;
import android.os.Handler;
import android.os.test.TestLooper;

import androidx.test.filters.SmallTest;

import com.android.server.wifi.util.ApConfigUtil;
import com.android.wifi.resources.R;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

/**
 * Unit tests for {@link com.android.server.wifi.WifiApConfigStore}.
 */
@SmallTest
public class WifiApConfigStoreTest extends WifiBaseTest {

    private static final String TAG = "WifiApConfigStoreTest";

    private static final String TEST_AP_CONFIG_FILE_PREFIX = "APConfig_";
    private static final String TEST_DEFAULT_2G_CHANNEL_LIST = "1,2,3,4,5,6";
    private static final String TEST_DEFAULT_AP_SSID = "TestAP";
    private static final String TEST_DEFAULT_HOTSPOT_SSID = "TestShare";
    private static final String TEST_DEFAULT_HOTSPOT_PSK = "TestPassword";
    private static final int RAND_SSID_INT_MIN = 1000;
    private static final int RAND_SSID_INT_MAX = 9999;
    private static final String TEST_CHAR_SET_AS_STRING = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final String TEST_STRING_UTF8_WITH_30_BYTES = "智者務其實愚者爭虛名";
    private static final String TEST_STRING_UTF8_WITH_32_BYTES = "ΣωκράτηςΣωκράτης";
    private static final String TEST_STRING_UTF8_WITH_33_BYTES = "一片汪洋大海中的一條魚";
    private static final String TEST_STRING_UTF8_WITH_34_BYTES = "Ευπροσηγοροςγινου";
    private static final MacAddress TEST_RANDOMIZED_MAC =
            MacAddress.fromString("d2:11:19:34:a5:20");

    @Mock private Context mContext;
    @Mock private WifiInjector mWifiInjector;
    private TestLooper mLooper;
    private Handler mHandler;
    @Mock private BackupManagerProxy mBackupManagerProxy;
    @Mock private WifiConfigStore mWifiConfigStore;
    @Mock private WifiConfigManager mWifiConfigManager;
    private File mLegacyApConfigFile;
    private Random mRandom;
    private MockResources mResources;
    @Mock private ApplicationInfo mMockApplInfo;
    @Mock private MacAddressUtil mMacAddressUtil;
    private SoftApStoreData.DataSource mDataStoreSource;
    private ArrayList<Integer> mKnownGood2GChannelList;

    @Before
    public void setUp() throws Exception {
        mLooper = new TestLooper();
        mHandler = new Handler(mLooper.getLooper());
        MockitoAnnotations.initMocks(this);
        mMockApplInfo.targetSdkVersion = Build.VERSION_CODES.P;
        when(mContext.getApplicationInfo()).thenReturn(mMockApplInfo);

        /* Setup expectations for Resources to return some default settings. */
        mResources = new MockResources();
        mResources.setString(R.string.config_wifiSoftap2gChannelList,
                             TEST_DEFAULT_2G_CHANNEL_LIST);
        mResources.setString(R.string.wifi_tether_configure_ssid_default,
                             TEST_DEFAULT_AP_SSID);
        mResources.setString(R.string.wifi_localhotspot_configure_ssid_default,
                             TEST_DEFAULT_HOTSPOT_SSID);
        /* Default to device that does not require ap band conversion */
        mResources.setBoolean(R.bool.config_wifi_convert_apband_5ghz_to_any, false);
        when(mContext.getResources()).thenReturn(mResources);

        // build the known good 2G channel list: TEST_DEFAULT_2G_CHANNEL_LIST
        mKnownGood2GChannelList = new ArrayList(Arrays.asList(1, 2, 3, 4, 5, 6));

        mRandom = new Random();
        when(mWifiInjector.getMacAddressUtil()).thenReturn(mMacAddressUtil);
        when(mMacAddressUtil.calculatePersistentMac(any(), any())).thenReturn(TEST_RANDOMIZED_MAC);
    }

    /**
     * Helper method to create and verify actions for the ApConfigStore used in the following tests.
     */
    private WifiApConfigStore createWifiApConfigStore(String legacyFilePath) {
        WifiApConfigStore store;
        if (legacyFilePath == null) {
            store = new WifiApConfigStore(
                    mContext, mWifiInjector, mHandler, mBackupManagerProxy,
                    mWifiConfigStore, mWifiConfigManager);
        } else {
            store = new WifiApConfigStore(
                    mContext, mWifiInjector, mHandler, mBackupManagerProxy,
                    mWifiConfigStore, mWifiConfigManager, legacyFilePath);
        }

        verify(mWifiConfigStore).registerStoreData(any());
        ArgumentCaptor<SoftApStoreData.DataSource> dataStoreSourceArgumentCaptor =
                ArgumentCaptor.forClass(SoftApStoreData.DataSource.class);
        verify(mWifiInjector).makeSoftApStoreData(dataStoreSourceArgumentCaptor.capture());
        mDataStoreSource = dataStoreSourceArgumentCaptor.getValue();

        return store;
    }

    private WifiApConfigStore createWifiApConfigStore() {
        return createWifiApConfigStore(null);
    }

    /**
     * Generate a SoftApConfiguration based on the specified parameters.
     */
    private SoftApConfiguration setupApConfig(
            String ssid, String preSharedKey, int keyManagement, int band, int channel,
            boolean hiddenSSID) {
        Builder configBuilder = new SoftApConfiguration.Builder();
        configBuilder.setSsid(ssid);
        configBuilder.setPassphrase(preSharedKey, SoftApConfiguration.SECURITY_TYPE_WPA2_PSK);
        if (channel == 0) {
            configBuilder.setBand(band);
        } else {
            configBuilder.setChannel(channel, band);
        }
        configBuilder.setHiddenSsid(hiddenSSID);
        return configBuilder.build();
    }

    /**
     * Generate a WifiConfiguration based on the specified parameters.
     */
    private WifiConfiguration setupWifiConfigurationApConfig(
            String ssid, String preSharedKey, int keyManagement, int band, int channel,
            boolean hiddenSSID) {
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = ssid;
        config.preSharedKey = preSharedKey;
        config.allowedKeyManagement.set(keyManagement);
        config.apBand = band;
        config.apChannel = channel;
        config.hiddenSSID = hiddenSSID;
        return config;
    }

    private void writeLegacyApConfigFile(WifiConfiguration config) throws Exception {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                new FileOutputStream(mLegacyApConfigFile)))) {
            out.writeInt(WifiApConfigStore.AP_CONFIG_FILE_VERSION);
            out.writeUTF(config.SSID);
            out.writeInt(config.apBand);
            out.writeInt(config.apChannel);
            out.writeBoolean(config.hiddenSSID);
            int authType = config.getAuthType();
            out.writeInt(authType);
            if (authType != KeyMgmt.NONE) {
                out.writeUTF(config.preSharedKey);
            }
        } catch (IOException e) {
            fail("Error writing hotspot configuration" + e);
        }
    }

    /**
     * Asserts that the WifiConfigurations equal to SoftApConfiguration.
     * This only compares the elements saved
     * for softAp used.
     */
    public static void assertWifiConfigurationEqualSoftApConfiguration(
            WifiConfiguration backup, SoftApConfiguration restore) {
        assertEquals(backup.SSID, restore.getSsid());
        assertEquals(backup.BSSID, restore.getBssid());
        assertEquals(ApConfigUtil.convertWifiConfigBandToSoftApConfigBand(backup.apBand),
                restore.getBand());
        assertEquals(backup.apChannel, restore.getChannel());
        assertEquals(backup.preSharedKey, restore.getPassphrase());
        int authType = backup.getAuthType();
        if (backup.getAuthType() == WifiConfiguration.KeyMgmt.WPA2_PSK) {
            assertEquals(SoftApConfiguration.SECURITY_TYPE_WPA2_PSK, restore.getSecurityType());
        } else {
            assertEquals(SoftApConfiguration.SECURITY_TYPE_OPEN, restore.getSecurityType());
        }
        assertEquals(backup.hiddenSSID, restore.isHiddenSsid());
    }

    private void verifyApConfig(SoftApConfiguration config1, SoftApConfiguration config2) {
        assertEquals(config1.getSsid(), config2.getSsid());
        assertEquals(config1.getPassphrase(), config2.getPassphrase());
        assertEquals(config1.getSecurityType(), config2.getSecurityType());
        assertEquals(config1.getBand(), config2.getBand());
        assertEquals(config1.getChannel(), config2.getChannel());
        assertEquals(config1.isHiddenSsid(), config2.isHiddenSsid());
    }

    private void verifyDefaultApConfig(SoftApConfiguration config, String expectedSsid,
            boolean isSaeSupport) {
        String[] splitSsid = config.getSsid().split("_");
        assertEquals(2, splitSsid.length);
        assertEquals(expectedSsid, splitSsid[0]);
        assertEquals(SoftApConfiguration.BAND_2GHZ, config.getBand());
        assertFalse(config.isHiddenSsid());
        int randomPortion = Integer.parseInt(splitSsid[1]);
        assertTrue(randomPortion >= RAND_SSID_INT_MIN && randomPortion <= RAND_SSID_INT_MAX);
        if (isSaeSupport) {
            assertEquals(SECURITY_TYPE_WPA3_SAE_TRANSITION, config.getSecurityType());
        } else {
            assertEquals(SECURITY_TYPE_WPA2_PSK, config.getSecurityType());
        }
        assertEquals(15, config.getPassphrase().length());
    }

    private void verifyDefaultApConfig(SoftApConfiguration config, String expectedSsid) {
        // Old test cases will just verify WPA2.
        verifyDefaultApConfig(config, expectedSsid, false);
    }

    private void verifyDefaultLocalOnlyApConfig(SoftApConfiguration config, String expectedSsid,
            int expectedApBand, boolean isSaeSupport) {
        String[] splitSsid = config.getSsid().split("_");
        assertEquals(2, splitSsid.length);
        assertEquals(expectedSsid, splitSsid[0]);
        assertEquals(expectedApBand, config.getBand());
        int randomPortion = Integer.parseInt(splitSsid[1]);
        assertTrue(randomPortion >= RAND_SSID_INT_MIN && randomPortion <= RAND_SSID_INT_MAX);
        if (isSaeSupport) {
            assertEquals(SECURITY_TYPE_WPA3_SAE_TRANSITION, config.getSecurityType());
        } else {
            assertEquals(SECURITY_TYPE_WPA2_PSK, config.getSecurityType());
        }
        assertEquals(15, config.getPassphrase().length());
    }

    private void verifyDefaultLocalOnlyApConfig(SoftApConfiguration config, String expectedSsid,
            int expectedApBand) {
        verifyDefaultLocalOnlyApConfig(config, expectedSsid, expectedApBand, false);
    }

    /**
     * AP Configuration is not specified in the config file,
     * WifiApConfigStore should fallback to use the default configuration.
     */
    @Test
    public void initWithDefaultConfiguration() throws Exception {
        WifiApConfigStore store = createWifiApConfigStore();
        verifyDefaultApConfig(store.getApConfiguration(), TEST_DEFAULT_AP_SSID);
        verify(mWifiConfigManager).saveToStore(true);
    }

    /**
     * Verify WifiApConfigStore can correctly load the existing configuration
     * from the legacy config file and migrate it to the new config store.
     */
    @Test
    public void initWithExistingConfigurationInLegacyFile() throws Exception {
        WifiConfiguration backupConfig = setupWifiConfigurationApConfig(
                "ConfiguredAP",    /* SSID */
                "randomKey",       /* preshared key */
                KeyMgmt.WPA2_PSK,   /* key management */
                1,                 /* AP band (5GHz) */
                40,                /* AP channel */
                true               /* Hidden SSID */);

        /* Create a temporary file for AP config file storage. */
        mLegacyApConfigFile = File.createTempFile(TEST_AP_CONFIG_FILE_PREFIX, "");

        SoftApConfiguration expectedConfig = setupApConfig(
                "ConfiguredAP",           /* SSID */
                "randomKey",              /* preshared key */
                SECURITY_TYPE_WPA2_PSK,   /* security type */
                SoftApConfiguration.BAND_5GHZ, /* AP band (5GHz) */
                40,                       /* AP channel */
                true                      /* Hidden SSID */);

        assertWifiConfigurationEqualSoftApConfiguration(backupConfig, expectedConfig);

        writeLegacyApConfigFile(backupConfig);
        WifiApConfigStore store = createWifiApConfigStore(mLegacyApConfigFile.getPath());
        verify(mWifiConfigManager).saveToStore(true);
        verify(mBackupManagerProxy).notifyDataChanged();
        verifyApConfig(expectedConfig, store.getApConfiguration());
        verifyApConfig(expectedConfig, mDataStoreSource.toSerialize());
        // Simulate the config store read to trigger the write to new config store.
        mDataStoreSource.reset();
        mLooper.dispatchAll();
        // Triggers write twice:
        // a) On reading the legacy file (new config store not ready yet)
        // b) When the new config store is ready.
        verify(mWifiConfigManager, times(2)).saveToStore(true);

        // The temporary legacy AP config file should be removed after migration.
        assertFalse(mLegacyApConfigFile.exists());
    }

    /**
     * Verify the handling of setting a null ap configuration.
     * WifiApConfigStore should fallback to the default configuration when
     * null ap configuration is provided.
     */
    @Test
    public void setNullApConfiguration() throws Exception {
        /* Initialize WifiApConfigStore with existing configuration. */
        SoftApConfiguration expectedConfig = setupApConfig(
                "ConfiguredAP",           /* SSID */
                "randomKey",              /* preshared key */
                SECURITY_TYPE_WPA2_PSK,   /* security type */
                SoftApConfiguration.BAND_5GHZ, /* AP band (5GHz) */
                40,                       /* AP channel */
                true                      /* Hidden SSID */);
        WifiApConfigStore store = createWifiApConfigStore();
        mDataStoreSource.fromDeserialized(expectedConfig);
        verifyApConfig(expectedConfig, store.getApConfiguration());

        store.setApConfiguration(null);
        verifyDefaultApConfig(store.getApConfiguration(), TEST_DEFAULT_AP_SSID);
        verifyDefaultApConfig(mDataStoreSource.toSerialize(), TEST_DEFAULT_AP_SSID);
        verify(mWifiConfigManager).saveToStore(true);
        verify(mBackupManagerProxy).notifyDataChanged();
    }

    /**
     * Verify AP configuration is correctly updated via setApConfiguration call.
     */
    @Test
    public void updateApConfiguration() throws Exception {
        /* Initialize WifiApConfigStore with default configuration. */
        WifiApConfigStore store = createWifiApConfigStore();

        verifyDefaultApConfig(store.getApConfiguration(), TEST_DEFAULT_AP_SSID);
        verify(mWifiConfigManager).saveToStore(true);

        /* Update with a valid configuration. */
        SoftApConfiguration expectedConfig = setupApConfig(
                "ConfiguredAP",                   /* SSID */
                "randomKey",                      /* preshared key */
                SECURITY_TYPE_WPA2_PSK,           /* security type */
                SoftApConfiguration.BAND_2GHZ,    /* AP band */
                0,                                /* AP channel */
                true                              /* Hidden SSID */);
        store.setApConfiguration(expectedConfig);
        verifyApConfig(expectedConfig, store.getApConfiguration());
        verifyApConfig(expectedConfig, mDataStoreSource.toSerialize());
        verify(mWifiConfigManager, times(2)).saveToStore(true);
        verify(mBackupManagerProxy, times(2)).notifyDataChanged();
    }

    /**
     * Due to different device hw capabilities, some bands are not available if a device is
     * dual/single mode capable.  This test verifies that a single mode device will have apBand =
     * ANY converted to 5GHZ.
     */
    @Test
    public void convertSingleModeDeviceAnyTo5Ghz() throws Exception {
        /* Initialize WifiApConfigStore with default configuration. */
        WifiApConfigStore store = createWifiApConfigStore();
        verifyDefaultApConfig(store.getApConfiguration(), TEST_DEFAULT_AP_SSID);
        verify(mWifiConfigManager).saveToStore(true);

        /* Update with a valid configuration. */
        SoftApConfiguration providedConfig = setupApConfig(
                "ConfiguredAP",                /* SSID */
                "randomKey",                   /* preshared key */
                SECURITY_TYPE_WPA2_PSK,        /* security type */
                SoftApConfiguration.BAND_ANY,  /* AP band (ANY) */
                0,                             /* AP channel */
                false                          /* Hidden SSID */);

        SoftApConfiguration expectedConfig = setupApConfig(
                "ConfiguredAP",                       /* SSID */
                "randomKey",                          /* preshared key */
                SECURITY_TYPE_WPA2_PSK,               /* security type */
                SoftApConfiguration.BAND_5GHZ,        /* AP band (5GHz) */
                0,                                    /* AP channel */
                false                                 /* Hidden SSID */);
        store.setApConfiguration(providedConfig);
        verifyApConfig(expectedConfig, store.getApConfiguration());
        verifyApConfig(expectedConfig, mDataStoreSource.toSerialize());
        verify(mWifiConfigManager, times(2)).saveToStore(true);
        verify(mBackupManagerProxy, times(2)).notifyDataChanged();
    }

    /**
     * Due to different device hw capabilities, some bands are not available if a device is
     * dual/single mode capable.  This test verifies that a single mode device will have apBand
     * is a multiband not including 5GHz converted into 2GHz.
     */
    @Test
    public void convertSingleModeDeviceMultibandTo2Ghz() throws Exception {
        /* Initialize WifiApConfigStore with default configuration. */
        WifiApConfigStore store = createWifiApConfigStore();
        verifyDefaultApConfig(store.getApConfiguration(), TEST_DEFAULT_AP_SSID);
        verify(mWifiConfigManager).saveToStore(true);

        /* Update with a valid configuration. */
        SoftApConfiguration providedConfig = setupApConfig(
                "ConfiguredAP",                /* SSID */
                "randomKey",                   /* preshared key */
                SECURITY_TYPE_WPA2_PSK,        /* security type */
                SoftApConfiguration.BAND_2GHZ | SoftApConfiguration.BAND_6GHZ,  /* AP band */
                0,                             /* AP channel */
                false                          /* Hidden SSID */);

        SoftApConfiguration expectedConfig = setupApConfig(
                "ConfiguredAP",                       /* SSID */
                "randomKey",                          /* preshared key */
                SECURITY_TYPE_WPA2_PSK,               /* security type */
                SoftApConfiguration.BAND_2GHZ,        /* AP band (5GHz) */
                0,                                    /* AP channel */
                false                                 /* Hidden SSID */);
        store.setApConfiguration(providedConfig);
        verifyApConfig(expectedConfig, store.getApConfiguration());
        verifyApConfig(expectedConfig, mDataStoreSource.toSerialize());
        verify(mWifiConfigManager, times(2)).saveToStore(true);
        verify(mBackupManagerProxy, times(2)).notifyDataChanged();
    }

    /**
     * Due to different device hw capabilities, some bands are not available if a device is
     * dual/single mode capable.  This test verifies that a single mode device does not convert
     * apBand to ANY.
     */
    @Test
    public void singleModeDevice5GhzNotConverted() throws Exception {
        /* Initialize WifiApConfigStore with default configuration. */
        WifiApConfigStore store = createWifiApConfigStore();
        verifyDefaultApConfig(store.getApConfiguration(), TEST_DEFAULT_AP_SSID);
        verify(mWifiConfigManager).saveToStore(true);

        /* Update with a valid configuration. */
        SoftApConfiguration expectedConfig = setupApConfig(
                "ConfiguredAP",                 /* SSID */
                "randomKey",                    /* preshared key */
                SECURITY_TYPE_WPA2_PSK,         /* security type */
                SoftApConfiguration.BAND_5GHZ,  /* AP band */
                40,                             /* AP channel */
                false                           /* Hidden SSID */);
        store.setApConfiguration(expectedConfig);
        verifyApConfig(expectedConfig, store.getApConfiguration());
        verifyApConfig(expectedConfig, mDataStoreSource.toSerialize());
        verify(mWifiConfigManager, times(2)).saveToStore(true);
        verify(mBackupManagerProxy, times(2)).notifyDataChanged();
    }

    /**
     * Due to different device hw capabilities, some bands are not available if a device is
     * dual/single mode capable.  This test verifies that a dual mode device will have apBand =
     * 5GHz converted to include 2.4GHz.
     */
    @Test
    public void convertDualModeDevice5GhzToAny() throws Exception {
        mResources.setBoolean(R.bool.config_wifi_convert_apband_5ghz_to_any, true);

        /* Initialize WifiApConfigStore with default configuration. */
        WifiApConfigStore store = createWifiApConfigStore();
        verifyDefaultApConfig(store.getApConfiguration(), TEST_DEFAULT_AP_SSID);
        verify(mWifiConfigManager).saveToStore(true);

        /* Update with a valid configuration. */
        SoftApConfiguration providedConfig = setupApConfig(
                "ConfiguredAP",                 /* SSID */
                "randomKey",                    /* preshared key */
                SECURITY_TYPE_WPA2_PSK,         /* security type */
                SoftApConfiguration.BAND_5GHZ,  /* AP band */
                40,                             /* AP channel */
                false                           /* Hidden SSID */);

        SoftApConfiguration expectedConfig = setupApConfig(
                "ConfiguredAP",                       /* SSID */
                "randomKey",                          /* preshared key */
                SECURITY_TYPE_WPA2_PSK,               /* security type */
                SoftApConfiguration.BAND_2GHZ
                | SoftApConfiguration.BAND_5GHZ,      /* AP band */
                0,                                    /* AP channel */
                false                                 /* Hidden SSID */);
        store.setApConfiguration(providedConfig);
        verifyApConfig(expectedConfig, store.getApConfiguration());
        verifyApConfig(expectedConfig, mDataStoreSource.toSerialize());
        verify(mWifiConfigManager, times(2)).saveToStore(true);
        verify(mBackupManagerProxy, times(2)).notifyDataChanged();
    }

    /**
     * Due to different device hw capabilities, some bands are not available if a device is
     * dual/single mode capable.  This test verifies that a dual mode device does not convert
     * apBand to 5Ghz.
     */
    @Test
    public void dualModeDeviceAnyNotConverted() throws Exception {
        mResources.setBoolean(R.bool.config_wifi_convert_apband_5ghz_to_any, true);

        /* Initialize WifiApConfigStore with default configuration. */
        WifiApConfigStore store = createWifiApConfigStore();
        verifyDefaultApConfig(store.getApConfiguration(), TEST_DEFAULT_AP_SSID);
        verify(mWifiConfigManager).saveToStore(true);

        /* Update with a valid configuration. */
        SoftApConfiguration expectedConfig = setupApConfig(
                "ConfiguredAP",                 /* SSID */
                "randomKey",                    /* preshared key */
                SECURITY_TYPE_WPA2_PSK,         /* security type */
                SoftApConfiguration.BAND_ANY,   /* AP band */
                0,                              /* AP channel */
                false                           /* Hidden SSID */);
        store.setApConfiguration(expectedConfig);
        verifyApConfig(expectedConfig, store.getApConfiguration());
        verifyApConfig(expectedConfig, mDataStoreSource.toSerialize());
        verify(mWifiConfigManager, times(2)).saveToStore(true);
        verify(mBackupManagerProxy, times(2)).notifyDataChanged();
    }

    /**
     * Due to different device hw capabilities, some bands are not available if a device is
     * dual/single mode capable.  This test verifies that a single mode device converts a persisted
     * ap config with ANY set for the apBand to 5GHz.
     */
    @Test
    public void singleModeDeviceAnyConvertedTo5GhzAtRetrieval() throws Exception {

        SoftApConfiguration persistedConfig = setupApConfig(
                "ConfiguredAP",                 /* SSID */
                "randomKey",                    /* preshared key */
                SECURITY_TYPE_WPA2_PSK,         /* security type */
                SoftApConfiguration.BAND_ANY,   /* AP band */
                0,                              /* AP channel */
                false                           /* Hidden SSID */);
        SoftApConfiguration expectedConfig = setupApConfig(
                "ConfiguredAP",                        /* SSID */
                "randomKey",                           /* preshared key */
                SECURITY_TYPE_WPA2_PSK,                /* security type */
                SoftApConfiguration.BAND_5GHZ,         /* AP band */
                0,                                     /* AP channel */
                false                                  /* Hidden SSID */);
        WifiApConfigStore store = createWifiApConfigStore();
        mDataStoreSource.fromDeserialized(persistedConfig);
        verifyApConfig(expectedConfig, store.getApConfiguration());
        verifyApConfig(expectedConfig, mDataStoreSource.toSerialize());
        verify(mWifiConfigManager).saveToStore(true);
        verify(mBackupManagerProxy).notifyDataChanged();
    }

    /**
     * Due to different device hw capabilities, some bands are not available if a device is
     * dual/single mode capable.  This test verifies that a single mode device does not convert
     * a persisted ap config with 5GHz set for the apBand.
     */
    @Test
    public void singleModeDeviceNotConvertedAtRetrieval() throws Exception {
        SoftApConfiguration persistedConfig = setupApConfig(
                "ConfiguredAP",                  /* SSID */
                "randomKey",                     /* preshared key */
                SECURITY_TYPE_WPA2_PSK,          /* security type */
                SoftApConfiguration.BAND_5GHZ,   /* AP band */
                40,                              /* AP channel */
                false                            /* Hidden SSID */);

        WifiApConfigStore store = createWifiApConfigStore();
        mDataStoreSource.fromDeserialized(persistedConfig);
        verifyApConfig(persistedConfig, store.getApConfiguration());
        verify(mWifiConfigManager, never()).saveToStore(true);
        verify(mBackupManagerProxy, never()).notifyDataChanged();
    }

    /**
     * Due to different device hw capabilities, some bands are not available if a device is
     * dual/single mode capable.  This test verifies that a dual mode device converts a persisted ap
     * config with 5GHz only set for the apBand to include 2.4GHz.
     */
    @Test
    public void dualModeDevice5GhzConvertedToAnyAtRetrieval() throws Exception {
        mResources.setBoolean(R.bool.config_wifi_convert_apband_5ghz_to_any, true);

        SoftApConfiguration persistedConfig = setupApConfig(
                "ConfiguredAP",                  /* SSID */
                "randomKey",                     /* preshared key */
                SECURITY_TYPE_WPA2_PSK,          /* security type */
                SoftApConfiguration.BAND_5GHZ,   /* AP band */
                40,                              /* AP channel */
                false                            /* Hidden SSID */);
        SoftApConfiguration expectedConfig = setupApConfig(
                "ConfiguredAP",                       /* SSID */
                "randomKey",                          /* preshared key */
                SECURITY_TYPE_WPA2_PSK,               /* security type */
                SoftApConfiguration.BAND_5GHZ
                | SoftApConfiguration.BAND_2GHZ,      /* AP band */
                0,                                    /* AP channel */
                false                                 /* Hidden SSID */);

        WifiApConfigStore store = createWifiApConfigStore();
        mDataStoreSource.fromDeserialized(persistedConfig);
        verifyApConfig(expectedConfig, store.getApConfiguration());
        verifyApConfig(expectedConfig, mDataStoreSource.toSerialize());
        verify(mWifiConfigManager).saveToStore(true);
        verify(mBackupManagerProxy).notifyDataChanged();
    }

    /**
     * Due to different device hw capabilities, some bands are not available if a device is
     * dual/single mode capable.  This test verifies that a dual mode device does not convert
     * a persisted ap config with ANY set for the apBand.
     */
    @Test
    public void dualModeDeviceNotConvertedAtRetrieval() throws Exception {
        mResources.setBoolean(R.bool.config_wifi_convert_apband_5ghz_to_any, true);

        SoftApConfiguration persistedConfig = setupApConfig(
                "ConfiguredAP",                 /* SSID */
                "randomKey",                    /* preshared key */
                SECURITY_TYPE_WPA2_PSK,         /* security type */
                SoftApConfiguration.BAND_ANY,   /* AP band */
                0,                              /* AP channel */
                false                           /* Hidden SSID */);

        WifiApConfigStore store = createWifiApConfigStore();
        mDataStoreSource.fromDeserialized(persistedConfig);
        verifyApConfig(persistedConfig, store.getApConfiguration());
        verify(mWifiConfigManager, never()).saveToStore(true);
        verify(mBackupManagerProxy, never()).notifyDataChanged();
    }

    /**
     * Verify a proper SoftApConfiguration is generate by getDefaultApConfiguration().
     */
    @Test
    public void getDefaultApConfigurationIsValid() {
        WifiApConfigStore store = createWifiApConfigStore();
        SoftApConfiguration config = store.getApConfiguration();
        assertTrue(WifiApConfigStore.validateApWifiConfiguration(config));
    }

    /**
     * Verify a proper local only hotspot config is generated when called properly with the valid
     * context.
     */
    @Test
    public void generateLocalOnlyHotspotConfigIsValid() {
        SoftApConfiguration config = WifiApConfigStore
                .generateLocalOnlyHotspotConfig(mContext, SoftApConfiguration.BAND_2GHZ, null);
        verifyDefaultLocalOnlyApConfig(config, TEST_DEFAULT_HOTSPOT_SSID,
                SoftApConfiguration.BAND_2GHZ);

        // verify that the config passes the validateApWifiConfiguration check
        assertTrue(WifiApConfigStore.validateApWifiConfiguration(config));
    }

    /**
     * Verify a proper local only hotspot config is generated for 5Ghz band.
     */
    @Test
    public void generateLocalOnlyHotspotConfigIsValid5G() {
        SoftApConfiguration config = WifiApConfigStore
                .generateLocalOnlyHotspotConfig(mContext, SoftApConfiguration.BAND_5GHZ, null);
        verifyDefaultLocalOnlyApConfig(config, TEST_DEFAULT_HOTSPOT_SSID,
                SoftApConfiguration.BAND_5GHZ);

        // verify that the config passes the validateApWifiConfiguration check
        assertTrue(WifiApConfigStore.validateApWifiConfiguration(config));
    }

    @Test
    public void generateLohsConfig_forwardsCustomMac() {
        SoftApConfiguration customConfig = new SoftApConfiguration.Builder()
                .setBssid(MacAddress.fromString("11:22:33:44:55:66"))
                .build();
        SoftApConfiguration softApConfig = WifiApConfigStore.generateLocalOnlyHotspotConfig(
                mContext, SoftApConfiguration.BAND_2GHZ, customConfig);
        assertThat(softApConfig.getBssid().toString()).isNotEmpty();
        assertThat(softApConfig.getBssid()).isEqualTo(
                MacAddress.fromString("11:22:33:44:55:66"));
    }

    @Test
    public void randomizeBssid_randomizesWhenEnabled() {
        mResources.setBoolean(R.bool.config_wifi_ap_mac_randomization_supported, true);
        SoftApConfiguration baseConfig = new SoftApConfiguration.Builder().build();

        WifiApConfigStore store = createWifiApConfigStore();
        SoftApConfiguration config = store.randomizeBssidIfUnset(mContext, baseConfig);

        assertEquals(TEST_RANDOMIZED_MAC, config.getBssid());
    }

    @Test
    public void randomizeBssid_usesFactoryMacWhenRandomizationOff() {
        mResources.setBoolean(R.bool.config_wifi_ap_mac_randomization_supported, false);
        SoftApConfiguration baseConfig = new SoftApConfiguration.Builder().build();

        WifiApConfigStore store = createWifiApConfigStore();
        SoftApConfiguration config = store.randomizeBssidIfUnset(mContext, baseConfig);

        assertThat(config.getBssid()).isNull();
    }

    @Test
    public void randomizeBssid_forwardsCustomMac() {
        mResources.setBoolean(R.bool.config_wifi_ap_mac_randomization_supported, true);
        Builder baseConfigBuilder = new SoftApConfiguration.Builder();
        baseConfigBuilder.setBssid(MacAddress.fromString("11:22:33:44:55:66"));

        WifiApConfigStore store = createWifiApConfigStore();
        SoftApConfiguration config = store.randomizeBssidIfUnset(mContext,
                baseConfigBuilder.build());

        assertThat(config.getBssid().toString()).isNotEmpty();
        assertThat(config.getBssid()).isEqualTo(
                MacAddress.fromString("11:22:33:44:55:66"));
    }

    /**
     * Helper method to generate random SSIDs.
     *
     * Note: this method has limited use as a random SSID generator.  The characters used in this
     * method do no not cover all valid inputs.
     * @param length number of characters to generate for the name
     * @return String generated string of random characters
     */
    private String generateRandomString(int length) {

        StringBuilder stringBuilder = new StringBuilder(length);
        int index = -1;
        while (stringBuilder.length() < length) {
            index = mRandom.nextInt(TEST_CHAR_SET_AS_STRING.length());
            stringBuilder.append(TEST_CHAR_SET_AS_STRING.charAt(index));
        }
        return stringBuilder.toString();
    }

    /**
     * Verify the SSID checks in validateApWifiConfiguration.
     *
     * Cases to check and verify they trigger failed verification:
     * null SoftApConfiguration.SSID
     * empty SoftApConfiguration.SSID
     * invalid WifiConfiguaration.SSID length
     *
     * Additionally check a valid SSID with a random (within valid ranges) length.
     */
    @Test
    public void testSsidVerificationInValidateApWifiConfigurationCheck() {
        Builder configBuilder = new SoftApConfiguration.Builder();
        configBuilder.setSsid(null);
        assertFalse(WifiApConfigStore.validateApWifiConfiguration(configBuilder.build()));
        // check a string if it's larger than 32 bytes with UTF-8 encode
        // Case 1 : one byte per character (use english words and Arabic numerals)
        configBuilder.setSsid(generateRandomString(WifiApConfigStore.SSID_MAX_LEN + 1));
        assertFalse(WifiApConfigStore.validateApWifiConfiguration(configBuilder.build()));
        // Case 2 : two bytes per character
        configBuilder.setSsid(TEST_STRING_UTF8_WITH_34_BYTES);
        assertFalse(WifiApConfigStore.validateApWifiConfiguration(configBuilder.build()));
        // Case 3 : three bytes per character
        configBuilder.setSsid(TEST_STRING_UTF8_WITH_33_BYTES);
        assertFalse(WifiApConfigStore.validateApWifiConfiguration(configBuilder.build()));

        // now check a valid SSID within 32 bytes
        // Case 1 :  one byte per character with random length
        int validLength = WifiApConfigStore.SSID_MAX_LEN - WifiApConfigStore.SSID_MIN_LEN;
        configBuilder.setSsid(generateRandomString(
                mRandom.nextInt(validLength) + WifiApConfigStore.SSID_MIN_LEN));
        assertTrue(WifiApConfigStore.validateApWifiConfiguration(configBuilder.build()));
        // Case 2 : two bytes per character
        configBuilder.setSsid(TEST_STRING_UTF8_WITH_32_BYTES);
        assertTrue(WifiApConfigStore.validateApWifiConfiguration(configBuilder.build()));
        // Case 3 : three bytes per character
        configBuilder.setSsid(TEST_STRING_UTF8_WITH_30_BYTES);
        assertTrue(WifiApConfigStore.validateApWifiConfiguration(configBuilder.build()));
    }

    /**
     * Verify the Open network checks in validateApWifiConfiguration.
     *
     * If the configured network is open, it should not have a password set.
     *
     * Additionally verify a valid open network passes verification.
     */
    @Test
    public void testOpenNetworkConfigInValidateApWifiConfigurationCheck() {
        assertTrue(WifiApConfigStore.validateApWifiConfiguration(
                new SoftApConfiguration.Builder()
                .setSsid(TEST_DEFAULT_HOTSPOT_SSID)
                .build()));
    }

    /**
     * Verify the WPA2_PSK network checks in validateApWifiConfiguration.
     *
     * If the configured network is configured with a preSharedKey, verify that the passwork is set
     * and it meets length requirements.
     */
    @Test
    public void testWpa2PskNetworkConfigInValidateApWifiConfigurationCheck() {
        Builder configBuilder = new SoftApConfiguration.Builder();
        configBuilder.setSsid(TEST_DEFAULT_HOTSPOT_SSID);
        // Builder will auto check auth type and passphrase

        // test random (valid length)
        int maxLen = WifiApConfigStore.PSK_MAX_LEN;
        int minLen = WifiApConfigStore.PSK_MIN_LEN;
        configBuilder.setPassphrase(
                generateRandomString(mRandom.nextInt(maxLen - minLen) + minLen),
                SoftApConfiguration.SECURITY_TYPE_WPA2_PSK);
        assertTrue(WifiApConfigStore.validateApWifiConfiguration(configBuilder.build()));
    }

    /**
     * Verify the default configuration security when SAE support.
     */
    @Test
    public void testDefaultConfigurationSecurityTypeIsWpa3SaeTransitionWhenSupport() {
        mResources.setBoolean(R.bool.config_wifi_softap_sae_supported, true);
        WifiApConfigStore store = createWifiApConfigStore();
        verifyDefaultApConfig(store.getApConfiguration(), TEST_DEFAULT_AP_SSID, true);
    }

    /**
     * Verify the LOHS default configuration security when SAE support.
     */
    @Test
    public void testLohsDefaultConfigurationSecurityTypeIsWpa3SaeTransitionWhenSupport() {
        mResources.setBoolean(R.bool.config_wifi_softap_sae_supported, true);
        SoftApConfiguration config = WifiApConfigStore
                .generateLocalOnlyHotspotConfig(mContext, SoftApConfiguration.BAND_5GHZ, null);
        verifyDefaultLocalOnlyApConfig(config, TEST_DEFAULT_HOTSPOT_SSID,
                SoftApConfiguration.BAND_5GHZ, true);

        // verify that the config passes the validateApWifiConfiguration check
        assertTrue(WifiApConfigStore.validateApWifiConfiguration(config));

    }

    @Test
    public void testResetToDefaultForUnsupportedConfig() {
        mResources.setBoolean(R.bool.config_wifiSofapClientForceDisconnectSupported, false);
        mResources.setBoolean(R.bool.config_wifi_softap_sae_supported, false);
        SoftApConfiguration sae_config = new SoftApConfiguration.Builder()
                .setPassphrase("secretsecret", SoftApConfiguration.SECURITY_TYPE_WPA3_SAE)
                .setMaxNumberOfClients(10)
                .enableClientControlByUser(true)
                .build();
        WifiApConfigStore store = createWifiApConfigStore();

        SoftApConfiguration resetedConfig = store.resetToDefaultForUnsupportedConfig(sae_config);
        assertEquals(resetedConfig.getMaxNumberOfClients(), 0);
        assertFalse(resetedConfig.isClientControlByUserEnabled());
    }
}
