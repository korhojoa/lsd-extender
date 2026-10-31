/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

/** A feature can reject reload before any registration is removed. */
public interface ReloadGuard {
    void prepareReload();
}
