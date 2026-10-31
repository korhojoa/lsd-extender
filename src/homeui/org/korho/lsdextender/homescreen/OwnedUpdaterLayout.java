/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.homescreen;

import de.vw.mib.widgets.Button;
import de.vw.mib.widgets.Container;
import de.vw.mib.widgets.SequencedImage;
import de.vw.mib.widgets.Slider;
import de.vw.mib.widgets.TextArea;
import de.vw.mib.widgets.controller.ButtonController;
import de.vw.mib.widgets.event.OutEventListener;
import de.vw.mib.widgets.models.ViewModel;
import de.vw.mib.widgets.models.WidgetModel;

/** Owned software-update body and fixed native action bar. */
final class OwnedUpdaterLayout {
    interface Action { void run(int mode, String request); }
    final Container body, footer;
    final TextArea message;
    final Slider progress;
    final SequencedImage activity;
    private final OwnedSettingsSkin.Context styles;
    private final Action dispatch;
    private Button button;
    private OutEventListener listener;
    private ViewModel view;
    private boolean activated;
    private String binding;
    OwnedUpdaterLayout(OwnedSettingsSkin.Context styles, Action action) {
        this.styles = styles; this.dispatch = action;
        body = container("owned-update-body"); footer = container("owned-update-footer");
        footer.set_y(533); footer.set_height(107);
        message = (TextArea)styles.control(OwnedSettingsSkin.UPDATE_MESSAGE).create();
        message.set_name("update-status");
        progress = (Slider)styles.control(OwnedSettingsSkin.UPDATE_PROGRESS).create();
        activity = (SequencedImage)styles.control(OwnedSettingsSkin.UPDATE_ACTIVITY).create();
        body.setChildren(new WidgetModel[] { message, progress, activity });
        hide();
    }
    private static Container container(String name) {
        Container value = (Container)OwnedSettingsWidgets.allocate("de.vw.mib.widgets.Container");
        value.set_name(name); value.set_width(1280); value.set_height(640); value.set_alpha(255);
        value.set_visible(true); value.set_availableInVariant(true); value.set_enabled(true);
        return value;
    }
    void initialized(ViewModel owner) { view = owner; }
    void active(boolean value) { activated = value; }
    void deinitialized() { view = null; activated = false; }
    void hide() { body.set_visible(false); footer.set_visible(false); }
    void render(boolean busy, boolean cancelling, String text, long done, long total,
            String name, String label, boolean enabled, int mode, String request) {
        body.set_visible(busy); footer.set_visible(true);
        message.set_text(cancelling ? "Cancellation requested" : text);
        message.set_name(cancelling ? "update-cancel-status" : "update-status");
        progress.set_visible(busy && total > 0);
        progress.set_value(percent(done, total));
        activity.set_visible(busy);
        String next = name + "/" + label + "/" + mode + "/" + request;
        if (!next.equals(binding)) { replaceAction(next, name, label, mode, request); }
        button.set_enabled(enabled);
    }
    static int percent(long done, long total) {
        if (done <= 0 || total <= 0) { return 0; }
        if (done >= total) { return 100; }
        return Math.min(99, (int)(100.0 * ((double)done / (double)total)));
    }
    private void replaceAction(String next, String name, String label, final int mode, final String request) {
        retireAction();
        if (view != null) { if (activated) { footer.deActivate(); } footer.deInit(); }
        boolean cancel = "update-cancel".equals(name);
        button = (Button)styles.control(cancel ? OwnedSettingsSkin.UPDATE_ACTION : OwnedUpdaterSkin.SETTINGS_ACTION).create();
        button.set_name(name); button.set_y(cancel ? 14 : 5); button.set_buttonMode(0);
        button.get_textAreaRefs()[0].set_text(label);
        final Button boundButton = button;
        listener = new OutEventListener() {
            public void outIntegerEventFired(String name, int value) { }
            public void outBooleanEventFired(String name, boolean value) { }
            public void outStringEventFired(String name, String value) { }
            public void outPointEventFired(String name, int x, int y, int z, int w) { }
            public void outListItemEventFired(String name, int a, int b, int c, int d, String text, long value) { }
            public void outSpellerEventFired(String name, String text, int a, int b, int c) { }
            public void outHMIEventFired(String name) {
                if (("released").equals(name) && boundButton.is_enabled()) { dispatch.run(mode, request); }
            }
        };
        button.getController().addOutEventListener(listener);
        footer.setChildren(new WidgetModel[] { button }); binding = next;
        if (view != null) {
            footer.init(view); if (activated) { footer.activate(); }
            // Native setChildren changes links only; retire the owned scene cache.
            view.invalidate(); view.repaint();
        }
    }
    Button action() { return button; }
    void retireAction() {
        if (button != null) {
            button.set_enabled(false);
            if (listener != null) { button.getController().removeOutEventListener(listener); }
        }
        listener = null; binding = null;
    }
    static String nativeText(int resource, String fallback) {
        de.vw.mib.datapool.I18nDatapool text = de.vw.mib.hmi.internal.ServiceManagerCommon.i18nDatapool;
        String value = text == null ? null : text.getString(resource);
        return value == null || value.length() == 0 ? fallback : value;
    }
}
