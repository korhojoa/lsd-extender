/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.homescreen;

import org.korho.lsdextender.AndroidAutoSettings;
import org.korho.lsdextender.AndroidAutoSettingsBridge;
import org.korho.lsdextender.AndroidAutoSettingsState;
import org.korho.lsdextender.CarPlaySettingsBridge;

/** Separate backend ownership and navigation for the two projection systems. */
final class SettingsBackend {
    private static final String CP_SAVED = "Saved settings; apply on next CarPlay start";
    private static final String AA_APPLIED = "Applied in current AA process", DIRECTORY_MISMATCH = "Settings directory mismatch";
    private static final String READY = "Ready";
    final String title, pending, unavailable;
    private final boolean carPlayProfile;

    private SettingsBackend(boolean carPlay, String title, String pending, String unavailable) {
        carPlayProfile = carPlay; this.title = title; this.pending = pending;
        this.unavailable = unavailable;
    }
    static SettingsBackend androidAuto() {
        return new SettingsBackend(false, "Android Auto settings", "Unsaved changes; apply on next AA start",
            "Settings service unavailable");
    }
    static SettingsBackend carPlay() {
        return new SettingsBackend(true, "Apple CarPlay settings", "Unsaved changes; apply on next CarPlay start",
            "CarPlay settings service unavailable");
    }
    private String nextStart() {
        return carPlayProfile ? "Saved. Applies at the next Apple CarPlay start."
            : "Saved. Applies at the next Android Auto start.";
    }

    /** Helper diagnostics remain on the backend; only fixed text reaches the page. */
    String status(AndroidAutoSettingsState state) {
        if (state == null || !state.ready) { return unavailable; }
        if (state.busy) { return "Refreshing settings..."; }
        if (state.completed > 0 && !state.lastSaveOK) { return saveFailure(); }
        String value = state.status;
        if (value == null) { return statusUnavailable(); }
        if (carPlayProfile) {
            if (CP_SAVED.equals(value)) { return nextStart(); }
        } else {
            if (noActiveStatus(value)
                    || "Saved for next AA start; active status unavailable".equals(value)
                    || "Saved; applies on next AA start".equals(value)) { return nextStart(); }
            if (AA_APPLIED.equals(value)) { return "Applied in the current Android Auto session."; }
            if (waitingForFeature(value)) { return "Saved. Waiting for Android Auto features."; }
            if (DIRECTORY_MISMATCH.equals(value)) { return "Settings do not match the current Android Auto session."; }
            if (value.startsWith("AA settings error: ")) { return "Android Auto settings could not be applied."; }
        }
        if (value.startsWith("Not saved:")) { return saveFailure(); }
        if (READY.equals(value)) { return "Settings ready."; }
        return statusUnavailable();
    }

    private static boolean noActiveStatus(String value) {
        String prefix = "Saved for next AA start; no active status (";
        return value.startsWith(prefix) && value.endsWith(")") && value.length() > prefix.length() + 1
            && value.indexOf('\n') < 0 && value.indexOf('\r') < 0;
    }

    private static boolean waitingForFeature(String value) {
        String[] features = { "cluster", "sensor", "wireless", "ui", "input", "launcher", "aauplink" };
        for (int index = 0; index < features.length; index++) {
            if (("Saved; waiting for feature " + features[index]).equals(value)) { return true; }
        }
        return false;
    }

    private String statusUnavailable() {
        return carPlayProfile ? "Apple CarPlay settings status unavailable." : "Android Auto settings status unavailable.";
    }

    String saveFailure() { return "Settings could not be saved. Try again."; }

    /** Only groups which the active helper exposes; wireless has its own live backend. */
    String[] groups(AndroidAutoSettingsState state) {
        java.util.Vector found = new java.util.Vector();
        org.korho.lsdextender.WirelessSettings wireless = org.korho.lsdextender.LsdExtender.getWirelessSettings();
        if (!carPlayProfile && wireless != null && wireless.snapshot() != null && wireless.snapshot().ready) {
            found.addElement("Wireless connection");
        }
        for (int i = 0; state != null && i < state.size(); i++) {
            String group = state.row(i).group;
            if (!found.contains(group)) { found.addElement(group); }
        }
        String[] result = new String[found.size()]; found.copyInto(result); return result;
    }
    AndroidAutoSettings get() { return carPlayProfile ? CarPlaySettingsBridge.get() : AndroidAutoSettingsBridge.get(); }
    long generation() { return carPlayProfile ? CarPlaySettingsBridge.generation() : AndroidAutoSettingsBridge.generation(); }

    /**
     * The registered backend has read its config. This is a snapshot check only:
     * failed refreshes can retain ready data. Entry admission separately requires
     * the background monitor's current worker and helper checks.
     */
    boolean configured() {
        AndroidAutoSettings backend = get();
        AndroidAutoSettingsState state = backend == null ? null : backend.snapshot();
        return state != null && state.ready;
    }

    /** Asks the worker to read the config again; no I/O in the caller. */
    void refresh() {
        AndroidAutoSettings backend = get();
        if (backend != null) { backend.refresh(); }
    }
}
