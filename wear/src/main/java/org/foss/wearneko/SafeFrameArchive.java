// SPDX-License-Identifier: LGPL-2.1-or-later
package org.foss.wearneko;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Small, framework-free ZIP boundary for normalized phone frame packs. */
final class SafeFrameArchive {
    static final long MAX_BYTES = 8L * 1024L * 1024L;
    static final int MAX_ENTRIES = 600;

    private SafeFrameArchive() {}

    static File resolve(File root, String path) throws IOException {
        if (path == null || path.isEmpty() || path.length() > 240 || path.contains("\\")
                || path.startsWith("/") || path.contains(":") || path.indexOf('\0') >= 0) {
            throw new IOException("Invalid frame path");
        }
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new IOException("Invalid frame path component");
            }
        }
        File destination = new File(root, path).getCanonicalFile();
        String prefix = root.getCanonicalPath() + File.separator;
        if (!destination.getPath().startsWith(prefix)) {
            throw new IOException("Frame path escaped the package");
        }
        return destination;
    }

    static void extract(File archive, File destination) throws IOException {
        if (!archive.isFile() || archive.length() > MAX_BYTES) {
            throw new IOException("Frame archive is missing or exceeds 8 MiB");
        }
        if (!destination.isDirectory() && !destination.mkdirs()) {
            throw new IOException("Could not create frame directory");
        }
        long total = 0;
        int count = 0;
        Set<String> names = new HashSet<>();
        byte[] buffer = new byte[16 * 1024];
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > MAX_ENTRIES) throw new IOException("Too many frame files");
                String name = entry.getName();
                if (entry.isDirectory()) {
                    String trimmed = name.endsWith("/") ? name.substring(0, name.length()-1) : name;
                    File directory = resolve(destination, trimmed);
                    if (!directory.isDirectory() && !directory.mkdirs()) {
                        throw new IOException("Could not create frame directory");
                    }
                    continue;
                }
                File file = resolve(destination, name);
                if (!names.add(file.getCanonicalPath())) throw new IOException("Duplicate frame file");
                if (!name.equals("frames.json") && !name.endsWith(".png")) {
                    throw new IOException("Unexpected frame file type");
                }
                File parent = file.getParentFile();
                if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
                    throw new IOException("Could not create frame directory");
                }
                try (FileOutputStream output = new FileOutputStream(file)) {
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        total += read;
                        if (total > MAX_BYTES) throw new IOException("Extracted frames exceed 8 MiB");
                        output.write(buffer, 0, read);
                    }
                }
                zip.closeEntry();
            }
        }
        if (!new File(destination, "frames.json").isFile()) {
            throw new IOException("frames.json is missing");
        }
    }

    static void deleteTree(File root) {
        File[] children = root.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        // Best-effort cleanup applies only to generated private frame directories.
        root.delete();
    }
}
