/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.ev;

import org.dsi.ifc.carkombi.BCConfiguration;
import org.dsi.ifc.carkombi.BCViewOptions;
import org.dsi.ifc.generalvehiclestates.TankInfo;
import org.dsi.ifc.global.CarBCCurrentRange;

/** Electric-only status, kept separate from the historical AA range fallback. */
final class ElectricVehicleStatus {
    private static final int ELECTRIC = 3;
    private static final int VALID = 1;
    private static final int MILES = 1;
    private int primaryEngine = -1, secondaryEngine = -1;
    private long primaryRange = -1, secondaryRange = -1;
    private int primaryWarning = -1, secondaryWarning = -1;

    boolean update(String name, Object[] args) {
        Object value = args != null && args.length > 0 ? args[0] : null;
        boolean valid = args != null && args.length > 1 && args[1] instanceof Integer
            && ((Integer) args[1]).intValue() == VALID;
        if (name.equals("updateBCViewOptions")) {
            configure(value, valid);
        } else if (name.equals("updateBCCurrentRange1")) {
            primaryRange = distance(value, valid);
        } else if (name.equals("updateBCCurrentRange2")) {
            secondaryRange = distance(value, valid);
        } else if (name.equals("updateTankInfo")) {
            TankInfo tank = valid && value instanceof TankInfo ? (TankInfo) value : null;
            primaryWarning = tank == null ? -1 : (tank.fuelWarning ? 1 : 0);
            secondaryWarning = tank == null ? -1 : (tank.fuelWarningSecondary ? 1 : 0);
        } else {
            return false;
        }
        return true;
    }

    private void configure(Object value, boolean valid) {
        BCViewOptions options = value instanceof BCViewOptions ? (BCViewOptions) value : null;
        BCConfiguration configuration = valid && options != null ? options.configuration : null;
        int primary = configuration == null ? -1 : configuration.primaryEngineType;
        int secondary = configuration == null ? -1 : configuration.secondaryEngineType;
        if (configuration == null || primary != primaryEngine || secondary != secondaryEngine) {
            primaryRange = -1;
            secondaryRange = -1;
        }
        primaryEngine = primary;
        secondaryEngine = secondary;
    }

    void kombiUnavailable() {
        primaryEngine = -1;
        secondaryEngine = -1;
        primaryRange = -1;
        secondaryRange = -1;
    }

    void generalUnavailable() {
        primaryWarning = -1;
        secondaryWarning = -1;
    }

    private static long distance(Object value, boolean valid) {
        if (!valid || !(value instanceof CarBCCurrentRange)) {
            return -1;
        }
        CarBCCurrentRange range = (CarBCCurrentRange) value;
        if (range.state != VALID || range.currentRangeValue < 0 || range.currentRangeValue >= 65535) {
            return -1;
        }
        if (range.currentRangeUnit == 0) {
            return (long) range.currentRangeValue * 1000L;
        }
        if (range.currentRangeUnit == MILES) {
            return Math.round(range.currentRangeValue * 1609.344);
        }
        return -1;
    }

    String render() {
        boolean primary = primaryEngine == ELECTRIC && secondaryEngine != ELECTRIC && secondaryEngine >= 0;
        boolean secondary = secondaryEngine == ELECTRIC && primaryEngine != ELECTRIC && primaryEngine >= 0;
        long range = primary ? primaryRange : (secondary ? secondaryRange : -1);
        int warning = primary ? primaryWarning : (secondary ? secondaryWarning : -1);
        StringBuffer text = new StringBuffer(96);
        append(text, "electric_range_m", range);
        append(text, "electric_range_warning", warning);
        return text.toString();
    }

    private static void append(StringBuffer text, String key, long value) {
        if (value < 0) {
            text.append('-').append(key).append('\n');
        } else {
            text.append(key).append("::").append(value).append('\n');
        }
    }
}
