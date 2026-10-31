/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

import de.vw.mib.asl.api.devicehandling.ASLDeviceHandlingFactory;
import de.vw.mib.asl.api.media.services.ResultCode;
import de.vw.mib.asl.api.media.services.SourceCallback;
import de.vw.mib.asl.api.media.services.sources.SourceId;
import de.vw.mib.asl.api.media.services.sources.current.CurrentMediaSourceService;
import de.vw.mib.asl.api.media.services.sources.medialist.Source;
import de.vw.mib.asl.framework.api.dsiproxy.DSIProxy;
import de.vw.mib.asl.framework.api.dsiproxy.DSIProxyFactory;
import de.vw.mib.asl.framework.api.entertainmentmanager.ASLEntertainmentmanagerFactory;
import de.vw.mib.asl.framework.api.entertainmentmanager.EntertainmentManagerService;
import de.vw.mib.asl.framework.api.framework.ASLFrameworkFactory;
import de.vw.mib.asl.framework.internal.framework.ServiceManager;
import de.vw.mib.asl.internal.media.accessor.MediaObjectAccessor;
import de.vw.mib.asl.internal.media.common.ResponseHandler;
import de.vw.mib.asl.internal.media.controller.MediaComponents;
import de.vw.mib.asl.internal.media.controller.MediaController;
import de.vw.mib.asl.internal.media.controller.commands.CommandCallback;
import de.vw.mib.asl.internal.media.controller.commands.CommandState;
import de.vw.mib.asl.internal.media.controller.commands.playback.CmdActivateSource;
import de.vw.mib.asl.internal.media.sourcehandling.state.MediaCollector;
import de.vw.mib.genericevents.EventGeneric;
import de.vw.mib.genericevents.Target;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.dsi.ifc.audio.DSIAudioManagement;
import org.dsi.ifc.audio.DSIAudioManagementListener;
import org.dsi.ifc.audio.DSISound;
import org.dsi.ifc.audio.DSISoundListener;
import org.dsi.ifc.base.DSIListener;
import org.dsi.ifc.media.AudioRoute;
import org.dsi.ifc.media.DSIMediaRouter;
import org.dsi.ifc.media.DSIMediaRouterListener;

/** Own the HMI internal-media connection before an application opens MPL1. */
public final class NativeMediaSession {
    private static final SourceId NATIVE_SOURCE = new SourceId(9, 0);
    private static NativeMediaSession owner;
    private static boolean observing;
    private static int entertainment = -1;
    private static boolean route;
    private static DSIAudioManagement audio;
    private static DSIMediaRouter router;
    private static EntertainmentManagerService manager;
    private final NativeMediaState state;
    private NativeMediaCommand acquisition;
    private static DSIListener responseListener;
    private static Target responseTarget;

    private NativeMediaSession() { state = new NativeMediaState(); }
    private NativeMediaSession(NativeMediaState saved) { state = saved; }

    /** Called only by a launcher worker. No HMI thread waits for audio. */
    public static NativeMediaSession acquire() throws IOException, InterruptedException {
        final NativeMediaSession session = new NativeMediaSession();
        ServiceManager.aslThreadSwitch.enqueue(new Runnable() {
            public void run() {
                if (session.state.closed) { return; }
                try {
                    if (owner != null) { throw new IOException("native media session already owned"); }
                    owner = session;
                    synchronized (NativeMediaBridge.class) { NativeMediaBridge.state = session.state; }
                    // Register the receiver before observe can publish its first
                    // source callback, including an asynchronous first answer.
                    NativeMediaBridge.setService(new BridgeService());
                    observe();
                    if (NativeMediaBridge.sourceKnown) { session.select(); }
                } catch (Throwable failure) { session.fail(String.valueOf(failure)); }
            }
        });
        long until = EventClock.millis() + 15000L;
        try {
            synchronized (session) {
                while (!session.state.ready && session.state.error == null) {
                    long left = until - EventClock.millis();
                    if (left <= 0) { session.state.error = "audio readiness timed out"; break; }
                    session.wait(left);
                }
            }
        } catch (InterruptedException interrupted) {
            session.release();
            throw interrupted;
        }
        if (!session.state.ready) {
            session.release();
            throw new IOException(session.state.error);
        }
        return session;
    }

    private static Object api(Class type) {
        return ASLFrameworkFactory.getASLFrameworkAPI().getServices().getASLComponentRegistry().getAPIInstance(type);
    }

