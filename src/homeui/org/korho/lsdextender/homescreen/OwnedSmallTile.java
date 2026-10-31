/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.homescreen;

import de.vw.mib.appui.cio.CioVisualizationRegister;
import de.vw.mib.cio.CioActionParameters;
import de.vw.mib.cio.CioExecutor;
import de.vw.mib.cio.CioIntent;
import de.vw.mib.cio.CioIntentVisualizationImpl;
import de.vw.mib.cio.ContentInformationObject;
import de.vw.mib.cio.app.framework.AppFrameworkCioFactory;
import de.vw.mib.cio.app.framework.CioIntentDefinition;
import de.vw.mib.cio.framework.CioIdService;
import de.vw.mib.cio.framework.CioIntentVisualization;
import de.vw.mib.cio.framework.CioVisualizationService;
import org.korho.lsdextender.EventClock;
import org.korho.lsdextender.Slog;

/** An independent hosted tile. Native HomeScreen alone chooses and persists its slot. */
final class OwnedSmallTile implements CioVisualizationService, org.korho.lsdextender.AslEventListener {
    static final String APP = "org.korho.lsdextender.settings.tile";
    static final String INSTANCE = "settings", LABEL = "Extender settings", VISUAL = "TileLabel";
    static final String CREATE = "CreateHostedApp", BIND = "BindHostedApp";
    static final String DISPOSE = "DisposeHostedApp", HEADER = "TileHeaderButtonAction";
    interface Content {
        boolean ready();
        void create();
        void bind(String area);
        void dispose();
        void open();
    }
    interface Selection {
        void request(CioIntent create, Result result);
    }
    interface Result { boolean current(); void completed(boolean dispatched); }
    private final AppFrameworkCioFactory factory;
    private final CioVisualizationRegister relay;
    private final Content content;
    private final Selection selection;
    private final Action[] actions;
    private boolean stopped, relayOwned, loadingRelay, loaded, created, disposed, selectionPending, selectionDispatched;
    private int selectionGeneration;
    private boolean homeObserved, homeRecoveryPending;
    private long visualId;
    private String lastDecision;
    private volatile String summary = "not checked";

