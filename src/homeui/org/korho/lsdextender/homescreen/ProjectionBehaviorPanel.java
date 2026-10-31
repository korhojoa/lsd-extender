/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.homescreen;

import org.korho.lsdextender.AndroidAutoSetting;
import org.korho.lsdextender.AndroidAutoSettings;
import org.korho.lsdextender.AndroidAutoSettingsState;
import java.util.Hashtable;
import java.util.Vector;

/** Projection settings draft model, independent of any widget or page host. */
final class ProjectionBehaviorPanel {
    /** An owned presentation supplies these actions after supported entry registration. */
    interface Display {
        void rows(String heading, SettingsRow[] rows);
        void edit(AndroidAutoSetting setting, int value);
        void error(String message);
    }

    static final String INHERIT = "inherit";
    static final String STATUS = "#status", STORE = "#store", EDIT = "#edit", SIGN = "#sign";
    private static final String WIRELESS = "Wireless connection";
    private final Runnable close;
    private final SettingsBackend profile;
    private final WirelessSettingsMenu wireless;
    private final Hashtable changes = new Hashtable();
    private final Presentation presentation = new Presentation();
    private final Editing editing = new Editing();
    private Display target;
    private AndroidAutoSettingsState source;
    private String group;
    private long savingAfter, backendGeneration;
    private boolean saving, visible;

    ProjectionBehaviorPanel(Runnable close, SettingsBackend profile) {
        this.close = close; this.profile = profile;
        wireless = new WirelessSettingsMenu();
    }

    void display(Display value) { target = value; wireless.display(value); }
    boolean shown() { return visible; }

    void open() { group = null; visible = true; refresh(); }
    void discard() { source = null; changes.clear(); group = null; saving = false; }
    void hide() { visible = false; discard(); }

    void back() {
        if (saving) { return; }
        if (group == null) { hide(); close.run(); return; }
        group = null; refresh();
    }

    void select(String key) {
        if (!visible || saving || STATUS.equals(key)) { return; }
        if (STORE.equals(key)) { save(); return; }
        if (group == null) { group = key; refresh(); }
    }

    /** Boolean projection values are drafted; wireless permissions apply immediately. */
    void toggle(String key) {
        if (!visible || saving) { return; }
        if (WIRELESS.equals(group)) { wireless.toggle(key); return; }
        AndroidAutoSetting setting = editing.setting(key);
        if (setting == null || !setting.toggle) { return; }
        change(key, value(setting) == 0 ? "1" : "0");
    }

    /** An option of a value row's popup. */
    boolean choose(String key, String value) {
        AndroidAutoSetting setting = editing.setting(key);
        if (setting == null) { return false; }
        if (EDIT.equals(value)) {
            if (target != null && !setting.toggle && setting.label.indexOf('|') < 0) {
                target.edit(setting, value(setting)); return true;
            }
            return false;
        }
        if (SIGN.equals(value)) {
            long negated = -(long)value(setting);
            if (!setting.toggle && setting.label.indexOf('|') < 0 && setting.minimum < 0
                    && negated >= setting.minimum && negated <= setting.maximum) { return change(key, String.valueOf(negated)); }
            return false;
        }
        return change(key, value);
    }

    boolean change(String key, String value) {
        AndroidAutoSetting setting = editing.setting(key);
        if (setting == null) { return false; }
        if (!Validation.valid(setting, value)) {
            if (target != null) { target.error("Value is outside the setting's allowed choices or range."); }
            return false;
        }
        changes.put(key, value);
        refresh();
        return true;
    }

    /** Admission is checked again by the model, after any presentation check. */
    private final class Editing {
        private AndroidAutoSetting setting(String key) {
            if (!visible || saving) { return null; }
            if (backendGeneration != profile.generation()) { refresh(); return null; }
            AndroidAutoSettings backend = profile.get();
            AndroidAutoSettingsState current = backend == null ? null : backend.snapshot();
            if (!bound(backend, backendGeneration)) { refresh(); return null; }
            AndroidAutoSetting setting = find(key);
            if (current == null || !current.ready || current.busy || source == null || !source.ready
                    || setting == null || setting.readOnly || !setting.group.equals(group)
                    || !presentation.active(key) || !presentation.shown(key)) { return null; }
            return setting;
        }
    }

    private static final class Validation {
        private Validation() { }
        private static boolean valid(AndroidAutoSetting setting, String value) {
            if (value == null) { return false; }
            if (INHERIT.equals(value)) { return true; }
            int number;
            try { number = Integer.parseInt(value); }
            catch (NumberFormatException failure) { return false; }
            if (!String.valueOf(number).equals(value) || number < setting.minimum || number > setting.maximum) { return false; }
            if ("main_dpi".equals(setting.key) && number != 0 && number < 60) { return false; }
            if (setting.toggle) { return number == 0 || number == 1; }
            int bar = setting.label.indexOf('|');
            if (bar < 0) { return true; }
            SettingsRow choices = ProjectionSettingRows.row(setting, number, false, true);
            for (int i = 0; i < choices.choiceValues.length; i++) {
                if (value.equals(choices.choiceValues[i])) { return true; }
            }
            return false;
        }

    }

    private AndroidAutoSetting find(String key) {
        for (int i = 0; source != null && i < source.size(); i++) {
            if (source.row(i).key.equals(key)) { return source.row(i); }
        }
        return null;
    }

    private int value(AndroidAutoSetting setting) {
        String pending = (String)changes.get(setting.key);
        return pending == null ? setting.value
            : pending.equals(INHERIT) ? setting.defaultValue : Integer.parseInt(pending);
    }

    private boolean inherited(AndroidAutoSetting setting) {
        String pending = (String)changes.get(setting.key);
        return pending == null ? setting.inherited : INHERIT.equals(pending);
    }

