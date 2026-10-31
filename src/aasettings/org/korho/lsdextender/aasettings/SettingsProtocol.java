/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.aasettings;

import org.korho.lsdextender.AndroidAutoSetting;
import org.korho.lsdextender.AndroidAutoSettingsState;
import java.util.Hashtable;
import java.util.StringTokenizer;
import java.util.Vector;

/** Parses the bounded helper protocol. Native code owns values and validation. */
public final class SettingsProtocol {
    private static final int MAX_REVISION_LENGTH = 64;
    private SettingsProtocol() { }

    public static AndroidAutoSettingsState parse(String text, long completed, boolean saved) throws Exception {
        if (text.length() > 131072 || !text.endsWith("end\t1\n")) { throw new Exception("Incomplete settings response"); }
        StringTokenizer lines = new StringTokenizer(text, "\n");
        String revision = null, status = null;
        Vector rows = new Vector();
        Hashtable seen = new Hashtable();
        while (lines.hasMoreTokens()) {
            String line = lines.nextToken();
            StringTokenizer fields = new StringTokenizer(line, "\t");
            String kind = fields.nextToken();
            if (kind.equals("error")) { throw new Exception(line.substring(6)); }
            if (kind.equals("meta")) {
                String[] meta = parseMeta(fields, revision);
                revision = meta[0]; status = meta[1];
            } else if (kind.equals("row")) {
                if (revision == null || fields.countTokens() != 11 || rows.size() >= 256) { throw new Exception("Invalid settings row"); }
                rows.addElement(parseRow(fields, seen));
            } else if (!line.equals("end\t1") || lines.hasMoreTokens()) { throw new Exception("Unexpected settings record"); }
        }
        if (revision == null || rows.isEmpty()) { throw new Exception("No settings received"); }
        AndroidAutoSetting[] array = new AndroidAutoSetting[rows.size()]; rows.copyInto(array);
        return new AndroidAutoSettingsState(true, false, revision, status, array, completed, saved);
    }

    /**
     * The config the helper did not find ("absent" record: a unit without that projection
     * system), or null for any other answer. A missing config is a normal state, not an error.
     */
    public static String absent(String text) {
        String head = "absent\t", tail = "\nend\t1\n";
        if (text == null || text.length() > 4096 || !text.startsWith(head) || !text.endsWith(tail)) { return null; }
        String name = text.substring(head.length(), text.length() - tail.length());
        return name.length() == 0 || name.indexOf('\n') >= 0 || name.indexOf('\t') >= 0 ? null : name;
    }

    private static String[] parseMeta(StringTokenizer fields, String previousRevision) throws Exception {
        if (previousRevision != null || fields.countTokens() != 3 || !fields.nextToken().equals("1")) {
            throw new Exception("Unsupported settings protocol");
        }
        String revision = fields.nextToken(), status = fields.nextToken();
        if (revision.length() > MAX_REVISION_LENGTH) { throw new Exception("Invalid revision"); }
        return new String[]{revision, status};
    }

    private static AndroidAutoSetting parseRow(StringTokenizer fields, Hashtable seen) throws Exception {
        String key = fields.nextToken(), group = fields.nextToken(), label = fields.nextToken();
        String type = fields.nextToken();
        int value = Integer.parseInt(fields.nextToken()), def = Integer.parseInt(fields.nextToken());
        int min = Integer.parseInt(fields.nextToken()), max = Integer.parseInt(fields.nextToken());
        int step = Integer.parseInt(fields.nextToken());
        boolean inherited = flag(fields.nextToken()), readOnly = flag(fields.nextToken());
        if ((!type.equals("bool") && !type.equals("int")) || key.length() > 80 || label.length() > 100
                || step < 1 || min > max || seen.put(key, Boolean.TRUE) != null) { throw new Exception("Invalid settings descriptor"); }
        return new AndroidAutoSetting(key, group, label, type.equals("bool"), value, def, min, max, step, inherited, readOnly);
    }

    private static boolean flag(String s) throws Exception {
        if (!s.equals("0") && !s.equals("1")) { throw new Exception("Invalid settings flag"); }
        return s.equals("1");
    }
}
