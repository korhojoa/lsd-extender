/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.homescreen;

import org.korho.lsdextender.Deferred;
import org.korho.lsdextender.LsdExtender;
import org.korho.lsdextender.MflListener;
import org.korho.lsdextender.Slog;
import de.vw.mib.asl.internal.smartphoneintegration.target.HsmTarget;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import java.util.Vector;

/** Own MENU actions and independently allocated native settings views. */
public final class HomeUiFeature implements MflListener, Deferred, org.korho.lsdextender.ReloadGuard {
    static { HomeUiFeature feature = new HomeUiFeature(); LsdExtender.register(feature); LsdExtender.defer(feature); }
    private final boolean updaterOnly = "true".equals(org.korho.lsdextender.Config.get("lsdextender.homeui.updater.only", "false"));
    private final Vector references = new Vector();
    private final SettingsMenuEntry[] entries = new SettingsMenuEntry[2];
    private final OwnedSettingsPage[] pages = new OwnedSettingsPage[2];
    private BundleContext context;
    private OwnedSettingsPage.Host host;
    private OwnedSettingsSkin styles;
    private OwnedSmallTile smallTile;
    private SmallTileContent tileContent;
    private SmallTileBacking tileBacking;
    private GamesMenuEntry games;
    private GameQuitOverlay gameQuit;
    private SettingsMenuEntry gamesEntry;
    private OwnedSettingsPage gamesPage;
    private UpdaterBacking updaterBacking;
    private SettingsMenuEntry updaterEntry;
    private OwnedSettingsPage updaterPage;
    private de.vw.mib.cio.app.framework.AppFrameworkCioFactory factory;
    private de.vw.mib.appui.cio.CioVisualizationRegister relay;
    private boolean stopped;
    private volatile boolean startupAtLastPass;
    private volatile String readiness = "not checked";

    private HomeUiFeature() { GameShutdown.start(updaterOnly); }
    public void prepareReload() {
        GamesMenuEntry.prepareReload(games);
        GameShutdown.prepareReload(updaterOnly);
    }

    public String name() {
        return "homeui (owned settings) [" + org.korho.lsdextender.homeui.Build.BANNER + "] " + readiness
            + " startupAtLastPass=" + startupAtLastPass + entryStatus(0, "aa") + entryStatus(1, "cp")
            + " updater={" + (updaterEntry == null ? "not installed" : updaterEntry.status()) + "}"
            + " tile={" + (smallTile == null ? "not installed" : smallTile.status()) + "}";
    }

    private String entryStatus(int index, String name) {
        return " " + name + "={" + (entries[index] == null ? "not installed" : entries[index].status()) + "}";
    }

    public synchronized boolean run() {
        if (stopped) { return true; }
        try {
        startupAtLastPass = de.vw.mib.asl.framework.internal.startupv7r.StartupServiceManager.startupDone;
        startStyles();
        if (!updaterOnly) {
        startBacking();
        }
        startUpdaterBacking();
        SettingsBackend aa = null, cp = null;
        if (!updaterOnly) { aa = SettingsBackend.androidAuto(); cp = SettingsBackend.carPlay(); }
        // Retire existing registrations even when both snapshots become unavailable.
        for (int i = 0; i < entries.length; i++) {
            if (entries[i] != null) { entries[i].refresh(); }
        }
        if (updaterEntry != null) { updaterEntry.refresh(); }
        runTilePass();
        // Each service performs its own initial read. Deferred availability
        // passes must not turn into helper processes while no page is open.
        if ((updaterOnly || (!aa.configured() && !cp.configured())) && !updaterBacking.live()) { waiting("helper snapshots unavailable"); }
            if (!NativeAdmission.ready(this)) { return false; }
            if (!updaterOnly) {
                installGames();
                install(0, "aa", aa, host, factory, relay);
                install(1, "cp", cp, host, factory, relay);
            }
            installUpdater();
            installTile();
            refreshEntries();
            runTilePass();
            readiness = styles != null && styles.ready() ? "registration attempted" : "native skin unavailable: " + (styles == null ? "not started" : styles.status());
            return true;
        } catch (RuntimeException failure) {
            shutdown();
            throw failure;
        }
    }

