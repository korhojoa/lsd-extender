/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

/** Nonblocking boundary between bundled widgets and the reloadable settings worker. */
public interface AndroidAutoSettings {
    AndroidAutoSettingsState snapshot();
    void refresh();
    boolean save(String revision, String[] keys, String[] values);
}