    OwnedSmallTile(AppFrameworkCioFactory factory, CioVisualizationRegister relay, Content content) {
        this(factory, relay, content, new NativeTileBinding());
    }
    OwnedSmallTile(AppFrameworkCioFactory factory, CioVisualizationRegister relay, Content content, Selection selection) {
        this.factory = factory; this.relay = relay; this.content = content;
        this.selection = selection;
        actions = new Action[] { new Action(CREATE), new Action(BIND), new Action(DISPOSE), new Action(HEADER) };
    }
    synchronized void refresh() {
        String reason = stopped ? "stopped" : content.ready() ? "ready" : "settings backing unavailable";
        decision(reason);
        if (stopped || !content.ready()) { withdraw(); return; }
        if (!homeObserved) { homeObserved = org.korho.lsdextender.LsdExtender.addAslListener(this); }
        if (actions[0].owned != null) { restoreSelection(); return; }
        try {
            loadingRelay = true;
            relay.registerRelayService(APP, INSTANCE, this);
            relayOwned = true; loadingRelay = false;
            // Publish creation last: every visible chooser entry already has its companions.
            disposed = false;
            for (int i = actions.length - 1; i >= 0; i--) { actions[i].register(); }
        } catch (RuntimeException failure) { withdraw(); throw failure; }
        finally { loadingRelay = false; }
        restoreSelection();
    }
    private void restoreSelection() { restoreSelection(false); }
    private void restoreSelection(final boolean existingLifetime) {
        if (selection == null || created && !existingLifetime || disposed || selectionPending || selectionDispatched || !(actions[0].owned instanceof CioIntent)) { return; }
        final ContentInformationObject registered = actions[0].owned;
        final int token = selectionGeneration;
        selectionPending = true;
        try {
            selection.request((CioIntent)registered, new Result() {
                public boolean current() {
                    synchronized (OwnedSmallTile.this) {
                        return token == selectionGeneration && registered == actions[0].owned // NOPMD CompareObjectsWithEquals - Exact registration lifetime.
                            && !stopped && (!created || existingLifetime) && !disposed && content.ready();
                    }
                }
                public void completed(boolean dispatched) {
                    synchronized (OwnedSmallTile.this) {
                        if (token != selectionGeneration || registered != actions[0].owned) { return; } // NOPMD CompareObjectsWithEquals - Exact registration lifetime.
                        selectionPending = false; selectionDispatched = dispatched;
                        if (dispatched) { decision("retained native binding dispatched"); }
                        if (homeRecoveryPending) {
                            homeRecoveryPending = false; selectionDispatched = false;
                            restoreSelection(true);
                        }
                    }
                }
            });
        } catch (RuntimeException failure) {
            selectionPending = false;
            decision("retained native selection query refused: " + failure);
        }
    }
    public int aslEventId() {
        return (1078641824);
    }
    public void onAslEvent(de.vw.mib.asl.internal.smartphoneintegration.target.HsmTarget target,
            de.vw.mib.genericevents.EventGeneric event) {
        if (event.getReceiverEventId() == aslEventId()) { homeActivated(); }
    }
    synchronized void homeActivated() {
        if (stopped || disposed) { return; }
        if (selectionPending) {
            // ASL selection and HMI visibility are asynchronous. Keep the HOME
            // edge if this query sampled before HOME became the active stage.
            homeRecoveryPending = true;
            return;
        }
        selectionDispatched = false;
        restoreSelection(true);
    }
    String status() { return summary; }
    private void decision(String reason) {
        summary = reason;
        if (!reason.equals(lastDecision)) {
            lastDecision = reason;
            Slog.write(" homeui tile decision=" + reason + " " + EventClock.stamp());
        }
    }
    synchronized void shutdown() { stopped = true; refresh(); }
    private void withdraw() {
        selectionGeneration++; selectionPending = false; selectionDispatched = false; homeRecoveryPending = false;
        // Withdraw chooser admission before releasing backing or its companion actions.
        for (int i = 0; i < actions.length; i++) { actions[i].unregister(); }
        if (created) { content.dispose(); created = false; }
        if (relayOwned) { relay.unregisterRelayService(APP, INSTANCE); relayOwned = false; loaded = false; }
    }
    public synchronized void load(CioIdService ids) {
        if (loadingRelay) { relayOwned = true; }
        visualId = ids.getCioVisualizationId(APP, INSTANCE, CREATE, VISUAL);
        loaded = true;
    }
    public void loadDone() { }
    public synchronized CioIntentVisualization getVisualization(long id) {
        OwnedSettingsSkin skin = OwnedSettingsSkin.active();
        return loaded && id == visualId
            ? skin == null ? new CioIntentVisualizationImpl(null, null, null, null, LABEL, null, null)
                : skin.tileVisualization(LABEL)
            : CioIntentVisualizationImpl.NOT_AVAILABLE;
    }
    private final class Action implements CioIntentDefinition, CioExecutor {
        private final String kind;
        private ContentInformationObject owned;
        private boolean registering;
        Action(String kind) { this.kind = kind; }
        void register() {
            registering = true;
            try {
                factory.createAndRegisterCio(this, this);
                if (owned == null) { throw new IllegalStateException("Owned tile registration refused: " + kind); }
            } finally { registering = false; }
        }
        void unregister() { if (owned != null) { owned.unregister(); owned = null; } }
        public boolean onCioIntentRegistration(ContentInformationObject information) {
            synchronized (OwnedSmallTile.this) {
                if (!registering || owned != null || stopped || information == null || !content.ready()) {
                    return (true);
                }
                owned = information;
                return (false);
            }
        }
        public void execute(ContentInformationObject information, CioActionParameters parameters) {
            synchronized (OwnedSmallTile.this) {
                if (owned == null || information != owned) { return; } // NOPMD CompareObjectsWithEquals - Exact owned action.
                if (DISPOSE.equals(kind)) {
                    selectionGeneration++; selectionPending = false; selectionDispatched = false; homeRecoveryPending = false;
                    disposed = true;
                    if (created) { content.dispose(); created = false; decision("owned content disposed"); }
                    else { decision("dispose received: own content inactive"); }
                    return;
                }
                if (stopped || !content.ready()) { refresh(); return; }
                if (CREATE.equals(kind)) {
                    if (created && disposed) { decision("create refused: disposal incomplete"); return; }
                    if (!created) { content.create(); created = true; disposed = false; decision("owned lifetime created"); }
                } else if (BIND.equals(kind)) {
                    if (!validHost(parameters)) { decision("bind refused: " + hostDescription(parameters)); }
                    else if (disposed) { decision("bind refused: disposed lifetime"); }
                    else {
                        // A retained native selection may bind our replacement after live reload.
                        // Initialize only our own content, never synthesize a native Create action.
                        if (!created) { content.create(); created = true; decision("bind initialized owned lifetime"); }
                        content.bind(parameters.getString("hostAppScreenArea"));
                    }
                } else if (HEADER.equals(kind) && created && !disposed) { content.open(); }
            }
        }
        public String getAppName() { return APP; }
        public String getAppInstanceName() { return INSTANCE; }
        public String getCioName() { return CREATE; }
        public String getIntentName() { return kind; }
        public String getCioIntentClassName() { return kind; }
        public String getUsage() { return CREATE.equals(kind) ? "SmallTile" : null; }
        public String getCioVisualizationName() { return VISUAL; }
        public String[] getCioVisualizationNames() { return new String[] { VISUAL }; }
        public int getActionId() { return (-1); }
        public int getFeatureFlagId() { return (-1); }
        public int getRank() { return (-1); }
        public boolean isForwardingCioIntent() { return false; }
        public String getForwardedCioIntentClassName() { return null; }
        public String getForwardedUsageName() { return null; }
    }
    private static String hostDescription(CioActionParameters parameters) {
        return parameters == null ? "parameters absent" : "host=" + parameters.getString("hostAppName")
            + " instance=" + parameters.getString("hostAppInstanceName")
            + " area=" + parameters.getString("hostAppScreenArea")
            + " layer=" + parameters.getString("hostedAppLayerLabel");
    }
    private static boolean validHost(CioActionParameters parameters) {
        if (parameters == null || !"HomeScreen".equals(parameters.getString("hostAppName"))
                || !"HomeScreen".equals(parameters.getString("hostAppInstanceName"))
                || !"Main".equals(parameters.getString("hostedAppLayerLabel"))) { return false; }
        return validArea(parameters.getString("hostAppScreenArea"));
    }
    static boolean validArea(String area) {
        return "ScreenAreaTileS1".equals(area) || "ScreenAreaTileS2".equals(area)
            || "ScreenAreaTileS3".equals(area) || "ScreenAreaTileS4".equals(area);
    }
}
