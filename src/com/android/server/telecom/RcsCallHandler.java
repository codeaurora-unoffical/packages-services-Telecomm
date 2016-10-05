/*
 * Copyright (c) 2016, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of The Linux Foundation nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.server.telecom;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.telecom.CallAudioState;
import android.telecom.PhoneAccountHandle;
import android.telephony.PhoneNumberUtils;
import android.telecom.VideoProfile;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Iterator;
import java.util.List;

import org.codeaurora.rcscommon.CallComposerData;
import org.codeaurora.rcscommon.EnrichedCallState;
import org.codeaurora.rcscommon.EnrichedCallUpdateCallback;
import org.codeaurora.rcscommon.IncomingEnrichedCallCallback;
import org.codeaurora.rcscommon.RcsManager;

/**
 * RcsCallHandler class handles all RCS call and rcs call content. This
 * class will bind the Rcs call content to actual call.
 */
public class RcsCallHandler implements CallsManager.CallsManagerListener {

    private class RcsCallUpdateData {
        private String mPhoneNumber;
        private EnrichedCallState mCallState;

        RcsCallUpdateData(String phoneNumber, EnrichedCallState callState) {
            mPhoneNumber = phoneNumber;
            mCallState = callState;
        }

        public EnrichedCallState getCallState() {
            return mCallState;
        }

        public String getPhoneNumber() {
            return mPhoneNumber;
        }
    }

    //single instance object.
    private static RcsCallHandler sEnrichCallHandler = null;

    /*
     * mCallComposerHashMap contains call and callcomposerdata. Each call will be binded with
     * a CallComposerData object
     */
    private ConcurrentHashMap<Call, CallComposerData> mCallComposerHashMap
            = new ConcurrentHashMap<Call, CallComposerData>();
    /*
     * mPendingCallComposerDataList will be having a pending CallComposerData object list,
     * this is used in the case where the RCS content is arrived earlier then the actual call.
     */
    private ArrayList<CallComposerData> mPendingCallComposerDataList
            = new ArrayList<CallComposerData>();

    private RcsManager mRcsManager;
    private Context mContext;
    private CallsManager mCallsManager;

    private static final int ON_ENRICH_INCOMING_CALL = 0;
    private static final int UPDATE_ENRICH_CALL_STATE = 1;

    private Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case ON_ENRICH_INCOMING_CALL:
                CallComposerData data = (CallComposerData) msg.obj;
                log("received data : " + data);
                if (data != null) {
                    updateCallComposerData(data);
                }

