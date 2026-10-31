/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.homescreen;

import java.lang.reflect.Field;
import java.util.List;
import de.vw.mib.asl.internal.homescreen.HomeScreenServices;
import de.vw.mib.asl.internal.impl.homescreen.HomeScreenServicesProvider;
import de.vw.mib.asl.internal.impl.homescreen.model.Tile;
import de.vw.mib.cio.CioIntent;
import de.vw.mib.cio.ContentInformationObject;
import de.vw.mib.desktop.internal.screenarea.ScreenAreaNode;
import de.vw.mib.desktop.internal.screenarea.ScreenTree;
import de.vw.mib.desktop.internal.screenarea.ViewNode;
import de.vw.mib.desktop.internal.viewhandler.SmartViewHandler;
import org.korho.lsdextender.EventClock;
import org.korho.lsdextender.Slog;

/** Rebind only the already selected owned app through the ordinary native host. */
final class NativeTileBinding implements OwnedSmallTile.Selection {
    private static final int MAX_TILES = 8, SINGLE_VIEW = 1;
    private String lastDecision;
    public void request(final CioIntent create, final OwnedSmallTile.Result result) {
        final de.vw.mib.genericevents.ThreadSwitchingTarget asl =
            de.vw.mib.asl.framework.internal.framework.ServiceManager.aslThreadSwitch;
        final de.vw.mib.event.dispatcher.FrameworkEventDispatcher hmi =
            de.vw.mib.desktop.internal.common.ServiceManager.eventDispatcher;
        if (asl == null || hmi == null) { result.completed(false); return; }
        asl.enqueue(new Runnable() {
            public void run() {
                String selected = null;
                try {
                    HomeScreenServices services = existingServices();
                    if (services != null) { selected = selectedArea(services.getTiles(), create.getCioIntentId()); }
                } catch (Exception failed) { failure("selection unavailable", failed); }
                final String area = selected;
                try {
                    hmi.invoke(new Runnable() {
                        public void run() {
                            boolean admitted = false;
                            try { admitted = area != null && vacantOwnedArea(area) && result.current(); }
                            catch (RuntimeException failed) { failure("view unavailable", failed); }
                            decision("selected area=" + area + " admitted=" + admitted);
                            if (!admitted) { result.completed(false); return; }
                            try {
                                asl.enqueue(new Runnable() {
                                    public void run() { install(create, area, result); }
                                });
                            } catch (RuntimeException failed) { failure("dispatch refused", failed); result.completed(false); }
                        }
                    });
                } catch (RuntimeException failed) { failure("dispatch refused", failed); result.completed(false); }
            }
        });
    }
    private void install(CioIntent create, String area, OwnedSmallTile.Result result) {
        boolean dispatched = false;
        try {
            HomeScreenServices services = existingServices();
            if (services != null && result.current()) {
                // Recheck on ASL immediately before the host action. A chooser
                // opened since the HMI sample retains its normal input and state.
                Field selection = services.getTileInstaller().getClass().getDeclaredField("tileWithActiveSelectionApp");
                selection.setAccessible(true);
                dispatched = installSelected(services, create, area, selection.get(services.getTileInstaller()) != null);
            }
        } catch (Exception failed) { failure("host bind refused", failed); }
        finally { result.completed(dispatched); }
    }
    private static HomeScreenServices existingServices() throws Exception {
        // The provider getter can construct stock services. Read only the
        // singleton created by native HOME startup, never create a replacement.
        Field field = HomeScreenServicesProvider.class.getDeclaredField("homeScreenServices");
        field.setAccessible(true);
        return (HomeScreenServices)field.get(null);
    }
    static boolean installSelected(HomeScreenServices services, CioIntent create, String area, boolean selectionActive) {
        if (selectionActive || !(create instanceof ContentInformationObject)
            || !((ContentInformationObject)create).isRegistered() || !OwnedSmallTile.validArea(area)
            || !area.equals(selectedArea(services.getTiles(), create.getCioIntentId()))) { return false; }
        Tile[] tiles = services.getTiles();
        for (int i = 0; i < tiles.length; i++) {
            if (tiles[i] != null && area.equals(tiles[i].getScreenArea())) {
                // Native install updates its own selected CIO, dispatches our
                // Create and Bind, preserves persistence, then refreshes headers.
                services.getTileInstaller().installTile(tiles[i], create);
                return true;
            }
        }
        return false;
    }
    private void failure(String phase, Exception failed) {
        decision(phase + " " + failed);
    }
    private synchronized void decision(String reason) {
        if (reason.equals(lastDecision)) { return; }
        lastDecision = reason;
        Slog.write(" homeui tile recovery " + reason + " " + EventClock.stamp());
    }
    static String selectedArea(Tile[] tiles, long intent) {
        if (tiles == null || tiles.length > MAX_TILES) { return null; }
        String area = null;
        for (int i = 0; i < tiles.length; i++) {
            Tile tile = tiles[i];
            if (tile == null || tile.getApp() == null || tile.getApp().getCioIntentId() != intent) { continue; }
            if (!OwnedSmallTile.validArea(tile.getScreenArea()) || area != null) { return null; }
            area = tile.getScreenArea();
        }
        return area;
    }
    private static boolean vacantOwnedArea(String area) {
        ScreenTree tree = de.vw.mib.desktop.internal.common.ServiceManager.screenAreaTree;
        if (tree == null) { return false; }
        ScreenAreaNode stage = tree.getScreenAreaNode("ScreenAreaStage");
        ScreenAreaNode target = tree.getScreenAreaNode("ScreenAreaStage." + area);
        if (stage == null || target == null || !stage.isActive()) { return false; }
        List stageViews = stage.getChildren();
        if (stageViews.size() != SINGLE_VIEW || !"HOMESCREEN".equals(((ViewNode)stageViews.get(0)).getViewHandler().getName())) { return false; }
        List children = target.getChildren();
        SmartViewHandler current = children.size() == SINGLE_VIEW ? ((ViewNode)children.get(0)).getViewHandler() : null;
        return recoverableArea(children.size(), current == null ? null : current.getName(),
            current != null && current.getView() != null && current.getView().is_activated());
    }
    static boolean recoverableArea(int children, String name, boolean active) {
        // Native leave deactivates the area before removing the old view. The
        // native selected app and active HOME, not this retired flag, own the slot.
        return children == 0 || children == SINGLE_VIEW && recoverableView(name, active);
    }
    static boolean recoverableView(String name, boolean active) {
        // A selected hosted app can outlive the chooser's native handler.
        // The ASL installer rechecks tileWithActiveSelectionApp immediately
        // before dispatch, so an actual open chooser still refuses recovery.
        return "SelectionTileS".equals(name)
            || !active && name != null && name.startsWith(OwnedSettingsPage.VIEW + ".smallTile.");
    }
}
