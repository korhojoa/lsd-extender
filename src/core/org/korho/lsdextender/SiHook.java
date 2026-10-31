/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

import de.vw.mib.asl.framework.internal.framework.ServiceManager;
import de.vw.mib.asl.internal.smartphoneintegration.common.SmartPhoneIntegrationHelper;
import de.vw.mib.asl.internal.smartphoneintegration.target.HsmTarget;
import org.korho.lsdextender.boot.HookInstaller;

/** Bounded owned readiness worker; unavailable ASL leaves startup untouched. */
public final class SiHook implements Runnable {
    private static final long WAIT_MS = 30000L;
    private static final int WAITING = 0;
    private static final int REGISTERING = 1;
    private static final int READY = 2;
    private static final int RETIRED = 3;
    private static final int ACTIVATED = 4;
    private static final int ACTIVATING = 5;
    private static boolean started;
    private final Environment environment;
    private final long deadline;
    private int state;
    private boolean queued;
    private boolean reported;
    private boolean registrationPending;
    private HsmTarget admitted;
    private String reason = "readiness_deadline";

    /** Private startup boundary, supplied by deterministic original-API tests. */
    interface Environment {
        long now();
        void pause() throws InterruptedException;
        HsmTarget lookup();
        void enqueue(Runnable callback);
        void install(HsmTarget target) throws Exception;
        void retire() throws Exception;
        void activate(HsmTarget target);
        void failed(String why);
    }

    SiHook(Environment environment) {
        this.environment = environment;
        deadline = environment.now() + WAIT_MS;
    }

    /** No native lookup, logging or configuration on the SPI caller. */
    public static void start() {
        synchronized (SiHook.class) {
            if (started) { return; }
            started = true;
        }
        Thread worker = new Thread(new SiHook(new NativeEnvironment()), "lsd-extender-own-ASL");
        worker.setDaemon(true);
        worker.start();
    }

    public void run() {
        try {
            for (int attempt = 0; attempt < 300; attempt++) {
                if (activateReady()) { return; }
                if (!canWait()) { break; }
                step();
                environment.pause();
            }
        } catch (Throwable failed) { retire("readiness_failed"); }
        if (activateReady()) { return; }
        retire("readiness_deadline");
        report();
    }

    private boolean canWait() {
        long now = environment.now();
        synchronized (this) { return state != RETIRED && now < deadline; }
    }

    private boolean activateReady() {
        HsmTarget target;
        synchronized (this) {
            if (state != READY) { return state == ACTIVATED; }
            state = ACTIVATING;
            target = admitted;
        }
        // All configuration, sockets, runtime jars and feature loading stay here.
        try {
            environment.activate(target);
            synchronized (this) { state = ACTIVATED; }
        } catch (Throwable failed) {
            synchronized (this) {
                state = RETIRED;
                reason = "activation_failed_partial";
            }
            report();
        }
        return true;
    }

    void step() {
        synchronized (this) { if (queued || state != WAITING) { return; } }
        if (!canWait()) { return; }
        final HsmTarget target = environment.lookup();
        if (target == null) { return; }
        long now = environment.now();
        synchronized (this) {
            if (queued || state != WAITING || now >= deadline) { return; }
            queued = true;
        }
        try {
            environment.enqueue(new Runnable() {
                public void run() { register(target); }
            });
        } catch (Throwable failed) { queueFailed(); }
    }

    private void queueFailed() {
        boolean completed;
        synchronized (this) {
            completed = state == READY;
            state = RETIRED;
            reason = "registration_queue_failed";
            if (completed) { registrationPending = true; }
        }
        if (completed) {
            try {
                environment.enqueue(new Runnable() {
                    public void run() {
                        try {
                            environment.retire();
                            synchronized (SiHook.this) { registrationPending = false; }
                        } catch (Throwable failed) { cleanupUnproved(); }
                    }
                });
            } catch (Throwable failed) { cleanupUnproved(); }
        }
    }

    private void register(HsmTarget target) {
        long now = environment.now();
        synchronized (this) {
            if (state != WAITING || now >= deadline) { return; }
            state = REGISTERING;
            registrationPending = true;
        }
        try { environment.install(target); }
        catch (OwnedAslTarget.CleanupFailure failed) { cleanupUnproved(); return; }
        catch (Throwable failed) {
            synchronized (this) { registrationPending = false; }
            retire("registration_failed"); return;
        }
        now = environment.now();
        synchronized (this) {
            if (state == REGISTERING && now < deadline) {
                registrationPending = false;
                admitted = target;
                state = READY;
                return;
            }
        }
        // A timed-out registration can remove only its own target on ASL.
        try { environment.retire(); }
        catch (Throwable failed) { cleanupUnproved(); return; }
        synchronized (this) { registrationPending = false; }
        retire("registration_late");
    }

    private void cleanupUnproved() {
        synchronized (this) {
            state = RETIRED;
            reason = "registration_cleanup_unproved";
        }
    }

    private void retire(String why) {
        synchronized (this) {
            if (state == READY || state == ACTIVATED || state == RETIRED) { return; }
            state = RETIRED;
            reason = why;
        }
    }

    private void report() {
        String why;
        synchronized (this) {
            if (reported) { return; }
            reported = true;
            why = registrationPending ? "registration_cleanup_unproved" : reason;
        }
        environment.failed(why);
    }

    private static final class NativeEnvironment implements Environment {
        public long now() { return System.currentTimeMillis(); }
        public void pause() throws InterruptedException { Thread.sleep(100L); }
        public HsmTarget lookup() {
            return ServiceManager.aslThreadSwitch == null ? null : SmartPhoneIntegrationHelper.getTarget();
        }
        public void enqueue(Runnable callback) { ServiceManager.aslThreadSwitch.enqueue(callback); }
        public void install(HsmTarget target) throws Exception { OwnedAslTarget.install(target); }
        public void retire() throws Exception { OwnedAslTarget.shutdown(); }
        public void activate(HsmTarget target) {
            LsdExtender.siTargetReady(target);
        }
        public void failed(String why) {
            HookInstaller.log("extension inactive: " + why);
        }
    }
}
