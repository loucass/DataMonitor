/*
 * Copyright (C) 2021 Dr.NooB
 *
 * This file is a part of Data Monitor <https://github.com/itsdrnoob/DataMonitor>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.drnoob.datamonitor.utils;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tier1 per-SIM helper with Tier0 fallback to NetworkStatsHelper.getSubscriberId().
 * Never crashes — every privileged call is permission-guarded + try/catch.
 * Transport pattern inspired by Traffic-Light (leekleak/traffic-light):
 * Mobile queries take subscriberId (null = aggregate), WiFi always null.
 */
public class SimHelper {
    private static final String TAG = SimHelper.class.getSimpleName();

    public static final int REQUEST_READ_PHONE_STATE = 101;

    private SimHelper() {
    }

    public static boolean hasPhoneStatePermission(Context context) {
        if (context == null) {
            return false;
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED;
    }

    @NonNull
    public static List<SimInfo> getActiveSimInfos(Context context) {
        List<SimInfo> result = new ArrayList<>();
        if (context == null) {
            Log.d(TAG, "getActiveSimInfos: null context");
            return result;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
            Log.d(TAG, "getActiveSimInfos: API < 22, no SubscriptionManager");
            return result;
        }
        if (!hasPhoneStatePermission(context)) {
            Log.d(TAG, "getActiveSimInfos: READ_PHONE_STATE not granted");
            return result;
        }
        try {
            SubscriptionManager subscriptionManager = SubscriptionManager.from(context);
            if (subscriptionManager == null) {
                Log.d(TAG, "getActiveSimInfos: SubscriptionManager is null");
                return result;
            }
            List<SubscriptionInfo> infos = subscriptionManager.getActiveSubscriptionInfoList();
            if (infos == null || infos.isEmpty()) {
                Log.d(TAG, "getActiveSimInfos: no active subscriptions");
                return result;
            }
            for (SubscriptionInfo info : infos) {
                if (info == null) {
                    continue;
                }
                int slotIndex = info.getSimSlotIndex();
                int subscriptionId = info.getSubscriptionId();
                String carrierName = safeToString(info.getCarrierName());
                String displayName = safeToString(info.getDisplayName());
                result.add(new SimInfo(slotIndex, subscriptionId, carrierName, displayName));
            }
            Collections.sort(result, new Comparator<SimInfo>() {
                @Override
                public int compare(SimInfo left, SimInfo right) {
                    return Integer.compare(left.slotIndex, right.slotIndex);
                }
            });
            Log.d(TAG, "getActiveSimInfos: count=" + result.size());
        } catch (SecurityException e) {
            Log.d(TAG, "getActiveSimInfos: SecurityException: " + e);
            e.printStackTrace();
        } catch (Exception e) {
            Log.d(TAG, "getActiveSimInfos: Exception: " + e);
            e.printStackTrace();
        }
        return result;
    }

    @SuppressLint("MissingPermission")
    @Nullable
    public static String getSubscriberIdForSub(Context context, int subscriptionId) {
        if (context == null) {
            Log.d(TAG, "getSubscriberIdForSub: null context");
            return null;
        }
        if (!hasPhoneStatePermission(context)) {
            Log.d(TAG, "getSubscriberIdForSub: READ_PHONE_STATE not granted, subId=" + subscriptionId);
            return null;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.d(TAG, "getSubscriberIdForSub: API < 24, no createForSubscriptionId");
            return null;
        }
        try {
            TelephonyManager telephonyManager =
                    (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (telephonyManager == null) {
                Log.d(TAG, "getSubscriberIdForSub: TelephonyManager is null");
                return null;
            }
            TelephonyManager subManager = telephonyManager.createForSubscriptionId(subscriptionId);
            if (subManager == null) {
                Log.d(TAG, "getSubscriberIdForSub: null manager for subId=" + subscriptionId);
                return null;
            }
            String subscriberId = subManager.getSubscriberId();
            Log.d(TAG, "getSubscriberIdForSub: subId=" + subscriptionId
                    + " present=" + (subscriberId != null));
            return subscriberId;
        } catch (SecurityException e) {
            Log.d(TAG, "getSubscriberIdForSub: SecurityException for subId=" + subscriptionId + ": " + e);
            e.printStackTrace();
            return null;
        } catch (Exception e) {
            Log.d(TAG, "getSubscriberIdForSub: Exception for subId=" + subscriptionId + ": " + e);
            e.printStackTrace();
            return null;
        }
    }

    @NonNull
    public static Map<Integer, String> getAllSubscriberIds(Context context) {
        Map<Integer, String> map = new LinkedHashMap<>();
        List<SimInfo> infos = getActiveSimInfos(context);
        for (SimInfo info : infos) {
            map.put(info.subscriptionId, getSubscriberIdForSub(context, info.subscriptionId));
        }
        Log.d(TAG, "getAllSubscriberIds: count=" + map.size());
        return map;
    }

    @NonNull
    public static List<String> getCarrierLabels(Context context) {
        List<String> labels = new ArrayList<>();
        List<SimInfo> infos = getActiveSimInfos(context);
        if (infos.isEmpty()) {
            labels.add("Mobile data");
            Log.d(TAG, "getCarrierLabels: no SIM info, fallback Mobile data");
            return labels;
        }
        for (SimInfo info : infos) {
            int humanSlot = info.slotIndex >= 0 ? (info.slotIndex + 1) : (labels.size() + 1);
            String carrier = info.carrierName != null ? info.carrierName.trim() : "";
            String display = info.displayName != null ? info.displayName.trim() : "";
            String name = !carrier.isEmpty() ? carrier : display;
            if (name.isEmpty()) {
                labels.add("SIM " + humanSlot);
            } else if (name.startsWith("SIM ")) {
                labels.add(name);
            } else {
                labels.add("SIM " + humanSlot + " \u2022 " + name);
            }
        }
        Log.d(TAG, "getCarrierLabels: " + labels.size());
        return labels;
    }

    private static String safeToString(CharSequence value) {
        if (value == null) {
            return "";
        }
        return value.toString();
    }

    public static class SimInfo {
        public final int slotIndex;
        public final int subscriptionId;
        public final String carrierName;
        public final String displayName;

        public SimInfo(int slotIndex, int subscriptionId, String carrierName, String displayName) {
            this.slotIndex = slotIndex;
            this.subscriptionId = subscriptionId;
            this.carrierName = carrierName != null ? carrierName : "";
            this.displayName = displayName != null ? displayName : "";
        }

        @Override
        public String toString() {
            return "SimInfo{slot=" + slotIndex + ", subId=" + subscriptionId
                    + ", carrier='" + carrierName + "', display='" + displayName + "'}";
        }
    }
}