                break;
            case UPDATE_ENRICH_CALL_STATE:
                RcsCallUpdateData updateData = (RcsCallUpdateData) msg.obj;
                updateEnrichCallStates(updateData.getPhoneNumber(), updateData.getCallState());
                break;
            default:
                log("nothing to handle");
            }
        }
    };

    private boolean mIsEnrichedCallStateRegistered = false;
    private EnrichedCallUpdateCallback mEnrichCallStateCallBack
            = new EnrichedCallUpdateCallback.Stub() {
        public void onEnrichedCallUpdate(String phoneNumber, EnrichedCallState state) {
            log("onEnrichedCallUpdate : " + phoneNumber + " state: " + state);
            Message msg = new Message();
            msg.what = UPDATE_ENRICH_CALL_STATE;
            RcsCallUpdateData data = new RcsCallUpdateData(phoneNumber, state);
            msg.obj = data;
            mHandler.sendMessage(msg);
        }
    };

    /*
     * This receiver is used to receive the broadcast when the RCSService is started.
     * This is just for the first time and only used here to get notified when the service
     * is started.
     */
    BroadcastReceiver mServiceConnectedReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            log( "received broadcast ServiceConnected");
            unsubscribeIncomingEnrichedCall();
            subscribeForIncomingEnrichCall();
            unregisterForEnrichCallState();
            registerForEnrichCallState();
        }
    };

    private IncomingEnrichedCallCallback mEnrichIncomingCallback
            = new IncomingEnrichedCallCallback.Stub() {
        @Override
        public void onIncomingEnrichedCall(CallComposerData data) {
            log("onIncomingEnrichedCall : " + data);
            if (data != null && data.getPhoneNumber() != null) {
                Message msg = new Message();
                msg.what = ON_ENRICH_INCOMING_CALL;
                msg.obj = data;
                mHandler.sendMessage(msg);
            }
        }
    };

    private RcsCallHandler(Context context, CallsManager callsManager) {
        log("Constructor");
        this.mContext = context;
        this.mCallsManager = callsManager;
        mRcsManager = RcsManager.getInstance(context);

        if (!mRcsManager.isEnrichCallFeatureEnabled()) {
            log("EnrichCallFeature is not Enabled");
            return;
        }
        mCallsManager.addListener(this);
        mContext.registerReceiver(mServiceConnectedReceiver,
                new IntentFilter(RcsManager.RCS_APP_START_ACTION));
        if (!mRcsManager.isServiceConnected()) {
            mRcsManager.initialize();
        } else {
            subscribeForIncomingEnrichCall();
            registerForEnrichCallState();
        }
    }

    public static RcsCallHandler getInstance() {
        if (sEnrichCallHandler != null) {
            return sEnrichCallHandler;
        }
        throw new RuntimeException("RcsCallHandler: Not initialized");
    }

    /**
     * Initialize the rcsmanager and listen for rcs incoming call and call
     * state.
     *
     * @param : context and the CallsManager instance
     * @return : a RcsCallHandler instance
     */
    public static RcsCallHandler init(Context context, CallsManager callsManager) {
        if (sEnrichCallHandler == null) {
            sEnrichCallHandler = new RcsCallHandler(context, callsManager);
        }
        return sEnrichCallHandler;
    }

    /**
     * Subscribe for incoming enrich call.
     */
    private void subscribeForIncomingEnrichCall() {
        log("subscribeForIncomingEnrichCall");
        mRcsManager.subscribeIncomingEnrichedCall(mEnrichIncomingCallback, 0/* SUBID */);
    }

    /**
     * Unsubscribe for incoming enrich call.
     */
    private void unsubscribeIncomingEnrichedCall() {
        log("unsubscribeForIncomingEnrichCall");
        mRcsManager.unsubscribeIncomingEnrichedCall(mEnrichIncomingCallback, 0/* SUBID */);
    }

    /**
     * register for enrich call state.
     */
    private void registerForEnrichCallState() {
        log("requestRegisterForEnrichCallState - ");
        if (!mIsEnrichedCallStateRegistered) {
            final RcsManager rcsManager = RcsManager.getInstance(mContext);
            if (rcsManager.isServiceConnected()) {
                rcsManager.subscribeEnrichedCallUpdate(mEnrichCallStateCallBack, 0/* SUBID */);
                mIsEnrichedCallStateRegistered = true;
            } else {
                rcsManager.initialize();
            }
        }
    }

    /**
     * unregister for enrich call state.
     */
    public void unregisterForEnrichCallState() {
        log("unregisterForEnrichCallState - ");
        if (mIsEnrichedCallStateRegistered) {
            if (mEnrichCallStateCallBack != null) {
                RcsManager rcsManager = RcsManager.getInstance(mContext);
                rcsManager.unsubscribeEnrichedCallUpdate(mEnrichCallStateCallBack, 0/* SUBID */);
            }
            mIsEnrichedCallStateRegistered = false;
        }
    }

    /**
     * update the enrich call state to the actual call with rcs content.
     *
     * @param String phonenumber of the call, EnrichCallState state of the RCS
     *            call.
     */
    private void updateEnrichCallStates(String phoneNumber, EnrichedCallState state) {
        log("updateEnrichCallStates: " + phoneNumber);
        Call call = getMatchingCall(phoneNumber);
        if (call != null) {
            CallComposerData data = getEnrichCallData(call);
            if (data != null && data.getCallState() != state) {
                data.setCallState(state);
                updateCallComposerData(data);
                return;
            } else {
                log("IGONORE update may be CallComposerData is null or call state is same");
            }
        }

        phoneNumber = PhoneNumberUtils.normalizeNumber(phoneNumber);
        log("update pending composerdata " + mPendingCallComposerDataList);

        for (Iterator<CallComposerData> iterator = mPendingCallComposerDataList.iterator();
                iterator.hasNext();) {
            CallComposerData composerData = iterator.next();
            if (composerData.getPhoneNumber() != null
                    && composerData.getPhoneNumber().equals(phoneNumber)) {
                log("Matched CallComposerData update enrich call state : " + state);
                iterator.remove();
                if (state != EnrichedCallState.FAILED) {
                    addPendingCallComposerData(composerData);
                }
            }
        }
    }

    /**
     * weather we can initiate the RCS call on specified subId
     *
     * @return boolean true if RCS call can be initiated else false.
     */
    public boolean canInitiateRcsCallonSub(int subId) {
        return mRcsManager.isRcsConfigEnabledonSub(subId);
    }

    /**
     * get the preferred RCS phone account to initiate the RCS call.
     *
     * @return PhoneAccountHandle.
     */
    public PhoneAccountHandle getPreferredRcsAccountHandler(String scheme, CallComposerData data,
            PhoneAccountHandle phoneAccountHandle) {

        PhoneAccountHandle phoneAccount = mCallsManager.getPhoneAccountRegistrar()
                .getOutgoingPhoneAccountForVideoState(scheme, VideoProfile.STATE_AUDIO_ONLY);

        if (phoneAccountHandle == null && phoneAccount == null && data != null && data.isValid()) {
            PhoneAccountRegistrar phoneAccountRegistrar = mCallsManager.getPhoneAccountRegistrar();
            List<PhoneAccountHandle> accounts = phoneAccountRegistrar.getCallCapablePhoneAccounts(
                    scheme, false);
            if (accounts != null) {
                for (PhoneAccountHandle account : accounts) {
                    int subId = phoneAccountRegistrar.getSubscriptionIdForPhoneAccount(account);
                    if (canInitiateRcsCallonSub(subId)) {
                        phoneAccountHandle = account;
                        break;
                    }
                }
            }
        }
        return phoneAccountHandle;
    }

    /**
     * This function will find the matching call and it will save the callcomposerdata and call
     * in mCallComposerHashMap. And also it will set the call extras with the same extras to trigger
     * updatecall to notify incall.
     *
     * @param : CallComposerData to be updated.
     */
    public void updateCallComposerData(CallComposerData composerData) {
        log("updateCallComposerData : " + composerData);
        if (composerData == null) {
            log("composerData is null");
            return;
        }
        Call call = getMatchingCall(composerData);

        if (call != null) {
            addCallComposerDataForCall(call, composerData);
            log("notify call to update extras");
            //notify call extra to incall.
            notifyIncall(call);
            return;
        }
        addPendingCallComposerData(composerData);
    }


    /**
     * Add callcomposerdata object to pending composer data list.
     *
     * @param CallComposerData
     */
    private void addPendingCallComposerData(CallComposerData data) {
        log("addPendingCallComposerData : " + data);
        mPendingCallComposerDataList.add(data);
    }

    /**
     * getEnrichCallExtra api will return the updated bundle, which contains the existing calls
     * extra along with the enrichcall extras. If extras from call is null then here new bundle
     * is created and enrich call extras will be set and return the bundle.
     *
     * @param Call.
     * @return Bundle with updated enrichcall extras.
     */
    public Bundle getEnrichCallExtra(Call call) {
        log("getEnrichCallExtra : " + call);
        if (call == null) {
            return null;
        }
        CallComposerData data = getEnrichCallData(call);
        Bundle bundle = call.getExtras();
        if (data != null) {
            log("update bundle : " + data);
            if (bundle == null) {
               bundle = new Bundle();
            }
            bundle.putBundle(RcsManager.ENRICH_CALL_CONTENT, data.getBundle());
        }
        return bundle;
    }

    /**
     * getEnrichCallData will return CallComposerData saved in CallComposerHashMap against the
     * Call which is been sent.
     *
     * @param Call for which the CallComposerData is requried.
     * @return CallComposerData, null if did not find the CallComposerData against the Call.
     */
    public CallComposerData getEnrichCallData(Call call) {
        if (call == null) {
            log("getEnrichCallData call is null");
            return null;
        }
        return mCallComposerHashMap.get(call);
    }

    /**
     * updateCallComposerDataToCall function will update the call with pending Composerdata.
     *
     * If RCS content arrived earler and then call is arrived in that case the RCS content will be
     * saved in Pending CallComposerData list and here when call arrives then we will update
     * the call with the CallComposerData if both call and pending CallComposerData
     * phone number matches.
     *
     * @param Call.
     */
    private void updateCallComposerDataToCall(Call call) {
        log("updateCallComposerDataToCall : " + call);
        String phoneNumber = call.getHandle() != null ? call.getHandle()
                    .getSchemeSpecificPart() : null;
        if (phoneNumber == null) {
            log("phonenumber is null");
            return;
        }
        phoneNumber = PhoneNumberUtils.normalizeNumber(phoneNumber);
        for (Iterator<CallComposerData> iterator = mPendingCallComposerDataList.iterator();
                iterator.hasNext();) {
            CallComposerData composerData = iterator.next();

            if (composerData.getPhoneNumber() != null
                    && composerData.getPhoneNumber().equals(phoneNumber)) {
                log("adding hashmap  : " + call + " " + composerData);
                addCallComposerDataForCall(call, composerData);
                iterator.remove();
                notifyIncall(call);
                return;
            }
        }
    }

    /**
     * removeCallComposerDataForCall function will remove the entry of call and CallComposerData.
     * when call is removed or disconnected then we will remove the entry from hash map.
     *
     * @param Call.
     */
    private void removeCallComposerDataForCall(Call call) {
        log("removeCallComposerDataForCall : " + call);
        mCallComposerHashMap.remove(call);
    }

    /**
     * addCallComposerDataForCall function will add the entry of call and CallComposerData.
     * when call is arrived or added then we will add the entry in hash map.
     *
     * @param Call.
     * @param CallComposerData.
     */
    private void addCallComposerDataForCall(Call call, CallComposerData data) {
        log("addCallComposerDataForCall : Call:" + call + " CallComposerData: " + data);
        mCallComposerHashMap.put(call,data);
    }

    /**
     * getMatchingCall function will search for a call with the given phonenumber string.
     *
     * @param String phoneNumber
     * @return Call object, null if call not found with given phoneNumber.
     */
    private Call getMatchingCall(String phoneNumber) {

        if (TextUtils.isEmpty(phoneNumber)) {
            log("phonenumber is null or empty returning null");
            return null;
        }
        phoneNumber = PhoneNumberUtils.normalizeNumber(phoneNumber);
        log("getMatchingCall : " + phoneNumber);
        String callNumber = null;

        for (Call call : mCallsManager.getCalls()) {
            callNumber = call.getHandle() != null ? call.getHandle().getSchemeSpecificPart() : null;
            callNumber = PhoneNumberUtils.normalizeNumber(callNumber);
            if (phoneNumber.equals(callNumber)) {
                log("Matched call returning call : " + call);
                return call;
            }
        }
        return null;
    }

    /**
     * getMatchingCall function will search for a call with the given CallComposerData phoneNumber.
     *
     * @param CallComposerData
     * @return Call object, null if call not found with given CallComposerData phoneNumber.
     */
    private Call getMatchingCall(CallComposerData data) {
        log("getMatchingCall : " + data);
        if (data == null) {
            return null;
        }
        return getMatchingCall(data.getPhoneNumber());
    }

    /**
     * notifyIncall function will call setExtra function of call object with the same extra value
     * of call object. By doing this updatecall function in IncallController will be called.
     * By which the Incall application will be notified with the updated RCS content.
     *
     * @param Call to be notified
     */
    private void notifyIncall(Call call) {
        log("notifyIncall : " + call);
        if (call != null) {
            call.setExtras(call.getExtras());
        }
    }

    // callsManager listerner callback start
    @Override
    public void onCallAdded(Call call) {
        log("onCallAdded");
        updateCallComposerDataToCall(call);
    }

    @Override
    public void onCallRemoved(Call call) {
        log("onCallRemoved");
        removeCallComposerDataForCall(call);
    }

    @Override
    public void onCallStateChanged(Call call, int oldState, int newState) {
    }

    @Override
    public void onConnectionServiceChanged(Call call, ConnectionServiceWrapper oldService,
            ConnectionServiceWrapper newService) {
    }

    @Override
    public void onIncomingCallAnswered(Call call) {
    }

    @Override
    public void onIncomingCallRejected(Call call, boolean rejectWithMessage, String textMessage) {
    }

    @Override
    public void onForegroundCallChanged(Call oldForegroundCall, Call newForegroundCall) {
    }

    @Override
    public void onCallAudioStateChanged(CallAudioState oldAudioState,
            CallAudioState newAudioState) {
    }

    @Override
    public void onRingbackRequested(Call call, boolean ringback) {
    }

    @Override
    public void onIsConferencedChanged(Call call) {
    }

    @Override
    public void onIsVoipAudioModeChanged(Call call) {
    }

    @Override
    public void onVideoStateChanged(Call call) {
    }

    @Override
    public void onCanAddCallChanged(boolean canAddCall) {
    }

    @Override
    public void onSessionModifyRequestReceived(Call call, VideoProfile videoProfile) {
    }

    @Override
    public void onMergeFailed(Call call) {
    }

    // callsManager listerner callback END

    private void log(String msg) {
        Log.d(this, msg);
    }
}
