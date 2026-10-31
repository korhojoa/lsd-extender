/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.ev;

import org.korho.lsdextender.Config;
import org.korho.lsdextender.Deferred;
import org.korho.lsdextender.DsiSubscribe;
import org.korho.lsdextender.EventClock;
import org.korho.lsdextender.LsdExtender;
import org.korho.lsdextender.MflListener;
import org.korho.lsdextender.PpsObject;
import de.vw.mib.asl.internal.smartphoneintegration.target.HsmTarget;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.dsi.ifc.base.DSIListener;
import org.dsi.ifc.carhybrid.BatteryControlChargeState;
import org.dsi.ifc.carhybrid.BatteryControlPlug;
import org.dsi.ifc.carhybrid.BatteryControlRemainingChargeTime;
import org.dsi.ifc.careco.RangeDataResidualEnergy;
import org.dsi.ifc.carkombi.BCTankLevel;
import org.dsi.ifc.global.CarBCCurrentRange;
import org.dsi.ifc.global.CarBCDistance;

public final class EvFile implements MflListener, Deferred, InvocationHandler {
    /** The PPS object GAL reads, with sequence and timestamp fields from PpsObject. */
    public static final String DEFAULT_FILE = "/ramdisk/pps/gal/ev";
    private static final int DISTANCE_MILES = 1;

    private static final int MAX_ATTEMPTS = 300;
    private static final int VALID = 1;
    private static final long MIN_INTERVAL_MS = 500;

    private boolean subscribed;
    private int attempts;

    // the current values; -1 / NaN = absent
    private long rangeM = -1, range2M = -1, rangeTotalM = -1;
    private double residualWh = Double.NaN, residual2Wh = Double.NaN;
    private int socHybrid = -1, socTank = -1;
    private int chargeState = -1, chargeMode = -1, remainingS = -1, targetSoc = -1, plugState = -1;
    private long seq;
    private boolean dirty;
    private long lastWrite;
    private final java.util.Hashtable seen = new java.util.Hashtable();
    private final ElectricVehicleStatus electric = new ElectricVehicleStatus();

    static {
        try {
            EvFile e = new EvFile();
            LsdExtender.register(e);
            LsdExtender.defer(e);
        } catch (Throwable ignored) {
            // fail open
        }
    }

    private EvFile() {
    }

    public String name() {
        return "ev [" + Build.BANNER + "]";
    }

    public boolean onMflEvent(HsmTarget target, int gesture) {
        return false;
    }

    // Deferred: subscribe once the HMI's DSI proxy API is reachable
    public boolean run() {
        if (subscribed) {
            return true;
        }
        if (!Config.getBool("lsdextender.ev.enabled", true)) {
            LsdExtender.trace(" ev: disabled by config");
            return true;
        }
        attempts++;
        HsmTarget t = LsdExtender.siTarget();
        if (t == null) {
            return attempts >= MAX_ATTEMPTS;
        }
        try {
            // each subscription is issued from the service's own registration callback
            // (DsiSubscribe): one made before the service is up is silently lost
            subscribe("org.dsi.ifc.carkombi.DSICarKombi", new int[]{4, 8, 9, 23, 24, 69});
            subscribe("org.dsi.ifc.generalvehiclestates.DSIGeneralVehicleStates", new int[]{4});
            subscribe("org.dsi.ifc.careco.DSICarEco", new int[]{32, 33});
            subscribe("org.dsi.ifc.carhybrid.DSICarHybrid", new int[]{7, 8, 24});
            subscribed = true;
            Thread w = new Thread(new Runnable() {
                public void run() {
                    EvFile.this.runWriter();
                }
            }, "lsd-extender-ev");
            w.setDaemon(true);
            w.start();
            LsdExtender.trace(" ev: subscriptions armed (kombi 4,8,9,23,24,69; general 4; eco 32,33; hybrid 7,8,24), writing "
                + Config.get("lsdextender.ev.file", DEFAULT_FILE));
            return true;
        } catch (Throwable e) {
            if (attempts == 1 || attempts % 50 == 0) {
                LsdExtender.trace(" ev: subscribe attempt " + attempts + " failed: " + e);
            }
            return attempts >= MAX_ATTEMPTS;
        }
    }

    private void subscribe(final String service, int[] attrs) throws Exception {
        Class lst = Class.forName(service + "Listener");
        Object listener = Proxy.newProxyInstance(lst.getClassLoader(), new Class[]{lst}, this);
        Runnable unavailable = null;
        if (service.equals("org.dsi.ifc.carkombi.DSICarKombi")
            || service.equals("org.dsi.ifc.generalvehiclestates.DSIGeneralVehicleStates")) {
            unavailable = new Runnable() {
                public void run() {
                    electricUnavailable(service);
                }
            };
        }
        DsiSubscribe.whenRegistered("ev", service, attrs, (DSIListener) listener, unavailable);
    }

