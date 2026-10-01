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

package com.drnoob.datamonitor.adapters.data;

import java.io.Serializable;

public class OverviewModel implements Serializable {
    private Float totalMobile, totalWifi;

    public OverviewModel() {
    }

    public OverviewModel(Float totalMobile, Float totalWifi) {
        this.totalMobile = totalMobile == null ? 0f : totalMobile;
        this.totalWifi = totalWifi == null ? 0f : totalWifi;
    }

    /** Compat: old code stored truncated MB as Long; keep same unit. */
    @Deprecated
    public OverviewModel(Long totalMobile, Long totalWifi) {
        this(totalMobile == null ? 0f : totalMobile.floatValue(),
                totalWifi == null ? 0f : totalWifi.floatValue());
    }

    public Float getTotalMobile() {
        return totalMobile;
    }

    public void setTotalMobile(Float totalMobile) {
        this.totalMobile = totalMobile;
    }

    public Float getTotalWifi() {
        return totalWifi;
    }

    public void setTotalWifi(Float totalWifi) {
        this.totalWifi = totalWifi;
    }

    /** Bytes helpers for formatData(Long,Long) without round-trip loss. */
    public long getTotalMobileBytes() {
        return (long) (totalMobile * 1048576f);
    }

    public long getTotalWifiBytes() {
        return (long) (totalWifi * 1048576f);
    }
}
