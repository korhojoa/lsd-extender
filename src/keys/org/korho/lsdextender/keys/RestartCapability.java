/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.keys;

import de.vw.mib.asl.framework.api.dsiproxy.DSIProxy;
import de.vw.mib.asl.framework.api.dsiproxy.DSIProxyFactory;
import de.vw.mib.asl.framework.api.dsiproxy.DSIServiceStateListener;
import de.vw.mib.asl.framework.internal.framework.ServiceManager;
import de.vw.mib.genericevents.Target;
import de.vw.mib.timer.TimerManager;
import de.vw.mib.timer.Invoker;
import org.dsi.ifc.keypanel.DSIKeyPanel;
import org.dsi.ifc.powermanagement.DSIPowerManagement;
import org.korho.lsdextender.EventClock;
import org.korho.lsdextender.KeyListener;
import org.korho.lsdextender.LsdExtender;

/** Live services and owned question lifetime. Keys can only open the question. */
public final class RestartCapability implements KeyListener, RestartPrompt.Owner {
    interface Platform {
        void enqueue(Runnable task);
        boolean isAslThread();
        DSIProxy proxy();
        Target target();
        TimerManager timers();
        Invoker invoker();
        long now();
        void log(String decision, long now);
    }
    private final Platform platform;
    private final RestartQuestion question;
    private final RestartPrompt prompt;
    private final RestartTimers timers;
    private final RestartWindow window = new RestartWindow();
    private static final long NO_DELAY = 0L;
    private final State input = new State(DSIKeyPanel.class);
    private final State power = new State(DSIPowerManagement.class);
    private DSIProxy proxy;
    private boolean closed;
    private boolean starting;
    private boolean cleanupQueued;
    private String cleanupFailure;
    private boolean cleanupDone;
    private static final int CLEANUP_WAIT_MS = 2000;
    private long epoch;

    public RestartCapability() {
        this(new NativePlatform(), null);
    }

    RestartCapability(Platform supplied, RestartPrompt dialog) {
        if (supplied == null) { throw new IllegalArgumentException("platform required"); }
        platform = supplied;
        prompt = dialog == null ? new RestartQuestionView(this) : dialog;
        question = new RestartQuestion(new RestartQuestion.Clock() { public long now() { return platform.now(); } },
            new RestartQuestion.Output() {
                public void open(final long ticket) {
                    openQuestion(ticket);
                }
                public void close(String reason) { prompt.close(reason); }
                public void log(String decision) { RestartCapability.this.log(decision); }
            });
        timers = new RestartTimers(platform, new RestartTimers.Owner() {
            public void unavailable() { question.service(false); log("native-timer-unavailable"); }
            public void log(String decision) { RestartCapability.this.log(decision); }
        });
    }

    /** Explicit caller retains this object even if setup or cleanup reports uncertainty. */
    public void start() {
        final long ticket;
        synchronized (this) {
            if (closed || starting || input.attempted || power.attempted) { return; }
            starting = true; ticket = ++epoch;
        }
        question.begin();
        try { platform.enqueue(new Runnable() { public void run() { setup(ticket); } }); }
        catch (Throwable uncertain) {
            synchronized (this) { closed = true; epoch++; starting = false; }
            question.shutdown();
            throw new IllegalStateException("restart setup enqueue unknown; retain cleanup authority");
        }
    }

    private void setup(long ticket) {
        synchronized (this) { if (closed || epoch != ticket) { starting = false; notifyAll(); return; } }
        try {
            DSIProxy selected = platform.proxy();
            if (selected == null || platform.target() == null) { throw new IllegalStateException("native dependencies unavailable"); }
            synchronized (this) { if (closed || epoch != ticket) { return; } proxy = selected; }
            add(input, selected);
            synchronized (this) { if (closed) { return; } }
            add(power, selected);
        } catch (Throwable failed) {
            synchronized (this) { closed = true; epoch++; }
            question.shutdown();
            platform.log("setup-failed-cleanup-retained", platform.now());
        } finally { synchronized (this) { starting = false; } }
    }

    private void add(State state, DSIProxy selected) {
        synchronized (this) { if (closed) { return; } state.attempted = true; }
        selected.addServiceStateListener(state.type, 0, state);
    }

