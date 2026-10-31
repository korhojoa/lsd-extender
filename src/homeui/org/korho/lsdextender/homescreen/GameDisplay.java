/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.homescreen;

import de.vw.mib.asl.framework.internal.framework.ServiceManager;
import de.vw.mib.genericevents.Target;
import java.lang.reflect.Field;
import org.dsi.ifc.displaymanagement.DSIDisplayManagement;
import org.korho.lsdextender.EventClock;
import org.korho.lsdextender.Slog;

/** Select an owned game's existing context through the public display service. */
final class GameDisplay implements GamesMenuEntry.Display {
    interface Request extends Runnable { boolean current(); }
    interface Backend {
        boolean available();
        void enqueue(Runnable action);
        boolean select(boolean controls);
    }
    private final GamesMenuEntry games;
    private final Backend backend;
    GameDisplay(GamesMenuEntry games) {
        this(games, new Backend() {
            public boolean available() { return ServiceManager.aslThreadSwitch != null && service() != null; }
            public void enqueue(Runnable action) { ServiceManager.aslThreadSwitch.enqueue(action); }
            public boolean select(boolean controls) {
                DSIDisplayManagement display = service();
                if (display == null) { return false; }
                // SDL qnxdi declares context 90 for displayable 80.
                display.switchContext(controls ? 0 : 90, 0, 0);
                return true;
            }
        });
    }
    GameDisplay(GamesMenuEntry games, Backend backend) { this.games = games; this.backend = backend; }
    private static DSIDisplayManagement service() {
        try {
            Target target = ServiceManager.eventMain.getEventDispatcher().getTarget(0x111700);
            if (target == null) { return null; }
            Field field = target.getClass().getDeclaredField("dsiDisplayManagement");
            field.setAccessible(true);
            return (DSIDisplayManagement)field.get(target);
        } catch (Exception unavailable) { return null; }
    }
    public boolean available() { return backend.available(); }
    public boolean select(GamesMenuEntry.Game game, boolean controls, Runnable ready) {
        return request(game, controls, ready, false);
    }
    public boolean restore(GamesMenuEntry.Game game) {
        if (game == null || !game.started || !games.canRestore(game)) { return false; }
        boolean queued = request(game, true, null, true);
        if (!queued) { Slog.write(" games display restore refused id=" + game.id + " reason=service unavailable " + EventClock.stamp()); }
        return queued;
    }
    private boolean request(final GamesMenuEntry.Game game, final boolean controls, final Runnable ready, final boolean restoring) {
        if (!available()) { return false; }
        backend.enqueue(new Runnable() {
            public void run() {
                synchronized (games) {
                    if (restoring ? !games.canRestore(game) : games.currentGame() != game) { return; } // NOPMD CompareObjectsWithEquals - Exact owned launch.
                    if (ready instanceof Request && !((Request)ready).current()) { return; }
                    try {
                        if (!backend.select(controls)) {
                            Slog.write(" games display request failed id=" + game.id + " reason=service unavailable " + EventClock.stamp());
                            return;
                        }
                    } catch (RuntimeException failure) {
                        Slog.write(" games display request failed id=" + game.id + " reason=" + failure + " " + EventClock.stamp());
                        return;
                    }
                    Slog.write(" games display " + (restoring ? "restore requested" : "requested") + " id=" + game.id + " context=" + (controls ? 0 : 90)
                        + " " + EventClock.stamp());
                    if (ready != null) { ready.run(); }
                }
            }
        });
        return true;
    }
}
