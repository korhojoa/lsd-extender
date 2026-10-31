/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.wirelessaa;

import de.vw.mib.asl.framework.api.dsiproxy.DSIProxy;
import de.vw.mib.asl.framework.api.dsiproxy.DSIProxyFactory;
import de.vw.mib.asl.framework.api.dsiproxy.DSIProxyInterceptor;
import de.vw.mib.asl.framework.internal.framework.dsi.util.Utils;
import de.vw.mib.asl.internal.smartphoneintegration.target.HsmTarget;
import org.dsi.ifc.smartphoneintegration.DSISmartphoneIntegrationListener;
import org.dsi.ifc.smartphoneintegration.Device;
import org.korho.lsdextender.Deferred;
import org.korho.lsdextender.EventClock;
import org.korho.lsdextender.LsdExtender;
import org.korho.lsdextender.MflListener;

/** Changes one copied notification name; the original stock dispatch continues. */
public final class WirelessLabelHook implements DSIProxyInterceptor, MflListener, Deferred {
    private static final String UPDATE = "updateDiscoveredDevices";
    private static final String PREFIX = "Wireless: ";
    private final Object registration = new Object();
    private DSIProxy proxy;
    private int typeIndex = -1;
    private boolean attempted, stopped, enabled;

    static void start() {
        WirelessLabelHook hook = new WirelessLabelHook();
        LsdExtender.register(hook);
        LsdExtender.defer(hook);
    }

    public String name() { return "wireless/label [" + Build.BANNER + "]"; }
    public boolean onMflEvent(HsmTarget target, int gesture) { return false; }

    public boolean run() {
        synchronized (this) { if (stopped || attempted) { return true; } }
        try {
            DSIProxy selected = DSIProxyFactory.getDSIProxyAPI().getDSIProxy();
            if (selected == null) { return false; }
            install(selected);
            return true;
        } catch (Throwable unavailable) {
            return false; // Core bounds deferred retries and its readiness deadline.
        }
    }

    void install(DSIProxy selected) throws Exception {
        synchronized (registration) {
            synchronized (this) { if (stopped || attempted) { return; } }
            DSISmartphoneIntegrationListener.class.getMethod(UPDATE, new Class[] { Device[].class, Integer.TYPE });
            int index = Utils.getTypeIndex(DSISmartphoneIntegrationListener.class);
            if (index < 0) { throw new IllegalStateException("unknown SI listener type"); }
            synchronized (this) {
                proxy = selected;
                typeIndex = index;
                attempted = true; // Exact detach also covers an API that accepts then throws.
                enabled = true;
            }
            try {
                selected.addListenerInterceptor(DSISmartphoneIntegrationListener.class, 0, this);
                log("registered");
            } catch (Throwable failed) {
                synchronized (this) { enabled = false; }
                log("registration failed");
            }
        }
    }

    /** Core retains this class loader if exact unregistration fails. */
    public void shutdown() {
        synchronized (registration) {
            DSIProxy selected;
            synchronized (this) {
                stopped = true;
                enabled = false;
                if (!attempted) { return; }
                selected = proxy;
            }
            selected.removeListenerInterceptor(DSISmartphoneIntegrationListener.class, 0, this);
            synchronized (this) { attempted = false; proxy = null; }
            log("removed");
        }
    }

    public synchronized boolean before(String type, int instance, int index, Object[] args) {
        if (!enabled || stopped || instance != 0 || index < 0
            || !DSISmartphoneIntegrationListener.class.getName().equals(type)) { return true; }
        try {
            if (!UPDATE.equals(Utils.getRuntimeListenerMethodName(typeIndex, index))
                || args == null || args.length != 2 || !(args[0] instanceof Device[])
                || !(args[1] instanceof Integer) || ((Integer)args[1]).intValue() != 1) { return true; }
            Device[] copy = labelled((Device[])args[0]);
            if (copy != null) { args[0] = copy; }
        } catch (Throwable unavailable) {
            // No partial publication: allocation and validation precede the sole argument assignment.
        }
        return true;
    }

    public void after(String type, int instance, int index, Object[] args) { }

    /** No stock list, handler, widget or source Device is changed. */
    static Device[] labelled(Device[] source) {
        if (source == null || source.length > 100) { return null; }
        int selected = -1;
        for (int i = 0; i < source.length; i++) {
            Device value = source[i];
            if (value == null || value.deviceID < 0 || value.deviceID >= 100) { return null; }
            for (int j = 0; j < i; j++) {
                if (source[j].deviceID == value.deviceID) { return null; }
            }
            if (value.connectionType == 1 && (value.connectionMethod & 8) != 0
                && "0001".equals(value.deviceAddress)) {
                if (selected >= 0) { return null; }
                selected = i;
            }
        }
        if (selected < 0) { return null; }
        Device original = source[selected];
        String name = original.deviceName;
        if (name != null && (name.startsWith(PREFIX) || name.length() > 256)) { return null; }
        String label = PREFIX + (name == null || name.length() == 0 ? "Android Auto" : name);
        Device[] copy = new Device[source.length];
        System.arraycopy(source, 0, copy, 0, source.length);
        copy[selected] = new Device(original.deviceID, label, original.connectionType,
            original.deviceAddress, original.connectionMethod);
        return copy;
    }

    private static void log(String message) {
        LsdExtender.trace(" wireless label: " + EventClock.stamp() + " " + message);
    }
}
