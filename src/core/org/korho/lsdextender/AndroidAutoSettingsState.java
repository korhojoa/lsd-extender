/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

/** Detached data only; cached views never retain a runtime class loader. */
public final class AndroidAutoSettingsState {
    public final boolean ready, busy, lastSaveOK;
    public final long completed;
    public final String revision, status, saveError;
    private final AndroidAutoSetting[] settings;

    public AndroidAutoSettingsState(boolean ready, boolean busy, String revision, String status,
            AndroidAutoSetting[] settings, long completed, boolean lastSaveOK) {
        this(ready, busy, revision, status, settings, completed, lastSaveOK, "");
    }
    public AndroidAutoSettingsState(boolean ready, boolean busy, String revision, String status,
            AndroidAutoSetting[] settings, long completed, boolean lastSaveOK, String saveError) {
        this.saveError = saveError == null ? "Settings could not be saved. Try again." : saveError;
        this.ready = ready; this.busy = busy; this.revision = revision; this.status = status;
        this.settings = (AndroidAutoSetting[])settings.clone();
        this.completed = completed; this.lastSaveOK = lastSaveOK;
    }
    public int size() { return settings.length; }
    public AndroidAutoSetting row(int index) { return settings[index]; }
    public AndroidAutoSetting[] rows() { return (AndroidAutoSetting[])settings.clone(); }
}
