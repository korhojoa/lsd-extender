/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.keys;

import de.vw.mib.desktop.ViewHandlerProvider;
import de.vw.mib.event.HMIEvent;
import de.vw.mib.event.TouchEvent;
import de.vw.mib.viewmanager.internal.ViewHandler;
import de.vw.mib.widgets.View;
import de.vw.mib.widgets.Button;
import de.vw.mib.widgets.TextArea;
import de.vw.mib.widgets.event.ViewChangedListener;
import de.vw.mib.widgets.models.ScreenAreaModel;
import de.vw.mib.widgets.models.ViewModel;
import de.vw.mib.widgets.models.WidgetModel;

/** Own Stage provider, controls and touch authority; never a stock popup. */
final class RestartQuestionView implements ViewHandlerProvider, ViewHandler, RestartPrompt, RestartQuestionController.Authority, ViewChangedListener {
    private final RestartPrompt.Owner owner;
    private final String name;
    private RestartStockState stock;
    private RestartReturn.Previous previous;
    private View root;
    private long ticket;
    private boolean initialized, active, closing;
    private boolean retired = true;
    private int pending;
    private int width, height;
    private RestartQuestionController controller;

    RestartQuestionView(RestartPrompt.Owner source) {
        owner = source;
        name = "org.korho.lsdextender.restart.question." + Integer.toHexString(System.identityHashCode(this));
    }
    public void open(final long value) {
        post(new Runnable() { public void run() { enter(value); } });
    }
    private void enter(long value) {
        if (!owner.current(value) || !canEnter()) { return; }
        try {
            stock = new RestartStockState(de.vw.mib.widgets.internal.ServiceManager.bundleContext);
            if (refuseStock()) { releaseStock(); return; }
            RestartReturn.Previous before = stock.capture();
            if (!owner.current(value)) { stock.release(); stock = null; return; }
            ticket = value; previous = before; closing = false; retired = false;
            RestartStockState.desktop().enterView(null, this, name, new String[] { RestartStockState.AREA }, true, before.distractionGroup);
            owner.log("confirmation-enter-requested width=" + width + " height=" + height + " " + RestartStockState.admission(before));
        } catch (Throwable refused) {
            releaseStock(); owner.cancel("confirmation-open-refused");
        }
    }
    public void close(final String reason) {
        synchronized (this) { if (root == null && previous == null && pending == 0) { return; } }
        post(new Runnable() { public void run() { leave(reason); } });
    }
    private void leave(String reason) {
        if (closing) { return; }
        closing = true; resetTouches();
        if (stock != null && previous != null) {
            try { owner.log(stock.restore(previous, name) ? "previous-view-return-requested" : "newer-stock-view-left-unchanged"); }
            catch (Throwable failed) { owner.log("previous-view-return-unavailable-stock-unchanged"); }
        }
        previous = null;
        releaseStock();
        owner.log("question-closed-" + reason);
    }
    private void releaseStock() { if (stock != null) { stock.release(); stock = null; } }
    boolean canEnter() { return previous == null && retired && !active; }
    public synchronized boolean released() { return retired && previous == null && pending == 0; }
    private void post(final Runnable work) {
        de.vw.mib.event.dispatcher.FrameworkEventDispatcher queue = de.vw.mib.desktop.internal.common.ServiceManager.eventDispatcher;
        if (queue == null) { throw new IllegalStateException("framework queue unavailable"); }
        synchronized (this) { pending++; }
        queue.invoke(new Runnable() {
            public void run() {
                try { work.run(); }
                finally { synchronized (RestartQuestionView.this) { pending--; RestartQuestionView.this.notifyAll(); } }
            }
        });
    }
    public ViewHandler getViewHandler(String requested) { return name.equals(requested) ? this : null; }
    public String getName() { return name; }
    public ViewModel getView() { return root; }
    public ScreenAreaModel[] getScreenAreas() { return new ScreenAreaModel[0]; }
    public void create() {
        if (root != null) { return; }
        ScreenAreaModel area = de.vw.mib.desktop.internal.common.ServiceManager.screenAreaTree
            .getScreenAreaNode(RestartStockState.AREA).getScreenArea();
        width = area.get_width(); height = area.get_height();
        if (width < 320 || height < 160) { throw new IllegalStateException("main screen dimensions unavailable"); }
        String font = "VWTextCarUI-Regular.ttf";
        try {
            if (de.vw.mib.widgets.internal.ServiceManager.fontManager == null
                    || de.vw.mib.widgets.internal.ServiceManager.fontManager.getFontFromCurrentSkin(font) == null) {
                throw new IllegalStateException("current native font unavailable");
            }
        } catch (java.io.IOException unavailable) { throw new IllegalStateException("current native font unavailable"); }
        root = newRoot();
        root.set_layoutAttribs(new de.vw.mib.properties.values.LayoutAttribs());
        bounds(root, 0, 0, width, height);
        root.setChildren(new WidgetModel[0]);
        root.set_scalingX(1.0F); root.set_scalingY(1.0F); root.set_scalingZ(1.0F);
        root.set_projectionNear(1.0F); root.set_projectionFar(10000.0F); root.set_projectionFOV(60.0F);
        root.set_viewTypes(new int[] { 0 }); root.set_colorBackground(de.vw.mib.graphics.Color.BLACK);
        root.setUI(new de.vw.mib.widgets.ui.ViewUI());
        controller = new RestartQuestionController(this);
        root.setController(controller);
        TextArea question = text(font, "Restart the infotainment unit?", width / 10, height / 4, width * 4 / 5, height / 4);
        Button restart = button(font, "Restart", width / 10, height * 3 / 5, true);
        Button cancel = button(font, "Cancel", width * 11 / 20, height * 3 / 5, false);
        root.setChildren(new WidgetModel[] { question, restart, cancel });
    }
    View newRoot() {
        View value = new View();
        value.addViewChangedListener(this);
        return value;
    }
    private Button button(String font, String label, int x, int y, boolean destructive) {
        Button button = new Button();
        button.set_layoutAttribs(new de.vw.mib.properties.values.LayoutAttribs());
        bounds(button, x, y, width * 7 / 20, height / 5);
        button.set_interactive(true);
        button.setUI(new de.vw.mib.widgets.ui.ButtonUI());
        button.setController(new de.vw.mib.widgets.controller.DefaultPushButtonController());
        TextArea caption = text(font, label, 0, 0, width * 7 / 20, height / 5);
        caption.set_alignmentHorizontal((1));
        caption.set_alignmentVertical((1));
        caption.set_colorBackground(destructive
            ? new de.vw.mib.graphics.Color(new de.vw.mib.graphics.Color4b(128, 32, 32))
            : de.vw.mib.graphics.Color.DARK_GRAY);
        button.setChildren(new WidgetModel[] { caption });
        // No OUT_RELEASED listener: only the fresh TouchEvent pair below confirms.
        return button;
    }
    private static void bounds(de.vw.mib.widgets.AbstractWidget widget, int x, int y, int width, int height) {
        widget.set_name("owned-restart-confirmation");
        widget.set_x(x); widget.set_y(y); widget.set_width(width); widget.set_height(height);
        widget.set_alpha(255); widget.set_availableInVariant(true); widget.set_enabled(true); widget.set_visible(true);
        widget.set_touchInsets(new de.vw.mib.graphics.Insets());
    }
    private static TextArea text(String font, String label, int x, int y, int width, int height) {
        TextArea text = new TextArea(); bounds(text, x, y, width, height);
        text.set_font(new de.vw.mib.graphics.font.FontAttribs(font, Math.max(18, height / 4)));
        text.set_padding(new de.vw.mib.graphics.Insets()); text.set_colorForeground(de.vw.mib.graphics.Color.WHITE);
        text.set_colorBackground(de.vw.mib.graphics.Color.BLACK); text.setUI(new de.vw.mib.widgets.ui.TextAreaUI());
        text.set_text(label); return text;
    }
    public synchronized void setup() { retired = false; }
    public void init(boolean reset) { if (root != null && !initialized) { root.init(root); initialized = true; } }
    public void activate() {
        if (!initialized || !owner.current(ticket)) { owner.cancel("activation-revoked"); return; }
        if (refuseStock()) { return; }
        active = true; resetTouches(); root.set_activated(true); root.activate(); owner.activated(ticket);
    }
    private void resetTouches() { if (controller != null) { controller.resetTouch(); } }
    private boolean refuseStock() {
        String reason = stock == null ? "stock-state-unavailable" : stock.refusal();
        if (reason == null) { return false; }
        owner.cancel("skip-" + reason); return true;
    }
    public void deactivate() { active = false; resetTouches(); if (root != null) { root.deActivate(); root.set_activated(false); } }
    public void deinit() { deactivate(); if (initialized) { root.deInit(); initialized = false; } }
    public synchronized void teardown() {
        if (previous != null && !closing) { owner.cancel("native-view-retired"); }
        // Supplied handlers are cached. Teardown, not destruction, ends this use.
        if (!initialized && !active) { retired = true; }
    }
    public void destroy() {
        if (root != null) { root.removeViewChangedListener(this); }
        deinit(); root = null; controller = null;
        synchronized (this) { retired = true; notifyAll(); }
    }
    public void reInit(boolean reset) { deinit(); init(reset); }
    public void loseFocus() { owner.cancel("focus-lost"); deactivate(); }
    public void gainFocus() { if (owner.current(ticket)) { activate(); } }
    public boolean consumeEvent(HMIEvent event) { return false; }
    public boolean consumeEvent(TouchEvent event) {
        return root != null && root.consumeTouchEvent(event);
    }
    public boolean touchAllowed() {
        if (!active || root == null || !owner.current(ticket) || stock == null) { return false; }
        if (refuseStock()) { return false; }
        if (!stock.stillOwn(name)) { owner.cancel("stock-view-changed"); return false; }
        return true;
    }
    public void touched(int activation) {
        if (activation == RestartTouch.RESTART) { owner.confirm(ticket); }
        else if (activation == RestartTouch.CANCEL) { owner.cancel("cancel-touch"); }
    }
    public int hit(int x, int y) {
        if (y < height * 3 / 5 || y >= height * 4 / 5) { return 0; }
        if (x >= width / 10 && x < width * 9 / 20) { return 1; }
        if (x >= width * 11 / 20 && x < width * 9 / 10) { return 2; }
        return 0;
    }
    public void viewCovered() {
        if (!active) { return; }
        active = false; resetTouches();
        owner.cancel("stock-view-covered-question");
    }
    public void viewUncovered() { }
    public void viewInited(View view) { }
    public void viewEntered(View view) { }
    public void viewLeft(View view) { }
}
