/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.aasettings;

import org.korho.lsdextender.AndroidAutoSetting;
import org.korho.lsdextender.AndroidAutoSettingsState;
import java.util.Hashtable;
import java.util.Vector;

/** Commit-result descriptions independent of any visible page or later operation. */
final class SettingsChangeLog {
    private static final int MINIMUM_KEY_LENGTH = 1;
    private SettingsChangeLog() { }

    static void report(String command, AndroidAutoSettingsState before, AndroidAutoSettingsState after, String[] job) {
        String[] changes = changes(before, after, job);
        for (int i = 0; i < changes.length; i++) {
            org.korho.lsdextender.Slog.write(" " + command + ": " + org.korho.lsdextender.EventClock.stamp() + " " + changes[i]);
        }
    }

    static String[] changes(AndroidAutoSettingsState before, AndroidAutoSettingsState after, String[] job) {
        Vector lines = new Vector(); Hashtable seen = new Hashtable();
        for (int i = 2; i < job.length; i++) {
            int equals = job[i].indexOf('=');
            if (equals < MINIMUM_KEY_LENGTH) { continue; }
            String key = job[i].substring(0, equals);
            if (seen.put(key, Boolean.TRUE) != null) { continue; }
            AndroidAutoSetting old = find(before, key), stored = find(after, key);
            if (old != null && stored != null && (old.value != stored.value || old.inherited != stored.inherited)) {
                lines.addElement("key=" + key + " previous=" + value(old) + " stored=" + value(stored));
            }
        }
        String[] result = new String[lines.size()]; lines.copyInto(result); return result;
    }

    private static AndroidAutoSetting find(AndroidAutoSettingsState state, String key) {
        for (int i = 0; i < state.size(); i++) { if (state.row(i).key.equals(key)) { return state.row(i); } }
        return null;
    }
    private static String value(AndroidAutoSetting setting) {
        return String.valueOf(setting.value) + (setting.inherited ? "(default)" : "(explicit)");
    }
}