    private void save() {
        if (!visible || saving || changes.isEmpty()) { return; }
        if (backendGeneration != profile.generation()) { refresh(); return; }
        AndroidAutoSettings backend = profile.get();
        AndroidAutoSettingsState current = backend == null ? null : backend.snapshot();
        if (!bound(backend, backendGeneration)) { refresh(); return; }
        if (current == null || !current.ready || current.busy || source == null || !source.ready) { return; }
        if (!source.revision.equals(current.revision)) {
            changes.clear(); source = current;
            if (target != null) { target.error("Settings changed elsewhere. Review the current values and try again."); }
            refresh(); return;
        }
        String[] keys = new String[changes.size()], values = new String[changes.size()];
        java.util.Enumeration entries = changes.keys(); int index = 0;
        while (entries.hasMoreElements()) {
            keys[index] = (String)entries.nextElement(); values[index] = (String)changes.get(keys[index]); index++;
        }
        savingAfter = current.completed;
        if (!bound(backend, backendGeneration)) { refresh(); return; }
        if (backend.save(source.revision, keys, values)) { saving = true; }
        else if (target != null) { target.error("Settings could not be saved. Try again."); }
        refresh();
    }

    void refresh() {
        if (!visible) { return; }
        long generation = profile.generation();
        AndroidAutoSettings backend = profile.get();
        if (generation != backendGeneration) { discard(); backendGeneration = generation; }
        if (backend != null) { backend.refresh(); }
        AndroidAutoSettingsState state = backend == null ? null : backend.snapshot();
        if (!bound(backend, generation)) {
            discard();
            if (target != null) { target.rows(profile.title, new SettingsRow[] {
                new SettingsRow(STATUS, "Settings service changed; refreshing...", "", false)
            }); }
            return;
        }
        completedSave(state);
        if (!saving && changes.isEmpty() && state != null) { source = state; }
        if (target == null) { return; }
        if (WIRELESS.equals(group)) { wireless.refresh(); return; }
        boolean usable = state != null && state.ready && !state.busy && !saving;
        String status = state == null ? profile.unavailable : saving ? "Saving..."
            : !changes.isEmpty() ? profile.pending : profile.status(state);
        target.rows(group == null ? profile.title : group, presentation.rows(status, usable));
    }

    private void completedSave(AndroidAutoSettingsState state) {
        if (saving && state != null && state.completed > savingAfter) {
            saving = false;
            if (state.completed - savingAfter != 1 && target != null) {
                target.error("Save result could not be confirmed. Review the current values.");
            }
            boolean retryable = state.completed - savingAfter == 1 && !state.lastSaveOK
                && source != null && source.revision.equals(state.revision);
            // A confirmed failed save has not consumed this draft. A changed
            // revision or an uncorrelated completion still invalidates it.
            if (!retryable) { changes.clear(); }
            source = state;
            if (state.completed - savingAfter == 1 && !state.lastSaveOK && target != null) { target.error(profile.saveFailure()); }
        }
    }

    private boolean bound(AndroidAutoSettings backend, long generation) {
        return generation == profile.generation() && backend == profile.get()
            && generation == profile.generation();
    }

    /** Draft presentation and field dependencies, separate from transaction guards. */
    private final class Presentation {
        private SettingsRow[] rows(String status, boolean usable) {
            Vector found = new Vector();
            if (status.length() > 0) { found.addElement(new SettingsRow(STATUS, status, "", false)); }
            if (!changes.isEmpty()) {
                found.addElement(new SettingsRow(STORE, "Store (" + changes.size() + ")", "", usable));
            }
            if (group == null) {
                String[] groups = profile.groups(source);
                for (int i = 0; i < groups.length; i++) {
                    found.addElement(new SettingsRow(groups[i], groups[i], "",
                        !saving && (profile.get() != null || WIRELESS.equals(groups[i]))));
                }
            } else {
                for (int i = 0; source != null && i < source.size(); i++) {
                    AndroidAutoSetting setting = source.row(i);
                    if (setting.group.equals(group) && shown(setting.key)) {
                        found.addElement(row(setting, usable && active(setting.key)));
                    }
                }
            }
            SettingsRow[] result = new SettingsRow[found.size()]; found.copyInto(result); return result;
        }

        /** Draft-aware value of another row, or the fallback if the backend has no such row. */
        private int current(String key, int fallback) {
            AndroidAutoSetting setting = find(key);
            return setting == null ? fallback : value(setting);
        }

        /**
         * The CarPlay size and frame-rate fields apply only to custom geometry.
         * Show these rows only while the geometry override is enabled.
         * Keep their stored values while the rows are hidden.
         */
        private boolean shown(String key) {
            if (key.equals("altscreen_width") || key.equals("altscreen_height") || key.equals("altscreen_max_fps")
                    || key.equals("altscreen_width_mm") || key.equals("altscreen_height_mm")) {
                return current("altscreen_override_geometry", 1) != 0;
            }
            return true;
        }

        /** The Maps cluster options do nothing while Cluster view is Off. */
        private boolean active(String key) {
            if (key.equals("altscreen_initial_url") || key.equals("altscreen_suggest_urls")
                    || key.equals("altscreen_cluster_view") || key.equals("altscreen_show_speed_limit")
                    || key.equals("altscreen_show_compass") || key.equals("altscreen_show_eta")
                    || key.equals("altscreen_maneuver_layout")) {
                return current("cluster_view", 1) != 0;
            }
            return true;
        }

        /** The descriptor reflects the draft value and preserves the setting's control kind. */
        private SettingsRow row(AndroidAutoSetting setting, boolean usable) {
            return ProjectionSettingRows.row(setting, value(setting), inherited(setting), usable);
        }
    }
}
