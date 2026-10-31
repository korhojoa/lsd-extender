/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

public final class Pps {
    private Pps() {
    }

    public static void write(String path, String attributes) throws IOException {
        File parent = new File(path).getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("PPS directory unavailable: " + parent);
        }
        RandomAccessFile out = new RandomAccessFile(path, "rw");
        try {
            out.write(attributes.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }
}