    /** Resolve native services separately from optional package admission. */
    private static final class NativeAdmission {
        static boolean ready(HomeUiFeature feature) {
            if (feature.context == null) {
                feature.context = de.vw.mib.widgets.internal.ServiceManager.bundleContext;
                if (feature.context == null) { feature.waiting("widget bundle context unavailable"); return false; }
            }
            if (feature.host != null) { return true; }
            de.vw.mib.desktop.DesktopManager desktop = de.vw.mib.desktop.internal.common.ServiceManager.desktopManager;
            de.vw.mib.event.dispatcher.FrameworkEventDispatcher dispatcher =
                de.vw.mib.desktop.internal.common.ServiceManager.eventDispatcher;
            de.vw.mib.app.desktop.ContentManager content = (de.vw.mib.app.desktop.ContentManager)
                feature.service("de.vw.mib.app.desktop.ContentManager");
            feature.factory =
                (de.vw.mib.cio.app.framework.AppFrameworkCioFactory)feature.service("de.vw.mib.cio.app.framework.AppFrameworkCioFactory");
            feature.relay = (de.vw.mib.appui.cio.CioVisualizationRegister)
                feature.service("de.vw.mib.appui.cio.CioVisualizationRegister");
            OwnedSettingsPage.NativeHost nativeHost = new OwnedSettingsPage.NativeHost(desktop, dispatcher, content, "HMI_HOMESCREEN");
            feature.host = nativeHost;
            if (feature.factory != null && feature.relay != null && feature.host.available()) { return true; }
            String reason = "native services: factory=" + (feature.factory != null) + " relay=" + (feature.relay != null)
                + " page=" + nativeHost.readiness();
            feature.release(); feature.waiting(reason); return false;
        }
    }

    private void startUpdaterBacking() {
        if (updaterBacking != null) { return; }
        final HomeUiFeature feature = this;
        updaterBacking = new UpdaterBacking(new Runnable() {
            public void run() {
                de.vw.mib.event.dispatcher.FrameworkEventDispatcher dispatcher =
                    de.vw.mib.desktop.internal.common.ServiceManager.eventDispatcher;
                if (dispatcher != null) {
                    dispatcher.invoke(new Runnable() {
                        public void run() { synchronized (feature) { if (!stopped) { feature.run(); } } }
                    });
                }
            }
        });
        updaterBacking.start();
    }

    private void installUpdater() {
        if (updaterEntry == null && styles != null && styles.ready()) {
            updaterPage = new OwnedSettingsPage(OwnedViewNames.allocate(OwnedSettingsPage.VIEW + ".updater"),
                host, new OwnedSettingsPage.NativeTree(null));
            updaterEntry = new SettingsMenuEntry("updater", updaterBacking, updaterPage, factory, relay);
            updaterBacking.hosted();
        }
        if (updaterEntry != null) {
            updaterEntry.refresh();
            updaterPage.refreshStyles(new Runnable() { public void run() { HomeUiFeature.this.run(); } });
        }
    }

    private final class GamesTree extends OwnedSettingsPage.NativeTree {
        GamesTree() { super(null); }
        String title() { return "Games"; }
        OwnedSettingsPage.Controls createControls(Runnable back, String font, int width, int height) {
            return new OwnedGamesControls(games, back, width, height);
        }
        OwnedSettingsPage.NativeTree replacement() { return new GamesTree(); }
    }

    private void installGames() {
        if (games == null) { games = new GamesMenuEntry(); }
        if (gamesEntry == null && styles != null && styles.ready()) {
            gamesPage = new OwnedSettingsPage(OwnedViewNames.allocate(OwnedSettingsPage.VIEW + ".games"), host, new GamesTree());
            gamesEntry = new SettingsMenuEntry("games", games, gamesPage, factory, relay);
            gameQuit = new GameQuitOverlay(games, host);
            LsdExtender.registerTouch(gameQuit); LsdExtender.registerKeys(gameQuit);
            games.returned(new Runnable() { public void run() {
                host.enqueue(new Runnable() { public void run() {
                    if (!stopped) {
                        gameQuit.finished(); gamesPage.close();
                        host.enqueue(new Runnable() { public void run() { if (!stopped) { gamesPage.open(); } } });
                    }
                } });
            } });
        }
        if (gamesEntry != null) {
            gamesEntry.refresh();
            gamesPage.refreshStyles(new Runnable() { public void run() { HomeUiFeature.this.run(); } });
        }
    }

    private void installTile() {
        if (smallTile == null) {
            tileContent = new SmallTileContent(updaterOnly ? SmallTileContent.NO_OPTIONAL_PAGES : tileBacking, pages, host,
                de.vw.mib.desktop.internal.common.ServiceManager.desktopManager);
            tileContent.games(gamesPage, games);
            smallTile = new OwnedSmallTile(factory, relay, tileContent);
        }
        tileContent.updater(updaterPage, updaterBacking);
        runTilePass();
    }

    private void startBacking() {
        if (tileBacking != null) { return; }
        final HomeUiFeature feature = this;
        tileBacking = new SmallTileBacking(new Runnable() {
            public void run() {
                de.vw.mib.event.dispatcher.FrameworkEventDispatcher dispatcher =
                    de.vw.mib.desktop.internal.common.ServiceManager.eventDispatcher;
                if (dispatcher != null) {
                    dispatcher.invoke(new Runnable() {
                        public void run() { synchronized (feature) { if (!stopped) { feature.run(); } } }
                    });
                }
            }
        });
        tileBacking.start();
    }

    private void runTilePass() {
        if (tileContent != null) { tileContent.recover(); }
        if (smallTile != null) { smallTile.refresh(); }
    }

