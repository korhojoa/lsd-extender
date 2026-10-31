/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.homescreen;

import org.korho.lsdextender.EventClock;
import org.korho.lsdextender.KeyListener;
import org.korho.lsdextender.TouchListener;
import org.korho.lsdextender.Slog;
import de.vw.mib.widgets.models.WidgetModel;

/** Gesture work is queued. Holding HOME or MENU also quits without an overlay. */
public final class GameQuitOverlay implements TouchListener, KeyListener {
    private static final int TWO_FINGER_RELEASE = 12, KEY_PRESSED = 1, TAP_TRAVEL = 24;
    interface Clock { long millis(); }
    private final Clock clock;
    private final GamesMenuEntry games;
    private final OwnedSettingsPage.Host host;
    private final OwnedSettingsPage overlay;
    private volatile boolean stopped;
    private long touchStart = -1L, keyStart = -1L;
    private int heldKey = -1, heldKeyboard = -1;
    private boolean twoFingers;
    private int touchX, touchY;
    private long pressSerial;
    private GamesMenuEntry.Game heldGame;
    private volatile GamesMenuEntry.Game overlayGame;
    private volatile boolean admitted;
    GameQuitOverlay(GamesMenuEntry games, OwnedSettingsPage.Host host) {
        this(games, host, new Clock() { public long millis() { return EventClock.millis(); } });
    }
    GameQuitOverlay(GamesMenuEntry games, OwnedSettingsPage.Host host, Clock clock) {
        this.games = games; this.host = host; this.clock = clock;
        overlay = new OwnedSettingsPage(OwnedViewNames.allocate(OwnedSettingsPage.VIEW + ".game-quit"), new OwnedSettingsPage.Host() {
            public boolean available() { return GameQuitOverlay.this.host.available(); }
            public void enqueue(Runnable action) { GameQuitOverlay.this.host.enqueue(action); }
            public void enter(String name, de.vw.mib.desktop.ViewHandlerProvider provider) {
                GameQuitOverlay.this.host.enter(name, provider);
                admitted = true;
                requestControls();
            }
            public void returnToMenu() {
                admitted = false;
                GameQuitOverlay.this.host.returnToMenu();
                GameQuitOverlay.this.host.enqueue(new Runnable() { public void run() { GameQuitOverlay.this.games.show(overlayGame); } });
            }
        }, new QuitTree());
    }
    private final class QuitTree extends OwnedSettingsPage.NativeTree {
        QuitTree() { super(null); }
        String title() { return "Game controls"; }
        public void create(Runnable back) {
            super.create(back);
            ((de.vw.mib.widgets.View)view()).set_colorBackground(new de.vw.mib.graphics.Color(0));
        }
        OwnedSettingsPage.Controls createControls(Runnable back, String font, int width, int height) {
            return new QuitControls(back, width, height);
        }
        OwnedSettingsPage.NativeTree replacement() { return new QuitTree(); }
        public void deactivate() {
            admitted = false;
            super.deactivate();
            host.enqueue(new Runnable() { public void run() { games.show(overlayGame); } });
        }
    }
    private final class QuitControls implements OwnedSettingsPage.Controls {
        private final Runnable backAction;
        private final OwnedSettingsSkin.Context skin;
        private static final int ROW_HEIGHT = 94, PANEL_HEIGHT = 300;
        private final OwnedSettingsWidgets.ListRows rows;
        private final int left, top, panelWidth;
        private boolean live, destroyed;
        QuitControls(final Runnable back, int width, int height) {
            this.backAction = back; skin = OwnedSettingsSkin.active().context();
            panelWidth = Math.min(640, width);
            left = (width - panelWidth) / 2; top = Math.max(0, (height - PANEL_HEIGHT) / 2);
            rows = new OwnedSettingsWidgets.ListRows(skin, width, height, new OwnedSettingsWidgets.RowLayout() {
                public void apply(OwnedSettingsWidgets.Row row) {
                    row.item.set_width(panelWidth); row.item.set_height(ROW_HEIGHT);
                    row.button.set_x(0); row.button.set_y(0); row.button.set_width(panelWidth); row.button.set_height(ROW_HEIGHT);
                    row.button.get_imageBackgroundRef().set_width(panelWidth);
                    row.caption.set_x(30); row.caption.set_width(panelWidth - 60);
                }
            });
            rows.list.set_x(left); rows.list.set_y(top + 90); rows.list.set_width(panelWidth);
            rows.list.set_padding(new de.vw.mib.graphics.Insets()); rows.list.set_height(ROW_HEIGHT * 2);
            rows.list.set_spacingVertical(0);
            rows.cursor.set_width(panelWidth);
            rows.scrollbar.set_x(left + panelWidth - rows.scrollbar.get_width());
            rows.scrollbar.set_y(top + 90); rows.scrollbar.set_height(ROW_HEIGHT * 2); rows.scrollbar.set_visible(false);
            render();
        }
        private void render() {
            rows.replace(new OwnedSettingsWidgets.Row[] {
                rows.row("game-quit", "Quit game", "", false, true, OwnedSettingsSkin.ROW, new Runnable() {
                    public void run() { if (live && games.isBusy()) { games.quit(); } }
                }),
                rows.row("game-continue", "Continue", "", false, true, OwnedSettingsSkin.ROW, backAction)
            });
        }
        public WidgetModel[] layeredChildren(WidgetModel[] headers) {
            WidgetModel[] result = new WidgetModel[headers.length + 2];
            System.arraycopy(headers, 0, result, 0, headers.length);
            for (int i = 0; i < headers.length; i++) {
                WidgetModel item = headers[i];
                item.set_y(top);
                if (item instanceof de.vw.mib.widgets.Button) { item.set_x(left + 20); item.set_width(60); }
                else if (item instanceof de.vw.mib.widgets.TextArea) { item.set_x(left + 100); item.set_width(panelWidth - 160); }
                else { item.set_x(left); item.set_width(panelWidth); item.set_height(PANEL_HEIGHT); }
            }
            result[headers.length] = rows.list; result[headers.length + 1] = rows.scrollbar;
            return result;
        }
        public boolean currentStyles() { return !destroyed && skin.current(); }
        public void back() { backAction.run(); }
        public void init() { rows.initialized(rows.list.getView()); }
        public void activate() {
            if (destroyed || live) { return; }
            live = true; rows.active(true); render();
        }
        public void deactivate() { live = false; rows.active(false); rows.retire(); }
        public void deinit() { deactivate(); rows.deinitialized(); }
        public void destroy() { deinit(); destroyed = true; }
    }
    public void onTouch(int gesture, int x, int y, long time) {
        if (stopped || !games.isBusy()) { return; }
        trackTouch(gesture, x, y, time);
        if ((gesture == 3 || gesture == TWO_FINGER_RELEASE) && touchStart >= 0) {
            long elapsed = time - touchStart; touchStart = -1L;
            if (twoFingers && nearTouch(x, y) && elapsed >= 0 && elapsed <= 600L) {
                host.enqueue(new Runnable() { public void run() {
                    if (!stopped && games.isBusy()) {
                        final GamesMenuEntry.Game game = games.currentGame();
                        if (!games.canReturn()) {
                            Slog.write(" games quit overlay refused reason=no return route " + EventClock.stamp());
                            return;
                        }
                        overlayGame = game;
                        overlay.refreshStyles(new Runnable() { public void run() { openControls(game); } });
                        openControls(game);
                    }
                } });
            }
        }
    }
    private void trackTouch(int gesture, int x, int y, long time) {
        if (gesture == 4 || gesture == 8 || gesture == TWO_FINGER_RELEASE) {
            touchStart = time; touchX = x; touchY = y;
            twoFingers = gesture != 4;
        }
        if (gesture == 5 || gesture == 6 || gesture == 7 || gesture == 9 || gesture == 10
            || gesture == 11 && (!twoFingers || !nearTouch(x, y))) { touchStart = -1L; }
        // Small simultaneous contacts may be classified as zoom. Only an
        // already armed two-finger contact may retain its original time/point.
    }
    private boolean nearTouch(int x, int y) {
        long dx = (long)x - touchX, dy = (long)y - touchY;
        return dx >= -TAP_TRAVEL && dx <= TAP_TRAVEL && dy >= -TAP_TRAVEL && dy <= TAP_TRAVEL;
    }
    private void openControls(GamesMenuEntry.Game game) {
        if (stopped || games.currentGame() != game) { return; } // NOPMD CompareObjectsWithEquals - Exact owned launch.
        if (!overlay.ready()) {
            Slog.write(" games quit overlay deferred reason=page unavailable " + EventClock.stamp());
            return;
        }
        if (admitted) { requestControls(); }
        else { overlay.open(); }
    }
    private void requestControls() {
        final GamesMenuEntry.Game game = overlayGame;
        boolean queued = games.controls(game, new GameDisplay.Request() {
            public boolean current() { return !stopped && admitted && overlay.ready() && overlayGame == game; } // NOPMD CompareObjectsWithEquals - Exact owned overlay.
            public void run() {
                Slog.write(" games quit overlay display requested id=" + game.id + " " + EventClock.stamp());
            }
        });
        Slog.write(" games quit overlay display queued=" + queued + " " + EventClock.stamp());
    }
    public boolean onKey(int keyboard, int key, int state) {
        observeKey(keyboard, key, state, clock.millis()); return false;
    }
    synchronized void observeKey(int keyboard, int key, int state, long time) {
        if (stopped || !games.isBusy() || (key != 78 && key != 116)) { return; }
        if (state == KEY_PRESSED) {
            if (key == heldKey && keyboard == heldKeyboard && keyStart >= 0) { return; }
            heldKey = key; heldKeyboard = keyboard; keyStart = time;
            heldGame = games.currentGame();
            final long serial = ++pressSerial;
            final GamesMenuEntry.Game game = heldGame;
            if (time < 0L || game == null) { return; }
            Thread timer = new Thread(new Runnable() {
                public void run() {
                    try { Thread.sleep(2000L); }
                    catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); return; }
                    hardwareQuit(serial, game, clock.millis());
                }
            }, "lsd-game-key-hold");
            timer.setDaemon(true); timer.start();
        } else if (key == heldKey && keyboard == heldKeyboard) {
            hardwareQuit(pressSerial, heldGame, time);
            if (state == 0) { keyStart = -1L; heldGame = null; pressSerial++; }
        }
    }
    private synchronized void hardwareQuit(long serial, GamesMenuEntry.Game game, long time) {
        if (stopped || serial != pressSerial || game == null || game != heldGame // NOPMD CompareObjectsWithEquals - Exact owned launch.
                || keyStart < 0L || time - keyStart < 2000L) { return; }
        keyStart = -1L; heldGame = null; pressSerial++;
        // quit starts its own worker. Never require page queue progress or confirmation.
        boolean accepted = games.quit(game);
        Slog.write(" games hardware quit direct accepted=" + accepted + " " + EventClock.stamp());
    }
    void finished() { host.enqueue(new Runnable() { public void run() { if (!stopped) { overlay.close(); } } }); }
    public synchronized void shutdown() { stopped = true; keyStart = -1L; heldGame = null; pressSerial++; overlay.shutdown(); }
}
