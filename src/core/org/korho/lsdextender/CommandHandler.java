/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

/**
 * A feature that accepts commands from a client of the TCP feed (Publisher).
 * Register with LsdExtender.registerCommands(); the first handler that returns a
 * non-null reply owns the command. Replies are single lines, sent to the client
 * that issued the command (and, like everything, logged).
 */
public interface CommandHandler {
    /** @return the reply line, or null if this handler does not know the command */
    String command(String line);
}
