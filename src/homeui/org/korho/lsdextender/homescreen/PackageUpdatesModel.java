/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.homescreen;

import java.io.IOException;
import java.util.Vector;

/** Owned package selection draft. The worker alone plans and changes files. */
final class PackageUpdatesModel {
    interface Receiver { void reply(String snapshot) throws Exception; }
    interface Transport {
        void run(PackageUpdatesProtocol.Request request, Receiver receiver) throws Exception;
        void abort(String request) throws Exception;
    }
    private static final PackageUpdatesModel INSTANCE = new PackageUpdatesModel(new PackageUpdatesClient());
    private final Transport transport;
    private final Vector desired = new Vector(), removed = new Vector();
    private PackageUpdatesProtocol.Reply reply;
    private String active, message = "Package management has not been checked";
    private boolean supported, busy, uncertain, cancelRequested;
    private long sequence, epoch;
    private String reviewed = "";

    static PackageUpdatesModel shared() { return INSTANCE; }
    PackageUpdatesModel(Transport transport) { this.transport = transport; }
    static final class State {
        final PackageUpdatesProtocol.Reply reply;
        final String message, desired, removed, reviewed, cancellableRequest;
        final boolean supported, busy, uncertain, cancelRequested;
        final long epoch;
        State(PackageUpdatesModel model) {
            reply = model.reply; message = model.message; desired = joined(model.desired); removed = joined(model.removed);
            reviewed = model.reviewed; supported = model.supported; busy = model.busy; uncertain = model.uncertain; epoch = model.epoch;
            cancelRequested = model.cancelRequested;
            cancellableRequest = model.active != null && model.busy && model.reply != null && model.reply.cancelSupported
                && model.active.equals(model.reply.request) ? model.active : "";
        }
    }
    synchronized State snapshot() { return new State(this); }
    synchronized boolean load() { return !busy && start("list"); }
    synchronized boolean select(String id, boolean selected, long expectedEpoch) {
        if (!supported || busy || uncertain || reply == null || expectedEpoch != epoch) { return false; }
        PackageUpdatesProtocol.Item item = item(id);
        if (item == null || selected && (!item.compatible || "-".equals(item.available))
                || !selected && !"-".equals(item.installed) && !item.managed) { return false; }
        desired.removeElement(id); removed.removeElement(id);
        if (selected) { desired.addElement(id); }
        else if (!"-".equals(item.installed)) { removed.addElement(id); }
        reviewed = ""; epoch++; message = "Selection changed; review before installation";
        return true;
    }
    synchronized boolean preview(long expectedEpoch) {
        return supported && !busy && !uncertain && reply != null && expectedEpoch == epoch && start("preview");
    }
    synchronized boolean apply(String expectedPreview, long expectedEpoch) {
        return supported && !busy && !uncertain && reply != null && expectedEpoch == epoch
            && PackageUpdatesProtocol.digest(expectedPreview) && expectedPreview.equals(reviewed) && start("apply");
    }
    synchronized boolean cancel(final String request, long expectedEpoch) {
        State value = snapshot();
        if (expectedEpoch != epoch || cancelRequested || request == null || request.length() == 0
                || !request.equals(value.cancellableRequest)) { return false; }
        cancelRequested = true; epoch++; message = "Cancellation requested; waiting for update result";
        Thread sender = new Thread(new Runnable() {
            public void run() {
                try { transport.abort(request); }
                catch (Exception refused) {
                    synchronized (PackageUpdatesModel.this) {
                        if (busy && request.equals(active)) {
                            cancelRequested = false; epoch++;
                            message = "Cancellation could not be requested; update continues";
                        }
                    }
                }
            }
        }, "owned-package-cancel");
        sender.setDaemon(true); sender.start(); return true;
    }
    synchronized void unreview() { if (!busy) { reviewed = ""; epoch++; } }
    synchronized void discard() {
        if (busy || reply == null) { return; }
        restoreIntent(reply); reviewed = ""; epoch++;
    }
    private void restoreIntent(PackageUpdatesProtocol.Reply value) {
        desired.removeAllElements(); removed.removeAllElements();
        for (int i = 0; i < value.size(); i++) {
            PackageUpdatesProtocol.Item item = value.item(i);
            if (item.desired) { desired.addElement(item.id); }
            if (item.removed) { removed.addElement(item.id); }
        }
    }
    private boolean start(final String operation) {
        final PackageUpdatesProtocol.Request request = new PackageUpdatesProtocol.Request(
            "packages-" + System.currentTimeMillis() + "-" + (++sequence), operation,
            reply == null ? 0 : reply.generation, reply == null ? "" : reply.context,
            "apply".equals(operation) ? reviewed : "", joined(desired), joined(removed));
        active = request.id; busy = true; reviewed = ""; cancelRequested = false; epoch++;
        message = "list".equals(operation) ? "Reading available and installed packages"
            : "preview".equals(operation) ? "Reviewing package selection" : "Applying reviewed package changes";
        Thread worker = new Thread(new Runnable() {
            public void run() { execute(request); }
        }, "owned-package-updates");
        worker.setDaemon(true); worker.start(); return true;
    }
    private void execute(final PackageUpdatesProtocol.Request request) {
        final boolean[] terminal = { false };
        try {
            transport.run(request, new Receiver() {
                public void reply(String raw) throws Exception {
                    PackageUpdatesProtocol.Reply next = PackageUpdatesProtocol.parse(raw, request.id, request.operation);
                    accept(request, next);
                    terminal[0] = next.terminal();
                }
            });
            if (!terminal[0]) { throw new IOException("Package operation has no terminal result"); }
        } catch (Exception failure) {
            synchronized (this) {
                if (request.id.equals(active)) {
                    reviewed = "";
                    if (PackageUpdatesProtocol.APPLY.equals(request.operation)) {
                        uncertain = true;
                        message = "Update outcome is not confirmed; refresh before making changes";
                    } else { message = "Package management unavailable; refresh to try again"; }
                }
            }
        } finally {
            synchronized (this) {
                if (request.id.equals(active)) { active = null; busy = false; cancelRequested = false; epoch++; }
            }
        }
    }
    private synchronized void accept(PackageUpdatesProtocol.Request request, PackageUpdatesProtocol.Reply next) throws IOException {
        if (!request.id.equals(active)) { throw new IOException("Retired package request"); }
        if ("preview".equals(request.operation) && (!next.context.equals(request.context) || next.generation != request.generation)) {
            reviewed = ""; uncertain = true;
            throw new IOException("Package context changed; reload required");
        }
        supported = true; reply = next; message = next.message;
        if (PackageUpdatesProtocol.PREVIEW.equals(next.phase)) { reviewed = next.preview; }
        if ("list".equals(next.phase) || "apply".equals(request.operation) && next.terminal()) {
            restoreIntent(next);
            uncertain = "recovery".equals(next.phase);
        }
        if ("apply".equals(request.operation) && next.terminal()) { reviewed = ""; }
        epoch++;
    }
    private PackageUpdatesProtocol.Item item(String id) {
        for (int i = 0; i < reply.size(); i++) { if (reply.item(i).id.equals(id)) { return reply.item(i); } }
        return null;
    }
    private static String joined(Vector values) {
        Vector sorted = new Vector();
        for (int i = 0; i < values.size(); i++) {
            String value = (String)values.elementAt(i); int at = 0;
            while (at < sorted.size() && ((String)sorted.elementAt(at)).compareTo(value) < 0) { at++; }
            sorted.insertElementAt(value, at);
        }
        StringBuffer text = new StringBuffer();
        for (int i = 0; i < sorted.size(); i++) { if (i > 0) { text.append(','); } text.append((String)sorted.elementAt(i)); }
        return text.toString();
    }
}
