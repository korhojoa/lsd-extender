/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.homescreen;

import java.util.Hashtable;
import java.util.Vector;
import de.vw.mib.widgets.AbstractWidget;
import de.vw.mib.widgets.AbstractButton;
import de.vw.mib.widgets.Button;
import de.vw.mib.widgets.Container;
import de.vw.mib.widgets.DropDownButton;
import de.vw.mib.widgets.InputField;
import de.vw.mib.widgets.ListItem;
import de.vw.mib.widgets.Scrollbar;
import de.vw.mib.widgets.Speller;
import de.vw.mib.widgets.SpellerButton;
import de.vw.mib.widgets.StaticImage;
import de.vw.mib.widgets.TextArea;
import de.vw.mib.widgets.TransformContainer;
import de.vw.mib.widgets.WidgetList;
import de.vw.mib.widgets.models.WidgetModel;
import de.vw.mib.widgets.models.ContainerModel;
import de.vw.mib.widgets.models.AbstractButtonModel;
import de.vw.mib.widgets.models.ViewModel;
import de.vw.mib.widgets.controller.ButtonController;
import de.vw.mib.widgets.controller.DefaultListItemController;
import de.vw.mib.widgets.controller.DefaultWidgetListController;
import de.vw.mib.widgets.controller.DefaultSpellerController;
import de.vw.mib.widgets.controller.DefaultPushButtonController;
import de.vw.mib.widgets.controller.DefaultDropDownButtonController;
import de.vw.mib.widgets.controller.DefaultScrollbarController;
import de.vw.mib.widgets.event.OutEventListener;
import de.vw.mib.widgets.interfaces.WidgetCloneable;
import de.vw.mib.widgets.interfaces.WidgetCloneFactory;

