/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.homescreen;

import org.korho.lsdextender.Slog;
import org.korho.lsdextender.EventClock;
import de.vw.mib.appui.cio.CioVisualizationRegister;
import de.vw.mib.cio.CioActionParameters;
import de.vw.mib.cio.CioExecutor;
import de.vw.mib.cio.CioIntentVisualizationImpl;
import de.vw.mib.cio.ContentInformationObject;
import de.vw.mib.cio.app.framework.AppFrameworkCioFactory;
import de.vw.mib.cio.app.framework.CioIntentDefinition;
import de.vw.mib.cio.framework.CioIdService;
import de.vw.mib.cio.framework.CioIntentVisualization;
import de.vw.mib.cio.framework.CioVisualizationService;

/** Own MENU action lifetime. This does not supply an application/page host. */
final class SettingsMenuEntry implements CioExecutor, CioIntentDefinition, CioVisualizationService {
    private static final String APP = "org.korho.lsdextender.settings";
    private static final String CIO = "OpenSettings", INTENT = "ShowSettings", VISUAL = "MenuLabel";
    private final String instance;
    private final SettingsBackend backend;
    private final Gate gate;
    private final Page page;
    private final SmallTileContent.Backing backing;
    private final int profile;
    private final AppFrameworkCioFactory factory;
    private final CioVisualizationRegister visualizations;
    private boolean relayRegistered, active, stopped, loaded, registeringRelay, registeringIntent, pageClosed;
    private ContentInformationObject ownedIntent;
    private String lastDecision;
    private volatile String currentStatus = "not checked";
    private long visualizationId;

    /** Only an independently managed OWN page may implement this capability. */
    interface Page {
        /** True only after native visibility, lifecycle and Back routing are usable. */
        boolean ready();
        void open();
        void close();
    }

    SettingsMenuEntry(String instance, SettingsBackend backend, Page page, SmallTileContent.Backing backing, int profile,
            AppFrameworkCioFactory factory, CioVisualizationRegister visualizations) {
        this(instance, backend, null, page, backing, profile, factory, visualizations);
    }

    interface Gate {
        String title();
        boolean live();
        String unavailable();
    }

    SettingsMenuEntry(String instance, Gate gate, Page page,
            AppFrameworkCioFactory factory, CioVisualizationRegister visualizations) {
        this(instance, null, gate, page, null, 0, factory, visualizations);
    }

    private SettingsMenuEntry(String instance, SettingsBackend backend, Gate gate, Page page,
            SmallTileContent.Backing backing, int profile,
            AppFrameworkCioFactory factory, CioVisualizationRegister visualizations) {
        if (instance == null || instance.length() == 0 || page == null
                || (gate == null && (backend == null || backing == null || profile < 0 || profile > 1))
                || factory == null || visualizations == null) {
            throw new IllegalArgumentException("Settings entry dependencies required");
        }
        this.instance = instance;
        this.backend = backend;
        this.gate = gate;
        this.page = page;
        this.backing = backing;
        this.profile = profile;
        this.factory = factory;
        this.visualizations = visualizations;
    }

    /** Called by the owning feature on its native lifecycle thread. No worker or timer. */
    synchronized void refresh() {
        String decision = decision();
        logDecision(decision);
        if (!"ready".equals(decision)) {
            withdraw();
            if (!pageClosed) { page.close(); pageClosed = true; }
            captureStatus(decision); return;
        }
        pageClosed = false;
        if (ownedIntent != null) { captureStatus(decision); return; }
        try {
            registeringRelay = true;
            visualizations.registerRelayService(APP, instance, this);
            relayRegistered = true;
            registeringRelay = false;
            registeringIntent = true;
            factory.createAndRegisterCio(this, this);
            active = ownedIntent != null;
            if (!active) { withdraw(); }
        } catch (RuntimeException failure) { withdraw(); throw failure; }
        finally { registeringRelay = false; registeringIntent = false; captureStatus(decision); }
    }

    synchronized void shutdown() {
        stopped = true;
        logDecision(decision());
        try { withdraw(); }
        finally {
            try { if (!pageClosed) { page.close(); pageClosed = true; } }
            finally { captureStatus("stopped"); }
        }
    }

    private String decision() {
        if (stopped) { return "stopped"; }
        if (!page.ready()) { return "page unavailable"; }
        OwnedSettingsSkin styles = OwnedSettingsSkin.active();
        if (styles == null || !styles.ready()) { return "verified native skin resources unavailable"; }
        if (gate != null) { return gate.live() ? "ready" : gate.unavailable(); }
        return backend.configured() && backing.live(profile) ? "ready" : "backend unavailable";
    }

    private boolean usable() { return "ready".equals(decision()); }

    String status() { return currentStatus; }

    private void captureStatus(String decision) {
        String registered;
        try { registered = String.valueOf(ownedIntent != null && ownedIntent.isRegistered()); }
        catch (RuntimeException failure) { registered = "query failed: " + failure; }
        currentStatus = decision + " active=" + active + " registered=" + registered
            + " relay=" + relayRegistered + " loaded=" + loaded;
    }

    private void logDecision(String decision) {
        if (!decision.equals(lastDecision)) {
            lastDecision = decision;
            Slog.write(" settings MENU " + instance + " decision=" + decision
                + " " + EventClock.stamp());
        }
    }

    private void withdraw() {
        active = false;
        if (ownedIntent != null) { ownedIntent.unregister(); ownedIntent = null; }
        if (relayRegistered) {
            visualizations.unregisterRelayService(APP, instance);
            relayRegistered = false;
            loaded = false;
        }
    }

    public synchronized boolean onCioIntentRegistration(ContentInformationObject information) {
        if (!registeringIntent || ownedIntent != null || information == null || !usable()) {
            return (true);
        }
        ownedIntent = information;
        return (false);
    }

    public synchronized void execute(ContentInformationObject information, CioActionParameters parameters) {
        if (!active || !relayRegistered || information != ownedIntent) { return; } // NOPMD CompareObjectsWithEquals - Exact owned registration identity.
        if (usable()) { page.open(); }
        else { refresh(); }
    }

    public synchronized void load(CioIdService ids) {
        // Native dispatcher inserts our relay before calling load. A duplicate key
        // fails before load, so it must not grant us cleanup ownership of that key.
        if (registeringRelay) { relayRegistered = true; }
        visualizationId = ids.getCioVisualizationId(APP, instance, CIO, VISUAL);
        loaded = true;
        captureStatus(lastDecision == null ? "not checked" : lastDecision);
    }

    public void loadDone() { }

    public synchronized CioIntentVisualization getVisualization(long id) {
        if (!loaded || id != visualizationId || !usable()) { return CioIntentVisualizationImpl.NOT_AVAILABLE; }
        return new CioIntentVisualizationImpl(null, null, null, null, (gate == null ? backend.title : gate.title()), null, null);
    }

    public int getActionId() { return (-1); }
    public String getAppInstanceName() { return instance; }
    public String getAppName() { return APP; }
    public String getCioIntentClassName() { return "MenuAction"; }
    public String getCioName() { return CIO; }
    public String getCioVisualizationName() { return VISUAL; }
    public String[] getCioVisualizationNames() { return new String[] { VISUAL }; }
    public int getFeatureFlagId() { return (-1); }
    public String getForwardedCioIntentClassName() { return null; }
    public String getForwardedUsageName() { return null; }
    public String getIntentName() { return INTENT; }
    public int getRank() { return (-1); }
    public String getUsage() { return "GridMenuAction"; }
    public boolean isForwardingCioIntent() { return false; }
}