    private synchronized void electricUnavailable(String service) {
        if (service.equals("org.dsi.ifc.carkombi.DSICarKombi")) {
            electric.kombiUnavailable();
        } else {
            electric.generalUnavailable();
        }
        dirty = true;
        notifyAll();
        LsdExtender.trace(" ev: electric status source unavailable: " + service);
    }

    // InvocationHandler for all four listener proxies: dispatch by callback name
    public Object invoke(Object proxy, Method m, Object[] args) {
        String n = m.getName();
        if (n.equals("hashCode")) {
            return new Integer(System.identityHashCode(proxy));
        }
        if (n.equals("equals")) {
            return new Boolean(proxy == (args == null ? null : args[0]));
        }
        if (n.equals("toString")) {
            return "lsd-extender ev listener";
        }
        try {
            handle(n, args);
        } catch (Throwable t) {
            LsdExtender.trace(" ev: " + n + " failed: " + t);
        }
        return ListenerReturn.empty(m.getReturnType());
    }

    private void firstSample(String n, Object[] args) {
        if (seen.get(n) == null) {
            seen.put(n, n);
            logSample(" ev: first ", n, args);
        }
    }

    /**
     * Every update of the charging attributes (hybrid 7, 8, 24), whole structs: they
     * arrive only on change, and the fields the cluster may use (current, chargeMode,
     * startReason, supplyState) are not in gal_ev.
     */
    private void chargingSample(String n, Object[] args) {
        seen.put(n, n);
        logSample(" ev: charging ", n, args);
    }

    private static void logSample(String prefix, String n, Object[] args) {
        StringBuffer b = new StringBuffer(prefix).append(n).append('(');
        for (int i = 0; args != null && i < args.length; i++) {
            b.append(i > 0 ? ", " : "").append(args[i]);
        }
        LsdExtender.trace(b.append(')').toString());
    }

    private synchronized void handle(String n, Object[] args) {
        boolean electricChanged = electric.update(n, args);
        if (electricChanged) {
            firstSample(n, args);
        }
        if (updateDriving(n, args) || updateCharging(n, args) || electricChanged) {
            dirty = true;
            notifyAll();
        }
    }

    private boolean updateDriving(String n, Object[] args) {
        if (n.equals("updateBCCurrentRange1") || n.equals("updateBCCurrentRange2")
            || n.equals("updateBCTotalCurrentRange") || n.equals("updateBCTankLevel1")
            || n.equals("updateRDResidualEnergy1") || n.equals("updateRDResidualEnergy2")) {
            if (!validCallback(args, 2)) {
                if (n.equals("updateBCCurrentRange1")) { rangeM = -1; }
                else if (n.equals("updateBCCurrentRange2")) { range2M = -1; }
                else if (n.equals("updateBCTotalCurrentRange")) { rangeTotalM = -1; }
                else if (n.equals("updateBCTankLevel1")) { socTank = -1; }
                else if (n.equals("updateRDResidualEnergy1")) { residualWh = Double.NaN; }
                else { residual2Wh = Double.NaN; }
                refused(n, "invalid_callback");
                return true;
            }
        }
        if (n.equals("updateBCCurrentRange1") || n.equals("updateBCCurrentRange2")) {
            firstSample(n, args);
            CarBCCurrentRange r = (CarBCCurrentRange) args[0];
            long m = (r != null && r.state == VALID) ? distanceM(r.currentRangeValue, r.currentRangeUnit) : -1;
            if (n.endsWith("1")) {
                rangeM = m;
            } else {
                range2M = m;
            }
        } else if (n.equals("updateBCTotalCurrentRange")) {
            firstSample(n, args);
            CarBCDistance d = (CarBCDistance) args[0];
            rangeTotalM = (d != null && d.distanceValueState == VALID) ? distanceM(d.distanceValue, d.distanceUnit) : -1;
        } else if (n.equals("updateBCTankLevel1")) {
            firstSample(n, args);
            BCTankLevel l = (BCTankLevel) args[0];
            socTank = (l != null && l.state == VALID) ? l.tankLevelValue : -1;
        } else if (n.equals("updateRDResidualEnergy1") || n.equals("updateRDResidualEnergy2")) {
            firstSample(n, args);
            RangeDataResidualEnergy e = (RangeDataResidualEnergy) args[0];
            double wh = Double.NaN;
            if (e != null && e.state == VALID && e.unit == 1) {
                wh = e.value * 1000.0;
            }
            if (n.endsWith("1")) {
                residualWh = wh;
            } else {
                residual2Wh = wh;
            }
        } else {
            return false;
        }
        return true;
    }

