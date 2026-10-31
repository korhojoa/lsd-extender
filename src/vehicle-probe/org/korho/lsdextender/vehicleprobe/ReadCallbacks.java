/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.vehicleprobe;

import org.dsi.ifc.carlight.TimeState;
import org.dsi.ifc.global.CarBCCurrentRange;
import org.dsi.ifc.global.CarBCDistance;
import org.dsi.ifc.global.CarBCTemperature;

/** Copy only allowlisted primitive fields; never retain a mutable native struct. */
final class ReadCallbacks {
    private static final String TEMPERATURE = "updateBCOutsideTemperature";
    private static final String RANGE = "updateBCCurrentRange1";
    private static final String DISTANCE = "updateBCTotalDistance";
    private static final String[] BOOLEANS = new String[]{"updateReverseGear", "updateVehicleStandstill", "updateParkingBrake"};
    private static final String[] LIGHTS = new String[]{"updateExtLightComingHome", "updateExtLightLeavingHome"};

    private ReadCallbacks() {
    }

    static void accept(ReadValues values, String name, Object[] args) {
        for (int i = 0; i < BOOLEANS.length; i++) {
            if (BOOLEANS[i].equals(name)) { values.update(i, "v=" + args[0], valid(args)); return; }
        }
        if (TEMPERATURE.equals(name)) {
            CarBCTemperature data = (CarBCTemperature)args[0];
            values.update(3, data == null ? null : "v=" + data.temperatureValue + " u=" + data.temperatureUnit + " s=" + data.state, valid(args));
        } else if (RANGE.equals(name)) {
            CarBCCurrentRange data = (CarBCCurrentRange)args[0];
            values.update(4, data == null ? null : "v=" + data.currentRangeValue + " u=" + data.currentRangeUnit + " s=" + data.state, valid(args));
        } else if (DISTANCE.equals(name)) {
            CarBCDistance data = (CarBCDistance)args[0];
            values.update(5, data == null ? null : "v=" + data.distanceValue + " u=" + data.distanceUnit + " s=" + data.distanceValueState, valid(args));
        } else {
            for (int i = 0; i < LIGHTS.length; i++) {
                if (LIGHTS[i].equals(name)) {
                    TimeState data = (TimeState)args[0];
                    values.update(6 + i, data == null ? null : "v=" + data.time + " s=" + data.state, valid(args));
                    return;
                }
            }
        }
    }

    private static int valid(Object[] args) { return ((Integer)args[1]).intValue(); }
}
