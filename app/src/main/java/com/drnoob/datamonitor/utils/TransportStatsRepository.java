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
 *
 * Transport aggregation pattern (dual-query Mobile(subId)/WiFi + null,
 * deviceTotal reconciliation, 2h chunking) is inspired by
 * Traffic-Light (leekleak/traffic-light). No Traffic-Light code is copied verbatim.
 */

package com.drnoob.datamonitor.utils;

import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Transport-split aggregator: Mobile (per-subscriberId) + WiFi (null).
 * All queries are best-effort — SecurityException falls back, RemoteException returns 0.
 */
public class TransportStatsRepository {
    private static final String TAG = TransportStatsRepository.class.getSimpleName();

    public static final long CHUNK_MILLIS = 2L * 60L * 60L * 1000L;

    public static final String KEY_SELECTED_TRANSPORT = "transport_filter";
    public static final String KEY_SELECTED_SIM = "selected_sim_subscriber";

    public static final String FILTER_ALL = "ALL";
    public static final String FILTER_MOBILE = "MOBILE";
    public static final String FILTER_WIFI = "WIFI";

    public static final String SIM_ALL = "ALL";

    public enum Transport {
        MOBILE(ConnectivityManager.TYPE_MOBILE),
        WIFI(ConnectivityManager.TYPE_WIFI);

        private final int networkType;

        Transport(int networkType) {
            this.networkType = networkType;
        }

        public int getNetworkType() {
            return networkType;
        }

        public static Transport fromNetworkType(int networkType) {
            if (networkType == ConnectivityManager.TYPE_WIFI) {
                return WIFI;
            }
            return MOBILE;
        }

        public static Transport fromFilterName(String name) {
            if (FILTER_WIFI.equalsIgnoreCase(name)) {
                return WIFI;
            }
            if (FILTER_MOBILE.equalsIgnoreCase(name)) {
                return MOBILE;
            }
            return null;
        }
    }

    public static class DayUsage {
        public long mobileBytes;
        public long wifiBytes;
        public Map<String, Long> perSimMobileBytes;

        public DayUsage() {
            this.mobileBytes = 0L;
            this.wifiBytes = 0L;
            this.perSimMobileBytes = new HashMap<String, Long>();
        }

        public DayUsage(long mobileBytes, long wifiBytes, Map<String, Long> perSimMobileBytes) {
            this.mobileBytes = Math.max(0L, mobileBytes);
            this.wifiBytes = Math.max(0L, wifiBytes);
            if (perSimMobileBytes != null) {
                this.perSimMobileBytes = perSimMobileBytes;
            } else {
                this.perSimMobileBytes = new HashMap<String, Long>();
            }
        }

        public long getTotalBytes() {
            return mobileBytes + wifiBytes;
        }
    }

    public static long getNetworkDataForType(Context context, long start, long end,
                                             String subscriberId, int networkType) {
        if (context == null || end <= start) {
            return 0L;
        }
        long deviceTotal = queryDeviceTotal(context, networkType, subscriberId, start, end);
        long sumBuckets = querySumBuckets(context, networkType, subscriberId, start, end);
        long reconciled = reconcile(deviceTotal, sumBuckets);
        Log.d(TAG, "getNetworkDataForType: type=" + networkType
                + ", device=" + deviceTotal + ", sum=" + sumBuckets
                + ", reconciled=" + reconciled);
        return reconciled;
    }

    public static long getNetworkDataForTypeChunked(Context context, long start, long end,
                                                    String subscriberId, int networkType) {
        if (context == null || end <= start) {
            return 0L;
        }
        List<long[]> chunks = splitRange(start, end, CHUNK_MILLIS);
        long total = 0L;
        for (long[] chunk : chunks) {
            total += getNetworkDataForType(context, chunk[0], chunk[1], subscriberId, networkType);
        }
        return total;
    }