    private boolean updateCharging(String n, Object[] args) {
        boolean changed = false;
        boolean state = n.equals("updateBatteryControlChargeState");
        boolean time = n.equals("updateBatteryControlRemainingChargeTime");
        boolean plug = n.equals("updateBatteryControlPlug");
        if (state || time || plug) {
            chargingSample(n, args);
            if (!validCallback(args, time ? 3 : 2)) {
                clearCharging(state, time, plug);
                refused(n, "invalid_callback");
                return true;
            }
        }
        if (n.equals("updateBatteryControlChargeState")) {
            BatteryControlChargeState s = (BatteryControlChargeState) args[0];
            if (s != null) {
                chargeMode = s.chargeMode;
                socHybrid = s.currentChargeLevel;
                chargeState = s.chargeState;
                remainingS = chargeSeconds(s.remainingChargeTime);
                targetSoc = s.targetSOC;
                changed = true;
            } else {
                clearCharging(true, false, false);
                refused(n, "missing_state");
                changed = true;
            }
        } else if (n.equals("updateBatteryControlRemainingChargeTime")) {
            BatteryControlRemainingChargeTime first = (BatteryControlRemainingChargeTime) args[0];
            BatteryControlRemainingChargeTime second = (BatteryControlRemainingChargeTime) args[1];
            BatteryControlRemainingChargeTime r = selectChargeTime(first, second);
            if (r != null) {
                remainingS = chargeSeconds(r.chargeTime);
                targetSoc = r.targetSOC;
            } else {
                remainingS = -1;
                targetSoc = -1;
                refused(n, "no_unique_estimate");
            }
            changed = true;
        } else if (n.equals("updateBatteryControlPlug")) {
            BatteryControlPlug p = (BatteryControlPlug) args[0];
            if (p != null) {
                plugState = p.plugState;
                changed = true;
            } else {
                plugState = -1;
                refused(n, "missing_plug");
                changed = true;
            }
        } else if (n.equals("updateBCTankLevel2") || n.equals("updateHybridCharge")) {
            firstSample(n, args);
        }
        return changed;
    }

    private static boolean validCallback(Object[] args, int count) {
        return args != null && args.length == count && args[count - 1] instanceof Integer
            && ((Integer) args[count - 1]).intValue() == VALID;
    }

    private void clearCharging(boolean state, boolean time, boolean plug) {
        if (state) { socHybrid = -1; chargeState = -1; chargeMode = -1; }
        if (state || time) { remainingS = -1; targetSoc = -1; }
        if (plug) { plugState = -1; }
    }

    private static void refused(String name, String reason) {
        LsdExtender.trace(" ev: refused " + name + " reason=" + reason + " " + EventClock.stamp());
    }

    private boolean applicable(BatteryControlRemainingChargeTime value) {
        if (value == null || value.mode < 0 || value.mode > 2 || value.chargeTime < 0
            || value.chargeTime >= 65535 || value.targetSOC < 0 || value.targetSOC > 100) {
            return false;
        }
        // Charge-state AC (1/4) and DC (2/5) use different estimate mode enums.
        if (chargeMode == 1 || chargeMode == 4) { return value.mode == 1 || value.mode == 2; }
        if (chargeMode == 2 || chargeMode == 5) { return value.mode == 0; }
        return true;
    }

    private BatteryControlRemainingChargeTime selectChargeTime(BatteryControlRemainingChargeTime first,
        BatteryControlRemainingChargeTime second) {
        boolean a = applicable(first), b = applicable(second);
        if (a && b && (first.chargeTime != second.chargeTime || first.targetSOC != second.targetSOC)) {
            return null;
        }
        return a ? first : (b ? second : null);
    }

    private static long distanceM(double value, int unit) {
        if (unit == 0) {
            return Math.round(value * 1000.0);
        }
        if (unit == DISTANCE_MILES) {
            return Math.round(value * 1609.344);
        }
        return -1;
    }

    private static int chargeSeconds(int raw) {
        if (raw < 0 || raw >= 0xFFFF) {
            return -1;
        }
        return raw * Config.getInt("lsdextender.ev.charge_time_unit_s", 60);
    }

