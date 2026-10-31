/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.homescreen;

import org.korho.lsdextender.LsdExtender;
import org.korho.lsdextender.WirelessSettings;
import org.korho.lsdextender.WirelessSettingsState;

/** Immediate permissions as checkbox rows. Keys are Bluetooth addresses, never row numbers. */
final class WirelessSettingsMenu {
    private static final String MASTER = "wireless-enabled", TITLE = "Wireless connection";
    private static final String LOADING = "Loading settings...", SAVING = "Saving...";
    private static final String WAITING = "Waiting for wireless services", ON = "Wireless AA is on", OFF = "Wireless AA is off";
    private ProjectionBehaviorPanel.Display target;

    void display(ProjectionBehaviorPanel.Display value) { target = value; }

    void toggle(String key) {
        WirelessSettings backend = LsdExtender.getWirelessSettings();
        WirelessSettingsState state = backend == null ? null : backend.snapshot();
        if (state == null || !state.ready) { refresh(); return; }
        if (MASTER.equals(key)) { backend.setEnabled(!state.enabled); }
        else {
            for (int i = 0; i < state.size(); i++) {
                if (state.address(i).equals(key)) { backend.setAllowed(key, !state.allowed(i)); }
            }
        }
        refresh();
    }

    void refresh() {
        if (target == null) { return; }
        WirelessSettings backend = LsdExtender.getWirelessSettings();
        WirelessSettingsState state = backend == null ? null : backend.snapshot();
        boolean usable = state != null && state.ready;
        int size = state == null ? 0 : state.size();
        SettingsRow[] rows = new SettingsRow[size + 2];
        String status = status(state);
        rows[0] = new SettingsRow(ProjectionBehaviorPanel.STATUS, state != null && size == 0
            ? status + "; pair a phone in Bluetooth" : status, "", false);
        rows[1] = permission(MASTER, "Wireless Android Auto", state != null && state.enabled,
            usable, state != null && state.busy);
        for (int i = 0; i < size; i++) {
            rows[i + 2] = permission(state.address(i), state.name(i) + (state.connected(i) ? " (connected)" : ""),
                state.allowed(i), usable, state.busy);
        }
        target.rows(TITLE, rows);
    }

    /** Producer errors contain diagnostic suffixes; none are page text. */
    private static String status(WirelessSettingsState state) {
        String value = state == null ? null : state.status;
        if (value != null) {
            if (value.startsWith("Cannot read saved settings; wireless AA is off: ")) {
                return "Wireless settings could not be read. Wireless Android Auto is off";
            }
            if (value.startsWith("Wireless control failed: ")) { return "Wireless control failed. Try again"; }
            if (value.startsWith("Not saved; check settings storage: ")) { return "Wireless settings could not be saved. Try again"; }
        }
        if (state == null || value == null) { return "Wireless service unavailable"; }
        if (LOADING.equals(value) && !state.ready) { return "Loading wireless settings..."; }
        if (!state.ready) { return "Wireless service unavailable"; }
        if (SAVING.equals(value) && state.busy) { return "Saving wireless settings..."; }
        if (WAITING.equals(value)) { return "Waiting for wireless services"; }
        if (ON.equals(value) && state.enabled) { return "Wireless Android Auto is on"; }
        if (OFF.equals(value) && !state.enabled) { return "Wireless Android Auto is off"; }
        return "Wireless settings status unavailable";
    }

    private static SettingsRow permission(String key, String label, boolean enabled, boolean usable, boolean busy) {
        // Revocation remains available while the worker is busy.
        return new SettingsRow(key, label, enabled ? "On" : "Off", usable && (!busy || enabled), enabled, false, false);
    }
}
