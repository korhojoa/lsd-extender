/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.homescreen;

import de.vw.mib.desktop.ViewHandlerProvider;
import de.vw.mib.desktop.internal.screenarea.ScreenAreaNode;
import de.vw.mib.event.HMIEvent;
import de.vw.mib.event.TouchEvent;
import de.vw.mib.widgets.models.ScreenAreaModel;
import de.vw.mib.widgets.models.ViewModel;
import de.vw.mib.widgets.models.WidgetModel;

/** Our provider enters only the tile area handed to our bind action. */
final class SmallTileContent implements OwnedSmallTile.Content {
    interface Backing {
        boolean live(int profile);
    }
    static final Backing NO_OPTIONAL_PAGES = new Backing() {
        public boolean live(int profile) { return false; }
    };
    interface Surface {
        ScreenAreaModel area(String area);
        void enter(String name, ViewHandlerProvider provider, String area);
        void leave(String name, String area);
        boolean owns(String name, ViewHandlerProvider provider, String area);
        OwnedSettingsPage.Tree tree(String area);
    }
    private final Backing backing;
    private final SettingsMenuEntry.Page[] pages;
    private SettingsMenuEntry.Page gamePage;
    private static final int GAMES_PROFILE = 2;
    private static final int UPDATER_PROFILE = 3;
    private GamesMenuEntry gamesBacking;
    private SettingsMenuEntry.Page updaterPage;
    private SettingsMenuEntry.Gate updaterBacking;
    private OwnedSettingsPage menu;
    private final OwnedSettingsPage.Host queue;
    private final Surface surface;
    private final java.util.Vector sessions = new java.util.Vector();
    private volatile TileHost binding;
    private volatile boolean created, stopped;
    private volatile TileOpening pendingRestore;
    private int bindingGeneration;
    SmallTileContent(Backing backing, SettingsMenuEntry.Page[] pages, OwnedSettingsPage.Host queue,
            final de.vw.mib.desktop.DesktopManager desktop) {
        this.backing = backing; this.pages = pages; this.queue = queue;
        surface = new Surface() {
            public ScreenAreaModel area(String name) { return screenArea(name); }
            public void enter(String name, ViewHandlerProvider provider, String area) {
                desktop.enterView(null, provider, name, new String[] { "ScreenAreaStage", area }, true, -1);
            }
            public void leave(String name, String area) {
                desktop.leaveView(name, new String[] { "ScreenAreaStage", area }, -1, true);
            }
            public boolean owns(String name, ViewHandlerProvider provider, String area) {
                de.vw.mib.desktop.internal.screenarea.ScreenTree tree =
                    de.vw.mib.desktop.internal.common.ServiceManager.screenAreaTree;
                ScreenAreaNode node = tree == null ? null : tree.getScreenAreaNode("ScreenAreaStage." + area);
                if (node == null || node.getChildren().isEmpty()) { return false; }
                java.util.List children = node.getChildren();
                de.vw.mib.desktop.internal.screenarea.ViewNode child =
                    (de.vw.mib.desktop.internal.screenarea.ViewNode)children.get(children.size() - 1);
                return name.equals(child.getViewHandler().getName())
                    && child.getViewHandler().getView() == provider.getViewHandler(name).getView(); // NOPMD CompareObjectsWithEquals - Exact owned model identity.
            }
            public OwnedSettingsPage.Tree tree(String area) {
                return OwnedSettingsPage.NativeHost.AREA.equals(area) ? (OwnedSettingsPage.Tree)new MenuTree() : new TileTree(area);
            }
        };
    }
    SmallTileContent(Backing backing, SettingsMenuEntry.Page[] pages, OwnedSettingsPage.Host queue, Surface surface) {
        this.backing = backing; this.pages = pages; this.queue = queue; this.surface = surface;
    }
    void games(SettingsMenuEntry.Page page, GamesMenuEntry backing) { gamePage = page; gamesBacking = backing; }
    void updater(SettingsMenuEntry.Page page, SettingsMenuEntry.Gate backing) { updaterPage = page; updaterBacking = backing; }
    public boolean ready() { return !stopped && (available(0) || available(1) || available(GAMES_PROFILE) || available(UPDATER_PROFILE)); }
    private boolean available(int profile) {
        if (profile == GAMES_PROFILE) { return gamesBacking != null && gamesBacking.live() && gamePage != null && gamePage.ready(); }
        if (profile == UPDATER_PROFILE) { return updaterBacking != null && updaterBacking.live() && updaterPage != null && updaterPage.ready(); }
        return backing.live(profile) && pages[profile] != null && pages[profile].ready();
    }
    public synchronized void create() { if (!stopped) { created = true; } }
    public synchronized void bind(String nextArea) {
        if (!created || stopped) { return; }
        if (binding != null && !binding.area.equals(nextArea)) {
            // Native HomeScreen normally disposes before moving an action to another slot.
            throw new IllegalStateException("Owned tile must be disposed before rebinding to another area");
        }
        bindingGeneration++;
        if (binding == null) { binding = new TileHost(nextArea); sessions.addElement(binding); }
        final TileHost next = binding;
        final int token = bindingGeneration;
        // Retire only our previous entry. The selector's native release can
        // arrive on either side of BindHostedApp; the normal queue fence puts
        // that release before our new admission, without leaving the selector.
        next.tile.close();
        TileOpening restore = new TileOpening(next);
        restore.token = token;
        pendingRestore = restore;
        recover();
    }
    public synchronized void dispose() {
        created = false; bindingGeneration++; pendingRestore = null;
        TileHost previous = binding; binding = null;
        if (previous != null) { previous.tile.close(); }
    }
    private final class MenuTree extends OwnedSettingsPage.NativeTree {
        MenuTree() { super(null); }
        String title() { return "Extender menu"; }
        OwnedSettingsPage.Controls createControls(Runnable back, String font, int width, int height) {
            return new OwnedMenuControls(new OwnedMenuControls.Actions() {
                public boolean available(int index) { return SmallTileContent.this.available(index); }
                public void open(int index) { openChild(index); }
            }, back, width, height);
        }
        OwnedSettingsPage.NativeTree replacement() { return new MenuTree(); }
    }
    public void open() {
        TileOpening next;
        synchronized (this) {
            recover();
            if (!created || !ready()) { return; }
            if (menu == null) {
                menu = new OwnedSettingsPage(OwnedViewNames.allocate(OwnedSettingsPage.VIEW + ".menu"), queue, surface.tree(OwnedSettingsPage.NativeHost.AREA));
            }
            next = binding == null ? null : new TileOpening(binding);
        }
        menu.open(next);
    }
    private void openChild(int index) {
        if (!available(index) || menu == null) { return; }
        SettingsMenuEntry.Page page = index == UPDATER_PROFILE ? updaterPage : index == GAMES_PROFILE ? gamePage : pages[index];
        menu.close();
        if (page instanceof OwnedSettingsPage) {
            ((OwnedSettingsPage)page).open(new OwnedSettingsPage.Opening() {
                public void beforeEnter() { }
                public void returned() { open(); }
                public void cancelled() { }
                public void failed() { open(); }
            });
        } else { page.open(); }
    }
    private boolean current(TileHost previous, int token) {
        return binding == previous && token == bindingGeneration && created && !stopped; // NOPMD CompareObjectsWithEquals - Exact owned binding.
    }
    private final class TileOpening implements OwnedSettingsPage.Opening {
        private final TileHost previous;
        private int token;
        private boolean restoreQueued;
        private volatile boolean admitted;
        TileOpening(TileHost previous) { this.previous = previous; }
        public void beforeEnter() {
            synchronized (SmallTileContent.this) {
                if (binding != previous || !created || stopped) { throw new IllegalStateException("Owned binding changed before page admission"); } // NOPMD CompareObjectsWithEquals - Exact owned binding.
                pendingRestore = null; token = ++bindingGeneration;
                previous.tile.close();
                decision("owned tile suspended for page");
            }
        }
        public void returned() {
            synchronized (SmallTileContent.this) {
                if (!current(previous, token)) { decision("return cancelled by later binding or disposal"); return; }
                if (!ready()) { decision("return cancelled by unavailable backing"); return; }
                previous.tile.open();
                decision("owned tile resume queued");
            }
        }
        public void cancelled() {
            synchronized (SmallTileContent.this) {
                if (!current(previous, token)) { return; }
                bindingGeneration++; pendingRestore = null;
                previous.tile.close();
                decision("owned return cancelled");
            }
        }
        public void failed() {
            synchronized (SmallTileContent.this) {
                if (!current(previous, token)) { return; }
                pendingRestore = this;
                decision("page admission failed; current owned tile restore pending");
                recover();
            }
        }
    }
    synchronized void recover() {
        final TileOpening restore = pendingRestore;
        if (restore == null) { return; }
        if (restore.admitted) { pendingRestore = null; return; }
        if (restore.restoreQueued) { return; }
        if (!current(restore.previous, restore.token)) { pendingRestore = null; return; }
        if (!ready()) { return; }
        try {
            restore.restoreQueued = true;
            queue.enqueue(new Runnable() {
                public void run() {
                    synchronized (SmallTileContent.this) {
                        if (pendingRestore != restore || !current(restore.previous, restore.token) || !ready()) { // NOPMD CompareObjectsWithEquals - Exact owned recovery request.
                            restore.restoreQueued = false;
                            return;
                        }
                        restore.previous.tile.open(new OwnedSettingsPage.Opening() {
                            public void beforeEnter() { }
                            public void returned() { }
                            public void cancelled() { failed(); }
                            public void failed() {
                                synchronized (SmallTileContent.this) { restore.restoreQueued = false; }
                            }
                        });
                    }
                }
            });
        } catch (RuntimeException failure) {
            restore.restoreQueued = false;
            decision("owned restore deferred until native queue recovers");
        }
    }
    private static void decision(String reason) {
        de.vw.mib.timer.TimerManager clock = de.vw.mib.widgets.internal.ServiceManager.timerManager;
        org.korho.lsdextender.Slog.write(" homeui tile lifecycle=" + reason
            + " mono_ms=" + (clock == null ? "UNKNOWN" : String.valueOf(clock.getSystemTimeMillis()))
            + " wall_ms=" + System.currentTimeMillis());
    }
    synchronized void shutdown() {
        stopped = true;
        if (menu != null) { menu.shutdown(); }
        dispose();
        for (int i = 0; i < sessions.size(); i++) { ((TileHost)sessions.elementAt(i)).tile.shutdown(); }
    }
    private ScreenAreaModel screenArea(String area) {
        de.vw.mib.desktop.internal.screenarea.ScreenTree tree =
            de.vw.mib.desktop.internal.common.ServiceManager.screenAreaTree;
        ScreenAreaNode node = tree == null || area == null ? null : tree.getScreenAreaNode("ScreenAreaStage." + area);
        return node == null ? null : node.getScreenArea();
    }
    private final class TileHost implements OwnedSettingsPage.Host {
        private final String area;
        private final OwnedSettingsPage tile;
        TileHost(String area) {
            this.area = area;
            tile = new OwnedSettingsPage(OwnedViewNames.allocate(OwnedSettingsPage.VIEW + ".smallTile." + sessions.size()), this, surface.tree(area));
        }
        public boolean available() { return binding == this && created && ready() && surface.area(area) != null; }
        public void enqueue(Runnable action) { queue.enqueue(action); }
        public void enter(String name, ViewHandlerProvider provider) {
            if (!available()) { throw new IllegalStateException("Owned tile unavailable at entry"); }
            TileOpening restore = pendingRestore;
            surface.enter(name, provider, area);
            // Entry holds the page monitor. Do not take the content monitor
            // in reverse order; the next recovery pass retires this marker.
            if (restore != null && restore.previous == this) { restore.admitted = true; } // NOPMD CompareObjectsWithEquals - Exact owned tile admission.
        }
        public void returnToMenu() {
            // Deactivation does not prove native removal. Leave only our exact
            // current handler/model, including a retained deactivated view.
            if (surface.owns(tile.getName(), tile, area)) {
                surface.leave(tile.getName(), area);
            }
        }
    }
    private final class TileTree implements OwnedSettingsPage.Tree {
        private de.vw.mib.widgets.View root;
        private de.vw.mib.widgets.Button button;
        private de.vw.mib.widgets.controller.WidgetController controller;
        private final String area;
        TileTree(String area) { this.area = area; }
        private final de.vw.mib.widgets.event.OutEventListener listener = new de.vw.mib.widgets.event.OutEventListener() {
            public void outHMIEventFired(String name) {
                if (("released").equals(name)) {
                    // Native input holds the tile monitor. Enter the page after releasing it.
                    queue.enqueue(new Runnable() { public void run() { open(); } });
                }
            }
            public void outIntegerEventFired(String name, int value) { }
            public void outBooleanEventFired(String name, boolean value) { }
            public void outStringEventFired(String name, String value) { }
            public void outPointEventFired(String name, int x, int y, int z, int w) { }
            public void outListItemEventFired(String name, int a, int b, int c, int d, String text, long value) { }
            public void outSpellerEventFired(String name, String text, int a, int b, int c) { }
        };
        public ViewModel view() { return root; }
        public void create(Runnable back) {
            ScreenAreaModel target = screenArea(area);
            if (target == null) { throw new IllegalStateException("Owned tile area unavailable"); }
            int width = target.get_width(), height = target.get_height();
            String font = OwnedSettingsPage.NativeTree.currentTextFont(de.vw.mib.widgets.internal.ServiceManager.fontManager);
            root = new de.vw.mib.widgets.View();
            initialize(root, width, height);
            root.set_scalingX(1.0F); root.set_scalingY(1.0F); root.set_scalingZ(1.0F);
            OwnedSettingsPage.NativeTree.configureProjection(root);
            root.set_viewTypes(new int[] { 0 });
            // The host supplies the headline; our content supplies its own native body frame.
            root.set_colorBackground(new de.vw.mib.graphics.Color(0));
            root.setUI(new de.vw.mib.widgets.ui.ViewUI());
            root.setController(new de.vw.mib.widgets.controller.DefaultViewController());
            button = new de.vw.mib.widgets.Button(); initialize(button, width, height);
            // Reserve the first 56 pixels for the tile header before placing the body.
            button.set_y(56); button.set_height(height - 56);
            button.set_interactive(true);
            button.setUI(new de.vw.mib.widgets.ui.ButtonUI());
            button.setController(new de.vw.mib.widgets.controller.DefaultPushButtonController());
            de.vw.mib.widgets.TextArea text = OwnedSettingsPage.NativeTree.text(font, OwnedSmallTile.LABEL, 0, 0, width, height - 56);
            text.set_colorBackground(new de.vw.mib.graphics.Color(0));
            // A small tile is not a full Stage heading; keep the complete caption in its own bounds.
            text.set_font(new de.vw.mib.graphics.font.FontAttribs(font, Math.max(18, Math.min(32, width / 16))));
            text.set_baselineOffset(0);
            text.set_alignmentHorizontal(1); text.set_alignmentVertical(1);
            OwnedSettingsSkin skin = OwnedSettingsSkin.active();
            if (skin == null) { throw new IllegalStateException("Native tile skin unavailable"); }
            button.setChildren(new WidgetModel[] { text });
            root.setChildren(new WidgetModel[] { skin.tileFrame(), button });
        }
        private void initialize(de.vw.mib.widgets.Container widget, int width, int height) {
            widget.set_name(OwnedSettingsPage.VIEW + ".smallTile");
            widget.set_layoutAttribs(new de.vw.mib.properties.values.LayoutAttribs());
            widget.setChildren(new WidgetModel[0]);
            widget.set_width(width); widget.set_height(height); widget.set_alpha(255);
            widget.set_visible(true); widget.set_availableInVariant(true); widget.set_enabled(true);
            widget.set_touchInsets(new de.vw.mib.graphics.Insets());
        }
        public void init() {
            root.init(root); controller = button.getController(); controller.addOutEventListener(listener);
        }
        public void activate() { root.set_activated(true); root.activate(); }
        public void deactivate() { root.deActivate(); root.set_activated(false); }
        public void deinit() {
            if (controller != null) { controller.removeOutEventListener(listener); controller = null; }
            root.deInit();
        }
        public void destroy() { root = null; button = null; controller = null; }
        public boolean consume(HMIEvent event) { return ready() && root.consumeHMIEvent(event, null); }
        public boolean consume(TouchEvent event) { return ready() && root.consumeTouchEvent(event); }
    }
}