    // writer thread: coalesces updates to one file write per MIN_INTERVAL_MS, and
    // rewrites the file unchanged (updated_ms bumped, seq kept) every heartbeat_s so a
    // reader can tell "producer alive, values steady" from "producer gone": a parked
    // car's values do not move for minutes, and gal drops a file older than 30 s
    private boolean stopped;

    /** Reload contract (core calls this reflectively): stop the writer thread. */
    public synchronized void shutdown() {
        stopped = true;
        notifyAll();
        LsdExtender.trace(" ev: writer stopped (reload); the object stays for the next instance");
    }

    void runWriter() {
        while (true) {
            String content;
            boolean heartbeat;
            synchronized (this) {
                long hb = heartbeatMs();
                while (!stopped && !dirty && System.currentTimeMillis() - lastWrite < hb) {
                    try {
                        wait(Math.max(50L, hb - (System.currentTimeMillis() - lastWrite)));
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (stopped) {
                    return;
                }
                heartbeat = !dirty;
                if (!heartbeat) {
                    long since = System.currentTimeMillis() - lastWrite;
                    if (since < MIN_INTERVAL_MS) {
                        try {
                            wait(MIN_INTERVAL_MS - since);
                        } catch (InterruptedException e) {
                            return;
                        }
                        continue;
                    }
                    dirty = false;
                    seq++;
                }
                lastWrite = System.currentTimeMillis();
                content = render();
            }
            write(content, heartbeat);
        }
    }

    private static long heartbeatMs() {
        int s = Config.getInt("lsdextender.ev.heartbeat_s", 10);
        return s <= 0 ? Long.MAX_VALUE / 4 : (long) s * 1000L;
    }

    /**
     * The object text: every key present (seq, updated_ms, the valid values) or
     * deleted (-key) in the same write, so the object holds exactly the keys the
     * key=value file would have had.
     */
    private String render() {
        int soc = socHybrid >= 0 ? socHybrid : socTank;
        long r = rangeM >= 0 ? rangeM : range2M;
        double wh = !Double.isNaN(residualWh) ? residualWh : residual2Wh;
        return render(seq, System.currentTimeMillis(), soc, r, rangeTotalM,
            Double.isNaN(wh) ? -1L : Math.round(wh), chargeState, remainingS, targetSoc, plugState)
            + electric.render();
    }

    /** The attribute lines of one publish; a negative value deletes its key. */
    static String render(long seq, long updatedMs, long socPct, long rangeM, long rangeTotalM,
        long residualWh, long chargeState, long remainingS, long targetSoc, long plugState) {
        StringBuffer b = new StringBuffer(256);
        b.append("seq::").append(seq).append('\n');
        b.append("updated_ms::").append(updatedMs).append('\n');
        attr(b, "soc_pct", socPct);
        attr(b, "range_m", rangeM);
        attr(b, "range_total_m", rangeTotalM);
        attr(b, "residual_energy_wh", residualWh);
        attr(b, "charge_state", chargeState);
        attr(b, "remaining_charge_s", remainingS);
        attr(b, "target_soc_pct", targetSoc);
        attr(b, "plug_state", plugState);
        return b.toString();
    }

    private static void attr(StringBuffer b, String key, long value) {
        if (value >= 0) {
            b.append(key).append("::").append(value).append('\n');
        } else {
            b.append('-').append(key).append('\n');
        }
    }

    private boolean heartbeatLogged;

    /** Missing PPS directory: one line, quiet retries with backoff (PpsDirRetry). */
    private final PpsDirRetry dir = new PpsDirRetry();

    private void write(String content, boolean heartbeat) {
        write(content, heartbeat, System.currentTimeMillis());
    }

    void write(String content, boolean heartbeat, long now) {
        String path = Config.get("lsdextender.ev.file", DEFAULT_FILE);
        if (!dir.due(now)) {
            return;
        }
        boolean say = heartbeat ? !heartbeatLogged : (seq == 1 || seq % 20 == 0);
        try {
            // one write, no truncation (PpsObject): the attributes change together
            PpsObject.writeObject(path, content);
            dir.written(path, seq, now);
            if (say) {
                if (heartbeat) {
                    heartbeatLogged = true;
                    LsdExtender.trace(" ev: heartbeat rewrite of " + path + " every "
                        + Config.getInt("lsdextender.ev.heartbeat_s", 10) + " s (updated_ms bumped, seq kept)");
                } else {
                    LsdExtender.trace(" ev: wrote " + path + " seq=" + seq + " " + content.replace('\n', ' '));
                }
            }
        } catch (Throwable t) {
            if (!dir.failed(path, now) && say) {
                LsdExtender.trace(" ev: write " + path + " failed: " + t);
            }
        }
    }
}
