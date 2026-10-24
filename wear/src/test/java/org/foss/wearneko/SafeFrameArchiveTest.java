// SPDX-License-Identifier: LGPL-2.1-or-later
package org.foss.wearneko;

import static org.junit.Assert.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class SafeFrameArchiveTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void rejectsAbsoluteAndTraversalPaths() throws Exception {
        File root = temporary.newFolder("root");
        for (String path : new String[]{"../escape.png", "/root.png", "C:/escape.png", "frames/../escape.png",
                "frames\\escape.png", "frames//escape.png", "./frame.png"}) {
            try { SafeFrameArchive.resolve(root, path); fail("Accepted " + path); }
            catch (IOException expected) { assertNotNull(expected.getMessage()); }
        }
    }

    @Test public void rejectsZipSlipBeforeWritingOutsideRoot() throws Exception {
        File archive = temporary.newFile("bad.zip");
        write(archive, "../escaped.png", new byte[]{1});
        File root = temporary.newFolder("frames");
        try { SafeFrameArchive.extract(archive, root); fail("Accepted traversal ZIP"); }
        catch (IOException expected) { assertFalse(new File(temporary.getRoot(), "escaped.png").exists()); }
    }

    @Test public void acceptsNormalizedManifestAndPngFiles() throws Exception {
        File archive = temporary.newFile("good.zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("frames.json")); zip.write("{}".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("frames/pet/idle.png")); zip.write(new byte[]{1,2,3}); zip.closeEntry();
        }
        File root = temporary.newFolder("good");
        SafeFrameArchive.extract(archive, root);
        assertTrue(new File(root, "frames.json").isFile());
        assertEquals(3, new File(root, "frames/pet/idle.png").length());
    }

    @Test public void limitsExpandedSizeEvenForHighlyCompressedFrames() throws Exception {
        File archive = temporary.newFile("large.zip");
        write(archive, "huge.png", new byte[(int) SafeFrameArchive.MAX_BYTES+1]);
        assertTrue(archive.length() < SafeFrameArchive.MAX_BYTES);
        try { SafeFrameArchive.extract(archive, temporary.newFolder("large")); fail("Accepted oversized expanded ZIP"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("8 MiB")); }
    }

    private static void write(File file, String path, byte[] bytes) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            zip.putNextEntry(new ZipEntry(path)); zip.write(bytes); zip.closeEntry();
        }
    }
}
