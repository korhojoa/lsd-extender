/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.keys;

import de.vw.mib.desktop.internal.DesktopManagerImpl;
import de.vw.mib.desktop.internal.viewhandler.SmartViewHandler;
import de.vw.mib.datapool.ASLDatapool;
import java.lang.reflect.Field;
import java.util.Map;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

/** Reads stock state only. It never retains a stock widget or changes a stock field. */
final class RestartStockState implements RestartReturn.Host {
    static final String AREA = "ScreenAreaStage";
    private final BundleContext context;
    private ServiceReference contentRef, poolRef;
    private Object content;
    private ASLDatapool pool;

    RestartStockState(BundleContext bundle) {
        if (bundle == null) { throw new IllegalStateException("widget context unavailable"); }
        context = bundle;
    }
    void acquire() {
        if (content == null) {
            contentRef = context.getServiceReference("de.vw.mib.app.desktop.ContentManager");
            if (contentRef == null) { throw new IllegalStateException("content service unavailable"); }
            content = context.getService(contentRef);
        }
        if (pool == null) {
            poolRef = context.getServiceReference("de.vw.mib.datapool.ASLDatapool");
            if (poolRef == null) { throw new IllegalStateException("datapool unavailable"); }
            pool = (ASLDatapool)context.getService(poolRef);
        }
        if (content == null || pool == null) { throw new IllegalStateException("stock state unavailable"); }
    }
    String refusal() {
        try {
            acquire();
            if (!de.vw.mib.asl.framework.internal.startupv7r.StartupServiceManager.startupDone) { return "startup-not-complete"; }
            DesktopManagerImpl desktop = desktop();
            if (((Boolean)field(desktop, "skinIsChanging")).booleanValue()) { return "skin-changing"; }
            de.vw.mib.desktop.internal.popup.PopupStackManager popups =
                de.vw.mib.desktop.internal.common.ServiceManager.popupStackManager;
            if (popups == null) { return "popup-state-unknown"; }
            String popup = popups.getNameOfUpmostPopupViewHandler();
            int countId = constant("generated.de.vw.mib.asl.internal.phone.call.ASLPhoneCallPropertyManager", "COUNT");
            // Plain getters only: default-value overloads can write the native pool.
            int count = pool.getInteger(countId);
            return classify(contents(), popup, count);
        } catch (Throwable unavailable) { return "stock-state-unavailable"; }
    }
    static String classify(Map values, String popup, int count) {
        if (popup != null && popup.length() > 0) { return "stock-popup-visible"; }
        if (count < 0) { return "call-state-unknown"; }
        if (count > 0) { return "phone-call-active"; }
        Object stage = values.get(AREA);
        if (!(stage instanceof String)) { return "stage-content-unknown"; }
        java.util.Iterator entries = values.values().iterator();
        while (entries.hasNext()) {
            Object value = entries.next();
            if (!(value instanceof String)) { return "stock-content-unknown"; }
            String id = (String)value;
            if (id.indexOf("RVC") >= 0 || id.indexOf("CAMERA") >= 0) { return "camera-content-visible"; }
        }
        return safeContent((String)stage) ? null : "stock-content-not-qualified stage=" + logValue((String)stage);
    }
    static String logValue(String value) {
        if (value == null) { return "unknown"; }
        String single = value.replace('\n', '?').replace('\r', '?').replace('\t', '?');
        return single.length() > 160 ? single.substring(0, 160) : single;
    }
    static String admission(RestartReturn.Previous before) {
        return "stage=" + logValue(before.content) + " previous_view=" + logValue(before.name);
    }
    static boolean safeContent(String id) {
        return "HMI_GRIDMENU".equals(id) || "HMI_RADIO".equals(id) || "HMI_TUNER".equals(id)
            || "HMI_MEDIA".equals(id) || "HMI_NAVI".equals(id);
    }
    RestartReturn.Previous capture() {
        String reason = refusal();
        if (reason != null) { throw new IllegalStateException(reason); }
        DesktopManagerImpl desktop = desktop();
        SmartViewHandler current = desktop.getDominantViewHandlerOfScreenArea(AREA);
        if (current == null || current.getViewHandlerProvider() == null) { throw new IllegalStateException("previous provider unavailable"); }
        String name = current.getShortViewHandlerName();
        if (name == null || name.length() == 0) { throw new IllegalStateException("previous name unavailable"); }
        return new RestartReturn.Previous(current.getViewHandlerProvider(), name, (String)contents().get(AREA), currentSkin(),
            ((Integer)field(desktop, "driverDistractionPreventionGroupId")).intValue());
    }
    boolean stillOwn(String name) {
        SmartViewHandler current = desktop().getDominantViewHandlerOfScreenArea(AREA);
        return current != null && name.equals(current.getShortViewHandlerName());
    }
    boolean restore(RestartReturn.Previous previous, String ownName) { return RestartReturn.restore(this, previous, ownName); }
    public void enter(RestartReturn.Previous previous) {
        desktop().enterView(null, previous.provider, previous.name, new String[] { AREA }, false, previous.distractionGroup);
    }
    public String currentName() {
        SmartViewHandler current = desktop().getDominantViewHandlerOfScreenArea(AREA);
        return current == null ? null : current.getShortViewHandlerName();
    }
    public String currentContent() { return (String)contents().get(AREA); }
    public Object currentSkin() { return de.vw.mib.desktop.internal.common.ServiceManager.skinClassLoader; }
    public boolean changingSkin() { return ((Boolean)field(desktop(), "skinIsChanging")).booleanValue(); }
    void release() {
        if (poolRef != null) { context.ungetService(poolRef); poolRef = null; pool = null; }
        if (contentRef != null) { context.ungetService(contentRef); contentRef = null; content = null; }
    }
    private Map contents() {
        if (!"de.vw.mib.app.framework.internal.AppVisibilityManager".equals(content.getClass().getName())) {
            throw new IllegalStateException("unrecognized content manager");
        }
        return (Map)field(content, "contents");
    }
    static DesktopManagerImpl desktop() {
        DesktopManagerImpl value = de.vw.mib.desktop.internal.common.ServiceManager.desktopManager;
        if (value == null) { throw new IllegalStateException("desktop unavailable"); }
        return value;
    }
    private static int constant(String type, String name) throws Exception { return Class.forName(type).getField(name).getInt(null); }
    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (Exception unavailable) { throw new IllegalStateException("stock state field unavailable: " + name); }
    }
}
