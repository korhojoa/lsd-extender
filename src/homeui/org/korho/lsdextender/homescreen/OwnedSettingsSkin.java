/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.homescreen;

import de.vw.mib.animation.easing.EasingParams;
import de.vw.mib.graphics.Color;
import de.vw.mib.graphics.Insets;
import de.vw.mib.graphics.font.FontAttribs;
import de.vw.mib.graphics.image.Image;
import de.vw.mib.widgets.AbstractWidget;
import de.vw.mib.widgets.Button;
import de.vw.mib.widgets.Container;
import de.vw.mib.widgets.InputField;
import de.vw.mib.widgets.ListItem;
import de.vw.mib.widgets.Scrollbar;
import de.vw.mib.widgets.Speller;
import de.vw.mib.widgets.SpellerButton;
import de.vw.mib.widgets.StaticImage;
import de.vw.mib.widgets.TextArea;
import de.vw.mib.widgets.models.TextAreaModel;
import de.vw.mib.widgets.models.WidgetModel;

/** Explicit owned control definitions using the installed P4 VW skin resources. */
final class OwnedSettingsSkin {
    static final String SCROLL = "scrollbar", CURSOR = "cursor", LIST = "list", CHECK = "checkbox", ROW = "row", CHOICE = "choice";
    static final String INPUT = "input", SPELLER = "speller", FRAME = "numeric-frame", BACKDROP = "numeric-backdrop", WELL = "input-well";
    static final String PAGE_BACKGROUND = "page-background", PAGE_TITLE = "page-title", PAGE_BACK = "page-back", PAGE_RULE = "page-rule";
    static final String UPDATE_ACTION = "update-action", UPDATE_MESSAGE = "update-message", UPDATE_PROGRESS = "update-progress", UPDATE_ACTIVITY = "update-activity";
    static final String BUILD = "BH29.319.29-201707121419";
    private static final String FONT = "VWTextCarUI-Regular.ttf", KEY_PREFIX = "key/";
    private static final int WIDTH = 1280, HEIGHT = 640, STATES = 18;
    private static OwnedSettingsSkin published;
    interface Source {
        int skin();
        String language();
        String build();
        Image image(int id);
        Image[] images(int id);
        EasingParams[] easing(int id);
    }
    private final Source source;
    private boolean stopped;
    OwnedSettingsSkin(Source source) { this.source = source; }
    static Source nativeSource() {
        return new Source() {
            private de.vw.mib.configuration.ConfigurationManager config() { return de.vw.mib.widgets.internal.ServiceManager.configurationManager; }
            public int skin() { return config() == null ? -1 : config().getCurrentSkinId(); }
            public String language() { return config() == null ? null : config().getCurrentGuiLanguage(); }
            public String build() { return config() == null ? null : config().getSkinBuildHmiSoftwareVersion(); }
            public Image image(int id) { return de.vw.mib.hmi.internal.ServiceManagerCommon.skinImagePool.getImage(id); }
            public Image[] images(int id) { return de.vw.mib.hmi.internal.ServiceManagerCommon.skinImagePool.getImageArray(id); }
            public EasingParams[] easing(int id) { return de.vw.mib.hmi.internal.ServiceManagerCommon.skinAnimationPool.getEasingParamsArray(id); }
        };
    }
    static synchronized OwnedSettingsSkin active() { return published; }
    static synchronized void publish(OwnedSettingsSkin value) { published = value; }
    void stop() { stopped = true; }
    boolean ready() {
        try {
            return !stopped && source.skin() == 1 && source.language() != null && BUILD.equals(source.build())
                && source.images(133).length == STATES && source.images(686).length == STATES && source.images(698).length == STATES;
        } catch (RuntimeException absent) { return false; }
    }
    String status() {
        try { return "skin=" + source.skin() + " build=" + source.build() + " language=" + source.language() + " ready=" + ready(); }
        catch (RuntimeException absent) { return "resource failure=" + absent.getClass().getName(); }
    }
    Context context() {
        if (!ready()) { throw new IllegalStateException("Verified native skin resources unavailable"); }
        return new Context(this, source.skin(), source.language());
    }
    static final class Context {
        private final OwnedSettingsSkin owner;
        private final int skin;
        private final String language;
        Context(OwnedSettingsSkin owner, int skin, String language) { this.owner = owner; this.skin = skin; this.language = language; }
        boolean current() { return owner.ready() && owner.source.skin() == skin && language.equals(owner.source.language()); }
        Control control(String kind) { if (!current()) { throw new IllegalStateException("Native skin context changed"); } return new Control(this, kind); }
    }
    static final class Control {
        private final Context context;
        private final String kind;
        Control(Context context, String kind) { this.context = context; this.kind = kind; }
        WidgetModel create() {
            if (!context.current()) { throw new IllegalStateException("Native skin context changed"); }
            return context.owner.create(kind);
        }
        Control key(String template) { return new Control(context, KEY_PREFIX + template); }
    }
    private Image imageResource(int id) {
        Image shared = source.image(id);
        return shared == null ? null : (Image)shared.clone();
    }
    Image[] imageStates(int id) {
        Image[] shared = source.images(id), owned = new Image[shared.length];
        for (int i = 0; i < shared.length; i++) { owned[i] = shared[i] == null ? null : (Image)shared[i].clone(); }
        return owned;
    }
    EasingParams[] timing(int id) {
        EasingParams[] shared = source.easing(id), owned = new EasingParams[shared.length];
        for (int i = 0; i < shared.length; i++) { owned[i] = shared[i] == null ? null : (EasingParams)shared[i].clone(); }
        return owned;
    }
    private WidgetModel create(String kind) {
        if (PAGE_BACKGROUND.equals(kind)) { return image("SettingsBackground", 28, 0, 0, WIDTH, HEIGHT); }
        // Image 143 draws the frame at y=82, two pixels above the list body.
        if (PAGE_RULE.equals(kind)) {
            StaticImage rule = image("Frame", 143, 0, 82, WIDTH, 16);
            de.vw.mib.graphics.Color4b blue = new de.vw.mib.graphics.Color4b(-15415472), white = new de.vw.mib.graphics.Color4b(-1);
            rule.set_color(new Color(blue, blue, white, white, true)); return rule;
        }
        if (PAGE_TITLE.equals(kind)) {
            TextArea title = text("Title", 30, 16, 1073, 55);
            title.set_font(new FontAttribs(FONT, 47)); title.set_lineSpacing(59); return title;
        }
        if (PAGE_BACK.equals(kind)) {
            Button back = button("Button", 149, 679, 81, 81);
            back.set_name("owned-settings-back"); back.set_x(1172); back.set_y(1);
            back.get_imageStateRef().set_x(14); back.get_imageStateRef().set_y(15);
            back.set_imageStateColors(colors(false));
            return back;
        }
        if (kind.startsWith("update-")) { return new OwnedUpdaterSkin(this).create(kind); }
        if (CHECK.equals(kind) || ROW.equals(kind) || CHOICE.equals(kind)) { return createRow(kind); }
        if (LIST.equals(kind)) {
            de.vw.mib.widgets.WidgetList list = (de.vw.mib.widgets.WidgetList)widget("WidgetList", 0, 84, WIDTH, 549);
            list.set_padding(new Insets(0, 30, 0, 111)); list.set_spacingVertical(-3); return list;
        }
        if (CURSOR.equals(kind)) { return image("CursorImage", 783, 0, 0, 1139, 94); }
        if (SCROLL.equals(kind)) { return scrollbar(); }
        if (SPELLER.equals(kind)) { return createSpeller(); }
        if (FRAME.equals(kind)) { return widget("Container", 0, 0, WIDTH, HEIGHT); }
        if (BACKDROP.equals(kind)) { return image("Background", 372, 296, 84, 689, 556); }
        if (INPUT.equals(kind)) { return createInput(); }
        if (WELL.equals(kind)) {
            Button well = button("Button", 182, -1, 481, 101);
            well.set_x(340); well.set_y(99); well.setChildren(new WidgetModel[] { well.getChild(0), createInput() });
            return well;
        }
        if (kind.startsWith(KEY_PREFIX)) { return createKey(kind.substring(KEY_PREFIX.length())); }
        throw new IllegalArgumentException("Unsupported owned control");
    }
    AbstractWidget widget(String type, int x, int y, int width, int height) {
        AbstractWidget model = (AbstractWidget)OwnedSettingsWidgets.allocate("de.vw.mib.widgets." + type);
        model.set_name("owned-settings/" + type); model.set_x(x); model.set_y(y); model.set_width(width); model.set_height(height);
        model.set_alpha(255); model.set_visible(true); model.set_availableInVariant(true); model.set_enabled(true);
        model.set_layoutable(true);
        return model;
    }
    StaticImage image(String name, int id, int x, int y, int width, int height) {
        StaticImage image = (StaticImage)widget("StaticImage", x, y, width, height);
        image.set_easing(timing(106));
        image.set_colorEnabled(true);
        image.set_name(name); image.set_image(id < 0 ? null : imageResource(id)); image.set_color(new Color(-1));
        return image;
    }
    TextArea text(String name, int x, int y, int width, int height) {
        TextArea text = (TextArea)widget("TextArea", x, y, width, height);
        text.set_easing(timing(98));
        text.set_name("owned-settings/" + name); text.set_font(new FontAttribs(FONT, 40)); text.set_fontStyles(new int[] { 0 });
        text.set_colorForeground(new Color(-1)); text.set_colorBackground(new Color(0)); text.set_padding(new Insets()); text.set_text("");
        text.set_baselineOffset(0); text.set_lineSpacing(53);
        return text;
    }
    Color[] colors(boolean background) {
        Color[] colors = new Color[STATES];
        for (int i = 0; i < colors.length; i++) {
            colors[i] = background ? (i < 14 ? new Color(i < 4 ? 0x661a1a1a : 0xff1a1a1a) : contrast()) : new Color(-1);
        }
        return colors;
    }
    private Color contrast() {
        de.vw.mib.graphics.Color4b dark = new de.vw.mib.graphics.Color4b(-2546688), white = new de.vw.mib.graphics.Color4b(-1);
        return new Color(dark, dark, white, white, true);
    }
    // Image 814 supplies the gear icon for the owned settings tile action.
    de.vw.mib.cio.framework.CioIntentVisualization tileVisualization(String label) {
        if (!ready()) { return de.vw.mib.cio.CioIntentVisualizationImpl.NOT_AVAILABLE; }
        return new de.vw.mib.cio.CioIntentVisualizationImpl(null, imageStates(814), colors(false),
            colors(false), label, colors(false), colors(false));
    }
    // The 385 x 313 body frame uses background 1359 and state images 34.
    Button tileFrame() {
        if (!ready()) { throw new IllegalStateException("Native tile skin unavailable"); }
        Button frame = button("Button", 1359, -1, 385, 313);
        StaticImage state = image("TileBodyFrame", -1, 0, 0, 385, 313);
        frame.set_imagesState(imageStates(34)); frame.set_imageStateRef(state);
        Color[] palette = new Color[STATES];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = i == 0 || i == 1 || i == 4 || i == 5 ? new Color(-1) : contrast();
        }
        frame.set_imageStateColors(palette); frame.set_easingStates(timing(67));
        frame.set_interactive(false);
        frame.setChildren(new WidgetModel[] { frame.get_imageBackgroundRef(), state });
        return frame;
    }
    // Buttons use a background image and optional checkbox or choice state images.
    Button button(String type, int background, int state, int width, int height) {
        Button button = (Button)widget(type, 0, 0, width, height);
        StaticImage backdrop = image("Background", -1, 0, 0, width, height);
        button.set_imagesBackground(imageStates(background)); button.set_imageBackgroundRef(backdrop);
        button.set_easing(timing(81)); button.set_easingStates(timing(85));
        button.set_textColors(colors(true)); button.set_textGlowColors(colors(false));
        if (state >= 0) {
            StaticImage icon = image("StateIcon", -1, 20, 21, 52, 52);
            icon.set_glowType(2); icon.set_easing(timing(25));
            button.set_imagesState(imageStates(state)); button.set_imageStateColors(colors(true)); button.set_imageStateRef(icon);
            button.setChildren(new WidgetModel[] { backdrop, icon });
        } else { button.setChildren(new WidgetModel[] { backdrop }); }
        return button;
    }
    private ListItem createRow(String kind) {
        ListItem row = (ListItem)widget("ListItem", 0, 0, 1139, 94);
        boolean checkbox = CHECK.equals(kind), choice = CHOICE.equals(kind);
        Button button = button(choice ? "DropDownButton" : "Button", 133, checkbox ? 686 : choice ? 698 : -1, 1139, 94);
        TextArea caption = text(checkbox ? "Text_Name" : choice ? "TextLabel" : "Text_Name", checkbox ? 92 : 30, 23, checkbox ? 1017 : 510, 47);
        WidgetModel[] prior = button.getChildren();
        WidgetModel[] children = new WidgetModel[prior.length + (checkbox ? 1 : 3)];
        System.arraycopy(prior, 0, children, 0, prior.length); children[prior.length] = caption;
        if (checkbox) { button.set_textAreaRefs(new TextAreaModel[] { caption }); }
        else {
            if (choice) {
                TextArea value = text("DropDownLabel", 600, 23, 447, 47);
                children[prior.length + 1] = image("Separator", 139, 569, 0, 2, 94); children[prior.length + 2] = value;
                button.set_textAreaRefs(new TextAreaModel[] { caption, value });
                button.get_imageStateRef().set_x(1067);
            } else {
                caption.set_width(1079); children = new WidgetModel[] { prior[0], caption };
                button.set_textAreaRefs(new TextAreaModel[] { caption });
            }
        }
        button.setChildren(children); row.setChildren(new WidgetModel[] { button });
        row.set_cursorInsets(new Insets()); row.set_limitation(-1);
        return row;
    }
    private Scrollbar scrollbar() {
        Scrollbar scroll = (Scrollbar)widget("Scrollbar", 1200, 84, 25, 549);
        StaticImage track = image("Background", -1, 0, 0, 25, 549), thumb = image("SliderImage", -1, 0, 0, 25, 520);
        scroll.setChildren(new WidgetModel[] { track, thumb }); scroll.set_imageBackgroundRef(track); scroll.set_imageScrollbarRef(thumb);
        scroll.set_imagesBackground(imageStates(71)); scroll.set_imagesScrollbar(imageStates(72));
        scroll.set_colorsBackground(new Color[0]); scroll.set_colorsScrollbar(scrollbarColors());
        scroll.set_orientation(2); scroll.set_minScrollbarSize(50); scroll.set_stepValues(0); scroll.set_visibilityMode(3);
        return scroll;
    }
    // Scrollbar states 0-13 use base/dynamic colors; later states use contrast.
    private Color[] scrollbarColors() {
        Color[] result = new Color[STATES];
        for (int i = 0; i < result.length; i++) {
            int base = i < 4 ? 1930741584 : -15415472;
            de.vw.mib.graphics.Color4b color = new de.vw.mib.graphics.Color4b(base);
            de.vw.mib.graphics.Color4b dynamic = new de.vw.mib.graphics.Color4b(i < 4 ? 1944190975 : -1);
            result[i] = i < 14 ? new Color(color, color, dynamic, dynamic, true) : contrast();
        }
        return result;
    }
    // The input well is 459 x 47 at (20,26), with cursor image 62 and font size 40.
    private InputField createInput() {
        InputField input = (InputField)widget("InputField", 20, 26, 459, 47);
        input.set_cursorImage(imageResource(62)); input.set_cursorOffset(-10); input.set_easing(timing(106));
        input.set_name("InputField"); input.set_font(new FontAttribs(FONT, 40)); input.set_fontStyles(new int[] { 0 });
        input.set_colorForeground(new Color(-15066598)); input.set_colorBackground(new Color(0));
        input.set_colorHighlightForeground(new Color(-1)); input.set_colorHighlightBackground(new Color(-1291845632));
        input.set_colorMatchForeground(new Color(0xFFFFFF));
        input.set_lineSpacing(53); input.set_padding(new Insets()); input.set_hideTyping(false);
        return input;
    }
    // The owned number pad uses the GENERIC_PIN_SETUP_1BTN digit alphabet.
    private Speller createSpeller() {
        Speller speller = (Speller)widget("Speller", 0, 0, WIDTH, HEIGHT);
        speller.set_alphabetList(new String[] { "GENERIC_PIN_SETUP_1BTN" });
        speller.set_languageList(new String[] { source.language() }); speller.set_alphabetIndex(0); speller.set_lowerUpper(1); speller.set_mode(1);
        String[] names = { "Template2", "Template6", "Template7", "Template8", "Template9", "Template10",
            "Template11", "Template12", "Template13", "Template14", "Template15", "Template17" };
        WidgetModel[] templates = new WidgetModel[names.length];
        for (int i = 0; i < names.length; i++) { templates[i] = createKey(names[i]); }
        speller.setChildren(templates);
        return speller;
    }
    // Key images are 184 for Delete, 164 for OK and 163 for digits.
    private SpellerButton createKey(String template) {
        boolean delete = "Template6".equals(template), ok = "Template2".equals(template);
        int digit = delete || ok ? -1 : Integer.parseInt(template.substring(8));
        SpellerButton key = (SpellerButton)button("SpellerButton", delete ? 184 : ok ? 164 : 163, -1, 0, 0);
        key.set_layoutAttribs(new de.vw.mib.properties.values.LayoutAttribs(2, 1, 1, 0, 0, 0, 0, 0, new int[0]));
        key.set_templateName(template); key.set_textFromAlphabetEnabled(!delete && !ok);
        key.set_buttonMode(delete ? 1 : ok ? 0 : 2); key.set_startTimePrimary(delete ? 800 : 2000);
        TextArea label = text("Text_Number", 0, 0, 128, 47); label.set_alignmentHorizontal(1);
        Container textContainer = (Container)widget("Container", 0, 0, 0, 0);
        textContainer.set_layoutAttribs(new de.vw.mib.properties.values.LayoutAttribs(0, 1, 0, 0, 0, 22, 0, 0, new int[0]));
        textContainer.setChildren(new WidgetModel[] { label });
        label.set_visible(!delete && !ok);
        int[] legends = { -1, 1164, 1165, 1166, 1167, 1168, 1170, 1173, 1174 };
        int state = delete ? 682 : ok ? 802 : digit == 17 ? 1171 : legends[digit - 7];
        if (state >= 0) {
            boolean function = delete || ok;
            StaticImage icon = image("Icon", -1, 0, 0, function ? 52 : 158, function ? 52 : 22);
            icon.set_glowType(2); icon.set_easing(timing(24));
            key.set_imagesState(imageStates(state)); key.set_imageStateRef(icon); key.set_imageStateColors(colors(true));
            Container iconContainer = (Container)widget("Container", 0, 0, 0, 0);
            iconContainer.set_layoutAttribs(new de.vw.mib.properties.values.LayoutAttribs(0, 1, function ? 1 : 2, 0, 0, 0, function ? 0 : 10, 0, new int[0]));
            iconContainer.setChildren(new WidgetModel[] { icon });
            key.setChildren(new WidgetModel[] { key.getChild(0), textContainer, iconContainer });
        } else { key.setChildren(new WidgetModel[] { key.getChild(0), textContainer }); }
        key.set_textAreaRefs(new TextAreaModel[] { label });
        if (delete) { key.set_repeatTimePrimary(500); key.set_startTimeSecondary(4000); key.set_repeatTimeSecondary(200); }
        return key;
    }
}
