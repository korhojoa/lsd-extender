/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.nativedestination;

import de.vw.mib.asl.framework.internal.framework.ServiceManager;
import de.vw.mib.asl.internal.smartphoneintegration.target.HsmTarget;
import java.util.Properties;
import org.korho.lsdextender.Config;
import org.korho.lsdextender.Deferred;
import org.korho.lsdextender.EventClock;
import org.korho.lsdextender.LsdExtender;
import org.korho.lsdextender.MflListener;
import org.korho.lsdextender.Pps;

/** Navigation calls run on ASL, including one strict PPS snapshot safety check. */
public final class NativeDestinationFeature implements MflListener, Deferred {
    private static final String ROOT = "/ramdisk/pps/gal/";
    private static final long WAIT_MS = 500L;
    private static final long REPLY_WAIT_MS = 65000L;
    private static final long MAX_COUNTER = 4294967295L;
    private final NativeRouteService service;
    private final NativeRouteService.Dispatcher cleanupDispatcher;
    private DestinationWire offer;
    private DestinationState state;
    private String lastDecision;
    private long epoch;
    private long requestedAt;
    private Thread worker;
    private Thread aslThread;
    private boolean closed;
    private boolean queued;
    private boolean cleanupQueued;
    private boolean released;
    private String pendingIdentity;

    static {
        NativeDestinationFeature feature = new NativeDestinationFeature();
        LsdExtender.register(feature);
        LsdExtender.defer(feature);
    }

    private NativeDestinationFeature() { this(new NativeRouteService(), null); }

    NativeDestinationFeature(NativeRouteService supplied, NativeRouteService.Dispatcher cleanup) {
        service = supplied;
        cleanupDispatcher = cleanup;
    }

    public String name() { return "native-destination [" + Build.BANNER + "]"; }
    public boolean onMflEvent(HsmTarget target, int gesture) { return false; }

