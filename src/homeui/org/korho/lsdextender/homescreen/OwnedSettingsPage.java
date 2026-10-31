/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.homescreen;

import de.vw.mib.desktop.ViewHandlerProvider;
import de.vw.mib.event.HMIEvent;
import de.vw.mib.event.TouchEvent;
import de.vw.mib.viewmanager.internal.ViewHandler;
import de.vw.mib.widgets.models.ScreenAreaModel;
import de.vw.mib.widgets.models.ViewModel;

/** Own provider lifetime. All desktop transitions run on the native framework queue. */
final class OwnedSettingsPage implements SettingsMenuEntry.Page, ViewHandlerProvider, ViewHandler {
    static final String VIEW = "org.korho.lsdextender.settings.page";
    interface Host {
        boolean available();
        void enqueue(Runnable action);
        void enter(String name, ViewHandlerProvider provider);
        void returnToMenu();
    }
    interface Opening {
        void beforeEnter();
        void returned();
        void cancelled();
        void failed();
    }
    interface Tree {
        ViewModel view();
        void create(Runnable back);
        void init();
        void activate();
        void deactivate();
        void deinit();
        void destroy();
        boolean consume(HMIEvent event);
        boolean consume(TouchEvent event);
    }
    private final String name;
    private final Host host;
    private volatile Tree tree;
    private volatile Runnable renewed;
    private Opening opening;
    private int returnGeneration;
    private boolean released = true, renewalQueued;
    private volatile boolean stopped;
    private boolean requested, entered, created, initialized, active;
    private int generation;

    OwnedSettingsPage(String name, Host host, Tree tree) {
        if (name == null || !name.startsWith(VIEW + ".") || host == null || tree == null) { throw new IllegalArgumentException("Owned page dependencies required"); }
        this.name = name;
        this.host = host;
        this.tree = tree;
    }

    public boolean ready() {
        // A tile queries page readiness while holding its own native handler monitor.
        // Volatile readiness inputs avoid acquiring that page monitor in reverse order.
        Tree currentTree = tree;
        return !stopped && renewed == null && host.available()
            && (!(currentTree instanceof NativeTree) || ((NativeTree)currentTree).currentStyles());
    }

    public synchronized void open() { open(null); }

    synchronized void open(Opening nextOpening) {
        if (!ready()) { if (nextOpening != null) { nextOpening.failed(); } return; }
        if (requested || entered) { return; }
        cancelOpening();
        final int token = generation;
        requested = true; opening = nextOpening;
        try {
            if (opening != null) { opening.beforeEnter(); }
            host.enqueue(new Runnable() {
                public void run() { enter(token); }
            });
        } catch (RuntimeException failure) {
            generation++; requested = false; failOpening(); throw failure;
        }
    }
    private void cancelOpening() {
        returnGeneration++;
        Opening previous = opening; opening = null;
        if (previous != null) { previous.cancelled(); }
    }
    private void failOpening() {
        returnGeneration++;
        // Keep cancellation authority while a failed admission restores its own tile.
        Opening previous = opening;
        if (previous != null) { previous.failed(); }
    }

    private synchronized void enter(int token) {
        if (token != generation || stopped || !requested) { return; }
        requested = false;
        if (!ready()) { failOpening(); return; }
        try { host.enter(name, this); entered = true; }
        catch (RuntimeException failure) { failOpening(); throw failure; }
    }

    /** Back and feature retirement both use native content arbitration. */
    public synchronized void close() { cancelOpening(); close(null); }

    private synchronized void back() {
        final Opening callback = opening;
        final int token = returnGeneration;
        close(callback == null ? null : new Runnable() {
            public void run() {
                returnOpening(token, callback);
            }
        });
    }

    private synchronized void returnOpening(int token, Opening callback) {
        if (!stopped && token == returnGeneration) { callback.returned(); }
    }

    private void close(final Runnable callback) {
        generation++;
        requested = false;
        if (!entered) { return; }
        host.enqueue(new Runnable() {
            public void run() { leave(callback); }
        });
    }

    private synchronized void leave(Runnable callback) {
        if (!entered) { return; }
        host.returnToMenu();
        entered = false; scheduleRenewal();
        if (callback != null && !stopped) { host.enqueue(callback); }
    }

    synchronized void shutdown() { stopped = true; renewed = null; close(); }

