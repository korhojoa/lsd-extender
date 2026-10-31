/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** Offline inventory configuration, generated only by the existing job thread. */
final class InventoryConfig {
    private static final int LIMIT = 8192;
    private static final int FIRST_PRINTABLE = 32;
    private static long sequence;
    private InventoryConfig() { }

    static boolean selected(String directory) {
        return directory != null && Config.get("lsdextender.updates.agent.config", null) == null
            && !new File(directory, "hu-agent.json").exists();
    }
    private static File stateRoot() {
        return new File(Config.get("lsdextender.updates.inventory.directory", "/mnt/ota/lsd-extender-inventory"));
    }
    static File template(String directory) { return new File(directory, "hu-agent-inventory.json.in"); }
    static String path() { return new File(stateRoot(), "hu-agent.json").getAbsolutePath(); }
    static boolean allowJob(boolean inventory) {
        boolean allowed = inventory || !selected(Config.directory());
        if (!allowed) { Slog.write(" updates: Inventory-only configuration cannot fetch or install updates"); }
        return allowed;
    }
    static String argumentsConfig(String directory, boolean inventory) throws IOException {
        if (!selected(directory)) { return UpdaterFiles.agentConfig(directory); }
        if (!inventory) { throw new IOException("Inventory-only configuration cannot fetch or install updates"); }
        return prepare(directory);
    }
    static String problem(String directory) {
        File source = template(directory);
        if (!source.isFile() || !source.canRead()) { return "Inventory template is missing or unreadable: " + source; }
        if (source.length() == 0 || source.length() > LIMIT) { return "Inventory template exceeds 8192 bytes or is empty: " + source; }
        return stateRoot().isAbsolute() ? null : "Inventory directory must be absolute: " + stateRoot();
    }
    static synchronized String prepare(String directory) throws IOException {
        String problem = problem(directory);
        if (problem != null) { throw new IOException(problem); }
        String text = expand(read(template(directory)), escape(new File(directory).getAbsolutePath()), escape(stateRoot().getAbsolutePath()));
        byte[] bytes = text.getBytes("UTF-8");
        if (bytes.length > LIMIT) { throw new IOException("Expanded inventory configuration exceeds 8192 bytes"); }
        File root = stateRoot(), target = new File(root, "hu-agent.json");
        if (target.exists()) {
            if (!text.equals(read(target))) { throw new IOException("Existing inventory configuration differs: " + target); }
            return target.getAbsolutePath();
        }
        if (!root.isDirectory() && !root.mkdirs()) { throw new IOException("Cannot create inventory directory: " + root); }
        File temporary = new File(root, "hu-agent.json." + System.currentTimeMillis() + "." + (++sequence) + ".tmp");
        if (!temporary.createNewFile()) { throw new IOException("Cannot create inventory configuration: " + temporary); }
        try {
            FileOutputStream output = new FileOutputStream(temporary);
            try { output.write(bytes); output.getFD().sync(); } finally { output.close(); }
            if (target.exists() || !temporary.renameTo(target)) { throw new IOException("Cannot publish inventory configuration: " + target); }
        } finally { if (temporary.exists() && !temporary.delete()) { Slog.write(" updates: cannot remove inventory temporary file: " + temporary); } }
        return target.getAbsolutePath();
    }
    private static String read(File source) throws IOException {
        FileInputStream input = new FileInputStream(source);
        byte[] bytes = new byte[LIMIT + 1];
        int count = 0;
        try {
            int next;
            while (count < bytes.length && (next = input.read(bytes, count, bytes.length - count)) != -1) { count += next; }
        } finally { input.close(); }
        if (count == 0 || count > LIMIT) { throw new IOException("Inventory configuration must contain 1..8192 bytes: " + source); }
        return new String(bytes, 0, count, "UTF-8");
    }
    private static String expand(String text, String card, String state) throws IOException {
        final String cardToken = "@CARD_ROOT@", stateToken = "@STATE_ROOT@";
        if (text.indexOf(cardToken) < 0 || text.indexOf(stateToken) < 0) { throw new IOException("Inventory template lacks required path tokens"); }
        StringBuffer result = new StringBuffer();
        int from = 0;
        while (from < text.length()) {
            int nextCard = text.indexOf(cardToken, from), nextState = text.indexOf(stateToken, from);
            if (nextCard < 0 && nextState < 0) { break; }
            boolean useCard = nextState < 0 || (nextCard >= 0 && nextCard < nextState);
            int offset = useCard ? nextCard : nextState;
            result.append(text.substring(from, offset)).append(useCard ? card : state);
            from = offset + (useCard ? cardToken.length() : stateToken.length());
        }
        return result.append(text.substring(from)).toString();
    }
    private static String escape(String text) {
        StringBuffer result = new StringBuffer();
        final String hex = "0123456789abcdef";
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (value == '"' || value == '\\') { result.append('\\').append(value); }
            else if (value < FIRST_PRINTABLE) { result.append("\\u00").append(hex.charAt(value >> 4)).append(hex.charAt(value & 15)); }
            else { result.append(value); }
        }
        return result.toString();
    }
}
