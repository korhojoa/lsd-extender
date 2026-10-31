/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

/** An observation of raw touch. It cannot consume stock input. */
public interface TouchListener {
    void onTouch(int gesture, int x, int y, long time);
}
