/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.homescreen;

import org.korho.lsdextender.AndroidAutoSetting;
import org.korho.lsdextender.AndroidAutoSettings;
import org.korho.lsdextender.AndroidAutoSettingsState;
import java.util.Vector;
import de.vw.mib.widgets.TextArea;
import de.vw.mib.widgets.models.WidgetModel;
import de.vw.mib.widgets.controller.SpellerController;
import de.vw.mib.widgets.event.OutEventListener;

/** Native owned lists, checkbox state and a native numeric Speller, with model-owned drafts. */
final class OwnedSettingsControls implements ProjectionBehaviorPanel.Display, OwnedSettingsPage.Controls {
    private static final String DEFAULT = "#default";
    private static final String BACKGROUND_NAME = "SettingsBackground";
    private final SettingsBackend backend;
    private WidgetModel[] ownHeaders = new WidgetModel[0];
    private final ProjectionBehaviorPanel panel;
    private final Presentation presentation = new Presentation();
    private final OwnedSettingsSkin.Context styles;
    private final OwnedSettingsWidgets.ListRows listRows;
    private final OwnedSettingsWidgets.NumberEditor editor;
    private final WidgetModel pageRule;
    private final OutEventListener numericEvents;
    private AndroidAutoSettings binding;
    private long backendGeneration;
    private String revision = "", title = "", failure = "", selectedKey;
    private SettingsRow[] descriptors = new SettingsRow[0], rendered;
    private String renderedContext;
    private SettingsRow choice;
    private AndroidAutoSetting editing;
    private SettingsNumberBuffer number;
    private boolean armed, live, numericShown;
    private volatile boolean destroyed;
    private int generation;
    private de.vw.mib.timer.Timer timer;

