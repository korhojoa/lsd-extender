/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.homescreen;

import java.util.Vector;
import org.korho.lsdextender.ProjectUpdates;
import org.korho.lsdextender.EventClock;
import org.korho.lsdextender.Slog;
import de.vw.mib.widgets.models.WidgetModel;

/** Updater actions and status on independently allocated native controls. */
final class OwnedUpdaterControls implements OwnedSettingsPage.Controls {
    private static final String BACKGROUND_NAME = "SettingsBackground";
    private static final int INVENTORY = 0, FETCH = 1, INSTALL = 2, CANCEL = 3, RESTART = 4;
    private static NativeRestartAction.Action testRestartAction;
    private static boolean restartControlsCreated;
    private final UpdaterRestart restart;
    private final Runnable close;
    private final OwnedSettingsSkin.Context styles;
    private final OwnedSettingsWidgets.ListRows rows;
    private final PackageUpdatesControls packages;
    private final OwnedUpdaterLayout layout;
    private de.vw.mib.widgets.TextArea title;
    private boolean live, destroyed;
    private int generation;
    private String rendered;
    private de.vw.mib.timer.Timer timer;

    /** Private fixtures install their recorder before opening this owned page.
     * Production leaves this unset and uses the native terminal action.
     * Existing controls and their final action are never changed.
     */
    static synchronized void useRestartActionForTest(NativeRestartAction.Action action) {
        if (action == null || restartControlsCreated) {
            throw new IllegalStateException("restart action must be supplied before page creation");
        }
        testRestartAction = action;
    }
    static synchronized NativeRestartAction createRestartAction() {
        restartControlsCreated = true;
        return testRestartAction == null ? NativeRestartAction.resolve()
            : NativeRestartAction.resolve(testRestartAction);
    }
    OwnedUpdaterControls(Runnable close, int width, int height) {
        this(close, width, height, createRestartAction());
    }
    OwnedUpdaterControls(Runnable close, int width, int height, NativeRestartAction action) {
        this(close, width, height, action, PackageUpdatesModel.shared());
    }
    OwnedUpdaterControls(Runnable close, int width, int height, NativeRestartAction action, PackageUpdatesModel model) {
        restart = new UpdaterRestart(action);
        this.close = close;
        styles = OwnedSettingsSkin.active().context();
        rows = new OwnedSettingsWidgets.ListRows(styles, width, height);
        layout = new OwnedUpdaterLayout(styles, new OwnedUpdaterLayout.Action() {
            public void run(int mode, String request) { action(mode, request); }
        });
        packages = new PackageUpdatesControls(model, rows, restart, new PackageUpdatesControls.Owner() {
            public boolean current() { return live && currentStyles(); }
            public void changed() { rendered = null; render(); }
        });
        render();
    }
    public WidgetModel[] layeredChildren(WidgetModel[] headers) {
        title = headerTitle(headers);
        WidgetModel[] result = new WidgetModel[headers.length + 5];
        int at = 0;
        for (int i = 0; i < headers.length; i++) {
            if (BACKGROUND_NAME.equals(headers[i].get_name())) { result[at++] = headers[i]; }
        }
        result[at++] = layout.body; result[at++] = layout.footer;
        result[at++] = rows.list; result[at++] = rows.scrollbar;
        result[at++] = styles.control(OwnedSettingsSkin.PAGE_RULE).create();
        for (int i = 0; i < headers.length; i++) {
            if (!BACKGROUND_NAME.equals(headers[i].get_name())) {
                result[at++] = headers[i];
            }
        }
        return result;
    }
    private static de.vw.mib.widgets.TextArea headerTitle(WidgetModel[] headers) {
        for (int i = 0; i < headers.length; i++) {
            if (headers[i] instanceof de.vw.mib.widgets.TextArea) { return (de.vw.mib.widgets.TextArea)headers[i]; }
        }
        return null;
    }
    public boolean currentStyles() { return !destroyed && styles.current(); }
    public void back() { if (!packages.back()) { close.run(); } }
    public void init() { rows.initialized(rows.list.getView()); layout.initialized(rows.list.getView()); }
    public void activate() {
        if (live || destroyed) { return; }
        live = true; rows.active(true); layout.active(true); rendered = null; render();
        de.vw.mib.timer.TimerManager manager = de.vw.mib.widgets.internal.ServiceManager.timerManager;
        de.vw.mib.event.dispatcher.FrameworkEventDispatcher dispatcher = de.vw.mib.desktop.internal.common.ServiceManager.eventDispatcher;
        if (manager != null && dispatcher != null) {
            final int token = ++generation;
            timer = manager.createTimer("owned updater refresh", 250L, true, new Runnable() {
                public void run() { if (live && token == generation && currentStyles()) { render(); } }
            }, dispatcher); timer.start();
        }
    }
    public void deactivate() {
        live = false; generation++; rows.active(false);
        if (timer != null) { timer.stop(); timer = null; }
        packages.close();
        rows.retire(); layout.retireAction(); layout.active(false); layout.deinitialized();
    }
    public void deinit() { deactivate(); rows.deinitialized(); }
    public void destroy() { deinit(); destroyed = true; }
    private void action(int mode, String request) {
        if (!live || !currentStyles()) { return; }
        if (mode != CANCEL && packages.state().busy) { return; }
        if (mode == INVENTORY) { ProjectUpdates.inspect(); }
        else if (mode == FETCH) { ProjectUpdates.fetch(); }
        else if (mode == INSTALL) { ProjectUpdates.install(); }
        else if (mode == CANCEL) {
            boolean accepted = PackageUpdatesClient.cancel(request);
            Slog.write(" updates: owned cancel " + (accepted ? "requested" : "refused") + " " + EventClock.stamp());
        }
        else if (mode == RESTART) {
            ProjectUpdates.State current = ProjectUpdates.snapshot();
            if (restart.userNow(current.busy, current.action)) {
                Slog.write(" updates: restart requested by explicit user touch " + EventClock.stamp());
            }
        }
        render();
    }
    private void add(Vector result, String key, String text, boolean enabled, int mode) {
        add(result, key, text, enabled, mode, "");
    }
    private void add(Vector result, String key, String text, boolean enabled, final int mode, final String request) {
        result.addElement(rows.row(key, text, "", false, enabled, OwnedSettingsSkin.ROW, new Runnable() {
            public void run() { action(mode, request); }
        }));
    }
    static String versionText(String record) { return UpdaterVersionText.format(record); }
    private int mainAction(ProjectUpdates.State state) {
        if (state.busy) { return CANCEL; }
        if (restart.visible()) { return RESTART; }
        return "ready".equals(state.phase) ? INSTALL : FETCH;
    }
    private static String actionName(int mode) {
        switch (mode) {
            case CANCEL: return "update-cancel";
            case RESTART: return "update-restart";
            case INSTALL: return "update-install";
            default: return "update-check";
        }
    }
    private static String actionLabel(int mode) {
        if (mode == CANCEL) { return OwnedUpdaterLayout.nativeText(5244, "Cancel"); }
        if (mode == RESTART) { return "Now restart"; }
        return mode == INSTALL ? "Install" : "Check";
    }
    private void renderLayout(ProjectUpdates.State state, boolean idle, boolean cancelling, String request) {
        int mode = mainAction(state);
        layout.render(state.busy, cancelling, state.message, state.done, state.total, actionName(mode), actionLabel(mode),
            state.busy ? request.length() != 0 : idle && !ProjectUpdates.admittedInventoryOnly(), mode, request);
        if (state.busy && !PackageUpdatesClient.requestCancelCapable(ProjectUpdates.class) || ProjectUpdates.admittedInventoryOnly()) { layout.footer.set_visible(false); }
        rows.list.set_visible(!state.busy); rows.scrollbar.set_visible(!state.busy); listHeight(449);
    }
    private void renderTitle(boolean busy, String phase) {
        if (title != null) {
            title.set_x(busy ? 112 : 30); title.set_width(busy ? 972 : 1073);
            title.set_text(busy ? OwnedUpdaterLayout.nativeText("checking".equals(phase) ? 4988 : 5252,
                "checking".equals(phase) ? "Searching for software updates..." : "Software update in progress...") : "Project updates");
        }
    }

