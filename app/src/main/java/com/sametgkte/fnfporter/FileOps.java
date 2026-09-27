package com.sametgkte.fnfporter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class FileOps {
    // JSON and scripts are fully materialized in memory by org.json/regex converters.
    // Reject pathological text inputs before an Android low-memory crash; binary assets use streaming copy.
    private static final long MAX_TEXT_FILE_BYTES = 32L * 1024L * 1024L;

    public static boolean folderMake(String folderPath) {
        File f = new File(folderPath);
        if (f.exists()) {
            if (f.isDirectory()) return true;
            AppLog.error("Cannot create directory; a file already exists at " + folderPath);
            return false;
        }
        boolean ok = f.mkdirs();
        if (!ok) AppLog.error("Could not create folder: " + folderPath);
        return ok || f.exists();
    }

    public static boolean fileCopy(String source, String destination) {
        File src = new File(source);
        if (!src.exists()) {
            AppLog.warn("Path " + source + " doesn't exist.");
            return false;
        }
        File dst = new File(destination);
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return true;
        } catch (Exception e) {
            AppLog.error("Something went wrong copying " + source, e);
            return false;
        }
    }

    public static boolean treeCopy(String source, String destination) {
        File src = new File(source);
        File dst = new File(destination);
        if (!src.exists()) {
            AppLog.warn("Path " + source + " does not exist.");
            return false;
        }
        // Merge into an existing output tree and overwrite matching files. This makes
        // repeated conversions refresh changed assets instead of silently skipping them.
        return copyRecursive(src, dst);
    }

    private static boolean copyRecursive(File src, File dst) {
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) {
                AppLog.error("Failed to create " + dst);
                return false;
            }
            File[] kids = src.listFiles();
            if (kids == null) return true;
            boolean ok = true;
            for (File k : kids) {
                if (!copyRecursive(k, new File(dst, k.getName()))) ok = false;
            }
            return ok;
        }
        return fileCopy(src.getAbsolutePath(), dst.getAbsolutePath());
    }

    public static List<File> listAll(File dir) {
        List<File> out = new ArrayList<>();
        if (dir == null || !dir.exists() || !dir.isDirectory()) return out;
        File[] kids = dir.listFiles();
        if (kids == null) return out;
        for (File k : kids) out.add(k);
        Collections.sort(out, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                int byName = a.getName().compareToIgnoreCase(b.getName());
                return byName != 0 ? byName : a.getName().compareTo(b.getName());
            }
        });
        return out;
    }

    public static List<File> listFiles(File dir, String suffix) {
        List<File> out = new ArrayList<>();
        for (File f : listAll(dir)) {
            if (f.isFile() && f.getName().toLowerCase(Locale.US).endsWith(suffix.toLowerCase(Locale.US))) {
                out.add(f);
            }
        }
        return out;
    }

    public static String readText(File file) throws IOException {
        long fileBytes = file.length();
        if (fileBytes > MAX_TEXT_FILE_BYTES) {
            throw new IOException("Text file exceeds safe conversion limit (" + fileBytes + " bytes; limit " + MAX_TEXT_FILE_BYTES + "): " + file.getName());
        }
        FileInputStream in = new FileInputStream(file);
        try {
            byte[] data = new byte[(int) fileBytes];
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) break;
                off += n;
            }
            return new String(data, 0, off, "UTF-8");
        } finally {
            in.close();
        }
    }

    public static void writeText(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(text.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }

    public static void writeBytes(File file, byte[] data) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }

    public static String join(String... parts) {
        if (parts.length == 0) return "";
        File f = new File(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            String p = parts[i];
            if (p.startsWith("/") || p.startsWith("\\")) p = p.substring(1);
            f = new File(f, p);
        }
        return f.getPath();
    }
}
