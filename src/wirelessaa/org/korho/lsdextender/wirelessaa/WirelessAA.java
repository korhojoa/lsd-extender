/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.wirelessaa;

import org.korho.lsdextender.LsdExtender;
import org.korho.lsdextender.MflListener;
import de.vw.mib.asl.internal.smartphoneintegration.target.HsmTarget;

/** Retains the non-consuming long-next Android Auto connection action. */
public final class WirelessAA implements MflListener {
    static {
        try {
            WirelessSettingsService.start();
            LsdExtender.register(new WirelessAA());
            LsdExtender.registerCommands(new SavedDeviceCommands());
        } catch (Throwable ignored) {
            // Fail open to the native media action.
        }
    }

    private WirelessAA() { }

    public String name() { return "wireless [" + Build.BANNER + "]"; }

    public boolean onMflEvent(final HsmTarget target, int gesture) {
        if (gesture == 5 && target != null && WirelessSettingsService.canConnect()) {
            WirelessSettingsService.prepareLaunch(new Runnable() {
                public void run() {
                    de.vw.mib.asl.internal.smartphoneintegration.common.DeviceASLExtended[] live =
                        de.vw.mib.asl.internal.smartphoneintegration.common.SmartPhoneIntegrationGlobalProperyAccessor
                            .getInstance().getDsiDevicelistASLExtended();
                    org.dsi.ifc.smartphoneintegration.Device[] devices = new org.dsi.ifc.smartphoneintegration.Device[live == null ? 0 : live.length];
                    for (int i = 0; i < devices.length; i++) {
                        devices[i] = live[i] == null || live[i].isBlacklisted() ? null : live[i].getDsiDevice();
                    }
                    if (de.vw.mib.asl.internal.smartphoneintegration.common.SmartPhoneIntegrationGlobalProperyAccessor
                            .getInstance().isInternalHFPorOCUCallActive()) { return; }
                    int state = de.vw.mib.asl.internal.smartphoneintegration.common.SmartPhoneIntegrationGlobalProperyAccessor
                        .getInstance().getCurrentConnectionState();
                    org.dsi.ifc.smartphoneintegration.Device device = launchDevice(devices, state);
                    if (device == null) {
                        org.korho.lsdextender.Slog.write(" wireless: owned launch refused; no unique native virtual USB device");
                        return;
                    }
                    target.getDSISmartphoneIntegration().connectDevice(device.getDeviceID(), 8);
                }
            });
        }
        return false;
    }

    static org.dsi.ifc.smartphoneintegration.Device launchDevice(org.dsi.ifc.smartphoneintegration.Device[] devices, int state) {
        return state == 0 ? virtualDevice(devices) : null;
    }

    /** Resolve a current native USB row only after the bridge gate passes. */
    static org.dsi.ifc.smartphoneintegration.Device virtualDevice(org.dsi.ifc.smartphoneintegration.Device[] devices) {
        org.dsi.ifc.smartphoneintegration.Device selected = null;
        for (int i = 0; devices != null && i < devices.length; i++) {
            org.dsi.ifc.smartphoneintegration.Device device = devices[i];
            if (device == null || device.getDeviceID() < 0 || !"0001".equals(device.getDeviceAddress())
                    || (device.getConnectionMethod() & 8) == 0) { continue; }
            if (selected != null && selected.getDeviceID() != device.getDeviceID()) { return null; }
            selected = device;
        }
        return selected;
    }
}