    /** One set of removable listeners per runtime generation. */
    private static void observe() throws Exception {
        if (observing) { return; }
        CurrentMediaSourceService sources = (CurrentMediaSourceService)api(CurrentMediaSourceService.class);
        manager = (EntertainmentManagerService)api(EntertainmentManagerService.class);
        Target target = ServiceManager.eventMain.getEventDispatcher().getTarget(1390000);
        if (sources == null || manager == null || target == null || MediaObjectAccessor.mMediaController == null
                || MediaObjectAccessor.getAudioManager() == null) {
            throw new IOException("HMI media services unavailable");
        }
        DSIProxy proxy = DSIProxyFactory.getDSIProxyAPI().getDSIProxy();
        audio = (DSIAudioManagement)proxy.getService(target, DSIAudioManagement.class);
        router = (DSIMediaRouter)proxy.getService(target, DSIMediaRouter.class);
        DSIListener audioListener = listener(DSIAudioManagementListener.class);
        // Responses are delivered to the target which issued the request.
        // Observe the actual HMI sound target, not a separate DSI client.
        proxy.addResponseListener(target, DSIAudioManagementListener.class, audioListener);
        responseTarget = target; responseListener = audioListener;
        DsiSubscribe.whenRegistered("native audio", DSIAudioManagement.class, new int[]{2, 3}, audioListener);
        DsiSubscribe.whenRegistered("native audio", DSIMediaRouter.class, new int[]{2},
            listener(DSIMediaRouterListener.class));
        DsiSubscribe.whenRegistered("native audio", DSISound.class, new int[]{(24)},
            listener(DSISoundListener.class));
        NativeMediaBridge.observe(sources);
        observing = true;
    }

    private static void sourceChanged(Source source) {
                NativeMediaSession session = owner;
                if (session == null || session.state.closed) { return; }
                if (!session.state.switched) { session.select(); }
                else if (session.state.ready && source != null && !nativeSource(source)) {
                    // A stock file player would share MPL1. End only our app

                    NativeAppSession app = NativeApps.find("launcher");
                    if (app != null) { app.quit(); }
                    log("media source changed; requested game exit");
                }
    }

    private static boolean nativeSource(Source source) {
        return source != null && SourceId.isEqual(NATIVE_SOURCE, source.getSourceId());
    }

    private void select() {
        if (state.closed || state.switched) { return; }
        // The same command MediaController.activateMediaSource builds, but
        // owned here: CommandQueue.addCommand can throw an exception that
        // belongs to ANOTHER command (CommandQueueOptimizer aborts older
        // queued commands and their callbacks run on this stack, uncaught;

        // queued and later granted). Our command's own state is the result.
        final CmdActivateSource command;
        final MediaCollector collector;
        try {
            if (ASLDeviceHandlingFactory.getDeviceHandlingApi().getDevices().getBySourceId(NATIVE_SOURCE) == null) {
                throw new IOException("WLAN source unavailable: deviceType=9 slot=0");
            }
            state.previousSource = NativeMediaBridge.currentSource;
            state.previousContext = ASLEntertainmentmanagerFactory.getEntertainmentmanagerApi().getCurrentContext();
            state.switched = true;
            log("acquiring MPL1; previous context=" + state.previousContext + " source=" + state.previousSource);
            collector = components().getSources().getCollector(NATIVE_SOURCE);
            command = new CmdActivateSource(components(), collector, true, true);
        } catch (Throwable failure) { state.selectionComplete = true; fail(String.valueOf(failure)); return; }
        command.setCommandCallback(new CommandCallback() {
            public void onCommandExecutionFinished(CommandState done) {
                if (!done.isTerminated()) { return; }
                selected(done.isCompleted() ? ResultCode.OK : ResultCode.NOK,
                    MediaCollector.createSourceState(collector.getMedium()));
            }
        });
        try {
            MediaObjectAccessor.mMediaController.addCommand(command);
        } catch (Throwable failure) {
            log("source switch: addCommand threw " + failure + " at " + NativeMediaDiagnostics.frames(failure)
                + "; own command " + (command.isTerminated() ? "terminated" : "still queued, waiting for its result"));
            // Terminated: our callback already ran with the real result.
            // Not terminated: it is queued and its callback will decide.
        }
    }

    private void selected(ResultCode result, Source source) {
        state.selectionComplete = true;
        if (state.closed) { restore(); return; }
        if (result != ResultCode.OK || source == null || source.isPlayable()) {
            fail("could not reserve an idle media source: " + result);
            return;
        }
        if (!MediaObjectAccessor.getAudioManager().isAudioConnectionAvailable()) {
            fail("audio management unavailable");
            return;
        }
        acquisition = new NativeMediaCommand(new AudioRequest());
        MediaObjectAccessor.mMediaController.addCommand(acquisition);
    }