    private void refreshEntries() {
        for (int i = 0; i < entries.length; i++) {
            if (entries[i] == null) { continue; }
            entries[i].refresh();
            if (styles != null && styles.ready()) {
                pages[i].refreshStyles(new Runnable() { public void run() { HomeUiFeature.this.run(); } });
            }
        }
    }

    private void startStyles() {
        if (styles == null) { styles = new OwnedSettingsSkin(OwnedSettingsSkin.nativeSource()); }
        OwnedSettingsSkin.publish(styles);
    }

    private void install(int index, String instance, SettingsBackend backend, OwnedSettingsPage.Host host,
            de.vw.mib.cio.app.framework.AppFrameworkCioFactory factory,
            de.vw.mib.appui.cio.CioVisualizationRegister relay) {
        if (entries[index] != null || !backend.configured() || styles == null || !styles.ready()) { return; }
        pages[index] = new OwnedSettingsPage(OwnedViewNames.allocate(OwnedSettingsPage.VIEW + "." + instance),
            host, new OwnedSettingsPage.NativeTree(backend));
        entries[index] = new SettingsMenuEntry(instance, backend, pages[index], tileBacking, index, factory, relay);
        entries[index].refresh();
    }

    private Object service(String type) {
        ServiceReference reference = context.getServiceReference(type);
        if (reference == null) { return null; }
        Object value = context.getService(reference);
        if (value != null) { references.addElement(reference); }
        return value;
    }

    private void waiting(String reason) {
        if (!reason.equals(readiness)) {
            readiness = reason;
            Slog.write(" homeui: owned settings waiting: " + reason + " wall_ms=" + System.currentTimeMillis());
        }
    }

    private void release() {
        host = null; factory = null; relay = null;
        for (int i = references.size() - 1; i >= 0; i--) {
            context.ungetService((ServiceReference)references.elementAt(i));
            references.removeElementAt(i);
        }
    }

    public synchronized boolean onMflEvent(HsmTarget target, int gesture) {
        if (!stopped && (entries[0] == null || entries[1] == null)) { run(); }
        for (int i = 0; !stopped && i < entries.length; i++) {
            if (entries[i] != null) { entries[i].refresh(); }
        }
        return false;
    }

    private RuntimeException shutdownUpdater() {
        RuntimeException failure = null;
        try { if (updaterBacking != null) { updaterBacking.stop(); } }
        catch (RuntimeException refused) { failure = refused; }
        try { if (updaterEntry != null) { updaterEntry.shutdown(); } }
        catch (RuntimeException refused) { failure = refused; }
        try { if (updaterPage != null) { updaterPage.shutdown(); } }
        catch (RuntimeException refused) { failure = refused; }
        return failure;
    }

    private RuntimeException shutdownGames() {
        return GameShutdown.run(this);
    }

    /** Keep the game's ordered teardown together, including its last failure. */
    private static final class GameShutdown {
        static void start(boolean updaterOnly) { if (!updaterOnly) { GameAudioSession.start(); } }
        static void prepareReload(boolean updaterOnly) { if (!updaterOnly) { GameAudioSession.prepareReload(); } }
        static RuntimeException run(HomeUiFeature feature) {
            RuntimeException failure = null;
            if (feature.gameQuit != null) { feature.gameQuit.shutdown(); }
            if (feature.games != null) { feature.games.stop(); }
            GameAudioSession.shutdown();
            try { if (feature.gamesEntry != null) { feature.gamesEntry.shutdown(); } }
            catch (RuntimeException refused) { failure = refused; }
            try { if (feature.gamesPage != null) { feature.gamesPage.shutdown(); } }
            catch (RuntimeException refused) { failure = refused; }
            return failure;
        }
    }

    public synchronized void shutdown() {
        stopped = true;
        readiness = "stopped";
        RuntimeException failure = shutdownUpdater();
        RuntimeException gamesFailure = updaterOnly ? null : shutdownGames();
        if (gamesFailure != null) { failure = gamesFailure; }
        try {
            try { if (tileBacking != null) { tileBacking.stop(); } }
            catch (RuntimeException refused) { failure = refused; }
            try { if (smallTile != null) { smallTile.shutdown(); } }
            catch (RuntimeException refused) { failure = refused; }
            try { if (tileContent != null) { tileContent.shutdown(); } }
            catch (RuntimeException refused) { failure = refused; }
            for (int i = 0; i < entries.length; i++) {
                try { if (entries[i] != null) { entries[i].shutdown(); } }
                catch (RuntimeException refused) { failure = refused; }
                try { if (pages[i] != null) { pages[i].shutdown(); } }
                catch (RuntimeException refused) { failure = refused; }
            }
        } finally {
            if (styles != null) {
                if (OwnedSettingsSkin.active() == styles) { OwnedSettingsSkin.publish(null); } // NOPMD CompareObjectsWithEquals - Only retire our exact published skin context.
                styles.stop(); styles = null;
            }
            release();
        }
        if (failure != null) { throw failure; }
    }
}
