/*
 * Copyright 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.telecom;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.ServiceManager;
import android.os.RemoteException;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telephony.PhoneNumberUtils;

import com.android.internal.telephony.CarrierAppUtils;
import com.android.internal.telephony.IExtTelephony;

/**
 * Utilities to deal with the system telephony services. The system telephony services are treated
 * differently from 3rd party services in some situations (emergency calls, audio focus, etc...).
 */
public final class TelephonyUtil {
    private static final String TELEPHONY_PACKAGE_NAME = "com.android.phone";

    private static final String PSTN_CALL_SERVICE_CLASS_NAME =
            "com.android.services.telephony.TelephonyConnectionService";

    private static final String LOG_TAG = "TelephonyUtil";

    private static final PhoneAccountHandle DEFAULT_EMERGENCY_PHONE_ACCOUNT_HANDLE =
            new PhoneAccountHandle(
                    new ComponentName(TELEPHONY_PACKAGE_NAME, PSTN_CALL_SERVICE_CLASS_NAME), "E");

    private TelephonyUtil() {}

    /**
     * @return fallback {@link PhoneAccount} to be used by Telecom for emergency calls in the
     * rare case that Telephony has not registered any phone accounts yet. Details about this
     * account are not expected to be displayed in the UI, so the description, etc are not
     * populated.
     */
    static PhoneAccount getDefaultEmergencyPhoneAccount() {
        return PhoneAccount.builder(DEFAULT_EMERGENCY_PHONE_ACCOUNT_HANDLE, "E")
                .setCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION |
                        PhoneAccount.CAPABILITY_CALL_PROVIDER |
                        PhoneAccount.CAPABILITY_PLACE_EMERGENCY_CALLS)
                .setIsEnabled(true)
                .build();
    }

    static boolean isPstnComponentName(ComponentName componentName) {
        final ComponentName pstnComponentName = new ComponentName(
                TELEPHONY_PACKAGE_NAME, PSTN_CALL_SERVICE_CLASS_NAME);
        return pstnComponentName.equals(componentName);
    }

    public static boolean shouldProcessAsEmergency(Context context, Uri handle) {
        return handle != null && isLocalEmergencyNumber(handle.getSchemeSpecificPart());
    }

    public static boolean isLocalEmergencyNumber(String address) {
        IExtTelephony mIExtTelephony =
            IExtTelephony.Stub.asInterface(ServiceManager.getService("extphone"));
        boolean result = false;
        try {
            result = mIExtTelephony.isLocalEmergencyNumber(address);
        }catch (RemoteException ex) {
            Log.e(LOG_TAG, ex, "RemoteException");
        } catch (NullPointerException ex) {
            Log.e(LOG_TAG, ex, "NullPointerException");
        }
        return result;
    }

    public static boolean isPotentialLocalEmergencyNumber(String address) {
        IExtTelephony mIExtTelephony =
            IExtTelephony.Stub.asInterface(ServiceManager.getService("extphone"));
        boolean result = false;
        try {
            result = mIExtTelephony.isPotentialLocalEmergencyNumber(address);
        }catch (RemoteException ex) {
            Log.e(LOG_TAG, ex, "RemoteException");
        } catch (NullPointerException ex) {
            Log.e(LOG_TAG, ex, "NullPointerException");
        }
        return result;
    }

    /**
     * Check is carrier one supported or not
     */
    public static boolean isCarrierOneSupported() {
        CarrierAppUtils.CARRIER carrier = CarrierAppUtils.getCarrierId();
        return (carrier != null && (CarrierAppUtils.CARRIER.TELEPHONY_CARRIER_ONE == carrier));
    }

    static boolean isLowBattery(Context context) {
        if(!isCarrierOneSupported()) {
            return false;
        }

        Intent batteryStatus = context.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

        final int batteryLevel = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        //determine whether device is under low battery or not based on battery level
        return (batteryLevel <= (context.getResources().getInteger(
                com.android.internal.R.integer.config_lowBatteryWarningLevel)));
    }
}