    public ViewHandler getViewHandler(String name) { return this.name.equals(name) ? this : null; }
    public String getName() { return name; }
    public synchronized ViewModel getView() { return tree.view(); }
    public synchronized void create() {
        if (created) { return; }
        tree.create(new Runnable() { public void run() { back(); } });
        created = true; released = false;
    }
    public synchronized void setup() { released = false; }
    public synchronized void init(boolean reset) {
        if (!created || initialized) { return; }
        tree.init(); initialized = true; released = false;
    }
    public synchronized void activate() {
        if (!initialized || active) { return; }
        tree.activate(); active = true;
    }
    public synchronized void deactivate() {
        if (active) { tree.deactivate(); active = false; }
    }
    public synchronized void deinit() {
        deactivate();
        if (initialized) { tree.deinit(); initialized = false; }
    }
    public synchronized void teardown() {
        // deinit alone also occurs during reInit; only native teardown confirms release.
        if (!initialized && !active) {
            generation++; requested = false; entered = false;
            released = true; scheduleRenewal();
        }
    }
    public synchronized void destroy() {
        deinit();
        if (created) { tree.destroy(); created = false; }
        entered = false; released = true;
    }
    /** Replace only a released owned tree; the native provider and handler identity stay stable. */
    synchronized void refreshStyles(Runnable callback) {
        if (stopped || !(tree instanceof NativeTree) || ((NativeTree)tree).currentStyles()) { return; }
        if (renewed == null) { renewed = callback; close(); }
        scheduleRenewal();
    }
    private void scheduleRenewal() {
        if (renewed == null || renewalQueued || stopped || !released || entered || initialized || active) { return; }
        renewalQueued = true;
        try { host.enqueue(new Runnable() { public void run() { renewTree(); } }); }
        catch (RuntimeException failure) { renewalQueued = false; throw failure; }
    }
    private void renewTree() {
        Runnable callback;
        synchronized (this) {
            renewalQueued = false;
            if (stopped) { return; }
            if (renewed == null || !released || entered || initialized || active) { return; }
            NativeTree fresh = null;
            try {
                fresh = ((NativeTree)tree).replacement();
                fresh.create(new Runnable() { public void run() { back(); } });
                if (!fresh.currentStyles()) { throw new IllegalStateException("Native styles retired during renewal"); }
            } catch (RuntimeException failure) {
                if (fresh != null) { fresh.destroy(); }
                de.vw.mib.timer.TimerManager clock = de.vw.mib.widgets.internal.ServiceManager.timerManager;
                org.korho.lsdextender.Slog.write(" homeui: owned tree renewal deferred: " + failure.getClass().getName()
                    + " mono_ms=" + (clock == null ? "UNKNOWN" : String.valueOf(clock.getSystemTimeMillis()))
                    + " wall_ms=" + System.currentTimeMillis());
                return; // A later valid skin refresh retries; no partial tree becomes visible.
            }
            tree.destroy();
            tree = fresh; created = true;
            callback = renewed; renewed = null;
        }
        callback.run();
    }
    public synchronized boolean consumeEvent(HMIEvent event) { return active && ready() && tree.consume(event); }
    public synchronized boolean consumeEvent(TouchEvent event) { return active && ready() && tree.consume(event); }
    public void reInit(boolean reset) { deinit(); init(reset); }
    public void loseFocus() { deactivate(); }
    public void gainFocus() { activate(); }
    public ScreenAreaModel[] getScreenAreas() { return new ScreenAreaModel[0]; }

