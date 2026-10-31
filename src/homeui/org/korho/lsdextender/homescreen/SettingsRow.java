/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.homescreen;

/** A detached row. Actions are bound to keys, never to a visible row number. */
final class SettingsRow {
    final String key, label, value;
    final boolean enabled, toggle, selected, resettable, inherited;
    final String[] choices, choiceValues;

    SettingsRow(String key, String label, String value, boolean enabled) {
        this(key, label, value, enabled, null, null);
    }

    SettingsRow(String key, String label, String value, boolean enabled, String[] choices, String[] values) {
        this(key, label, value, enabled, choices, values, false, false, false, false);
    }

    SettingsRow(String key, String label, String value, boolean enabled, String[] choices, String[] values, boolean inherited) {
        this(key, label, value, enabled, choices, values, false, false, true, inherited);
    }

    SettingsRow(String key, String label, String value, boolean enabled,
            boolean selected, boolean resettable, boolean inherited) {
        this(key, label, value, enabled, null, null, true, selected, resettable, inherited);
    }

    private SettingsRow(String key, String label, String value, boolean enabled, String[] choices, String[] values,
            boolean toggle, boolean selected, boolean resettable, boolean inherited) {
        this.key = key; this.label = label; this.value = value; this.enabled = enabled;
        this.choices = choices; this.choiceValues = values;
        this.toggle = toggle; this.selected = selected; this.resettable = resettable; this.inherited = inherited;
    }

    boolean same(SettingsRow other) {
        return key.equals(other.key) && label.equals(other.label) && value.equals(other.value)
            && enabled == other.enabled && toggle == other.toggle && selected == other.selected
            && resettable == other.resettable && inherited == other.inherited && sameStrings(choices, other.choices)
            && sameStrings(choiceValues, other.choiceValues);
    }

    private static boolean sameStrings(String[] left, String[] right) {
        if (left == null || right == null) { return left == null && right == null; }
        if (left.length != right.length) { return false; }
        for (int i = 0; i < left.length; i++) {
            if (!left[i].equals(right[i])) { return false; }
        }
        return true;
    }
}