    OwnedSettingsControls(SettingsBackend backend, Runnable close, String font, int width, int height) {
        this.backend = backend;
        OwnedSettingsSkin skin = OwnedSettingsSkin.active();
        if (skin == null) { throw new IllegalStateException("Native skin resources unavailable"); }
        styles = skin.context();
        pageRule = styles.control(OwnedSettingsSkin.PAGE_RULE).create();
        panel = new ProjectionBehaviorPanel(close, backend); panel.display(this);
        listRows = new OwnedSettingsWidgets.ListRows(styles, width, height);
        editor = new OwnedSettingsWidgets.NumberEditor(styles);
        numericEvents = new OutEventListener() {
            public void outIntegerEventFired(String name, int value) { }
            public void outBooleanEventFired(String name, boolean value) { }
            public void outStringEventFired(String name, String value) { }
            public void outPointEventFired(String name, int x, int y, int z, int w) { }
            public void outListItemEventFired(String name, int a, int b, int c, int d, String text, long value) { }
            public void outHMIEventFired(String name) { }
            public void outSpellerEventFired(String name, String text, int cursor, int start, int end) {
                if (!allowed() || editing == null || number == null) { return; }
                number.position(cursor, start, end);
                if (("keyPressed").equals(name)) { number.insert(text); }
                else if (("deleted").equals(name)) { number.delete(false); }
                else if (("longDeleted").equals(name)) { number.delete(true); }
                else if (("okPressed").equals(name)) { applyNumber(); return; }
                updateNumber();
            }
        };
        render();
    }
    WidgetModel[] children() {
        return new WidgetModel[] { listRows.list, editor.frame, editor.backdrop, editor.well, editor.speller, listRows.scrollbar, pageRule };
    }
    public WidgetModel[] layeredChildren(WidgetModel[] headers) {
        ownHeaders = new WidgetModel[headers.length]; System.arraycopy(headers, 0, ownHeaders, 0, headers.length);
        WidgetModel[] content = children(), result = new WidgetModel[content.length + headers.length];
        int at = 0;
        for (int i = 0; i < headers.length; i++) {
            if (BACKGROUND_NAME.equals(headers[i].get_name())) { result[at++] = headers[i]; }
        }
        System.arraycopy(content, 0, result, at, content.length); at += content.length;
        for (int i = 0; i < headers.length; i++) {
            if (!BACKGROUND_NAME.equals(headers[i].get_name())) { result[at++] = headers[i]; }
        }
        return result;
    }
    public boolean currentStyles() { return !destroyed && styles.current(); }
    public void init() {
        if (destroyed) { return; }
        disarm();
        editor.speller.getController().addOutEventListener(numericEvents);
        listRows.initialized(listRows.list.getView()); armed = true;
    }
    public void activate() {
        if (destroyed || live) { return; }
        live = true; listRows.active(true); rememberBinding(); clearEditor(); selectedKey = null; failure = ""; rendered = null;
        panel.open();
        de.vw.mib.timer.TimerManager manager = de.vw.mib.widgets.internal.ServiceManager.timerManager;
        de.vw.mib.event.dispatcher.FrameworkEventDispatcher dispatcher = de.vw.mib.desktop.internal.common.ServiceManager.eventDispatcher;
        if (manager != null && dispatcher != null) {
            final int token = ++generation;
            timer = manager.createTimer("owned settings refresh", 250L, true, new Runnable() {
                public void run() { if (live && token == generation && !destroyed) { refresh(); } }
            }, dispatcher); timer.start();
        }
    }
    private boolean currentBinding() {
        AndroidAutoSettings owner = backend.get();
        AndroidAutoSettingsState state = owner == null ? null : owner.snapshot();
        return owner == binding && backendGeneration == backend.generation() && revision.equals(state == null ? "" : state.revision);
    }
    private void rememberBinding() {
        backendGeneration = backend.generation(); binding = backend.get();
        AndroidAutoSettingsState state = binding == null ? null : binding.snapshot();
        revision = state == null ? "" : state.revision;
    }
    private boolean allowed() {
        if (!armed || !live || destroyed) { return false; }
        if (!styles.current()) { deactivate(); return false; }
        if (!currentBinding()) { refresh(); return false; }
        return true;
    }
    void refresh() {
        if (!live || destroyed) { return; }
        if (!styles.current()) { deactivate(); return; }
        if (!currentBinding()) { clearEditor(); selectedKey = null; failure = "Settings changed; review current values"; }
        rememberBinding(); panel.refresh();
    }
    private void clearEditor() { editing = null; number = null; choice = null; }
    public void deactivate() {
        live = false; generation++; listRows.active(false);
        if (timer != null) { timer.stop(); timer = null; }
        panel.hide(); clearEditor(); selectedKey = null; binding = null; revision = "";
        listRows.retire();
    }
    public void deinit() { deactivate(); disarm(); listRows.deinitialized(); }
    public void destroy() { deinit(); destroyed = true; panel.display(null); }
    private void disarm() {
        armed = false;
        editor.speller.getController().removeOutEventListener(numericEvents);
    }
    public void back() {
        if (!live) { return; }
        if (editing != null || choice != null) { clearEditor(); failure = ""; render(); }
        else { selectedKey = null; panel.back(); }
    }
    public void rows(String name, SettingsRow[] value) {
        if (!title.equals(name)) { clearEditor(); selectedKey = null; }
        if (choice != null) {
            SettingsRow current = null;
            for (int i = 0; i < value.length; i++) { if (choice.key.equals(value[i].key)) { current = value[i]; } }
            choice = current;
        }
        title = name; descriptors = value; render();
    }
    public void edit(AndroidAutoSetting setting, int value) {
        editing = setting; number = new SettingsNumberBuffer(value); failure = ""; render();
    }
    public void error(String value) { failure = value; render(); }
    private boolean canChange(String key) {
        if (key == null || !currentBinding()) { return false; }
        AndroidAutoSettings owner = backend.get();
        AndroidAutoSettingsState state = owner == null ? null : owner.snapshot();
        if (state == null || !state.ready || state.busy) { return false; }
        for (int i = 0; i < state.size(); i++) { if (key.equals(state.row(i).key)) { return !state.row(i).readOnly; } }
        return false;
    }
    private void reset() {
        String key = editing == null ? selectedKey : editing.key;
        if (canChange(key) && panel.choose(key, ProjectionBehaviorPanel.INHERIT)) { clearEditor(); failure = ""; render(); }
    }
    private void updateNumber() { failure = ""; render(); }
    private void applyNumber() {
        if (!canChange(editing.key)) { failure = "Setting unavailable or busy; value retained"; render(); return; }
        try {
            int value = number.value(editing.minimum, editing.maximum, "main_dpi".equals(editing.key));
            if (panel.change(editing.key, String.valueOf(value))) { clearEditor(); failure = ""; }
            else { failure = "Setting changed or busy; value retained"; }
        } catch (NumberFormatException invalid) { failure = "Enter a whole number in range"; }
        render();
    }
    private boolean sameRows(SettingsRow[] next, String context) {
        if (rendered == null || !context.equals(renderedContext) || rendered.length != next.length) { return false; }
        for (int i = 0; i < next.length; i++) { if (!next[i].same(rendered[i])) { return false; } }
        return true;
    }
    private void render() { presentation.draw(); }
    /** Layout and native-row presentation do not own draft or backend state. */
    private final class Presentation {
        void draw() {
        if (!styles.current()) { return; }
        boolean numeric = editing != null;
        layout(numeric);
        if (numeric != numericShown) {
            listRows.list.invalidateList((64));
            if (numeric) { listRows.scrollbar.set_visible(false); }
            else { listRows.scrollbar.adjustmentValueChanged(listRows.list, 0); }
        }
        numericShown = numeric;
        pageRule.set_visible(true);
        listRows.list.set_visible(!numeric); editor.speller.set_visible(numeric); editor.input.set_visible(numeric);
        editor.frame.set_visible(numeric); editor.backdrop.set_visible(numeric); editor.well.set_visible(numeric);
        if (numeric) {
            editor.update(number);
            boolean valid;
            try { number.value(editing.minimum, editing.maximum, "main_dpi".equals(editing.key)); valid = true; }
            catch (NumberFormatException invalid) { valid = false; }
            editor.speller.set_okEnabled(valid && canChange(editing.key));
            return;
        }
        showRows();
    }
    private void showRows() {
        SettingsRow[] next = displayedRows();
        String context = backendGeneration + "/" + revision + "/" + title + "/" + (choice == null ? "rows" : choice.key + "/" + choice.value + "/" + choice.inherited);
        if (sameRows(next, context)) { return; }
        rendered = next; renderedContext = context;
        OwnedSettingsWidgets.Row[] nativeRows = new OwnedSettingsWidgets.Row[next.length];
        for (int i = 0; i < next.length; i++) {
            final SettingsRow row = next[i];
            final SettingsRow parentChoice = choice;
            String role = styleRole(row, parentChoice);
            boolean checked = checked(row, parentChoice);
            nativeRows[i] = listRows.row(row.key, row.label, row.value, checked,
                row.enabled, role, new Runnable() { public void run() { if (allowed()) { select(row, parentChoice); } } });
        }
        listRows.replace(nativeRows);
    }
    private void layout(boolean numeric) {
        for (int i = 0; i < ownHeaders.length; i++) {
            ownHeaders[i].set_visible(true);
            if (ownHeaders[i] instanceof TextArea) {
                ((TextArea)ownHeaders[i]).set_text(numeric ? editing.label : choice == null ? title : choice.label);
            }
        }
    }
    private SettingsRow[] displayedRows() {
        Vector shown = new Vector();
        if (failure.length() > 0) { shown.addElement(new SettingsRow("#failure", failure, "", false)); }
        for (int i = 0; i < descriptors.length; i++) {
            SettingsRow row = descriptors[i];
            if (!ProjectionBehaviorPanel.STATUS.equals(row.key)) { shown.addElement(row); }
        }
        if (canChange(selectedKey)) { shown.addElement(new SettingsRow(DEFAULT, "Default", "", true)); }
        SettingsRow[] next;
        if (choice == null) { next = new SettingsRow[shown.size()]; shown.copyInto(next); }
        else {
            next = new SettingsRow[choice.choices.length];
            for (int i = 0; i < next.length; i++) { next[i] = new SettingsRow(choice.choiceValues[i], choice.choices[i], "", choice.enabled); }
        }
        return next;
    }
    private String styleRole(SettingsRow row, SettingsRow parentChoice) {
        if (parentChoice != null) { return OwnedSettingsSkin.CHECK; }
        if (row.choices != null) { return OwnedSettingsSkin.CHOICE; }
        return row.toggle ? OwnedSettingsSkin.CHECK : OwnedSettingsSkin.ROW;
    }
    private boolean checked(SettingsRow row, SettingsRow parentChoice) {
        if (parentChoice == null) { return row.toggle && row.selected; }
        return parentChoice.inherited ? ProjectionBehaviorPanel.INHERIT.equals(row.key) : row.label.equals(parentChoice.value);
    }
    }
    private void select(SettingsRow row, SettingsRow parentChoice) {
        failure = "";
        if (DEFAULT.equals(row.key)) { reset(); }
        else if (parentChoice != null) {
            if (!canChange(parentChoice.key)) { return; }
            selectedKey = parentChoice.key; choice = null; panel.choose(parentChoice.key, row.key); render();
        } else if (row.toggle) {
            selectedKey = row.key; panel.toggle(row.key); render();
        } else if (row.choices != null) {
            selectedKey = row.key;
            if (row.choices.length == 2 && ProjectionBehaviorPanel.EDIT.equals(row.choiceValues[0])) { panel.choose(row.key, ProjectionBehaviorPanel.EDIT); }
            else { choice = row; render(); }
        } else { selectedKey = null; panel.select(row.key); }
    }
}