    public static DayUsage getDayUsage(Context context, long start, long end,
                                       Map<Integer, String> subIdToSubscriber) {
        DayUsage usage = new DayUsage();
        if (context == null || end <= start) {
            return usage;
        }
        try {
            usage.wifiBytes = getNetworkDataForType(
                    context, start, end, null, ConnectivityManager.TYPE_WIFI);
        } catch (SecurityException e) {
            Log.d(TAG, "getDayUsage: wifi SecurityException: " + e.getMessage());
            usage.wifiBytes = 0L;
        }
        if (subIdToSubscriber == null || subIdToSubscriber.isEmpty()) {
            try {
                usage.mobileBytes = getNetworkDataForType(
                        context, start, end, null, ConnectivityManager.TYPE_MOBILE);
            } catch (SecurityException e) {
                Log.d(TAG, "getDayUsage: mobile aggregate SecurityException: " + e.getMessage());
                usage.mobileBytes = 0L;
            }
            return usage;
        }
        long mobileSum = 0L;
        for (Map.Entry<Integer, String> entry : subIdToSubscriber.entrySet()) {
            String subscriberId = entry.getValue();
            long perSim = 0L;
            try {
                perSim = getNetworkDataForType(
                        context, start, end, subscriberId, ConnectivityManager.TYPE_MOBILE);
            } catch (SecurityException e) {
                Log.d(TAG, "getDayUsage: per-sim SecurityException, subId=" + entry.getKey());
                perSim = 0L;
            }
            String key = subscriberId != null ? subscriberId : ("sub_" + entry.getKey());
            usage.perSimMobileBytes.put(key, perSim);
            mobileSum += perSim;
        }
        usage.mobileBytes = Math.max(0L, mobileSum);
        return usage;
    }

    public static List<DayUsage> getWeekUsage(Context context, List<long[]> days,
                                              Map<Integer, String> subIdToSubscriber) {
        List<DayUsage> result = new ArrayList<DayUsage>();
        if (days == null) {
            return result;
        }
        for (long[] day : days) {
            if (day == null || day.length < 2) {
                result.add(new DayUsage());
                continue;
            }
            result.add(getDayUsage(context, day[0], day[1], subIdToSubscriber));
        }
        return result;
    }

    public static List<long[]> splitRange(long start, long end, long chunkMillis) {
        List<long[]> out = new ArrayList<long[]>();
        if (end <= start || chunkMillis <= 0) {
            return out;
        }
        long cursor = start;
        while (cursor < end) {
            long next = Math.min(end, cursor + chunkMillis);
            out.add(new long[]{cursor, next});
            cursor = next;
        }
        return out;
    }

    public static long getTotalForFilter(DayUsage usage, Transport filter, String subscriberIdOrAll) {
        if (usage == null) {
            return 0L;
        }
        if (filter == null) {
            return Math.max(0L, usage.mobileBytes) + Math.max(0L, usage.wifiBytes);
        }
        if (filter == Transport.WIFI) {
            return Math.max(0L, usage.wifiBytes);
        }
        if (subscriberIdOrAll == null || SIM_ALL.equals(subscriberIdOrAll)) {
            return Math.max(0L, usage.mobileBytes);
        }
        Long perSim = usage.perSimMobileBytes.get(subscriberIdOrAll);
        if (perSim != null) {
            return Math.max(0L, perSim);
        }
        Log.d(TAG, "getTotalForFilter: unknown sim key, falling back to aggregate");
        return Math.max(0L, usage.mobileBytes);
    }

    public static float scaleProgress(float mb, float maxMb) {
        if (maxMb <= 0f || mb <= 0f) {
            return 0f;
        }
        float ratio = mb / maxMb;
        if (ratio > 1f) {
            return 1f;
        }
        return ratio;
    }

    public static long reconcile(long deviceTotal, long sumBuckets) {
        long device = Math.max(0L, deviceTotal);
        long sum = Math.max(0L, sumBuckets);
        return Math.max(device, sum);
    }

    public static float overlapRatio(long rangeStart, long rangeEnd,
                                     long windowStart, long windowEnd) {
        if (rangeEnd <= rangeStart) {
            return 0f;
        }
        long overlapStart = Math.max(rangeStart, windowStart);
        long overlapEnd = Math.min(rangeEnd, windowEnd);
        if (overlapEnd <= overlapStart) {
            return 0f;
        }
        return (float) (overlapEnd - overlapStart) / (float) (rangeEnd - rangeStart);
    }

    public static String getSelectedTransportFilter(Context context) {
        if (context == null) {
            return FILTER_ALL;
        }
        try {
            android.content.SharedPreferences prefs = SharedPreferences.getUserPrefs(context);
            if (prefs == null) {
                return FILTER_ALL;
            }
            return prefs.getString(KEY_SELECTED_TRANSPORT, FILTER_ALL);
        } catch (Exception e) {
            Log.d(TAG, "getSelectedTransportFilter: " + e.getMessage());
            return FILTER_ALL;
        }
    }

