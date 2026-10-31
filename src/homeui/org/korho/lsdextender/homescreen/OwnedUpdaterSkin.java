/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.homescreen;

import de.vw.mib.graphics.Color;
import de.vw.mib.graphics.font.FontAttribs;
import de.vw.mib.widgets.Button;
import de.vw.mib.widgets.StaticImage;
import de.vw.mib.widgets.TextArea;
import de.vw.mib.widgets.models.TextAreaModel;
import de.vw.mib.widgets.models.WidgetModel;

/** SWDL resource facts applied only to independently allocated owned controls. */
final class OwnedUpdaterSkin {
    static final String SETTINGS_ACTION = "update-settings-action";
    private final OwnedSettingsSkin skin;
    OwnedUpdaterSkin(OwnedSettingsSkin skin) { this.skin = skin; }
    WidgetModel create(String kind) {
        if (OwnedSettingsSkin.UPDATE_ACTION.equals(kind)) { return updateAction(); }
        if (SETTINGS_ACTION.equals(kind)) { return settingsAction(); }
        if (OwnedSettingsSkin.UPDATE_MESSAGE.equals(kind)) {
            TextArea message = skin.text("CurrentModule", 46, 97, 912, 33);
            message.set_font(new FontAttribs("VWTextCarUI-Regular.ttf", 28)); message.set_lineSpacing(37);
            message.set_easing(skin.timing(106)); return message;
        }
        if (OwnedSettingsSkin.UPDATE_PROGRESS.equals(kind)) { return updateProgress(); }
        if (OwnedSettingsSkin.UPDATE_ACTIVITY.equals(kind)) {
            de.vw.mib.widgets.SequencedImage activity = (de.vw.mib.widgets.SequencedImage)skin.widget("SequencedImage", 20, 7, 72, 72);
            activity.set_name("owned-update-activity"); activity.set_sequenceImages(skin.imageStates(724));
            activity.set_easing(skin.timing(49)); activity.set_valueMin(0); activity.set_valueMax(11); activity.set_repeat(true);
            activity.set_color(new Color(-1)); return activity;
        }
        throw new IllegalArgumentException("Unsupported owned updater control");
    }
    // Ssv CustomerSWDownload_big: native Settings action above the bottom edge.
    private Button settingsAction() {
        Button action = skin.button("Button", 133, -1, 1155, 94);
        action.set_x(29); action.set_y(538);
        TextArea label = skin.text("TextLabel", 30, 23, 1099, 47);
        label.set_alignmentHorizontal(1);
        action.set_textAreaRefs(new TextAreaModel[] { label });
        action.setChildren(new WidgetModel[] { action.get_imageBackgroundRef(), label });
        return action;
    }
    // Spm AbortComplete: Footer y=533, local x=629/y=14, 637 x 93.
    private Button updateAction() {
        Button action = skin.button("Button", 138, -1, 637, 93);
        action.set_x(629); action.set_y(547); action.set_textColors(footerTextColors());
        TextArea label = skin.text("TextLabel", 30, 18, 577, 47);
        label.set_alignmentHorizontal(1);
        action.set_textAreaRefs(new TextAreaModel[] { label });
        action.setChildren(new WidgetModel[] { action.get_imageBackgroundRef(), label });
        return action;
    }
    // Spm COLOR__ARRAY1_19, unlike dark text on native navigation rows.
    private Color[] footerTextColors() {
        Color[] result = skin.colors(false), contrast = skin.colors(true);
        java.util.Arrays.fill(result, 0, 4, new Color(1946157055));
        System.arraycopy(contrast, 14, result, 14, result.length - 14);
        return result;
    }
    // Spm ProgressBar: native Slider with track 40 and progress 41.
    private de.vw.mib.widgets.Slider updateProgress() {
        de.vw.mib.widgets.Slider progress = (de.vw.mib.widgets.Slider)skin.widget("Slider", 30, 137, 742, 14);
        progress.set_name("owned-update-progress"); progress.set_progressMode(true); progress.set_interactive(false);
        progress.set_valueMin(0); progress.set_valueMax(100); progress.set_step(1);
        progress.set_imagesSlider(skin.imageStates(40)); progress.set_colorsSlider(skin.colors(false));
        progress.set_imagesProgress(skin.imageStates(41)); progress.set_colorsProgress(skin.colors(false));
        StaticImage track = skin.image("Background", -1, 0, 0, 742, 14), fill = skin.image("StateImage", -1, 0, 0, 0, 14);
        progress.set_imageSliderRef(track); progress.set_imageProgressRef(fill);
        progress.set_imageProgressIndicatorRefs(new de.vw.mib.widgets.models.StaticImageModel[0]);
        progress.setChildren(new WidgetModel[] { track, fill }); return progress;
    }
}