/** Fresh native widget trees. No factory or controller is installed on a stock object. */
final class OwnedSettingsWidgets {
    private static final int SINGLE_INPUT = 1, ANCESTORS = 32, VISIBLE_CLONES = 64;
    private static int nextTarget = 0x6f000000;
    private OwnedSettingsWidgets() { }
    static WidgetModel allocate(String type) {
        AbstractWidget widget;
        if (type.equals("de.vw.mib.widgets.ListItem")) { widget = new ListItem(); widget.setUI(new de.vw.mib.widgets.ui.ListItemUI()); widget.setController(new DefaultListItemController()); }
        else if (type.equals("de.vw.mib.widgets.WidgetList")) { widget = new WidgetList(); widget.setUI(new de.vw.mib.widgets.ui.WidgetListUI()); widget.setController(new DefaultWidgetListController()); }
        else if (type.equals("de.vw.mib.widgets.Scrollbar")) { widget = new Scrollbar(); widget.setUI(new de.vw.mib.widgets.ui.ScrollbarUI()); widget.setController(new DefaultScrollbarController()); }
        else if (type.equals("de.vw.mib.widgets.Speller")) { widget = new Speller(); widget.setUI(new de.vw.mib.widgets.ui.SpellerUI()); widget.setController(new DefaultSpellerController()); }
        else if (type.equals("de.vw.mib.widgets.InputField")) { widget = new InputField(); widget.setUI(new de.vw.mib.widgets.ui.InputFieldUI()); }
        else if (type.equals("de.vw.mib.widgets.SpellerButton")) { widget = new SpellerButton(); widget.setUI(new de.vw.mib.widgets.ui.SpellerButtonUI()); widget.setController(new DefaultPushButtonController()); }
        else if (type.equals("de.vw.mib.widgets.DropDownButton")) { widget = new DropDownButton(); widget.setUI(new de.vw.mib.widgets.ui.DropDownButtonUI()); widget.setController(new DefaultDropDownButtonController()); }
        else if (type.equals("de.vw.mib.widgets.Button")) { widget = new Button(); widget.setUI(new de.vw.mib.widgets.ui.ButtonUI()); widget.setController(new DefaultPushButtonController()); }
        else if (type.equals("de.vw.mib.widgets.TextArea")) { widget = new TextArea(); widget.setUI(new de.vw.mib.widgets.ui.TextAreaUI()); }
        else if (type.equals("de.vw.mib.widgets.StaticImage")) { widget = new StaticImage(); widget.setUI(new de.vw.mib.widgets.ui.StaticImageUI()); }
        else if (type.equals("de.vw.mib.widgets.TransformContainer")) { widget = new TransformContainer(); widget.setUI(new de.vw.mib.widgets.ui.TransformContainerUI()); }
        else if (type.equals("de.vw.mib.widgets.Container")) { widget = new Container(); widget.setUI(new de.vw.mib.widgets.ui.ContainerUI()); }
        else { widget = allocateUpdater(type); if (widget == null) { throw new IllegalArgumentException("Unsupported native reference widget: " + type); } }
        if (widget instanceof TransformContainer) {
            TransformContainer transform = (TransformContainer)widget;
            transform.set_scalingX(1.0F); transform.set_scalingY(1.0F); transform.set_scalingZ(1.0F);
        }
        if (widget instanceof Container) { ((Container)widget).set_layoutAttribs(new de.vw.mib.properties.values.LayoutAttribs()); }
        widget.set_touchInsets(new de.vw.mib.graphics.Insets());
        if (widget instanceof Container) { ((Container)widget).setChildren(new WidgetModel[0]); }
        if (widget instanceof AbstractButton) { ((AbstractButton)widget).set_interactive(true); }
        return widget;
    }
    private static AbstractWidget allocateUpdater(String type) {
        AbstractWidget widget;
        if (type.equals("de.vw.mib.widgets.Slider")) { widget = new de.vw.mib.widgets.Slider(); widget.setUI(new de.vw.mib.widgets.ui.SliderUI()); widget.setController(new de.vw.mib.widgets.controller.DefaultSliderController()); }
        else if (type.equals("de.vw.mib.widgets.SequencedImage")) { widget = new de.vw.mib.widgets.SequencedImage(); widget.setUI(new de.vw.mib.widgets.ui.SequencedImageUI()); }
        else { return null; }
        return widget;
    }
    static void descendants(WidgetModel widget, Class type, Vector found) {
        if (type.isInstance(widget)) { found.addElement(widget); }
        if (widget instanceof ContainerModel) {
            WidgetModel[] children = ((ContainerModel)widget).getChildren();
            for (int i = 0; children != null && i < children.length; i++) { descendants(children[i], type, found); }
        }
    }
    /** Route only the main encoder to the one visible list in our own page. */
    static boolean consumeRotation(ViewModel view, de.vw.mib.event.HMIEvent event) {
        return Rotary.consume(view, event);
    }
    private static final class Rotary {
        private static final int SINGLE_LIST = 1;
        private Rotary() { }
        static boolean consume(ViewModel view, de.vw.mib.event.HMIEvent event) {
            if (view == null || !view.is_activated()) { return false; }
            String input = input(event);
            if (input == null) { return false; }
            Vector lists = new Vector(); descendants(view, WidgetList.class, lists);
            if (lists.size() != SINGLE_LIST) { return false; }
            WidgetList list = (WidgetList)lists.elementAt(0);
            return eligible(list) && list.consumeHMIEvent(event, input);
        }
        private static boolean eligible(WidgetList list) {
            return list.getWidgetCloneFactory() instanceof ListRows && list.is_visible() && list.areParentsVisible()
                && list.get_alpha() > 0 && list.get_scrollTypeRotary() >= 0;
        }
        private static String input(de.vw.mib.event.HMIEvent event) {
            if (event == null || event.isConsumed() || event.getType() != (16)
                    || !(event instanceof de.vw.mib.event.RotationEvent)
                    || ((de.vw.mib.event.RotationEvent)event).getTicks() <= 0) { return null; }
            de.vw.mib.event.SystemEventIdMap ids = de.vw.mib.event.internal.ServiceManager.systemEventIdMap;
            if (ids == null) { return null; }
            // The key panel supplies separate direction ids and positive ticks.
            // Resolve the runtime ids rather than assuming a firmware table index.
            int next = ids.getInternalSystemEventId(288), previous = ids.getInternalSystemEventId(283);
            if (next < 0 || previous < 0 || next == previous) { return null; }
            if (event.getId() == next) { return ("selectNext"); }
            if (event.getId() == previous) { return ("selectPrevious"); }
            return null;
        }
    }
    /** Bounded diagnostics of our tree and existing visible clones; never creates list items. */
    static String[] snapshot(WidgetModel root) {
        Vector fields = new Vector(), seen = new Vector();
        inspect(root, fields, seen, 0, false);
        String[] result = new String[fields.size()]; fields.copyInto(result); return result;
    }
    private static void inspect(WidgetModel widget, Vector fields, Vector seen, int depth, boolean clone) {
        if (widget == null || !widget.is_visible()) { return; }
        if (seen.contains(widget)) { return; }
        if (depth > 32 || seen.size() >= 1024) { throw new IllegalStateException("Owned control snapshot bound"); }
        seen.addElement(widget);
        if (observable(widget)) { record(widget, fields, clone); }
        if (widget instanceof WidgetList) {
            WidgetList list = (WidgetList)widget;
            int first = Math.max(0, list.getFirstVisibleIndex()), last = list.getLastVisibleIndex();
            if (last - first >= VISIBLE_CLONES) { throw new IllegalStateException("Owned visible clone bound"); }
            for (int i = first; i <= last; i++) { WidgetModel item = ownedVisibleItem(list, i);
                if (item != null) { inspect(item, fields, seen, depth + 1, true); } }
            return;
        }
        if (widget instanceof Speller && ((Speller)widget).getWidgetCloneFactory() instanceof NumberEditor) {
            NumberEditor editor = (NumberEditor)((Speller)widget).getWidgetCloneFactory();
            java.util.Enumeration keys = editor.clones.keys();
            while (keys.hasMoreElements()) { inspect((WidgetModel)keys.nextElement(), fields, seen, depth + 1, true); }
        }
        if (widget instanceof ContainerModel) {
            WidgetModel[] children = ((ContainerModel)widget).getChildren();
            for (int i = 0; children != null && i < children.length; i++) { inspect(children[i], fields, seen, depth + 1, clone); }
        }
    }
    private static boolean observable(WidgetModel widget) {
        return widget instanceof WidgetList || widget instanceof Scrollbar || widget instanceof ListItem || widget instanceof Speller
            || widget instanceof InputField || widget instanceof Button || widget instanceof TextArea || widget instanceof de.vw.mib.widgets.Slider
            || widget instanceof de.vw.mib.widgets.SequencedImage;
    }
    private static void record(WidgetModel widget, Vector fields, boolean clone) {
        String type = widget.getClass().getName(), name = widget.get_name();
        String state = effectiveState(widget) + controlState(widget, fields) + clipState(widget);
            fields.addElement("control name=" + name + " type=" + type + " clone=" + clone + " enabled=" + widget.is_enabled()
                + " visible=" + widget.is_visible() + " variant=" + widget.is_availableInVariant()
                + " alpha=" + widget.get_alpha() + " paintable=" + widget.isPaintable()
                + " x=" + widget.getAbsX() + " y=" + widget.getAbsY() + " width=" + widget.get_width()
                + " height=" + widget.get_height() + state);
    }
    /** Validate parent links before any native absolute getter can traverse them. */
    private static String effectiveState(WidgetModel widget) {
        Vector seen = new Vector();
        boolean visible = true, variant = true, enabled = true, paintable = true;
        int alpha = 255;
        for (WidgetModel current = widget; current != null; current = current.getParent()) {
            if (seen.contains(current) || seen.size() > ANCESTORS) { throw new IllegalStateException("Owned control ancestor cycle or bound"); }
            seen.addElement(current);
            visible &= current.is_visible(); variant &= current.is_availableInVariant();
            enabled &= current.is_enabled(); paintable &= current.isPaintable();
            alpha = (int)(alpha * current.get_alpha() / 255.0F);
        }
        return " effective_visible=" + visible + " effective_variant=" + variant + " effective_enabled=" + enabled
            + " effective_alpha=" + alpha + " effective_paintable=" + paintable;
    }
    private static String controlState(WidgetModel widget, Vector fields) {
            String state = "";
            if (widget instanceof WidgetList) { listState((WidgetList)widget, fields); }
            if (widget instanceof Button) { state += " checked=" + ((Button)widget).is_activated(); }
            if (widget instanceof WidgetList) {
                WidgetList list = (WidgetList)widget;
                state += " first=" + list.getFirstVisibleIndex() + " last=" + list.getLastVisibleIndex();
            }
            if (widget instanceof de.vw.mib.widgets.Slider) {
                de.vw.mib.widgets.Slider progress = (de.vw.mib.widgets.Slider)widget;
                state += " value=" + progress.get_value() + " min=" + progress.get_valueMin() + " max=" + progress.get_valueMax();
            }
            if (widget instanceof InputField) { state += " text=" + ((InputField)widget).get_text(); }
            if (widget instanceof TextArea) { state += " text=" + ((TextArea)widget).get_text(); }
            if (widget instanceof SpellerButton) {
                de.vw.mib.widgets.speller.SpellerElement element = ((SpellerButton)widget).getSpellerElement();
                state += element == null ? " element=UNKNOWN" : " element=" + element.getId()
                    + " function=" + element.getReturnFunction() + " value=" + element.getReturnValue();
            }
        return state;
    }
    private static String clipState(WidgetModel widget) {
        String state = "";
            WidgetModel ancestor = widget.getParent();
            int parents = 0, clipLeft = Integer.MIN_VALUE, clipTop = Integer.MIN_VALUE;
            int clipRight = Integer.MAX_VALUE, clipBottom = Integer.MAX_VALUE;
            boolean clipped = false;
            while (ancestor != null) {
                if (++parents > ANCESTORS) { throw new IllegalStateException("Owned control ancestor bound"); }
                if (ancestor instanceof WidgetList) {
                    clipped = true; clipLeft = Math.max(clipLeft, ancestor.getAbsX()); clipTop = Math.max(clipTop, ancestor.getAbsY());
                    clipRight = Math.min(clipRight, ancestor.getAbsX() + ancestor.get_width());
                    clipBottom = Math.min(clipBottom, ancestor.getAbsY() + ancestor.get_height());
                }
                ancestor = ancestor.getParent();
            }
            boolean contained = !clipped || widget.getAbsX() >= clipLeft && widget.getAbsY() >= clipTop
                && widget.getAbsX() + widget.get_width() <= clipRight && widget.getAbsY() + widget.get_height() <= clipBottom;
            if (clipped) {
                state += " clip_x=" + clipLeft + " clip_y=" + clipTop
                    + " clip_width=" + Math.max(0, clipRight - clipLeft) + " clip_height=" + Math.max(0, clipBottom - clipTop);
            }
            state += " fully_visible=" + contained;
        return state;
    }
    private static void listState(WidgetList list, Vector fields) {
        String view = list.getView() == null ? "UNKNOWN" : list.getView().get_name(), name = list.get_name();
        fields.addElement("list-state view=" + view + " name=" + name + " selected=" + list.get_selectedIndex()
            + " count=" + list.get_itemCount() + " first=" + list.getFirstVisibleIndex() + " last=" + list.getLastVisibleIndex()
            + " first_item=" + list.get_firstItemIndex() + " first_position=" + list.get_firstItemIndexPosition());
        listPart(view, name, "cursor", list.get_cursorImageRef(), fields);
        if (list.getWidgetCloneFactory() instanceof ListRows) {
            Scrollbar scrollbar = ((ListRows)list.getWidgetCloneFactory()).scrollbar;
            listPart(view, name, "scrollbar", scrollbar, fields);
            listPart(view, name, "thumb", scrollbar.get_imageScrollbarRef(), fields);
        } else {
            listPart(view, name, "scrollbar", null, fields); listPart(view, name, "thumb", null, fields);
        }
    }
    private static void listPart(String view, String list, String role, WidgetModel widget, Vector fields) {
        String field = "list-part view=" + view + " list=" + list + " role=" + role;
        fields.addElement(widget == null ? field + " state=unavailable" : field + " state=observed" + effectiveState(widget) + " visible=" + widget.is_visible()
            + " variant=" + widget.is_availableInVariant() + " alpha=" + widget.get_alpha()
            + " enabled=" + widget.is_enabled() + " paintable=" + widget.isPaintable() + " x=" + widget.getAbsX()
            + " y=" + widget.getAbsY() + " width=" + widget.get_width() + " height=" + widget.get_height());
    }
    /** Stock instance is live only after native paint has transferred this absolute data row. */
    static WidgetModel existingClone(WidgetList list, int index) {
        if (index < list.getFirstVisibleIndex() || index > list.getLastVisibleIndex()) { return null; }
        de.vw.mib.widgets.models.ListItemModel item = list.getItem(index);
        return item != null && item.isAssigned() && item.getAbsoluteIndex() == index ? item : null;
    }
    /** Our static rows have one index per registered template and need no data-transfer callback. */
    private static WidgetModel ownedVisibleItem(WidgetList list, int index) {
        if (!(list.getWidgetCloneFactory() instanceof ListRows)) { return null; }
        if (index < list.getFirstVisibleIndex() || index > list.getLastVisibleIndex()) { return null; }
        ListRows rows = (ListRows)list.getWidgetCloneFactory();
        de.vw.mib.widgets.models.ListItemModel item = list.getItem(index), master = list.getItemMaster(index);
        if (item == null || master == null) { return null; }
        boolean registered = rows.templates.containsKey(master) && list.getItemIndex(index) == 0;
        boolean owned = item == master || rows.clones.containsKey(item);
        return registered && owned && item.isAssigned() ? item : null;
    }
    static final class Row {
        final String key;
        final ListItem item;
        final Button button;
        final TextArea caption, value;
        final OutEventListener listener;
        final OwnedSettingsSkin.Control style;
        Row(OwnedSettingsSkin.Control style, String key, String label, String shownValue, boolean checked, boolean enabled, final Runnable action) {
            this.style = style; this.key = key; item = (ListItem)style.create();
            item.set_name(key); item.set_targetId(++nextTarget); item.set_x(0); item.set_y(0);
            item.set_selectable(true); item.set_activatedIndex(-1);
            Vector buttons = new Vector(), texts = new Vector();
            descendants(item, Button.class, buttons); descendants(item, TextArea.class, texts);
            if (buttons.isEmpty() || texts.isEmpty()) { throw new IllegalStateException("Native row lacks button or text"); }
            button = (Button)buttons.elementAt(0);
            TextArea[] columns = nativeColumns(texts);
            TextArea labelPart = columns[0], valuePart = columns[1];
            if (button instanceof DropDownButton && (labelPart == null || valuePart == null)) {
                throw new IllegalStateException("Native choice label/value roles unavailable");
            }
            caption = labelPart == null ? (TextArea)texts.elementAt(0) : labelPart;
            value = valuePart;
            caption.set_text(label);
            if (value != null) { value.set_text(shownValue); }
            button.set_name(key); button.set_buttonMode(0); button.set_activated(checked);
            item.set_selectedButtonRefs(new AbstractButtonModel[] { button });
            // Checkbox/choice value is backend state, separate from the list row activation cursor.
            item.set_activatedButtonRefs(new AbstractButtonModel[0]);
            item.set_enabled(enabled); button.set_enabled(enabled); button.set_activated(checked);
            item.set_limitation(-1);
            item.setListItemControl(new de.vw.mib.datapool.LocalListItemControl(1));
            listener = new OutEventListener() {
            public void outIntegerEventFired(String name, int value) { }
            public void outBooleanEventFired(String name, boolean value) { }
            public void outStringEventFired(String name, String value) { }
            public void outPointEventFired(String name, int x, int y, int z, int w) { }
            public void outListItemEventFired(String name, int a, int b, int c, int d, String text, long value) { }
            public void outSpellerEventFired(String name, String text, int a, int b, int c) { }
                public void outHMIEventFired(String name) {
                    if (("released").equals(name) && button.is_enabled()) { action.run(); }
                }
            };
            button.getController().addOutEventListener(listener);
        }
        private static TextArea[] nativeColumns(Vector texts) {
            TextArea[] columns = new TextArea[2];
            for (int i = 0; i < texts.size(); i++) {
                TextArea text = (TextArea)texts.elementAt(i); text.set_text("");
                if (text.get_name().endsWith("/TextLabel") || text.get_name().endsWith("/Text_Name")) {
                    if (columns[0] != null) { throw new IllegalStateException("Ambiguous native row label"); }
                    columns[0] = text;
                }
                if (text.get_name().endsWith("/DropDownLabel") || text.get_name().endsWith("/Text_Value")) {
                    if (columns[1] != null) { throw new IllegalStateException("Ambiguous native row value"); }
                    columns[1] = text;
                }
            }
            return columns;
        }
        void retire() { button.set_enabled(false); button.getController().removeOutEventListener(listener); }
    }
    interface RowLayout { void apply(Row row); }
    static final class ListRows implements WidgetCloneFactory {
        final WidgetList list;
        final Scrollbar scrollbar;
        final StaticImage cursor;
        private final Hashtable templates = new Hashtable(), clones = new Hashtable();
        private final OwnedSettingsSkin.Context styles;
        private final RowLayout layout;
        private boolean readyForClones, interactive;
        private ViewModel view;
        ListRows(OwnedSettingsSkin.Context styles, int width, int height) { this(styles, width, height, null); }
        ListRows(OwnedSettingsSkin.Context styles, int width, int height, RowLayout layout) {
            this.styles = styles; this.layout = layout;
            list = (WidgetList)styles.control(OwnedSettingsSkin.LIST).create();
            list.set_name("owned-settings-list");
            if (list.get_x() < 0 || list.get_x() + list.get_width() > width) {
                throw new IllegalStateException("Native list reference exceeds owned page width");
            }
            if (list.get_y() + list.get_height() > height) { throw new IllegalStateException("Native list exceeds owned page height"); }
            list.set_orientation(0); list.set_scrollTypeTouch(0); list.set_scrollTypeRotary(0);
            scrollbar = (Scrollbar)styles.control(OwnedSettingsSkin.SCROLL).create();
            cursor = (StaticImage)styles.control(OwnedSettingsSkin.CURSOR).create();
            scrollbar.set_name("owned-settings-scrollbar"); cursor.set_name("owned-settings-cursor");
            if (scrollbar.get_x() < 0 || scrollbar.get_x() + scrollbar.get_width() > width) {
                throw new IllegalStateException("Native scrollbar reference exceeds owned page width");
            }
            scrollbar.set_y(list.get_y()); scrollbar.set_height(list.get_height());
            if (scrollbar.get_imageBackgroundRef() != null) {
                scrollbar.get_imageBackgroundRef().set_x(0); scrollbar.get_imageBackgroundRef().set_y(0);
                scrollbar.get_imageBackgroundRef().set_width(scrollbar.get_width());
                scrollbar.get_imageBackgroundRef().set_height(scrollbar.get_height());
            }
            list.set_forwardPressDelay(120); list.setWidgetCloneFactory(this);
            list.setChildren(new WidgetModel[] { cursor });
        }
        void initialized(ViewModel owner) {
            readyForClones = true; view = owner; list.set_cursorImageRef(cursor); scrollbar.set_adjustableWidgetRef(list);
        }
        void deinitialized() {
            scrollbar.set_adjustableWidgetRef(null); list.set_cursorImageRef(null); readyForClones = false; interactive = false;
        }
        void active(boolean value) { interactive = value; }
        void replace(Row[] rows) {
            if (readyForClones) { scrollbar.set_adjustableWidgetRef(null); if (interactive) { list.deActivate(); } list.deInit(); }
            retireRows(templates); templates.clear();
            // The native list paints non-item children after the last row as overlays.
            WidgetModel[] children = new WidgetModel[rows.length + 1]; children[rows.length] = cursor;
            for (int i = 0; i < rows.length; i++) { children[i] = rows[i].item; templates.put(rows[i].item, rows[i]); }
            list.setChildren(children);
            if (readyForClones) { list.init(view); scrollbar.set_adjustableWidgetRef(list); if (interactive) { list.activate(); } }
        }
        public WidgetCloneable createWidgetCloneable(WidgetCloneable master) {
            final Row source = (Row)templates.get(master);
            if (source == null) { throw new IllegalArgumentException("Foreign list master"); }
            Row clone = new Row(source.style, source.key,
                source.caption.get_text(), source.value == null ? "" : source.value.get_text(), source.button.is_activated(), source.button.is_enabled(), new Runnable() {
                    public void run() { source.listener.outHMIEventFired(("released")); }
                });
            if (layout != null) { layout.apply(clone); }
            clones.put(clone.item, clone);
            clone.item.set_targetId(source.item.get_targetId());
            return clone.item;
        }
        public void destroyWidgetCloneable(WidgetCloneable widget) {
            Row row = (Row)clones.remove(widget);
            if (row == null) { throw new IllegalArgumentException("Foreign list clone"); }
            row.retire();
        }
        void retire() { retireRows(templates); retireRows(clones); }
        private void retireRows(Hashtable rows) {
            java.util.Enumeration values = rows.elements();
            while (values.hasMoreElements()) { ((Row)values.nextElement()).retire(); }
        }
        Row row(String key, String caption, String value, boolean checked, boolean enabled, String role, Runnable action) {
            Row row = new Row(styles.control(role), key, caption, value, checked, enabled, action);
            if (layout != null) { layout.apply(row); }
            return row;
        }
    }
    static final class NumberKeyData implements de.vw.mib.widgets.interfaces.WidgetCloneData { }
    static final class NumberEditor implements WidgetCloneFactory {
        final Speller speller;
        final InputField input;
        final WidgetModel frame, backdrop;
        final Button well;
        private final Hashtable templates = new Hashtable(), clones = new Hashtable();
        NumberEditor(OwnedSettingsSkin.Context styles) {
            speller = (Speller)styles.control(OwnedSettingsSkin.SPELLER).create();
            frame = styles.control(OwnedSettingsSkin.FRAME).create();
            backdrop = styles.control(OwnedSettingsSkin.BACKDROP).create();
            frame.set_name("owned-settings-number-frame"); backdrop.set_name("owned-settings-number-backdrop");
            well = (Button)styles.control(OwnedSettingsSkin.WELL).create();
            Vector inputs = new Vector(); descendants(well, InputField.class, inputs);
            if (inputs.size() != SINGLE_INPUT) { throw new IllegalStateException("Native input well must contain exactly one InputField"); }
            input = (InputField)inputs.elementAt(0);
            well.set_name("owned-settings-input-well"); well.set_interactive(false);
            speller.set_name("owned-settings-number-speller"); input.set_name("owned-settings-number-input");
            // Native key dispatch counts its last digit again after our synchronous data update.
            // Allow that pending digit; SettingsNumberBuffer still bounds the actual text to eleven.
            speller.set_charMin(0); speller.set_charMax(12);
            input.set_spellerRef(speller); input.set_hideTyping(false);
            Vector keys = new Vector(); descendants(speller, SpellerButton.class, keys);
            if (keys.isEmpty()) { throw new IllegalStateException("Native numeric keyboard templates unavailable"); }
            for (int i = 0; i < keys.size(); i++) {
                SpellerButton key = (SpellerButton)keys.elementAt(i);
                templates.put(key, styles.control(OwnedSettingsSkin.SPELLER).key(key.get_templateName()));
            }
            speller.setWidgetCloneFactory(this);
            update(new SettingsNumberBuffer(0));
        }
        void update(SettingsNumberBuffer number) {
            de.vw.mib.properties.values.SpellerData data = new de.vw.mib.properties.values.SpellerData();
            data.setEnteredText(number.getText()); data.setCursorPosition(number.getCursor());
            data.setSelectionStart(number.getStart()); data.setSelectionEnd(number.getEnd());
            data.setAvailableCharacters("0123456789");
            speller.set_spellerData(data);
        }
        public WidgetCloneable createWidgetCloneable(WidgetCloneable template) {
            OwnedSettingsSkin.Control style = (OwnedSettingsSkin.Control)templates.get(template);
            if (style == null) { throw new IllegalArgumentException("Foreign numeric key template"); }
            SpellerButton key = (SpellerButton)style.create();
            key.setCloneData(new NumberKeyData());
            clones.put(key, Boolean.TRUE);
            return key;
        }
        public void destroyWidgetCloneable(WidgetCloneable key) {
            if (clones.remove(key) == null) { throw new IllegalArgumentException("Foreign numeric key clone"); }
        }
    }
}