    /** MediaController keeps its MediaComponents private; the queue needs it. */
    private static MediaComponents components() throws Exception {
        Field field = MediaController.class.getDeclaredField("allComponents");
        field.setAccessible(true);
        return (MediaComponents)field.get(MediaObjectAccessor.mMediaController);
    }

    private final class AudioRequest implements NativeMediaCommand.Audio {
        public boolean closed() { return state.closed; }
        public void acquire(ResponseHandler response) {
            log("queued media acquisition started");
            MediaObjectAccessor.getAudioManager().rqDummyAudioConnection(response);
        }
        public void fade(ResponseHandler response) {
            state.granted = true;
            MediaObjectAccessor.getAudioManager().rqMediaFadeIn(response);
        }
        public void finished(boolean success) {
            if (state.closed) { return; }
            if (!success) { fail("queued media acquisition failed"); return; }
            state.faded = true;
            // The same request HsmSoundMediaRouterHandling sends for an
            // internal media connection: MPL1 (input 1) -> TDM1_OUT_MPL (1).
            router.setAudioRoutes(new AudioRoute[]{new AudioRoute(1, 1, 0)});
            log("requested route MPL1 -> TDM1_OUT_MPL");
            unlock();
            audio.getActiveEntertainmentConnection(1);
            changed();
        }
    }

    private void unlock() {
        state.unlocked = false;
        EventGeneric event = ServiceManager.mGenericEventFactory.newEvent(4000012);
        event.setInt(0, 20);
        MediaObjectAccessor.getAudioManager().sendSafe(event);
        audio.getVolumelock(20, 1);
    }

    private synchronized void changed() {
        // No wait for a route report: the stock HMI sets the MPL1 route and
        // never waits for one, and sends nothing when it is already routed

        if (!state.closed && state.error == null && !state.ready && state.granted && state.faded && state.unlocked && entertainment == 20) {
            state.ready = true;
            log("ready connection=20 route=MPL1 volumeLock=false; starting native PCM");
            notifyAll();
        }
    }

    private synchronized void fail(String reason) {
        state.error = reason;
        log("failed: " + reason);
        notifyAll();
    }

    /** Called after the child process has closed PCM, including failed launches. */
    public void release() {
        state.closed = true;
        ServiceManager.aslThreadSwitch.enqueue(new Runnable() {
            public void run() {
                if (acquisition != null && !acquisition.isTerminated()) { acquisition.abort("native launch cancelled"); }
                restore();
            }
        });
    }

    private void restore() {
        if (owner != this) { return; }
        // A queued source switch can complete after the worker timed out.
        // Its callback performs the matching restoration before releasing ownership.
        if (state.switched && !state.selectionComplete) { return; }
        // Acquisition can fail before media services exist. There is no stock
        // source or context to restore until our selection actually began.
        if (!state.switched) { clearOwnership(); return; }

        int context = ASLEntertainmentmanagerFactory.getEntertainmentmanagerApi().getCurrentContext();
        if (context != 2 || !nativeSource(NativeMediaBridge.currentSource)) { clearOwnership(); return; }
        try {
            if (state.previousSource != null) {
                MediaObjectAccessor.mMediaController.activateMediaSource(state.previousSource.getSourceId(), new SourceCallback() {
                    public void response(ResultCode result, Source source) {
                        // A replacement runtime resumes the retained restoration.
                        // An old queued response must not alter its source/context.
                        if (owner != NativeMediaSession.this) { return; } // NOPMD CompareObjectsWithEquals - Exact owned session.
                        if (result == ResultCode.OK && state.previousContext == 2 && state.previousSource.isPlayable()
                                && !state.previousSource.isPlaying()) {
                            EventGeneric pause = ServiceManager.mGenericEventFactory.newEvent(0,
                                MediaObjectAccessor.mMediaController.getTargetId(), 0x40000011);
                            pause.setInt(0, 2);
                            MediaObjectAccessor.mMediaController.sendSafe(pause);
                        }
                        restoreContext();
                        clearOwnership();
                    }
                });
            } else { restoreContext(); clearOwnership(); }
            log("released; restoring context=" + state.previousContext + " source=" + state.previousSource);
        } catch (Throwable failure) { clearOwnership(); log("restore failed: " + failure); }
    }

    private void clearOwnership() {
        if (owner == this) {
            owner = null;
            synchronized (NativeMediaBridge.class) {
                if (NativeMediaBridge.state == state) { NativeMediaBridge.state = null; } // NOPMD CompareObjectsWithEquals - Retire only this exact lease.
            }
        }
    }

