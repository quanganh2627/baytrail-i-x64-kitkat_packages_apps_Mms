/*
 * Copyright (C) 2008 Esmertec AG.
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.mms;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.drm.DrmManagerClient;
import android.location.Country;
import android.location.CountryDetector;
import android.location.CountryListener;
import android.net.ConnectivityManager;
import android.os.StrictMode;
import android.preference.PreferenceManager;
import android.provider.SearchRecentSuggestions;
import android.telephony.SmsManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.mms.data.Contact;
import com.android.mms.data.Conversation;
import com.android.mms.layout.LayoutManager;
import com.android.mms.transaction.MessagingNotification;
import com.android.mms.transaction.MmsSystemEventReceiver;
import com.android.mms.transaction.SmsReceiver;
import com.android.mms.transaction.SmsReceiverService;
import com.android.mms.util.DownloadManager;
import com.android.mms.util.DraftCache;
import com.android.mms.util.PduLoaderManager;
import com.android.mms.util.RateController;
import com.android.mms.util.ThumbnailManager;

import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyIntents2;

public class MmsApp extends Application {
    public static final String LOG_TAG = "Mms";

    private SearchRecentSuggestions mRecentSuggestions;
    private TelephonyManager mTelephonyManager;
    private TelephonyManager mTelephonyManager2;
    private CountryDetector mCountryDetector;
    private CountryListener mCountryListener;
    private String mCountryIso;
    private static MmsApp sMmsApp = null;
    private PduLoaderManager mPduLoaderManager;
    private ThumbnailManager mThumbnailManager;
    private DrmManagerClient mDrmManagerClient;

    private boolean mDynamicDataSimSupported = false;

    private String mIMSI_SIM1 = null;
    private String mIMSI_SIM2 = null;

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            final String state = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
            if (IccCardConstants.INTENT_VALUE_ICC_ABSENT.equals(state)) {
                mIMSI_SIM1 = null;
                mIMSI_SIM2 = null;
            }
            MmsApp.getApplication().initIMSI();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        if (Log.isLoggable(LogTag.STRICT_MODE_TAG, Log.DEBUG)) {
            // Log tag for enabling/disabling StrictMode violation log. This will dump a stack
            // in the log that shows the StrictMode violator.
            // To enable: adb shell setprop log.tag.Mms:strictmode DEBUG
            StrictMode.setThreadPolicy(
                    new StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build());
        }

        sMmsApp = this;

        // Load the default preference values
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

        // Figure out the country *before* loading contacts and formatting numbers
        mCountryDetector = (CountryDetector) getSystemService(Context.COUNTRY_DETECTOR);
        mCountryListener = new CountryListener() {
            @Override
            public synchronized void onCountryDetected(Country country) {
                mCountryIso = country.getCountryIso();
            }
        };
        mCountryDetector.addCountryListener(mCountryListener, getMainLooper());

        mDynamicDataSimSupported = getTelephonyManager().isDynamicDataSimSupported();

        Context context = getApplicationContext();
        mPduLoaderManager = new PduLoaderManager(context);
        mThumbnailManager = new ThumbnailManager(context);

        MmsConfig.init(this);
        Contact.init(this);
        DraftCache.init(this);
        Conversation.init(this);
        DownloadManager.init(this);
        RateController.init(this);
        LayoutManager.init(this);
        MessagingNotification.init(this);

        activePendingMessages();
        registerListeners();
    }

    /**
     * Try to process all pending messages(which were interrupted by user, OOM, Mms crashing,
     * etc...) when Mms app is (re)launched.
     */
    private void activePendingMessages() {
        // For Mms: try to process all pending transactions if possible
        MmsSystemEventReceiver.wakeUpService(this);

        // For Sms: retry to send smses in outbox and queued box
        sendBroadcast(new Intent(SmsReceiverService.ACTION_SEND_INACTIVE_MESSAGE,
                null,
                this,
                SmsReceiver.class));
    }

    synchronized public static MmsApp getApplication() {
        return sMmsApp;
    }

    @Override
    public void onTerminate() {
        mCountryDetector.removeCountryListener(mCountryListener);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();

        mPduLoaderManager.onLowMemory();
        mThumbnailManager.onLowMemory();
    }

    public PduLoaderManager getPduLoaderManager() {
        return mPduLoaderManager;
    }

    public ThumbnailManager getThumbnailManager() {
        return mThumbnailManager;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        LayoutManager.getInstance().onConfigurationChanged(newConfig);
    }

    public static SmsManager getSmsManager2() {
        return SmsManager.get2ndSmsManager();
    }

    /**
     * @return Returns the TelephonyManager.
     */
    public TelephonyManager getTelephonyManager() {
        if (mTelephonyManager == null) {
            mTelephonyManager = (TelephonyManager)getApplicationContext()
                    .getSystemService(Context.TELEPHONY_SERVICE);
        }
        return mTelephonyManager;
    }

    /**
     * @return Returns the TelephonyManager.
     */
    public TelephonyManager getTelephonyManager2() {
        if (mTelephonyManager2 == null) {
            mTelephonyManager2 = TelephonyManager.get2ndTm();
        }
        return mTelephonyManager2;
    }

    public static boolean isOnDataSim(ConnectivityManager mgr, String imsi) {
        if (MmsConfig.isDualSimSupported() && !TextUtils.isEmpty(imsi)) {
            String subscriberId = null;
            int id = mgr.getDataSim();
            if (id == 1) {
                subscriberId = getApplication().getTelephonyManager2().getSubscriberId();
            } else {
                subscriberId = getApplication().getTelephonyManager().getSubscriberId();
            }
            return TextUtils.equals(imsi, subscriberId);
        }
        return true;
    }

    public static boolean isDataSimReady(ConnectivityManager mgr) {
        if (MmsConfig.isDualSimSupported()) {
            int id = mgr.getDataSim();
            if (id == 1) {
                return isSecondarySimReady();
            } else {
                return isPrimarySimReady();
            }
        }
        return isPrimarySimReady();
    }

    public static boolean isNonDataSimReady(ConnectivityManager mgr) {
        if (MmsConfig.isDualSimSupported()) {
            int id = mgr.getDataSim();
            if (id == 1) {
                return isPrimarySimReady();
            } else {
                return isSecondarySimReady();
            }
        }
        return false;
    }

    /**
     * @param imsi The SIM Card's IMSI info
     * @return Return true if this SIM Card is primary SIM, always true for non-dsds phone
     */
    public static boolean isPrimaryIMSI(String imsi) {
        if (MmsConfig.isDualSimSupported() && !TextUtils.isEmpty(imsi)) {
            String pri = getApplication().getTelephonyManager().getSubscriberId();
            return TextUtils.equals(imsi, pri);
        }
        return true;
    }

    /**
     * @param simId index of sim slot
     * @return Return true if the SIM card of simId is secondary SIM
     */
    public static boolean isPrimaryId(int simId) {
        if (MmsConfig.isDualSimSupported()) {
            return (simId == TelephonyManager.getPrimarySim());
        }
        return true;
    }

    public static boolean isPrimarySimReady() {
        TelephonyManager phone = getApplication().getTelephonyManager();
        if (phone != null) {
            return phone.getSimState() == TelephonyManager.SIM_STATE_READY;
        }
        return false;
    }

    public static boolean isPrimarySimPinLocked() {
       TelephonyManager phone = getApplication().getTelephonyManager();
       if (phone != null) {
           return phone.getSimState() == TelephonyManager.SIM_STATE_PIN_REQUIRED;
       }
       return false;
    }

    /**
     * @param imsi The SIM Card's IMSI info
     * @return Return true if this SIM Card is secondary SIM
     */
    public static boolean isSecondaryIMSI(String imsi) {
        if (MmsConfig.isDualSimSupported() && !TextUtils.isEmpty(imsi)) {
            String pri = getApplication().getTelephonyManager2().getSubscriberId();
            return TextUtils.equals(imsi, pri);
        }
        return false;
    }

    /**
     * @param simId index of sim slot
     * @return Return true if the SIM card of simId is secondary SIM
     */
    public static boolean isSecondaryId(int simId) {
        if (MmsConfig.isDualSimSupported()) {
            if (simId != TelephonyManager.getPrimarySim()
                    && (simId == MmsConfig.DSDS_SLOT_1_ID || simId == MmsConfig.DSDS_SLOT_2_ID)) {
                return true;
            }
        }
        return false;
    }

    public static String getSecondaryIMSI() {
        if (MmsConfig.isDualSimSupported()) {
            TelephonyManager phone = TelephonyManager.get2ndTm();
            if (phone != null) {
                return phone.getSubscriberId();
            }
        }
        return null;
    }

    public static boolean isSecondarySimReady() {
        if (MmsConfig.isDualSimSupported()) {
            TelephonyManager phone = TelephonyManager.get2ndTm();
            if (phone != null) {
                return phone.getSimState() == TelephonyManager.SIM_STATE_READY;
            }
        }
        return false;
    }

    public static boolean isSecondarySimPinLocked() {
       if (MmsConfig.isDualSimSupported()) {
           TelephonyManager phone = TelephonyManager.get2ndTm();
           if (phone != null) {
               return phone.getSimState() == TelephonyManager.SIM_STATE_PIN_REQUIRED;
           }
       }
       return false;
    }

    private static boolean isSmsSending = false;
    private static boolean isSmsSending2 = false;

    public static void setSmsSendingState(boolean isPrimary, boolean isSending) {
        if (isPrimary) {
            isSmsSending = isSending;
        } else {
            isSmsSending2 = isSending;
        }
    }

    public static boolean isSmsSending(boolean isPrimary) {
        return isPrimary ? isSmsSending : isSmsSending2;
    }

    /**
     * Find SIM index of the specified IMSI.
     *
     * @param imsi SIM card IMSI string
     * @return Return corresponding SIM index, return DSDS_INVALID_SLOT_ID if no match SIM found.
     */
    public static int getSimIdByIMSI(final String imsi) {
        if (MmsConfig.isDualSimSupported()) {
            if (TextUtils.isEmpty(imsi)) {
                return MmsConfig.DSDS_INVALID_SLOT_ID;
            }

            MmsApp app = getApplication();

            // ensure imsi is ready
            app.initIMSI();

            if (TextUtils.equals(imsi,app.mIMSI_SIM1)) {
                return MmsConfig.DSDS_SLOT_1_ID;
            } else if (TextUtils.equals(imsi,app.mIMSI_SIM2)) {
                return MmsConfig.DSDS_SLOT_2_ID;
            } else {
                return MmsConfig.DSDS_INVALID_SLOT_ID;
            }
        }
        return MmsConfig.DSDS_INVALID_SLOT_ID;
    }

    /**
     * Find IMSI of the specified SIM
     *
     * @param index SIM card index
     * @return Return corresponding IMSI, null if correspond SIM absent
     */
    public static String getIMSIBySimId(final int index) {
        if (MmsConfig.isDualSimSupported()) {
            MmsApp app = getApplication();

            // ensure imsi is ready
            app.initIMSI();

            if (index == MmsConfig.DSDS_SLOT_1_ID) {
                return app.mIMSI_SIM1;
            } else if (index == MmsConfig.DSDS_SLOT_2_ID) {
                return app.mIMSI_SIM2;
            }
        }
        return null;
    }

    /**
     * read imsi from sim card
     */
    private void initIMSI() {
        if (TextUtils.isEmpty(mIMSI_SIM1) || TextUtils.isEmpty(mIMSI_SIM2)) {
            // get SIM 1 and SIM 2's IMSI base on primary SIM in use
            if (isSecondaryId(MmsConfig.DSDS_SLOT_1_ID)) {
                mIMSI_SIM2 = getTelephonyManager().getSubscriberId();
                mIMSI_SIM1 = getTelephonyManager2().getSubscriberId();
            } else {
                mIMSI_SIM1 = getTelephonyManager().getSubscriberId();
                mIMSI_SIM2 = getTelephonyManager2().getSubscriberId();
            }
        }
    }

    /**
     * Returns the content provider wrapper that allows access to recent searches.
     * @return Returns the content provider wrapper that allows access to recent searches.
     */
    public SearchRecentSuggestions getRecentSuggestions() {
        /*
        if (mRecentSuggestions == null) {
            mRecentSuggestions = new SearchRecentSuggestions(this,
                    SuggestionsProvider.AUTHORITY, SuggestionsProvider.MODE);
        }
        */
        return mRecentSuggestions;
    }

    // This function CAN return null.
    public String getCurrentCountryIso() {
        if (mCountryIso == null) {
            Country country = mCountryDetector.detectCountry();
            if (country != null) {
                mCountryIso = country.getCountryIso();
            }
        }
        return mCountryIso;
    }

    public DrmManagerClient getDrmManagerClient() {
        if (mDrmManagerClient == null) {
            mDrmManagerClient = new DrmManagerClient(getApplicationContext());
        }
        return mDrmManagerClient;
    }

    public boolean isDynamicDataSimSupported() {
        return mDynamicDataSimSupported;
    }

    private void registerListeners() {
        final IntentFilter intentFilter =
                 new IntentFilter(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        if (MmsConfig.isDualSimSupported()) {
            intentFilter.addAction(TelephonyIntents2.ACTION_SIM_STATE_CHANGED);
        }

        registerReceiver(mReceiver, intentFilter);
    }
}
