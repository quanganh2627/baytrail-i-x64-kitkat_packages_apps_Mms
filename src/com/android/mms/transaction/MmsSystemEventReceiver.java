/*
 * Copyright (C) 2007-2008 Esmertec AG.
 * Copyright (C) 2007-2008 The Android Open Source Project
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

package com.android.mms.transaction;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.provider.Telephony.Mms;
import android.util.Log;
import com.android.internal.telephony.TelephonyConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyIntents2;

import com.android.internal.telephony.PhoneConstants;

import com.android.mms.LogTag;
import com.android.mms.MmsApp;
import com.android.mms.MmsConfig;

/**
 * MmsSystemEventReceiver receives the
 * {@link android.content.intent.ACTION_BOOT_COMPLETED},
 * {@link com.android.internal.telephony.TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED}
 * and performs a series of operations which may include:
 * <ul>
 * <li>Show/hide the icon in notification area which is used to indicate
 * whether there is new incoming message.</li>
 * <li>Resend the MM's in the outbox.</li>
 * </ul>
 */
public class MmsSystemEventReceiver extends BroadcastReceiver {
    private static final String TAG = "MmsSystemEventReceiver";
    private static ConnectivityManager mConnMgr = null;

    public static final String TelephonyIntents2_ACTION_ANY_DATA_CONNECTION_STATE_CHANGED =
            "com.pekall.intent.ANY_DATA_STATE2";

    private static MmsSystemEventReceiver sMmsSystemEventReceiver;
    private static MmsSystemEventReceiver sMmsSystemEventReceiver2;
    public static void wakeUpService(Context context) {
        if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE)) {
            Log.v(TAG, "wakeUpService: start transaction service ...");
        }

        context.startService(new Intent(context, TransactionService.class));
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE)) {
            Log.v(TAG, "Intent received: " + intent);
        }

        String action = intent.getAction();
        if (action.equals(Mms.Intents.CONTENT_CHANGED_ACTION)) {
            Uri changed = (Uri) intent.getParcelableExtra(Mms.Intents.DELETED_CONTENTS);
            MmsApp.getApplication().getPduLoaderManager().removePdu(changed);
        } else if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
            if (mConnMgr == null) {
                mConnMgr = (ConnectivityManager) context
                        .getSystemService(Context.CONNECTIVITY_SERVICE);
            }
            if (!mConnMgr.getMobileDataEnabled()) {
                if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE)) {
                    Log.v(TAG, "mobile data turned off, bailing");
                }
                return;
            }
            NetworkInfo mmsNetworkInfo = mConnMgr
                    .getNetworkInfo(ConnectivityManager.TYPE_MOBILE_MMS);
            boolean available = mmsNetworkInfo.isAvailable();
            boolean isConnected = mmsNetworkInfo.isConnected();

            if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE)) {
                Log.v(TAG, "TYPE_MOBILE_MMS available = " + available +
                           ", isConnected = " + isConnected);
            }

            // Wake up transact service when MMS data is available and isn't connected.
            if (available && !isConnected) {
                wakeUpService(context);
            }
/*        } else if (action.equals(TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED)) {
            String state = intent.getStringExtra(PhoneConstants.STATE_KEY);

            if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE)) {
                Log.v(TAG, "ANY_DATA_STATE event received: " + state);
            }

            if (state.equals("CONNECTED")) {
                wakeUpService(context);
            }  */
        } else if (action.equals(TelephonyIntents2_ACTION_ANY_DATA_CONNECTION_STATE_CHANGED)) {
            // Secondary SIM is always DISCONNECTED before enable network on it
            // so cannot depends on the state as above
            boolean netavailable = ! intent.getBooleanExtra(PhoneConstants.NETWORK_UNAVAILABLE_KEY, false);
            String apn = intent.getStringExtra(PhoneConstants.DATA_APN_TYPE_KEY);

            if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE)) {
                Log.v(TAG, "ANY_DATA_STATE2 event received: " + netavailable + " for apn type " + apn);
            }

            if (netavailable && PhoneConstants.APN_TYPE_MMS.equals(apn)) {
                wakeUpService(context);
            }
        } else if (action.equals(Intent.ACTION_BOOT_COMPLETED)) {
            // We should check whether there are unread incoming
            // messages in the Inbox and then update the notification icon.
            // Called on the UI thread so don't block.
            MessagingNotification.nonBlockingUpdateNewMessageIndicator(
                    context, MessagingNotification.THREAD_NONE, false);

            // Scan and send pending Mms once after boot completed since
            // ACTION_ANY_DATA_CONNECTION_STATE_CHANGED wasn't registered in a whole life cycle
            wakeUpService(context);
        }
    }
	/*
    // kk_ignore (these methods are not from kk iteself)
    public static void registerForConnectionStateChanges(Context context) {
        unRegisterForConnectionStateChanges(context);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED);
        if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE)) {
            Log.v(TAG, "registerForConnectionStateChanges");
        }
        if (sMmsSystemEventReceiver == null) {
            sMmsSystemEventReceiver = new MmsSystemEventReceiver();
        }

        context.registerReceiver(sMmsSystemEventReceiver, intentFilter);
    }

    public static void unRegisterForConnectionStateChanges(Context context) {
        if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE)) {
            Log.v(TAG, "unRegisterForConnectionStateChanges");
        }
        if (sMmsSystemEventReceiver != null) {
            try {
                context.unregisterReceiver(sMmsSystemEventReceiver);
            } catch (IllegalArgumentException e) {
                // Allow un-matched register-unregister calls
            }
        }
    }
    */
    public static void registerForConnectionStateChanges2(Context context) {
        if (!MmsConfig.isDualSimSupported()) {
            return;
        }
        unRegisterForConnectionStateChanges2(context);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(TelephonyIntents2_ACTION_ANY_DATA_CONNECTION_STATE_CHANGED);
        if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE)) {
            Log.v(TAG, "registerForConnectionStateChanges2");
        }
        if (sMmsSystemEventReceiver2 == null) {
            sMmsSystemEventReceiver2 = new MmsSystemEventReceiver();
        }

        context.registerReceiver(sMmsSystemEventReceiver2, intentFilter);
    }

    public static void unRegisterForConnectionStateChanges2(Context context) {
        if (!MmsConfig.isDualSimSupported()) {
            return;
        }
        if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE)) {
            Log.v(TAG, "unRegisterForConnectionStateChanges2");
        }
        if (sMmsSystemEventReceiver2 != null) {
            try {
                context.unregisterReceiver(sMmsSystemEventReceiver2);
            } catch (IllegalArgumentException e) {
                // Allow un-matched register-unregister calls
                Log.v(TAG, "IllegalArgumentException: registerForConnectionStateChanges2");
            }
        }
    }
}