    private void changed(State state, boolean available) {
        boolean live;
        synchronized (this) {
            if (closed || !state.attempted) { return; }
            state.ready = available; epoch++;
            live = input.ready && power.ready;
        }
        question.service(live);
    }

    /** Generic delivery lacks validity; it can never authorize a restart. */
    public boolean onKey(int keyboard, int key, int state) { return false; }

    /** Called only from the existing Keys notification, before its unchanged fanout. */
    public boolean onRawKey(int keyboard, int key, int state, int validity) {
        try {
            if (!RestartSettings.acceptsRaw(this, keyboard, key, state)) { return false; }
            question.warmup(org.korho.lsdextender.Config.getInt("lsdextender.restart.warmup_ms", 60000));
            question.key(keyboard, key, state, validity);
            long remaining = question.remaining();
            if (remaining > NO_DELAY) { timers.after(remaining + 1L, new Runnable() { public void run() { question.tick(); } }); }
        } catch (Throwable refused) { log("key-processing-refused-no-restart"); }
        return false;
    }

    public boolean current(long ticket) {
        long now = platform.now();
        synchronized (this) { if (closed || !input.ready || !power.ready) { return false; } }
        return RestartSettings.current(window, ticket, now) && question.current(ticket);
    }
    private void openQuestion(final long ticket) {
        if (!RestartSettings.enabled()) { cancel("restart-disabled"); return; }
        long now = platform.now();
        if (!window.open(ticket, now)) { cancel("clock-unavailable"); return; }
        try {
            timers.after(15000L, new Runnable() { public void run() { if (question.current(ticket) || question.confirmed(ticket)) { cancel("timeout"); } } });
            platform.enqueue(new Runnable() { public void run() { if (current(ticket)) { prompt.open(ticket); } } });
        } catch (Throwable failed) { cancel("question-enqueue-unknown"); }
    }
    public void activated(final long ticket) {
        if (!current(ticket)) { cancel("activation-revoked"); return; }
        if (!window.activate(ticket, platform.now())) { return; }
        log("confirmation-visible");
    }
    public void confirm(final long ticket) {
        long now = platform.now();
        if (!RestartSettings.active(window, ticket, now)) { cancel("confirmation-expired"); return; }
        if (!question.consume(ticket)) { log("stale-touch-refused"); return; }
        try {
            platform.enqueue(new Runnable() { public void run() {
                try { attemptConfirmedTouch(ticket); }
                catch (Throwable failed) { cancel("native-confirmation-uncertain-no-retry"); }
            } });
        } catch (Throwable failed) { cancel("confirmation-enqueue-unknown-no-retry"); }
    }
    public void cancel(String reason) { question.cancel(reason); }
    public void log(String decision) { platform.log(decision, platform.now()); }

    private void attemptConfirmedTouch(long confirmation) {
        long now = platform.now();
        if (!RestartSettings.active(window, confirmation, now)) { cancel("confirmation-expired-before-dispatch"); return; }
        if (!question.confirmed(confirmation)) { log("confirmation-revoked-before-dispatch"); return; }
        DSIProxy selected;
        long ticket;
        synchronized (this) {
            if (closed || !input.ready || !power.ready || proxy == null) {
                throw new IllegalStateException("restart capability revoked");
            }
            selected = proxy; ticket = epoch;
        }
        Target target = platform.target();
        if (target == null) { throw new IllegalStateException("native target unavailable"); }
        Object service = selected.getService(target, DSIPowerManagement.class, 0);
        if (!(service instanceof DSIPowerManagement)) { throw new IllegalStateException("power service unavailable"); }
        synchronized (this) {
            if (closed || epoch != ticket || !input.ready || !power.ready || !question.dispatch(confirmation)) {
                throw new IllegalStateException("restart service epoch changed");
            }
        }
        // The owned ticket was consumed before this call. A void return is not completion.
        log("touch-confirmed-native-request");
        ((DSIPowerManagement)service).rebootSystem();
        log("confirmed-native-request-returned-not-completion");
    }