    private void listHeight(int height) {
        rows.list.set_height(height); rows.scrollbar.set_height(height);
        // Native slider layout does not resize this independently allocated child.
        rows.scrollbar.get_imageBackgroundRef().set_height(height);
    }
    private void render() {
        if (live) { packages.check(); }
        if (packages.showing()) {
            layout.hide(); rows.list.set_visible(true); rows.scrollbar.set_visible(true); listHeight(549);
            renderTitle(false, "");
            String identity = "packages/" + packages.identity();
            if (!identity.equals(rendered)) { rendered = identity; rows.replace(packages.render()); }
            return;
        }
        ProjectUpdates.State state = ProjectUpdates.snapshot();
        PackageUpdatesModel.State packageState = packages.state();
        String request = PackageUpdatesClient.cancellableRequest();
        boolean cancelling = PackageUpdatesClient.cancelling();
        String identity = state.phase + "\n" + state.action + "\n" + state.message + "\n" + state.versions + "\n"
            + state.done + "/" + state.total + "/" + cancelling + "/" + state.busy
            + "/" + state.cancelSupported + "/" + ProjectUpdates.admittedInventoryOnly() + "/" + restart.visible() + "/" + request;
        identity += "/" + packages.identity();
        if (identity.equals(rendered)) { return; }
        rendered = identity;
        // Finish header geometry before row/footer lifecycle invalidates the scene.
        renderTitle(state.busy, state.phase);
        if (restart.refresh(state.busy, state.action)) {
            Slog.write(" updates: restart control " + (restart.visible() ? "shown" : "hidden") + " " + EventClock.stamp());
        }
        Vector result = new Vector();
        boolean idle = !state.busy && !packageState.busy;
        boolean inventoryResult = "inventory".equals(state.phase) && !state.busy && "Installed versions".equals(state.message);
        if (!inventoryResult || packageState.busy) {
            add(result, "update-status", packageState.busy ? packageState.message : state.message, false, 0);
        }

        add(result, "update-inventory", "Installed versions", idle, 0);

        if (!ProjectUpdates.admittedInventoryOnly()) {
            packages.entries(result);
        }
        String versions = state.versions;
        int begin = 0, count = 0;
        while (begin < versions.length() && count < 128) {
            int end = versions.indexOf('|', begin); if (end < 0) { end = versions.length(); }
            String value = versionText(versions.substring(begin, end));
            add(result, "update-version-" + count, value, false, 0);
            begin = end + 1; count++;
        }
        OwnedSettingsWidgets.Row[] next = new OwnedSettingsWidgets.Row[result.size()];
        result.copyInto(next); rows.replace(next);
        // Native list initialization updates its scrollbar visibility and bounds.
        renderLayout(state, idle, cancelling, request);
    }
}
