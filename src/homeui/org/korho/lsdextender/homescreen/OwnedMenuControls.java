/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.homescreen;

import de.vw.mib.widgets.models.WidgetModel;

/** The owned HOME menu opens only live child pages. */
final class OwnedMenuControls implements OwnedSettingsPage.Controls {
    interface Actions { boolean available(int index); void open(int index); }
    private static final String[] TITLES = { "Android Auto", "Apple CarPlay", "Games", "Project updates" };
    private final Actions actions;
    private static final String BACKGROUND_NAME = "SettingsBackground";
    private final Runnable close;
    private final OwnedSettingsSkin.Context styles;
    private final OwnedSettingsWidgets.ListRows rows;
    private boolean live, destroyed;
    OwnedMenuControls(Actions actions, Runnable close, int width, int height) {
        this.actions = actions; this.close = close;
        styles = OwnedSettingsSkin.active().context();
        rows = new OwnedSettingsWidgets.ListRows(styles, width, height);
        render();
    }
    private void render() {
        java.util.Vector found = new java.util.Vector();
        for (int i = 0; i < TITLES.length; i++) {
            final int index = i;
            if (actions.available(index)) {
                found.addElement(rows.row("menu-" + i, TITLES[i], "", false, true,
                    OwnedSettingsSkin.ROW, new Runnable() {
                        public void run() {
                            if (live && currentStyles() && actions.available(index)) { actions.open(index); }
                        }
                    }));
            }
        }
        OwnedSettingsWidgets.Row[] items = new OwnedSettingsWidgets.Row[found.size()];
        found.copyInto(items);
        rows.replace(items);
    }
    public WidgetModel[] layeredChildren(WidgetModel[] headers) {
        WidgetModel[] result = new WidgetModel[headers.length + 3];
        int at = 0;
        for (int i = 0; i < headers.length; i++) {
            if (BACKGROUND_NAME.equals(headers[i].get_name())) { result[at++] = headers[i]; }
        }
        result[at++] = rows.list; result[at++] = rows.scrollbar;
        result[at++] = styles.control(OwnedSettingsSkin.PAGE_RULE).create();
        for (int i = 0; i < headers.length; i++) {
            if (!BACKGROUND_NAME.equals(headers[i].get_name())) { result[at++] = headers[i]; }
        }
        return result;
    }
    public boolean currentStyles() { return !destroyed && styles.current(); }
    public void back() { close.run(); }
    public void init() { rows.initialized(rows.list.getView()); }
    public void activate() { live = true; render(); rows.active(true); }
    public void deactivate() { live = false; rows.active(false); rows.retire(); }
    public void deinit() { deactivate(); rows.deinitialized(); }
    public void destroy() { deinit(); destroyed = true; }
}
