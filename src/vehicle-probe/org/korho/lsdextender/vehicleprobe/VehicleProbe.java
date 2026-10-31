/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.vehicleprobe;

import de.vw.mib.asl.framework.api.dsiproxy.DSIProxyFactory;
import de.vw.mib.asl.framework.internal.framework.ServiceManager;
import de.vw.mib.asl.internal.smartphoneintegration.target.HsmTarget;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.dsi.ifc.base.DSIListener;
import org.dsi.ifc.carlight.DSICarLight;
import org.dsi.ifc.carlight.ExtLightViewOptions;
import org.dsi.ifc.carlight.TimeState;
import org.korho.lsdextender.CommandHandler;
import org.korho.lsdextender.Config;
import org.korho.lsdextender.Deferred;
import org.korho.lsdextender.DsiSubscribe;
import org.korho.lsdextender.EventClock;
import org.korho.lsdextender.LsdExtender;
import org.korho.lsdextender.MflListener;

/** Optional feature loaded only by an explicit jar and feature configuration. */
public final class VehicleProbe implements MflListener, Deferred, CommandHandler, InvocationHandler {
    private static final String PREFIX = "vehicle-probe";
    private static final String SERVICE = "org.dsi.ifc.carlight.DSICarLight";
    private static final String OBJECT_STRING = "toString";
    private static final String OBJECT_HASH = "hashCode";
    private static final String UPDATE_VALUE = "updateExtLightComingHome";
    private static final String UPDATE_OPTIONS = "updateExtLightViewOptions";
    private static final String ASYNC_ERROR = "asyncException";
    private final boolean enabled = Config.getBool("lsdextender.vehicle_probe.enabled", false);
    private final boolean readOnly = Config.getBool("lsdextender.vehicle_probe.read_only", false);
    private final VehicleReads reads = new VehicleReads();
    private final ComingHomeProbe probe = new ComingHomeProbe(enabled, new NativePlatform());
    private volatile boolean closed;

    static {
        VehicleProbe feature = new VehicleProbe();
        LsdExtender.register(feature);
        LsdExtender.registerCommands(feature);
        LsdExtender.defer(feature);
    }

    private VehicleProbe() {
    }

    public String name() { return PREFIX + " [" + Build.BANNER + "] enabled=" + enabled + " read_only=" + readOnly; }

    public boolean onMflEvent(HsmTarget target, int gesture) { return false; }

    public boolean run() {
        if (closed) { return true; }
        if (readOnly) { reads.start(); }
        if (!enabled) { return true; }
        try {
            Class listener = Class.forName(SERVICE + "Listener");
            DSIListener owned = (DSIListener)Proxy.newProxyInstance(listener.getClassLoader(), new Class[]{listener}, this);
            DsiSubscribe.whenRegistered(PREFIX, SERVICE, new int[]{12, 13}, owned, new Runnable() {
                public void run() { probe.unavailable(); }
            });
        } catch (Throwable unavailable) { // An optional service must not break stock startup.
            probe.unavailable();
        }
        return true;
    }

    public Object invoke(Object proxy, Method method, Object[] args) {
        String name = method.getName();
        if (OBJECT_STRING.equals(name)) { return PREFIX; }
        if (OBJECT_HASH.equals(name)) { return new Integer(System.identityHashCode(proxy)); }
        if ("equals".equals(name)) { return Boolean.valueOf(proxy == args[0]); } // NOPMD CompareObjectsWithEquals - proxy identity
        if (UPDATE_VALUE.equals(name)) {
            TimeState value = (TimeState)args[0];
            if (value == null) { probe.unavailable(); }
            else { probe.value(value.time, value.state, ((Integer)args[1]).intValue()); }
        } else if (UPDATE_OPTIONS.equals(name)) {
            ExtLightViewOptions value = (ExtLightViewOptions)args[0];
            probe.availability(value == null || value.comingHome == null ? 0 : value.comingHome.state,
                ((Integer)args[1]).intValue());
        } else if (ASYNC_ERROR.equals(name)) {
            probe.unavailable();
        }
        return null;
    }

    public String command(String line) { return ProbeCommands.command(probe, line); }

    public void shutdown() { closed = true; probe.close(); reads.close(); }

    private static final class NativePlatform implements ComingHomeProbe.Platform {
        public long now() { return EventClock.millis(); }

        public void enqueue(Runnable work) {
            if (ServiceManager.aslThreadSwitch == null) { throw new IllegalStateException("ASL queue unavailable"); }
            ServiceManager.aslThreadSwitch.enqueue(work);
        }

        public void send(int time, boolean state) {
            DSICarLight service = (DSICarLight)DSIProxyFactory.getDSIProxyAPI().getDSIProxy()
                .getService(LsdExtender.nativeTarget(), DSICarLight.class);
            if (service == null) { throw new IllegalStateException("CarLight unavailable"); }
            service.setExtLightComingHome(new TimeState(time, state));
        }

        public void log(String detail) { LsdExtender.trace(" vehicle-probe " + EventClock.stamp() + " " + detail); }
    }
}