    /** Revoke immediately; only a non-ASL worker waits for native retirement. */
    public void shutdown() {
        synchronized (this) { closed = true; epoch++; }
        question.shutdown();
        if (platform.isAslThread()) {
            cleanup();
            verifyCleanup();
            return;
        }
        boolean enqueue;
        synchronized (this) {
            if (cleanupDone) { return; }
            enqueue = !cleanupQueued;
            if (enqueue) { cleanupQueued = true; cleanupFailure = null; }
        }
        if (enqueue) {
            try { platform.enqueue(new Runnable() { public void run() { cleanup(); } }); }
            catch (Throwable failed) {
                synchronized (this) { cleanupFailure = "restart cleanup enqueue unknown; handle retained"; notifyAll(); }
                throw new IllegalStateException("restart cleanup enqueue unknown; handle retained");
            }
        }
        awaitCleanup();
    }

    private void awaitCleanup() {
        long deadline = System.currentTimeMillis() + CLEANUP_WAIT_MS;
        int waits = 0;
        synchronized (this) {
            while (cleanupQueued && cleanupFailure == null) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0 || waits++ >= CLEANUP_WAIT_MS / 50) {
                    throw new IllegalStateException("restart cleanup timed out; handle retained");
                }
                try { wait(Math.min(remaining, 50L)); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("restart cleanup interrupted; handle retained");
                }
            }
        }
        RestartPromptWait.released(prompt, CLEANUP_WAIT_MS);
        verifyCleanup();
    }

    private synchronized void verifyCleanup() {
        if (cleanupQueued || input.attempted || power.attempted || starting || !prompt.released() || !timers.isEmpty() || cleanupFailure != null) {
            throw new IllegalStateException(cleanupFailure == null ? "restart cleanup pending; handle retained" : cleanupFailure);
        }
    }

    private void cleanup() {
        String failure = null;
        try { timers.close(); }
        catch (Throwable failed) { failure = "restart timer cleanup failed; handle retained: " + failed; }
        try { remove(input); }
        catch (Throwable failed) { failure = "restart input listener cleanup failed; handle retained: " + failed; }
        try { remove(power); }
        catch (Throwable failed) { failure = "restart power listener cleanup failed; handle retained: " + failed; }
        synchronized (this) { cleanupFailure = failure; cleanupDone = failure == null && !starting; cleanupQueued = false; notifyAll(); }
        if (failure != null) { platform.log("listener-cleanup-failed-retained", platform.now()); }
    }

    private void remove(State state) {
        DSIProxy selected;
        synchronized (this) { if (!state.attempted) { return; } selected = proxy; }
        selected.removeServiceStateListener(state.type, 0, state);
        synchronized (this) { state.attempted = false; state.ready = false; }
    }

    private final class State implements DSIServiceStateListener {
        private final Class type;
        private boolean attempted;
        private boolean ready;
        State(Class service) { type = service; }
        public void registered(String name, int instance) {
            if (instance == 0 && type.getName().equals(name)) { changed(this, true); }
        }
        public void unregistered(String name, int instance) {
            if (instance == 0 && type.getName().equals(name)) { changed(this, false); }
        }
    }

    private static final class NativePlatform implements Platform {
        public void enqueue(Runnable task) {
            if (ServiceManager.aslThreadSwitch == null) { throw new IllegalStateException("ASL queue unavailable"); }
            ServiceManager.aslThreadSwitch.enqueue(task);
        }
        public boolean isAslThread() {
            if (ServiceManager.eventMain == null || ServiceManager.eventMain.getEventDispatcher() == null
                || ServiceManager.eventMain.getEventDispatcher().getTask(("hsmtask")) == null) {
                throw new IllegalStateException("ASL thread identity unavailable; cleanup handle retained");
            }
            return ServiceManager.eventMain.getEventDispatcher().getTask(("hsmtask")).isInCurrentMIBThread();
        }
        public DSIProxy proxy() { return DSIProxyFactory.getDSIProxyAPI().getDSIProxy(); }
        public Target target() { return LsdExtender.nativeTarget(); }
        public TimerManager timers() { return ServiceManager.timerManager; }
        public Invoker invoker() { return ServiceManager.aslThreadSwitch; }
        public long now() { return EventClock.millis(); }
        public void log(String decision, long now) {
            LsdExtender.trace(" restart hold: " + decision + " mono_ms=" + now + " wall_ms=" + System.currentTimeMillis());
        }
    }
}
