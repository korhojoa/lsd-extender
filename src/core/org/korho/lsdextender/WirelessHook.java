/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

import org.dsi.ifc.smartphoneintegration.Device;

/** Optional runtime preparation for a real virtual USB device selected by stock SI. */
public interface WirelessHook {
    /** Queue preparation for a selected real virtual USB row, before DSI launch. */
    void beforeConnect(Device device);
}
