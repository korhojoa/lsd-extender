/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.keys;

import de.vw.mib.desktop.ViewHandlerProvider;

/** Return only our occupied area to its exact previous native provider. */
final class RestartReturn {
    interface Host {
        String currentName();
        String currentContent();
        Object currentSkin();
        boolean changingSkin();
        void enter(Previous previous);
    }
    static final class Previous {
        final ViewHandlerProvider provider;
        final String name, content;
        final Object skin;
        final int distractionGroup;
        Previous(ViewHandlerProvider source, String view, String id, Object skinIdentity, int group) {
            if (source == null || view == null || id == null || skinIdentity == null) { throw new IllegalArgumentException("complete previous view required"); }
            provider = source; name = view; content = id; skin = skinIdentity; distractionGroup = group;
        }
    }
    private RestartReturn() { }
    static boolean restore(Host host, Previous previous, String ownName) {
        if (previous == null || host.changingSkin() || !ownName.equals(host.currentName())
                || !previous.content.equals(host.currentContent()) || previous.skin != host.currentSkin()) { // NOPMD CompareObjectsWithEquals - exact skin generation.
            return false;
        }
        host.enter(previous); return true;
    }
}