    /** Reconnect observers to retained data without interrupting native PCM. */
    public static void start() {
        NativeMediaBridge.setService(new BridgeService());
        final NativeMediaState saved = NativeMediaBridge.state;
        if (saved == null) { return; }
        onAsl(new Runnable() {
            public void run() {
                owner = new NativeMediaSession(saved);
                try { observe(); }
                catch (Exception failure) { throw new IllegalStateException(String.valueOf(failure)); }
                if (saved.closed) { owner.restore(); }
            }
        });
    }

    /** Called before the loader removes this generation's DSI notifications. */
    public static void shutdown() {
        if (!observing && responseListener == null) { NativeMediaBridge.setService(null); return; }
        onAsl(new Runnable() {
            public void run() {
                NativeMediaBridge.setService(null);
                if (responseListener != null) {
                    DSIProxyFactory.getDSIProxyAPI().getDSIProxy().removeResponseListener(
                        responseTarget, DSIAudioManagementListener.class, responseListener);
                }
                responseListener = null; responseTarget = null; observing = false;
                owner = null; audio = null; router = null; manager = null;
            }
        });
    }

    private static final class BridgeService implements NativeMediaBridge.Service {
        public void sourceChanged(Source source) { NativeMediaSession.sourceChanged(source); }
        public void release(final NativeMediaState saved) {
            ServiceManager.aslThreadSwitch.enqueue(new Runnable() {
                public void run() {
                    if (owner != null && owner.state == saved) { owner.restore(); } // NOPMD CompareObjectsWithEquals - only this lease may restore the source.
                }
            });
        }
    }

    private static void onAsl(final Runnable work) {
        final org.korho.lsdextender.ThreadTask task = new org.korho.lsdextender.ThreadTask(work);
        ServiceManager.aslThreadSwitch.enqueue(task);
        task.await();
    }

    private void restoreContext() {
        if (state.previousContext != 2 && state.previousContext > 0) { manager.requestContext(state.previousContext); }
    }

    private static DSIListener listener(Class type) {
        return (DSIListener)Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, new InvocationHandler() {
            public Object invoke(Object proxy, Method method, Object[] args) {
                String name = method.getName();
                if (name.equals("hashCode")) { return new Integer(System.identityHashCode(proxy)); }
                if (name.equals("equals")) { return Boolean.valueOf(proxy == args[0]); } // NOPMD CompareObjectsWithEquals - Proxy Object.equals uses identity.
                if (name.equals("toString")) { return "NativeMediaObserver"; }
                observeEvent(name, args);
                return null;
            }
        });
    }

    private static int number(Object[] args, int index) { return ((Number)args[index]).intValue(); }

    private static void observeEvent(String name, Object[] args) {
        NativeMediaSession session = owner;
        if (name.equals("updateActiveEntertainmentConnection") && number(args, 1) == 1) {
            entertainment = number(args, 2) == 1 ? number(args, 0) : -1;
            if (session != null) { log("entertainment=" + entertainment); }
        } else if (name.equals("updateActiveAudioRoutes")) {
            route = NativeMediaDiagnostics.hasMediaRoute((AudioRoute[])args[0], number(args, 1));
            if (session != null) { log("MPL1 route=" + route + " routes=" + NativeMediaDiagnostics.routes((AudioRoute[])args[0]) + " validity=" + args[1]); }
        } else if (session != null) { session.observeSessionEvent(name, args); }
        if (session != null) { session.changed(); }
    }

    private void observeSessionEvent(String name, Object[] args) {
        SessionEvents.observe(this, name, args);
    }

    /** Decode the per-session sound callbacks without changing their order. */
    private static final class SessionEvents {
        static void observe(NativeMediaSession session, String name, Object[] args) {
            if (name.equals("responseVolumelock") && number(args, 0) == 20 && number(args, 1) == 1) {
                session.state.unlocked = !((Boolean)args[2]).booleanValue();
                log("connection=20 volumeLock=" + args[2]);
            } else if (name.equals("updateVolume") && number(args, 1) == 1) {
                log("volume connection=" + args[0] + " value=" + args[2] + " validity=" + args[3]);
            } else if (name.equals("updateActiveConnection")) {
                log("foreground=" + args[0] + " terminal=" + args[1] + " validity=" + args[2]);
            } else if (name.equals("pauseConnection") || name.equals("startConnection")
                    || name.equals("stopConnection") || name.equals("fadedIn")) {
                log(name + " connection=" + args[0]);
                if (name.equals("fadedIn") && number(args, 0) == 20 && session.state.ready && nativeSource(NativeMediaBridge.currentSource)) {
                    session.unlock();
                }
            }
        }
    }

    private static void log(String text) {
        Slog.write(" native audio: mono_ms=" + EventClock.millis()
            + " wall_ms=" + System.currentTimeMillis() + " " + text);
    }
}
