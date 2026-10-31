/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.keys;

/** Feature-local UI contract; no core or stock interface is changed. */
interface RestartPrompt {
    void open(long ticket);
    void close(String reason);
    boolean released();
    interface Owner {
        boolean current(long ticket);
        void confirm(long ticket);
        void cancel(String reason);
        void activated(long ticket);
        void log(String decision);
    }
}
