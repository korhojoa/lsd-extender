/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

/** Immutable descriptor produced by the native settings schema. */
public final class AndroidAutoSetting {
    public final String key, group, label;
    public final boolean toggle, inherited, readOnly;
    public final int value, defaultValue, minimum, maximum, step;

    public AndroidAutoSetting(String key, String group, String label, boolean toggle,
            int value, int defaultValue, int minimum, int maximum, int step,
            boolean inherited, boolean readOnly) {
        this.key = key; this.group = group; this.label = label; this.toggle = toggle;
        this.value = value; this.defaultValue = defaultValue; this.minimum = minimum;
        this.maximum = maximum; this.step = step; this.inherited = inherited; this.readOnly = readOnly;
    }
}
