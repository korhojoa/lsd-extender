/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.homescreen;

import org.korho.lsdextender.AndroidAutoSetting;
import java.util.Vector;

/**
 * Detached control descriptors: booleans are checkboxes, enumerations are choices.
 * Numeric values retain owned editing and reset actions.
 */
final class ProjectionSettingRows {
    private static final String DEFAULT = "Default";
    private static final String MAIN_DPI = "main_dpi", CLUSTER_DPI = "cluster_density";
    private static final String VIEWING_DISTANCE = "cluster_viewing_distance", CLUSTER_SIZE = "cluster_codec_resolution";
    private static final String EV_MODEL = "sensor_ev_send", EV_CAPACITY = "sensor_ev_capacity_wh";

    private ProjectionSettingRows() { }

    /** number and inherited are the draft-aware value and default state of the setting. */
    static SettingsRow row(AndroidAutoSetting setting, int number, boolean inherited, boolean usable) {
        if ("altscreen_initial_url_str".equals(setting.key) && setting.readOnly) {
            return new SettingsRow(setting.key, "Raw URL override: "
                + (number == 0 ? "Not set" : "Active - overrides choices"), "", false);
        }
        boolean enabled = usable && !setting.readOnly;
        int bar = setting.label.indexOf('|');
        if (setting.toggle) {
            String label = bar < 0 ? setting.label : setting.label.substring(0, bar);
            return new SettingsRow(setting.key, caption(setting, label), "", enabled, number != 0, !inherited, inherited);
        }
        if (bar >= 0) {
            return choiceRow(setting, number, enabled, bar, inherited);
        }
        String shownValue = formatted(setting, number) + (setting.readOnly ? " (legacy)" : inherited ? " (default)" : "");
        boolean signed = setting.minimum < 0;
        return new SettingsRow(setting.key, caption(setting, setting.label), shownValue, enabled,
            signed ? new String[]{"Enter value", "Change sign", DEFAULT} : new String[]{"Enter value", DEFAULT},
            signed ? new String[]{ProjectionBehaviorPanel.EDIT, ProjectionBehaviorPanel.SIGN, ProjectionBehaviorPanel.INHERIT}
                : new String[]{ProjectionBehaviorPanel.EDIT, ProjectionBehaviorPanel.INHERIT});
    }

    /** Keep application qualifiers inside the existing native caption column. */
    private static String caption(AndroidAutoSetting setting, String label) {
        String original, compact;
        if (EV_MODEL.equals(setting.key)) {
            original = "Send EV energy model"; compact = "EV model";
        } else if (EV_CAPACITY.equals(setting.key)) {
            original = "Usable battery capacity (Wh)"; compact = "Capacity Wh";
        } else if (MAIN_DPI.equals(setting.key)) {
            original = "Main screen density (dpi)"; compact = "Main dpi";
        } else if (CLUSTER_DPI.equals(setting.key)) {
            original = "Cluster density (dpi)"; compact = "Cluster dpi";
        } else if (VIEWING_DISTANCE.equals(setting.key)) {
            original = "Viewing distance (mm)"; compact = "Distance mm";
        } else if (CLUSTER_SIZE.equals(setting.key)) {
            original = "Cluster video size"; compact = "Cluster size";
        } else { return label; }
        if (!label.equals(original) && !label.startsWith(original + " (")) { return label; }
        return compact + label.substring(original.length());
    }

    private static String formatted(AndroidAutoSetting setting, int number) {
        if (setting.toggle) {
            return number == 0 ? "Off" : "On";
        }
        if (setting.key.equals("sensor_ev_capacity_wh")) {
            return (number / 1000) + "." + (number % 1000 / 100) + " kWh";
        }
        return String.valueOf(number);
    }

    private static SettingsRow choiceRow(AndroidAutoSetting setting, int number, boolean enabled, int bar, boolean inherited) {
        // Legacy options use their zero-based index. "value=label" carries
        // explicit values, such as the loader's log thresholds 3 through 7.
        String[] named = options(setting.label.substring(bar + 1));
        String[] labels = new String[named.length + 1];
        String[] values = new String[labels.length];
        labels[0] = "altscreen_cluster_view".equals(setting.key) ? "Unset" : DEFAULT;
        values[0] = ProjectionBehaviorPanel.INHERIT;
        String shownValue = "Custom (edited)";
        for (int i = 0; i < named.length; i++) {
            int equals = named[i].indexOf('=');
            String optionValue = equals < 0 ? String.valueOf(i) : named[i].substring(0, equals);
            labels[i + 1] = equals < 0 ? named[i] : named[i].substring(equals + 1);
            values[i + 1] = optionValue;
            if (String.valueOf(number).equals(optionValue)) { shownValue = labels[i + 1]; }
        }
        return new SettingsRow(setting.key, caption(setting, setting.label.substring(0, bar)), shownValue, enabled,
            labels, values, inherited);
    }

    /** "a|b|c" split on '|' (the J9 class library has no String.split). */
    private static String[] options(String text) {
        Vector parts = new Vector();
        int start = 0;
        for (int bar = text.indexOf('|'); bar >= 0; bar = text.indexOf('|', start)) {
            parts.addElement(text.substring(start, bar));
            start = bar + 1;
        }
        parts.addElement(text.substring(start));
        String[] result = new String[parts.size()]; parts.copyInto(result); return result;
    }
}
