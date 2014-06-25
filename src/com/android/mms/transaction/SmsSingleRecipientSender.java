/*
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

package com.android.mms.transaction;

import java.util.ArrayList;

import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Telephony.Mms;
import android.provider.Telephony.Sms;
import android.telephony.PhoneNumberUtils;
import android.telephony.SmsManager;
import android.util.Log;

import com.android.mms.LogTag;
import com.android.mms.MmsApp;
import com.android.mms.MmsConfig;
import com.android.mms.data.Conversation;
import com.android.mms.ui.MessageUtils;
import com.google.android.mms.MmsException;

public class SmsSingleRecipientSender extends SmsMessageSender {

    private final boolean mRequestDeliveryReport;
    private String mDest;
    private Uri mUri;
    private static final String TAG = "SmsSingleRecipientSender";

    public SmsSingleRecipientSender(Context context, String dest, String msgText, long threadId,
            boolean requestDeliveryReport, Uri uri) {
        super(context, null, msgText, threadId);
        mRequestDeliveryReport = requestDeliveryReport;
        mDest = dest;
        mUri = uri;
    }

    public boolean sendMessage(long token) throws MmsException {
        return sendMessage(null, token);
    }

    public boolean sendMessage(String imsi, long token) throws MmsException {
        if (LogTag.DEBUG_SEND) {
            Log.v(TAG, "sendMessage token: " + token);
        }
        if (mMessageText == null) {
            // Don't try to send an empty message, and destination should be just
            // one.
            throw new MmsException("Null message body or have multiple destinations.");
        }
        boolean isDualSimSupported = MmsConfig.isDualSimSupported();
        boolean usePrimarySim = !isDualSimSupported || !MmsApp.isSecondaryIMSI(imsi);
        SmsManager smsManager = usePrimarySim ? SmsManager.getDefault() : MmsApp.getSmsManager2();
        ArrayList<String> messages = null;
        if ((MmsConfig.getEmailGateway() != null) &&
                (Mms.isEmailAddress(mDest) || MessageUtils.isAlias(mDest))) {
            String msgText;
            msgText = mDest + " " + mMessageText;
            mDest = MmsConfig.getEmailGateway();
            messages = smsManager.divideMessage(msgText);
        } else {
            messages = smsManager.divideMessage(mMessageText);
            // remove spaces and dashes from destination number
            // (e.g. "801 555 1212" -> "8015551212")
            // (e.g. "+8211-123-4567" -> "+82111234567")
            mDest = PhoneNumberUtils.stripSeparators(mDest);
            mDest = Conversation.verifySingleRecipient(mContext, mThreadId, mDest);
        }
        int messageCount = messages.size();

        if (messageCount == 0) {
            // Don't try to send an empty message.
            throw new MmsException("SmsMessageSender.sendMessage: divideMessage returned " +
                    "empty messages. Original message is \"" + mMessageText + "\"");
        }

        boolean moved = Sms.moveMessageToFolder(mContext, mUri, Sms.MESSAGE_TYPE_OUTBOX, 0);
        if (!moved) {
            throw new MmsException("SmsMessageSender.sendMessage: couldn't move message " +
                    "to outbox: " + mUri);
        }
        if (LogTag.DEBUG_SEND) {
            Log.v(TAG, "sendMessage mDest: " + mDest + " mRequestDeliveryReport: " +
                    mRequestDeliveryReport);
        }

        ArrayList<PendingIntent> deliveryIntents =  new ArrayList<PendingIntent>(messageCount);
        ArrayList<PendingIntent> sentIntents = new ArrayList<PendingIntent>(messageCount);
        for (int i = 0; i < messageCount; i++) {
            if (mRequestDeliveryReport && (i == (messageCount - 1))) {
                // TODO: Fix: It should not be necessary to
                // specify the class in this intent.  Doing that
                // unnecessarily limits customizability.
                deliveryIntents.add(PendingIntent.getBroadcast(
                        mContext, 0,
                        new Intent(
                                MessageStatusReceiver.MESSAGE_STATUS_RECEIVED_ACTION,
                                mUri,
                                mContext,
                                MessageStatusReceiver.class),
                                0));
            } else {
                deliveryIntents.add(null);
            }
            Intent intent  = new Intent(SmsReceiverService.MESSAGE_SENT_ACTION,
                    mUri,
                    mContext,
                    SmsReceiver.class);

            int requestCode = 0;
            if (i == messageCount -1) {
                // Changing the requestCode so that a different pending intent
                // is created for the last fragment with
                // EXTRA_MESSAGE_SENT_SEND_NEXT set to true.
                requestCode = 1;
                intent.putExtra(SmsReceiverService.EXTRA_MESSAGE_SENT_SEND_NEXT, true);
            }
            if (isDualSimSupported) {
                intent.putExtra(SmsReceiverService.EXTRA_MESSAGE_SENT_IMSI, imsi);
            }
            if (LogTag.DEBUG_SEND) {
                Log.v(TAG, "sendMessage sendIntent: " + intent);
            }
            sentIntents.add(PendingIntent.getBroadcast(mContext, requestCode, intent, 0));
        }
        try {
            smsManager.sendMultipartTextMessage(mDest, mServiceCenter, messages, sentIntents, deliveryIntents);

            // use primary SIM when imsi is empty or absent, e.g. the old SIM Card is absent
            // update IMSI if specified IMSI absent
            if (isDualSimSupported && usePrimarySim && !MmsApp.isPrimaryIMSI(imsi)) {
                ContentValues values = new ContentValues(1);
                values.put(Sms_IMSI, MmsApp.getApplication().getTelephonyManager().getSubscriberId());
                mContext.getContentResolver().update(mUri, values, null, null);
            }
        } catch (Exception ex) {
            Log.e(TAG, "SmsMessageSender.sendMessage: caught", ex);
            throw new MmsException("SmsMessageSender.sendMessage: caught " + ex +
                    " from SmsManager.sendTextMessage()");
        }
        if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE) || LogTag.DEBUG_SEND) {
            log("sendMessage: address=" + mDest + ", threadId=" + mThreadId +
                    ", uri=" + mUri + ", msgs.count=" + messageCount);
        }
        return false;
    }

    private void log(String msg) {
        Log.d(LogTag.TAG, "[SmsSingleRecipientSender] " + msg);
    }
}
