/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.telecom;

import android.content.Context;
import android.media.AudioManager;
import android.telecom.AudioState;
import android.telecom.CallState;

import com.android.internal.util.Preconditions;

import java.util.Objects;

/**
 * This class manages audio modes, streams and other properties.
 */
final class CallAudioManager extends CallsManagerListenerBase
        implements WiredHeadsetManager.Listener {
    private static final int STREAM_NONE = -1;

    private final StatusBarNotifier mStatusBarNotifier;
    private final AudioManager mAudioManager;
    private final BluetoothManager mBluetoothManager;
    private final WiredHeadsetManager mWiredHeadsetManager;

    private AudioState mAudioState;
    private int mAudioFocusStreamType;
    private boolean mIsRinging;
    private boolean mIsTonePlaying;
    private boolean mWasSpeakerOn;
    private int mMostRecentlyUsedMode = AudioManager.MODE_IN_CALL;
    private boolean mSpeedUpAudioForMtCall = false;
    private Context mContext;
    private String mSubId;

    CallAudioManager(Context context, StatusBarNotifier statusBarNotifier,
            WiredHeadsetManager wiredHeadsetManager) {
        mStatusBarNotifier = statusBarNotifier;
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mBluetoothManager = new BluetoothManager(context, this);
        mWiredHeadsetManager = wiredHeadsetManager;
        mWiredHeadsetManager.addListener(this);

        saveAudioState(getInitialAudioState(null));
        mAudioFocusStreamType = STREAM_NONE;
        mContext = context;
    }

    AudioState getAudioState() {
        return mAudioState;
    }

    @Override
    public void onCallAdded(Call call) {
        onCallUpdated(call);

        if (hasFocus() && getForegroundCall() == call) {
            if (!call.isIncoming()) {
                // Unmute new outgoing call.
                setSystemAudioState(false, mAudioState.getRoute(),
                        mAudioState.getSupportedRouteMask());
            }
        }
    }

    @Override
    public void onCallRemoved(Call call) {
        // If we didn't already have focus, there's nothing to do.
        if (hasFocus()) {
            if (CallsManager.getInstance().getCalls().isEmpty()) {
                Log.v(this, "all calls removed, reseting system audio to default state");
                setInitialAudioState(null, false /* force */);
                mWasSpeakerOn = false;
            }
            updateAudioStreamAndMode();
        }
    }

    @Override
    public void onCallStateChanged(Call call, int oldState, int newState) {
        onCallUpdated(call);
    }

    @Override
    public void onIncomingCallAnswered(Call call) {
        int route = mAudioState.getRoute();

        // BT stack will connect audio upon receiving active call state.
        // We unmute the audio for the new incoming call.

        setSystemAudioState(false /* isMute */, route, mAudioState.getSupportedRouteMask());

        if (mContext == null) {
            Log.d(this, "Speedup Audio Path enhancement: Context is null");
        } else if (mContext.getResources().getBoolean(
                com.android.server.telecom.R.bool.config_speed_up_audio_on_mt_calls)) {
            Log.d(this, "Speedup Audio Path enhancement");
            mSpeedUpAudioForMtCall = true;
            mSubId = call.getTargetPhoneAccount().getId();
            if (mIsRinging) {
                setIsRinging(false);
            } else {
                updateAudioStreamAndMode();
            }
        }
    }

    @Override
    public void onForegroundCallChanged(Call oldForegroundCall, Call newForegroundCall) {
        onCallUpdated(newForegroundCall);
        // Ensure that the foreground call knows about the latest audio state.
        updateAudioForForegroundCall();
    }

    @Override
    public void onIsVoipAudioModeChanged(Call call) {
        updateAudioStreamAndMode();
    }

    /**
      * Updates the audio route when the headset plugged in state changes. For example, if audio is
      * being routed over speakerphone and a headset is plugged in then switch to wired headset.
      */
    @Override
    public void onWiredHeadsetPluggedInChanged(boolean oldIsPluggedIn, boolean newIsPluggedIn) {
        // This can happen even when there are no calls and we don't have focus.
        int newRoute;
        if (!hasFocus()) {
            return;
        }

        Log.d(this,"isBluetoothAudioOn(): "+isBluetoothAudioOn());

        if (!isBluetoothAudioOn()) {
            newRoute = AudioState.ROUTE_EARPIECE;
            if (newIsPluggedIn) {
                newRoute = AudioState.ROUTE_WIRED_HEADSET;
            } else if (mWasSpeakerOn) {
                Call call = getForegroundCall();
                if (call != null && call.isAlive()) {
                    // Restore the speaker state.
                    newRoute = AudioState.ROUTE_SPEAKER;
                }
            }
        } else {
            newRoute = AudioState.ROUTE_BLUETOOTH;
        }
        setSystemAudioState(mAudioState.isMuted(), newRoute, calculateSupportedRoutes());
    }

    void toggleMute() {
        mute(!mAudioState.isMuted());
    }

    void mute(boolean shouldMute) {
        if (!hasFocus()) {
            return;
        }

        Log.v(this, "mute, shouldMute: %b", shouldMute);

        // Don't mute if there are any emergency calls.
        if (CallsManager.getInstance().hasEmergencyCall()) {
            shouldMute = false;
            Log.v(this, "ignoring mute for emergency call");
        }

        if (mAudioState.isMuted() != shouldMute) {
            setSystemAudioState(shouldMute, mAudioState.getRoute(),
                    mAudioState.getSupportedRouteMask());
        }
    }

    /**
     * Changed the audio route, for example from earpiece to speaker phone.
     *
     * @param route The new audio route to use. See {@link AudioState}.
     */
    void setAudioRoute(int route) {
        // This can happen even when there are no calls and we don't have focus.
        if (!hasFocus()) {
            return;
        }

        Log.v(this, "setAudioRoute, route: %s", AudioState.audioRouteToString(route));

        // Change ROUTE_WIRED_OR_EARPIECE to a single entry.
        int newRoute = selectWiredOrEarpiece(route, mAudioState.getSupportedRouteMask());

        // If route is unsupported, do nothing.
        if ((mAudioState.getSupportedRouteMask() | newRoute) == 0) {
            Log.wtf(this, "Asking to set to a route that is unsupported: %d", newRoute);
            return;
        }

        if (mAudioState.getRoute() != newRoute) {
            // Remember the new speaker state so it can be restored when the user plugs and unplugs
            // a headset.
            mWasSpeakerOn = newRoute == AudioState.ROUTE_SPEAKER;
            setSystemAudioState(mAudioState.isMuted(), newRoute,
                    mAudioState.getSupportedRouteMask());
        }
    }

    void setIsRinging(boolean isRinging) {
        if (mIsRinging != isRinging) {
            Log.v(this, "setIsRinging %b -> %b", mIsRinging, isRinging);
            mIsRinging = isRinging;
            updateAudioStreamAndMode();
        }
    }

    /**
     * Sets the tone playing status. Some tones can play even when there are no live calls and this
     * status indicates that we should keep audio focus even for tones that play beyond the life of
     * calls.
     *
     * @param isPlayingNew The status to set.
     */
    void setIsTonePlaying(boolean isPlayingNew) {
        ThreadUtil.checkOnMainThread();

        if (mIsTonePlaying != isPlayingNew) {
            Log.v(this, "mIsTonePlaying %b -> %b.", mIsTonePlaying, isPlayingNew);
            mIsTonePlaying = isPlayingNew;
            updateAudioStreamAndMode();
        }
    }

    /**
     * Updates the audio routing according to the bluetooth state.
     */
    void onBluetoothStateChange(BluetoothManager bluetoothManager) {
        // This can happen even when there are no calls and we don't have focus.
        if (!hasFocus()) {
            return;
        }

        int supportedRoutes = calculateSupportedRoutes();
        int newRoute = mAudioState.getRoute();
        if (bluetoothManager.isBluetoothAudioConnectedOrPending()) {
            newRoute = AudioState.ROUTE_BLUETOOTH;
        } else if (mAudioState.getRoute() == AudioState.ROUTE_BLUETOOTH) {
            newRoute = selectWiredOrEarpiece(AudioState.ROUTE_WIRED_OR_EARPIECE, supportedRoutes);
            // Do not switch to speaker when bluetooth disconnects.
            mWasSpeakerOn = false;
        }

        setSystemAudioState(mAudioState.isMuted(), newRoute, supportedRoutes);
    }

    boolean isBluetoothAudioOn() {
        return mBluetoothManager.isBluetoothAudioConnected();
    }

    boolean isBluetoothDeviceAvailable() {
        return mBluetoothManager.isBluetoothAvailable();
    }

    private void saveAudioState(AudioState audioState) {
        mAudioState = audioState;
        mStatusBarNotifier.notifyMute(mAudioState.isMuted());
        mStatusBarNotifier.notifySpeakerphone(mAudioState.getRoute() == AudioState.ROUTE_SPEAKER);
    }

    private void onCallUpdated(Call call) {
        boolean wasNotVoiceCall = mAudioFocusStreamType != AudioManager.STREAM_VOICE_CALL;
        if (call != null) {
            if (call.getState() != CallState.DISCONNECTED) {
                updateAudioStreamAndMode();
            }
            if ((call.getState() == CallState.ACTIVE) &&
                    (call.getTargetPhoneAccount() != null) &&
                    call.getTargetPhoneAccount().getId().equals(mSubId) && mSpeedUpAudioForMtCall) {
                Log.d(this,"Reset mSpeedUpAudioForMtCall");
                mSpeedUpAudioForMtCall = false;
            }
        }

        // If we transition from not voice call to voice call, we need to set an initial state.
        if (wasNotVoiceCall && mAudioFocusStreamType == AudioManager.STREAM_VOICE_CALL) {
            setInitialAudioState(call, true /* force */);
        }
    }

    private void setSystemAudioState(boolean isMuted, int route, int supportedRouteMask) {
        setSystemAudioState(false /* force */, isMuted, route, supportedRouteMask);
    }

    private void setSystemAudioState(
            boolean force, boolean isMuted, int route, int supportedRouteMask) {
        if (!hasFocus()) {
            return;
        }

        AudioState oldAudioState = mAudioState;
        saveAudioState(new AudioState(isMuted, route, supportedRouteMask));
        if (!force && Objects.equals(oldAudioState, mAudioState)) {
            return;
        }
        Log.i(this, "changing audio state from %s to %s", oldAudioState, mAudioState);

        // Mute.
        if (mAudioState.isMuted() != mAudioManager.isMicrophoneMute()) {
            Log.i(this, "changing microphone mute state to: %b", mAudioState.isMuted());
            mAudioManager.setMicrophoneMute(mAudioState.isMuted());
        }

        // Audio route.
        if (mAudioState.getRoute() == AudioState.ROUTE_BLUETOOTH) {
            turnOnSpeaker(false);
            turnOnBluetooth(true);
        } else if (mAudioState.getRoute() == AudioState.ROUTE_SPEAKER) {
            turnOnBluetooth(false);
            turnOnSpeaker(true);
        } else if (mAudioState.getRoute() == AudioState.ROUTE_EARPIECE ||
                mAudioState.getRoute() == AudioState.ROUTE_WIRED_HEADSET) {
            turnOnBluetooth(false);
            turnOnSpeaker(false);
        }

        if (!oldAudioState.equals(mAudioState)) {
            CallsManager.getInstance().onAudioStateChanged(oldAudioState, mAudioState);
            updateAudioForForegroundCall();
        }
    }

    private void turnOnSpeaker(boolean on) {
        // Wired headset and earpiece work the same way
        if (mAudioManager.isSpeakerphoneOn() != on) {
            Log.i(this, "turning speaker phone %s", on);
            mAudioManager.setSpeakerphoneOn(on);
        }
    }

    private void turnOnBluetooth(boolean on) {
        if (mBluetoothManager.isBluetoothAvailable()) {
            boolean isAlreadyOn = mBluetoothManager.isBluetoothAudioConnectedOrPending();
            if (on != isAlreadyOn) {
                Log.i(this, "connecting bluetooth %s", on);
                if (on) {
                    mBluetoothManager.connectBluetoothAudio();
                } else {
                    mBluetoothManager.disconnectBluetoothAudio();
                }
            }
        }
    }

    private void updateAudioStreamAndMode() {
        Log.i(this, "updateAudioStreamAndMode, mIsRinging: %b, mIsTonePlaying: %b", mIsRinging,
                mIsTonePlaying);
        Log.v(this, "updateAudioStreamAndMode, mSpeedUpAudioForMtCall: %b", mSpeedUpAudioForMtCall);
        if (mIsRinging && !mSpeedUpAudioForMtCall) {
            requestAudioFocusAndSetMode(AudioManager.STREAM_RING, AudioManager.MODE_RINGTONE);
        } else {
            Call call = getForegroundCall();
            if (call != null && call.getState() != CallState.DISCONNECTED) {
                int mode = call.getIsVoipAudioMode() ?
                        AudioManager.MODE_IN_COMMUNICATION : AudioManager.MODE_IN_CALL;
                requestAudioFocusAndSetMode(AudioManager.STREAM_VOICE_CALL, mode);
            } else if (mIsTonePlaying) {
                // There is no call, however, we are still playing a tone, so keep focus.
                // Since there is no call from which to determine the mode, use the most
                // recently used mode instead.
                requestAudioFocusAndSetMode(
                        AudioManager.STREAM_VOICE_CALL, mMostRecentlyUsedMode);
            } else if (!hasRingingForegroundCall()) {
                abandonAudioFocus();
            } else {
                // mIsRinging is false, but there is a foreground ringing call present. Don't
                // abandon audio focus immediately to prevent audio focus from getting lost between
                // the time it takes for the foreground call to transition from RINGING to ACTIVE/
                // DISCONNECTED. When the call eventually transitions to the next state, audio
                // focus will be correctly abandoned by the if clause above.
            }
        }
    }

    private void requestAudioFocusAndSetMode(int stream, int mode) {
        Log.i(this, "requestAudioFocusAndSetMode, stream: %d -> %d", mAudioFocusStreamType, stream);
        Preconditions.checkState(stream != STREAM_NONE);

        // Even if we already have focus, if the stream is different we update audio manager to give
        // it a hint about the purpose of our focus.
        if (mAudioFocusStreamType != stream) {
            Log.v(this, "requesting audio focus for stream: %d", stream);
            mAudioManager.requestAudioFocusForCall(stream,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        }
        mAudioFocusStreamType = stream;

        setMode(mode);
    }

    private void abandonAudioFocus() {
        if (hasFocus()) {
            setMode(AudioManager.MODE_NORMAL);
            Log.v(this, "abandoning audio focus");
            mAudioManager.abandonAudioFocusForCall();
            mAudioFocusStreamType = STREAM_NONE;
            mSpeedUpAudioForMtCall = false;
        }
    }

    /**
     * Sets the audio mode.
     *
     * @param newMode Mode constant from AudioManager.MODE_*.
     */
    private void setMode(int newMode) {
        Preconditions.checkState(hasFocus());
        int oldMode = mAudioManager.getMode();
        Log.v(this, "Request to change audio mode from %d to %d", oldMode, newMode);

        if (oldMode != newMode) {
            if (oldMode == AudioManager.MODE_IN_CALL && newMode == AudioManager.MODE_RINGTONE) {
                Log.i(this, "Transition from IN_CALL -> RINGTONE. Resetting to NORMAL first.");
                mAudioManager.setMode(AudioManager.MODE_NORMAL);
            }
            mAudioManager.setMode(newMode);
            Log.d(this, "SetMode Done");
            mMostRecentlyUsedMode = newMode;
        }
    }

    private int selectWiredOrEarpiece(int route, int supportedRouteMask) {
        // Since they are mutually exclusive and one is ALWAYS valid, we allow a special input of
        // ROUTE_WIRED_OR_EARPIECE so that callers dont have to make a call to check which is
        // supported before calling setAudioRoute.
        if (route == AudioState.ROUTE_WIRED_OR_EARPIECE) {
            route = AudioState.ROUTE_WIRED_OR_EARPIECE & supportedRouteMask;
            if (route == 0) {
                Log.wtf(this, "One of wired headset or earpiece should always be valid.");
                // assume earpiece in this case.
                route = AudioState.ROUTE_EARPIECE;
            }
        }
        return route;
    }

    private int calculateSupportedRoutes() {
        int routeMask = AudioState.ROUTE_SPEAKER;

        if (mWiredHeadsetManager.isPluggedIn()) {
            routeMask |= AudioState.ROUTE_WIRED_HEADSET;
        } else {
            routeMask |= AudioState.ROUTE_EARPIECE;
        }

        if (mBluetoothManager.isBluetoothAvailable()) {
            routeMask |=  AudioState.ROUTE_BLUETOOTH;
        }

        return routeMask;
    }

    private AudioState getInitialAudioState(Call call) {
        int supportedRouteMask = calculateSupportedRoutes();
        int route = selectWiredOrEarpiece(
                AudioState.ROUTE_WIRED_OR_EARPIECE, supportedRouteMask);

        // We want the UI to indicate that "bluetooth is in use" in two slightly different cases:
        // (a) The obvious case: if a bluetooth headset is currently in use for an ongoing call.
        // (b) The not-so-obvious case: if an incoming call is ringing, and we expect that audio
        //     *will* be routed to a bluetooth headset once the call is answered. In this case, just
        //     check if the headset is available. Note this only applies when we are dealing with
        //     the first call.
        if (call != null && mBluetoothManager.isBluetoothAvailable() && isBluetoothAudioOn()) {
            switch(call.getState()) {
                case CallState.ACTIVE:
                case CallState.ON_HOLD:
                case CallState.DIALING:
                case CallState.CONNECTING:
                case CallState.RINGING:
                    route = AudioState.ROUTE_BLUETOOTH;
                    break;
                default:
                    break;
            }
        }

        return new AudioState(false, route, supportedRouteMask);
    }

    private void setInitialAudioState(Call call, boolean force) {
        AudioState audioState = getInitialAudioState(call);
        Log.v(this, "setInitialAudioState %s, %s", audioState, call);
        setSystemAudioState(
                force, audioState.isMuted(), audioState.getRoute(),
                audioState.getSupportedRouteMask());
    }

    private void updateAudioForForegroundCall() {
        Call call = CallsManager.getInstance().getForegroundCall();
        if (call != null && call.getConnectionService() != null) {
            call.getConnectionService().onAudioStateChanged(call, mAudioState);
        }
    }

    /**
     * Returns the current foreground call in order to properly set the audio mode.
     */
    private Call getForegroundCall() {
        Call call = CallsManager.getInstance().getForegroundCall();

        // We ignore any foreground call that is in the ringing state because we deal with ringing
        // calls exclusively through the mIsRinging variable set by {@link Ringer}.
        if (call != null && call.getState() == CallState.RINGING && !mSpeedUpAudioForMtCall ) {
            call = null;
        }
        return call;
    }

    private boolean hasRingingForegroundCall() {
        Call call = CallsManager.getInstance().getForegroundCall();
        return call != null && call.getState() == CallState.RINGING;
    }

    private boolean hasFocus() {
        return mAudioFocusStreamType != STREAM_NONE;
    }
}
