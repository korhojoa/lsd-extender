/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

import org.dsi.ifc.smartphoneintegration.Device;

/** Optional preparation only; stock SI connection state remains authoritative. */
public final class WirelessLaunch {
    private WirelessLaunch() {
    }

    public static void prepare(Device device, int method) {
        if (device == null || method != 8 || !"0001".equals(device.getDeviceAddress())
            || (device.getConnectionMethod() & 8) == 0) { return; }
        WirelessHook hook = LsdExtender.getWirelessHook();
        if (hook == null) { return; }
        try { hook.beforeConnect(device); }
        catch (LinkageError oldRuntime) { Slog.write(" wireless: early preparation unavailable in runtime jar"); }
        catch (RuntimeException failed) { Slog.write(" wireless: early preparation failed: " + failed); }
    }
}
