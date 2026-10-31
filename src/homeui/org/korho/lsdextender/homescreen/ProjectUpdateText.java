/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.homescreen;

import de.vw.mib.datapool.I18nDatapool;

/** A display value resolved from the current original native language table. */
final class ProjectUpdateText {
    private final I18nDatapool datapool;
    private final int resource, index;
    private final String suffix;
    ProjectUpdateText(I18nDatapool datapool, int resource, int index) {
        this(datapool, resource, index, "");
    }
    ProjectUpdateText(I18nDatapool datapool, int resource, int index, String suffix) {
        this.datapool = datapool; this.resource = resource; this.index = index; this.suffix = suffix;
    }
    String value() { return (index < 0 ? datapool.getString(resource) : datapool.getStrings(resource)[index]) + suffix; }
}
