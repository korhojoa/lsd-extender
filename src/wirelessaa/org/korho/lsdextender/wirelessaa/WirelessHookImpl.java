/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.wirelessaa;

import org.korho.lsdextender.WirelessHook;
import org.dsi.ifc.smartphoneintegration.Device;

/** Runtime preparation callback registered by {@link BtWatch} on load. */
public final class WirelessHookImpl implements WirelessHook {
    public void beforeConnect(Device device) {
        WirelessSettingsService.prepareLaunch();
    }
}