    public static void setSelectedTransportFilter(Context context, String filter) {
        if (context == null) {
            return;
        }
        try {
            android.content.SharedPreferences prefs = SharedPreferences.getUserPrefs(context);
            if (prefs == null) {
                return;
            }
            prefs.edit().putString(KEY_SELECTED_TRANSPORT, filter != null ? filter : FILTER_ALL).apply();
        } catch (Exception e) {
            Log.d(TAG, "setSelectedTransportFilter: " + e.getMessage());
        }
    }

    public static String getSelectedSimSubscriber(Context context) {
        if (context == null) {
            return SIM_ALL;
        }
        try {
            android.content.SharedPreferences prefs = SharedPreferences.getUserPrefs(context);
            if (prefs == null) {
                return SIM_ALL;
            }
            return prefs.getString(KEY_SELECTED_SIM, SIM_ALL);
        } catch (Exception e) {
            return SIM_ALL;
        }
    }

    public static void setSelectedSimSubscriber(Context context, String subscriberIdOrAll) {
        if (context == null) {
            return;
        }
        try {
            android.content.SharedPreferences prefs = SharedPreferences.getUserPrefs(context);
            if (prefs == null) {
                return;
            }
            prefs.edit().putString(KEY_SELECTED_SIM,
                    subscriberIdOrAll != null ? subscriberIdOrAll : SIM_ALL).apply();
        } catch (Exception e) {
            Log.d(TAG, "setSelectedSimSubscriber: " + e.getMessage());
        }
    }

    private static long queryDeviceTotal(Context context, int networkType,
                                         String subscriberId, long start, long end) {
        try {
            NetworkStatsManager manager = (NetworkStatsManager)
                    context.getSystemService(Context.NETWORK_STATS_SERVICE);
            if (manager == null) {
                return 0L;
            }
            NetworkStats.Bucket bucket = manager.querySummaryForDevice(networkType, subscriberId, start, end);
            if (bucket == null) {
                return 0L;
            }
            return sanitize(bucket.getRxBytes()) + sanitize(bucket.getTxBytes());
        } catch (RemoteException e) {
            Log.d(TAG, "queryDeviceTotal RemoteException: " + e.getMessage());
            return 0L;
        } catch (SecurityException e) {
            Log.d(TAG, "queryDeviceTotal SecurityException, retry null subscriber");
            try {
                NetworkStatsManager manager = (NetworkStatsManager)
                        context.getSystemService(Context.NETWORK_STATS_SERVICE);
                if (manager == null) {
                    return 0L;
                }
                NetworkStats.Bucket bucket = manager.querySummaryForDevice(networkType, null, start, end);
                if (bucket == null) {
                    return 0L;
                }
                return sanitize(bucket.getRxBytes()) + sanitize(bucket.getTxBytes());
            } catch (Exception retry) {
                return 0L;
            }
        }
    }

    private static long querySumBuckets(Context context, int networkType,
                                        String subscriberId, long start, long end) {
        NetworkStats networkStats = null;
        try {
            NetworkStatsManager manager = (NetworkStatsManager)
                    context.getSystemService(Context.NETWORK_STATS_SERVICE);
            if (manager == null) {
                return 0L;
            }
            try {
                networkStats = manager.querySummary(networkType, subscriberId, start, end);
            } catch (SecurityException e) {
                networkStats = manager.querySummary(networkType, null, start, end);
            }
            if (networkStats == null) {
                return 0L;
            }
            long total = 0L;
            NetworkStats.Bucket bucket = new NetworkStats.Bucket();
            do {
                networkStats.getNextBucket(bucket);
                total += sanitize(bucket.getRxBytes()) + sanitize(bucket.getTxBytes());
            } while (networkStats.hasNextBucket());
            return total;
        } catch (RemoteException e) {
            return 0L;
        } catch (SecurityException e) {
            return 0L;
        } finally {
            if (networkStats != null) {
                try {
                    networkStats.close();
                } catch (Exception e) {
                    Log.d(TAG, "querySumBuckets close: " + e.getMessage());
                }
            }
        }
    }

    private static long sanitize(long bytes) {
        return Math.max(0L, bytes);
    }
}
