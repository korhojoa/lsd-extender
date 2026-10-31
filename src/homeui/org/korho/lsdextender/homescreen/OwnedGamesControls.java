/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.homescreen;

import de.vw.mib.widgets.models.WidgetModel;

/** Installed games use our native rows and the existing script launcher. */
final class OwnedGamesControls implements OwnedSettingsPage.Controls {
    private final GamesMenuEntry games;
    private static final String BACKGROUND_NAME = "SettingsBackground";
    private final Runnable close;
    private final OwnedSettingsSkin.Context styles;
    private final OwnedSettingsWidgets.ListRows rows;
    private boolean live, destroyed;
    OwnedGamesControls(GamesMenuEntry games, Runnable close, int width, int height) {
        this.games = games; this.close = close;
        styles = OwnedSettingsSkin.active().context();
        rows = new OwnedSettingsWidgets.ListRows(styles, width, height);
        render();
    }
    private void render() {
        GamesMenuEntry.Game[] scripts = games.catalog();
        OwnedSettingsWidgets.Row[] items = new OwnedSettingsWidgets.Row[scripts.length];
        for (int i = 0; i < scripts.length; i++) {
            final GamesMenuEntry.Game script = scripts[i];
            items[i] = rows.row("game-" + i, script.title, games.isBusy() ? "Running: " + games.runningTitle() : "", false,
                games.selectable(script.id), OwnedSettingsSkin.ROW, new Runnable() {
                    public void run() {
                        if (live && currentStyles()) { games.launch(script.id); render(); }
                    }
                });
        }
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
