/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

/** Stable boundary between bundled widgets and the reloadable wireless feature. */
public interface WirelessSettings {
    WirelessSettingsState snapshot();

    /** Nonblocking requests. False means busy, unavailable, or invalid. */
    boolean setEnabled(boolean enabled);
    boolean setAllowed(String address, boolean allowed);
}