    public synchronized boolean run() {
        aslThread = Thread.currentThread();
        if (closed || worker != null || !Config.getBool("lsdextender.nav.destination", false)) { return true; }
        worker = new Thread(new Runnable() { public void run() { observe(); } }, "lsd-native-destination");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    private void observe() {
        while (!isClosed()) {
            try { sample(); }
            catch (Throwable failed) { invalidate(); decision("DESTINATION_INPUT_UNAVAILABLE"); }
            try { Thread.sleep(WAIT_MS); }
            catch (InterruptedException stopped) { return; }
        }
    }

    private synchronized boolean isClosed() { return closed; }

    private void sample() throws Exception {
        String identity;
        synchronized (this) { identity = pendingIdentity; pendingIdentity = null; }
        if (identity != null) {
            Pps.write(ROOT + "nav_destination_status", "schema::1\nstate::native_active_identity_unattributed\n"
                    + "native_identity::" + identity + "\n");
        }
        DestinationWire next = DestinationWire.read(ROOT + "nav_destination", false);
        if (!next.available) { invalidate(); decision(DestinationState.UNAVAILABLE); return; }
        boolean replacement;
        synchronized (this) {
            if (closed) { return; }
            replacement = offer == null || !offer.sameOffer(next);
            if (offer != null && next.identity().equals(offer.identity())
                    && (!offer.sameOffer(next) || next.serial < offer.serial)) {
                invalidate();
                decision("INVALID_OFFER_GENERATION");
                return;
            }
            if (!replacement) { offer = next; }
        }
        if (replacement) { startRequest(next); }
        if (expired()) { return; }
        DestinationWire reply = DestinationWire.read(ROOT + "nav_destination_reply", true);
        synchronized (this) {
            if (closed || state == null || !offer.sameOffer(next)) { return; }
            state.reply(1, reply.epoch, reply.session, reply.destination, reply.challenge,
                    reply.serial, reply.available, reply.address);
            if (!state.observing() || queued) { return; }
            queueStep();
        }
    }

    private void startRequest(DestinationWire next) throws Exception {
            long baseline = DestinationWire.read(ROOT + "nav_destination_reply", true).serial;
            String token = newChallenge();
            DestinationState fresh = new DestinationState(1, next.epoch, next.session, next.destination,
                    next.serial, true, next.address, token, baseline);
            synchronized (this) {
                if (closed) { return; }
                invalidate();
                offer = next;
                state = fresh;
                requestedAt = EventClock.millis();
            }
            Pps.write(ROOT + "nav_destination_request", next.request(token, baseline));
            decision("DESTINATION_CHALLENGE_SENT");
    }

    private synchronized boolean expired() {
            long now = EventClock.millis();
            if (state != null && DestinationState.WAITING.equals(state.state())) {
                if (now < 0L || requestedAt < 0L || now < requestedAt) { invalidate(); return true; }
                if (now - requestedAt >= REPLY_WAIT_MS) { state.timeout(); decision(state.state()); return true; }
            }
            return false;
    }

    private void queueStep() {
        final DestinationState selected = state;
        final String transaction = offer.identity();
        final long selectedEpoch = epoch;
        final long selectedAt = requestedAt;
        queued = true;
        try {
            if (ServiceManager.aslThreadSwitch == null) { throw new IllegalStateException("ASL queue unavailable"); }
            boolean scheduled = NativeRouteService.enqueue(new NativeRouteService.Dispatcher() {
                public void enqueue(Runnable task) { ServiceManager.aslThreadSwitch.enqueue(task); }
            }, new Runnable() {
                public void run() {
                    try {
                        synchronized (NativeDestinationFeature.this) { aslThread = Thread.currentThread(); }
                        String result = service.step(selected, transaction, new NativeRouteService.Current() {
                            private boolean checked;
                            private boolean valid;
                            public boolean current() {
                                if (!selected(selected, selectedEpoch)) { return false; }
                                if (!DestinationState.ACCEPTED.equals(selected.state())
                                        && !timely(EventClock.millis(), selectedAt)) {
                                    selected.timeout();
                                    return false;
                                }
                                if (!checked) {
                                    checked = true;
                                    try {
                                        DestinationWire actual = DestinationWire.read(ROOT + "nav_destination", false);
                                        synchronized (NativeDestinationFeature.this) {
                                            valid = offer != null && offer.sameOffer(actual)
                                                    && actual.serial >= offer.serial;
                                        }
                                    } catch (Throwable failed) { valid = false; }
                                    if (!valid) { selected.retire(); }
                                }
                                return valid && selected(selected, selectedEpoch);
                            }
                        });
                        synchronized (NativeDestinationFeature.this) {
                            if (selected(selected, selectedEpoch)) { pendingIdentity = service.observedIdentity(); }
                        }
                        decision(result);
                    } finally {
                        synchronized (NativeDestinationFeature.this) { queued = false; }
                    }
                }
            }, selected);
            if (!scheduled) { queued = false; decision("ASL_ENQUEUE_UNKNOWN_NO_RETRY"); }
        } catch (Throwable uncertain) {
            selected.unknown();
            queued = false;
            decision("ASL_ENQUEUE_UNKNOWN_NO_RETRY");
        }
    }

    private synchronized boolean selected(DestinationState selected, long selectedEpoch) {
        return !closed && state == selected && epoch == selectedEpoch;
    }

    static boolean timely(long now, long started) {
        return started >= 0 && now >= started && now - started < REPLY_WAIT_MS;
    }

    private synchronized void invalidate() {
        epoch++;
        if (state != null) { state.retire(); }
        state = null;
        offer = null;
        pendingIdentity = null;
    }

    private synchronized void decision(String value) {
        if (!value.equals(lastDecision)) {
            lastDecision = value;
            LsdExtender.trace(" native-destination: " + value + " (native work is not cancelled)");
        }
    }

    private String newChallenge() {
        long event = EventClock.millis();
        if (event < 0) { throw new IllegalStateException("HMI clock unavailable"); }
        Properties properties = System.getProperties();
        synchronized (properties) {
            String key = "org.korho.lsdextender.nativeDestination.challenge";
            Object old = properties.get(key);
            long counter = old == null ? 0L : DestinationWire.number((String)old, false);
            if (counter >= MAX_COUNTER) { throw new IllegalStateException("challenge exhausted"); }
            counter++;
            properties.put(key, Long.toString(counter));
            return word(counter) + word(System.currentTimeMillis()) + word(event)
                    + word(System.identityHashCode(this));
        }
    }

    private static String word(long value) {
        String text = Long.toHexString(value & MAX_COUNTER);
        StringBuffer result = new StringBuffer(8);
        for (int i = text.length(); i < 8; i++) { result.append('0'); }
        return result.append(text).toString();
    }

    /** Retire local work first; cleanup failure retains this lifecycle object's authority. */
    public void shutdown() {
        Thread old;
        synchronized (this) {
            closed = true;
            invalidate();
            old = worker;
        }
        stopWorker(old);
        releaseObserver();
    }

    private static void stopWorker(Thread old) {
        if (old != null) {
            old.interrupt();
            try { old.join(1000L); }
            catch (InterruptedException failed) { throw new IllegalStateException("worker cleanup interrupted"); }
            if (old.isAlive()) { throw new IllegalStateException("owned destination worker still active"); }
        }
    }

    private void releaseObserver() {
        synchronized (this) {
            if (released) { return; }
            if (cleanupQueued) { throw new IllegalStateException("owned observer cleanup pending"); }
            if (Thread.currentThread() == aslThread) {
                service.close();
                released = true;
                return;
            }
            if (cleanupDispatcher == null && ServiceManager.aslThreadSwitch == null) {
                throw new IllegalStateException("ASL cleanup unavailable");
            }
            cleanupQueued = true;
            try {
                Runnable cleanup = new Runnable() {
                    public void run() {
                        try { service.close(); synchronized (NativeDestinationFeature.this) { released = true; } }
                        catch (Throwable failed) { decision("OWNED_OBSERVER_CLEANUP_FAILED_HANDLE_RETAINED"); }
                        finally {
                            synchronized (NativeDestinationFeature.this) {
                                cleanupQueued = false;
                                NativeDestinationFeature.this.notifyAll();
                            }
                        }
                    }
                };
                if (cleanupDispatcher == null) { ServiceManager.aslThreadSwitch.enqueue(cleanup); }
                else { cleanupDispatcher.enqueue(cleanup); }
            } catch (Throwable failed) { cleanupQueued = false; throw new IllegalStateException("cleanup queue failed"); }
            if (!released && cleanupQueued) {
                // One capped wait: a clock adjustment or spurious wakeup cannot extend it.
                try { wait(2000L); }
                catch (InterruptedException failed) { throw new IllegalStateException("ASL cleanup wait interrupted"); }
            }
            if (!released) { throw new IllegalStateException("owned observer cleanup unresolved; handle retained"); }
        }
    }
}