    /** Public native services; no provider, factory or stock widget is retained. */
    static final class NativeHost implements Host {
        // Native SimpleGridMenu's Main layer uses the desktop Stage area.
        static final String AREA = "ScreenAreaStage";
        private final de.vw.mib.desktop.DesktopManager desktop;
        private final de.vw.mib.event.dispatcher.FrameworkEventDispatcher dispatcher;
        private final de.vw.mib.app.desktop.ContentManager content;
        private final String returnContent;
        private final java.util.Vector actions = new java.util.Vector();
        private boolean scheduled;
        NativeHost(de.vw.mib.desktop.DesktopManager desktop,
                de.vw.mib.event.dispatcher.FrameworkEventDispatcher dispatcher,
                de.vw.mib.app.desktop.ContentManager content) {
            this(desktop, dispatcher, content, "HMI_GRIDMENU");
        }
        NativeHost(de.vw.mib.desktop.DesktopManager desktop,
                de.vw.mib.event.dispatcher.FrameworkEventDispatcher dispatcher,
                de.vw.mib.app.desktop.ContentManager content, String returnContent) {
            this.desktop = desktop; this.dispatcher = dispatcher; this.content = content;
            this.returnContent = returnContent;
        }
        public boolean available() {
            return "ready".equals(readiness());
        }
        String readiness() {
            if (desktop == null) { return "desktop unavailable"; }
            if (dispatcher == null) { return "dispatcher unavailable"; }
            if (content == null) { return "content manager unavailable"; }
            if (de.vw.mib.desktop.internal.common.ServiceManager.screenAreaTree == null) { return "screen tree unavailable"; }
            de.vw.mib.desktop.internal.screenarea.ScreenAreaNode node =
                de.vw.mib.desktop.internal.common.ServiceManager.screenAreaTree.getScreenAreaNode(AREA);
            if (node == null) { return "MENU node unavailable"; }
            return node.getScreenArea() == null ? "MENU widget unavailable" : "ready";
        }
        public synchronized void enqueue(Runnable action) {
            actions.addElement(action);
            if (!scheduled) {
                scheduled = true;
                try { schedule(); }
                catch (RuntimeException failure) {
                    scheduled = false;
                    actions.removeElement(action);
                    throw failure;
                }
            }
        }
        private void schedule() {
            dispatcher.invoke(new Runnable() { public void run() { runNext(); } });
        }
        private void runNext() {
            Runnable action;
            synchronized (this) {
                action = (Runnable)actions.firstElement();
                actions.removeElementAt(0);
            }
            try { action.run(); }
            finally {
                // Native content updates enqueue their own framework callbacks.
                // This fence must run after those callbacks before another own
                // transition is dispatched. No native queue is drained or blocked.
                try {
                    dispatcher.invoke(new Runnable() { public void run() { continueQueue(); } });
                } catch (RuntimeException failure) {
                    synchronized (this) { scheduled = false; }
                    throw failure;
                }
            }
        }
        private synchronized void continueQueue() {
            if (actions.isEmpty()) { scheduled = false; return; }
            try { schedule(); }
            catch (RuntimeException failure) { scheduled = false; throw failure; }
        }
        public void enter(String name, ViewHandlerProvider provider) {
            desktop.enterView(null, provider, name, new String[] { AREA }, true, -1);
        }
        public void returnToMenu() {
            // ContentManager queues native revoke and grant in order. MENU's
            // ContentProcessor.requestContent is an empty static processor.
            content.updateContent(AREA, "NO_CONTENT");
            content.updateContent(AREA, returnContent);
        }
    }

    /** Newly allocated native controls only, never a clone of a stock tree. */
    interface Controls {
        de.vw.mib.widgets.models.WidgetModel[] layeredChildren(de.vw.mib.widgets.models.WidgetModel[] headers);
        boolean currentStyles();
        void back();
        void init();
        void activate();
        void deactivate();
        void deinit();
        void destroy();
    }

