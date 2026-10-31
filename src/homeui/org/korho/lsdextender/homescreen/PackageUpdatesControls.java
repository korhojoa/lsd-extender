/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.homescreen;

import java.util.Vector;
import org.korho.lsdextender.ProjectUpdates;

/** Owned native package rows. No backend I/O or transaction executes on input. */
final class PackageUpdatesControls {
    interface Owner { boolean current(); void changed(); }
    private static final int AVAILABLE = 0, INSTALLED = 1, REVIEW_ACTION = 2, APPLY = 3, REFRESH = 4, NOW = 5, CANCEL = 6;
    private static final String RETAINED = "retained";
    private final PackageUpdatesModel model;
    private final OwnedSettingsWidgets.ListRows rows;
    private final UpdaterRestart restart;
    private final Owner owner;
    private boolean visible, installedOnly, checked;
    PackageUpdatesControls(PackageUpdatesModel model, OwnedSettingsWidgets.ListRows rows, UpdaterRestart restart, Owner owner) {
        this.model = model; this.rows = rows; this.restart = restart; this.owner = owner;
    }
    PackageUpdatesModel.State state() { return model.snapshot(); }
    String identity() { return state().epoch + "/" + installedOnly; }
    boolean showing() { return visible; }
    void check() {
        if (!checked && !ProjectUpdates.snapshot().busy && !ProjectUpdates.admittedInventoryOnly()) { checked = model.load(); }
    }
    boolean back() {
        if (!visible) { return false; }
        if (model.snapshot().reviewed.length() > 0) { model.unreview(); }
        else { visible = false; model.discard(); }
        owner.changed(); return true;
    }
    void close() { visible = false; model.discard(); }
    void entries(Vector result) {
        PackageUpdatesModel.State value = state();
        if (!value.supported) { return; }
        button(result, "packages-available", "Available packages", true, AVAILABLE, value);
        button(result, "packages-installed", "Installed packages", true, INSTALLED, value);
    }
    private void button(Vector result, String key, String title, boolean enabled, final int action, final PackageUpdatesModel.State value) {
        result.addElement(rows.row(key, title, "", false, enabled, OwnedSettingsSkin.ROW, new Runnable() {
            public void run() {
                if (!owner.current()) { return; }
                if (action == AVAILABLE || action == INSTALLED) {
                    if (!state().supported) { return; }
                    installedOnly = action == INSTALLED; visible = true; model.unreview();
                } else if (action == REVIEW_ACTION) { model.preview(value.epoch); }
                else if (action == APPLY) { model.apply(value.reviewed, value.epoch); }
                else if (action == REFRESH) { model.load(); }
                else if (action == CANCEL) { model.cancel(value.cancellableRequest, value.epoch); }
                else if (action == NOW && state().epoch == value.epoch && restartDue(state())) { restart.userNow(false, "restart"); }
                owner.changed();
            }
        }));
    }
    private static boolean restartDue(PackageUpdatesModel.State state) {
        return !ProjectUpdates.snapshot().busy && !state.busy && !state.uncertain && state.reply != null && state.reply.restart && state.reply.done > 0
            && PackageUpdatesProtocol.oneOf(state.reply.phase, "installed|cancelled");
    }
    OwnedSettingsWidgets.Row[] render() {
        PackageUpdatesModel.State value = state(); Vector result = new Vector();
        label(result, "package-status", value.message, "");
        if (value.cancellableRequest.length() > 0) {
            button(result, "package-cancel", value.cancelRequested ? "Cancelling..." : "Cancel", !value.cancelRequested, CANCEL, value);
        }
        button(result, "package-tab-available", "Available", true, AVAILABLE, value);
        button(result, "package-tab-installed", "Installed", true, INSTALLED, value);
        if (value.reply != null && value.reply.total > 0) {
            String units = "bytes".equals(value.reply.units) ? "bytes" : "components";
            label(result, "package-progress", "Progress", value.reply.done + " / " + value.reply.total + " " + units);
        }
        if (value.reviewed.length() > 0) { review(result, value); }
        else { catalog(result, value); }
        boolean idle = value.supported && !ProjectUpdates.snapshot().busy && !value.busy && !value.uncertain;
        button(result, "package-review", "Review changes", idle, REVIEW_ACTION, value);
        button(result, "package-refresh", "Refresh packages", !value.busy && !ProjectUpdates.snapshot().busy, REFRESH, value);
        if (restartDue(value)) {
            restart.refresh(false, "restart"); button(result, "package-now", "Now restart", true, NOW, value);
        }
        OwnedSettingsWidgets.Row[] next = new OwnedSettingsWidgets.Row[result.size()]; result.copyInto(next); return next;
    }
    private void catalog(Vector result, final PackageUpdatesModel.State value) {
        if (value.reply == null) { return; }
        for (int i = 0; i < value.reply.size(); i++) {
            final PackageUpdatesProtocol.Item item = value.reply.item(i);
            if (installedOnly && "-".equals(item.installed) || !installedOnly && "-".equals(item.available)) { continue; }
            final boolean selected = PackageUpdatesProtocol.contains(value.desired, item.id)
                || installedOnly && !PackageUpdatesProtocol.contains(value.removed, item.id);
            boolean selectable = selected ? "-".equals(item.installed) || item.managed : item.compatible && !"-".equals(item.available);
            String detail = !item.compatible ? item.reason : installedOnly ? item.installed : item.available;
            result.addElement(rows.row("package-" + item.id, item.title, detail, selected,
                !ProjectUpdates.snapshot().busy && !value.busy && !value.uncertain && selectable, OwnedSettingsSkin.CHECK, new Runnable() {
                    public void run() {
                        if (owner.current() && model.select(item.id, !selected, value.epoch)) { owner.changed(); }
                    }
                }));
        }
    }
    private void review(Vector result, PackageUpdatesModel.State value) {
        for (int i = 0; i < value.reply.changeCount(); i++) {
            PackageUpdatesProtocol.Change change = value.reply.change(i);
            String title = change.id;
            for (int at = 0; at < value.reply.size(); at++) {
                PackageUpdatesProtocol.Item item = value.reply.item(at);
                if (item.id.equals(change.id)) { title = ("dependency".equals(item.role) ? "Required: " : "") + item.title; }
            }
            String action = "install".equals(change.action) ? "Install " : "remove".equals(change.action) ? "Remove " : "Update ";
            label(result, "package-change-" + i, action + title, change.from + " -> " + change.to);
        }
        for (int i = 0; i < value.reply.size(); i++) {
            PackageUpdatesProtocol.Item item = value.reply.item(i);
            if (RETAINED.equals(item.role)) { label(result, "package-kept-" + i, "Kept for installed packages: " + item.title, ""); }
        }
        label(result, "package-download", "Download", bytes(value.reply.download));
        label(result, "package-staging", "Staging space", bytes(value.reply.staging));
        label(result, "package-rollback", "Recovery space", bytes(value.reply.rollback));
        label(result, "package-reserved", "Required free space", bytes(value.reply.reserved));
        if (value.reply.restart) { label(result, "package-disruption", "Restart required after installation", ""); }
        button(result, "package-apply", "Apply reviewed changes", !ProjectUpdates.snapshot().busy && !value.busy && !value.uncertain, APPLY, value);
    }
    private void label(Vector result, String key, String title, String detail) {
        result.addElement(rows.row(key, title, detail, false, false, OwnedSettingsSkin.ROW, new Runnable() {
            public void run() { /* Informational row. */ }
        }));
    }
    private static String bytes(long value) {
        return value < 1024 ? value + " B" : (value / 1024 + (value % 1024 == 0 ? 0 : 1)) + " KiB";
    }
}