    static class NativeTree implements Tree {
        private static final String TEXT_FONT = "VWTextCarUI-Regular.ttf";
        private final SettingsBackend backend;
        private de.vw.mib.widgets.View root;
        private de.vw.mib.widgets.Button backButton;
        private volatile Controls controls;
        private Runnable backAction;
        private de.vw.mib.widgets.controller.WidgetController backController;
        private final de.vw.mib.widgets.event.OutEventListener backListener =
            new de.vw.mib.widgets.event.OutEventListener() {
                public void outHMIEventFired(String name) {
                    if (("released").equals(name)
                            && backController != null && backAction != null) {
                        if (controls == null) { backAction.run(); } else { controls.back(); }
                    }
                }
                public void outIntegerEventFired(String name, int value) { }
                public void outBooleanEventFired(String name, boolean value) { }
                public void outStringEventFired(String name, String value) { }
                public void outPointEventFired(String name, int x, int y, int z, int w) { }
                public void outListItemEventFired(String name, int a, int b, int c, int d, String text, long value) { }
                public void outSpellerEventFired(String name, String text, int a, int b, int c) { }
            };
        NativeTree(SettingsBackend backend) { this.backend = backend; }
        NativeTree replacement() { return new NativeTree(backend); }
        String title() { return backend == null ? "Project updates" : backend.title; }
        Controls createControls(Runnable back, String font, int width, int height) {
            return backend == null ? (Controls)new OwnedUpdaterControls(back, width, height)
                : (Controls)new OwnedSettingsControls(backend, back, font, width, height);
        }
        public ViewModel view() { return root; }
        public void create(Runnable back) {
            backAction = back;
            ScreenAreaModel area = de.vw.mib.desktop.internal.common.ServiceManager.screenAreaTree
                .getScreenAreaNode(NativeHost.AREA).getScreenArea();
            int width = area.get_width(), height = area.get_height();
            String font = currentTextFont(de.vw.mib.widgets.internal.ServiceManager.fontManager);
            root = new de.vw.mib.widgets.View();
            root.set_layoutAttribs(new de.vw.mib.properties.values.LayoutAttribs());
            root.setChildren(new de.vw.mib.widgets.models.WidgetModel[0]);
            bounds(root, 0, 0, width, height);
            root.set_scalingX(1.0F); root.set_scalingY(1.0F); root.set_scalingZ(1.0F);
            configureProjection(root);
            root.set_viewTypes(new int[] { 6, 7 });
            root.set_colorBackground(de.vw.mib.graphics.Color.BLACK);
            root.setUI(new de.vw.mib.widgets.ui.ViewUI());
            root.setController(new de.vw.mib.widgets.controller.DefaultViewController());
            OwnedSettingsSkin.Context skin = OwnedSettingsSkin.active().context();
            de.vw.mib.widgets.Button button = (de.vw.mib.widgets.Button)skin.control(OwnedSettingsSkin.PAGE_BACK).create();
            backButton = button;
            de.vw.mib.widgets.TextArea title = (de.vw.mib.widgets.TextArea)skin.control(OwnedSettingsSkin.PAGE_TITLE).create();
            title.set_text(title());
            controls = createControls(back, font, width, height);
            root.setChildren(controls.layeredChildren(new de.vw.mib.widgets.models.WidgetModel[] {
                skin.control(OwnedSettingsSkin.PAGE_BACKGROUND).create(), title, button }));
        }
        static String currentTextFont(de.vw.mib.graphics.font.FontManager manager) {
            if (manager == null) { throw new IllegalStateException("Native current font manager unavailable"); }
            try {
                if (manager.getFontFromCurrentSkin(TEXT_FONT) == null) {
                    throw new IllegalStateException("Native current font unavailable");
                }
            } catch (java.io.IOException failure) { throw new IllegalStateException("Native current font unavailable"); }
            // Keep the requested base asset so native locale/linking runs on lookup.
            return TEXT_FONT;
        }
        static void configureProjection(de.vw.mib.widgets.View view) {
            // View uses native perspective type3, including for a flat Stage page.
            view.set_projectionNear(1.0F);
            view.set_projectionFar(10000.0F);
            view.set_projectionFOV(60.0F);
        }
        static de.vw.mib.widgets.Button createBackButton(String font, int width, int height) {
            de.vw.mib.widgets.Button button = new de.vw.mib.widgets.Button();
            button.set_layoutAttribs(new de.vw.mib.properties.values.LayoutAttribs());
            button.setChildren(new de.vw.mib.widgets.models.WidgetModel[0]);
            bounds(button, 0, 0, width, height);
            button.set_interactive(true);
            button.setUI(new de.vw.mib.widgets.ui.ButtonUI());
            button.setController(new de.vw.mib.widgets.controller.DefaultPushButtonController());
            de.vw.mib.widgets.TextArea label = text(font, "Back", 0, 0, width, height);
            label.set_colorBackground(de.vw.mib.graphics.Color.DARK_GRAY);
            button.setChildren(new de.vw.mib.widgets.models.WidgetModel[] { label });
            return button;
        }
        private static void bounds(de.vw.mib.widgets.AbstractWidget widget, int x, int y, int width, int height) {
            widget.set_name(VIEW); widget.set_x(x); widget.set_y(y);
            widget.set_width(width); widget.set_height(height); widget.set_alpha(255);
            widget.set_availableInVariant(true); widget.set_enabled(true); widget.set_visible(true);
            widget.set_touchInsets(new de.vw.mib.graphics.Insets());
        }
        static de.vw.mib.widgets.TextArea text(String font, String value, int x, int y, int width, int height) {
            de.vw.mib.widgets.TextArea text = new de.vw.mib.widgets.TextArea();
            bounds(text, x, y, width, height);
            text.set_font(new de.vw.mib.graphics.font.FontAttribs(font, Math.max(18, height / 3)));
            text.set_baselineOffset(0);
            text.set_padding(new de.vw.mib.graphics.Insets());
            text.set_colorForeground(de.vw.mib.graphics.Color.WHITE);
            text.set_colorBackground(de.vw.mib.graphics.Color.BLACK);
            text.setUI(new de.vw.mib.widgets.ui.TextAreaUI());
            text.set_text(value);
            return text;
        }
        boolean currentStyles() {
            Controls current = controls;
            return current == null || current.currentStyles();
        }
        public void init() {
            root.init(root);
            disarmBack();
            backController = backButton.getController();
            backController.addOutEventListener(backListener);
            if (controls != null) { controls.init(); }
        }
        private void disarmBack() {
            if (backController != null) {
                backController.removeOutEventListener(backListener);
                backController = null;
            }
        }
        public void activate() { root.set_activated(true); root.activate(); if (controls != null) { controls.activate(); } }
        public void deactivate() { if (controls != null) { controls.deactivate(); } root.deActivate(); root.set_activated(false); }
        public void deinit() { if (controls != null) { controls.deinit(); } disarmBack(); root.deInit(); }
        public void destroy() { if (controls != null) { controls.destroy(); controls = null; } disarmBack(); root = null; backButton = null; backAction = null; }
        public boolean consume(HMIEvent event) {
            return OwnedSettingsWidgets.consumeRotation(root, event) || root.consumeHMIEvent(event, null);
        }
        public boolean consume(TouchEvent event) { return root.consumeTouchEvent(event); }
    }

}
