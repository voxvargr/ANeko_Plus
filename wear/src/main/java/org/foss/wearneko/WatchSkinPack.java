// SPDX-License-Identifier: LGPL-2.1-or-later
package org.foss.wearneko;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Validates normalized frames before publishing a new immutable pack in private storage. */
final class WatchSkinPack {
    static final class Frame {
        final File file;
        final int durationMs;
        Frame(File file, int durationMs) { this.file = file; this.durationMs = durationMs; }
    }

    static final class Skin {
        final String id;
        final String name;
        final Map<String, List<Frame>> motions;
        Skin(String id, String name, Map<String, List<Frame>> motions) {
            this.id = id; this.name = name; this.motions = motions;
        }
    }

    final File directory;
    final List<Skin> pets;
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };

    private WatchSkinPack(File directory, List<Skin> pets) { this.directory = directory; this.pets = pets; }

    static WatchSkinPack importArchive(Context context, File archive) throws IOException, JSONException {
        File root = new File(context.getFilesDir(), "watch-frames");
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Could not create frame storage");
        File pending = new File(root, "pending-" + UUID.randomUUID());
        try {
            SafeFrameArchive.extract(archive, pending);
            read(pending);
            File installed = new File(root, "pack-" + UUID.randomUUID());
            if (!pending.renameTo(installed)) throw new IOException("Could not publish frame pack");
            return read(installed);
        } finally {
            if (pending.exists()) SafeFrameArchive.deleteTree(pending);
        }
    }

    static WatchSkinPack read(File directory) throws IOException, JSONException {
        File manifest = new File(directory, "frames.json");
        if (!manifest.isFile() || manifest.length() > 256 * 1024) {
            throw new IOException("Frame manifest is missing or too large");
        }
        JSONObject root = new JSONObject(new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8));
        if (root.optInt("version", -1) != 1) throw new JSONException("Unsupported frame version");
        JSONArray pets = root.getJSONArray("pets");
        if (pets.length() < 1 || pets.length() > 6) throw new JSONException("Invalid frame pet count");
        List<Skin> result = new ArrayList<>();
        int frameCount = 0;
        Map<String, Boolean> validated = new HashMap<>();
        for (int p = 0; p < pets.length(); p++) {
            JSONObject pet = pets.getJSONObject(p);
            String id = pet.getString("id");
            String name = pet.optString("name", "Companion");
            if (id.isEmpty() || id.length() > 240 || name.length() > 240) {
                throw new JSONException("Invalid frame pet name");
            }
            JSONObject motions = pet.getJSONObject("motions");
            if (motions.length() < 1 || motions.length() > 32) throw new JSONException("Invalid motion count");
            Map<String, List<Frame>> parsed = new HashMap<>();
            Iterator<String> states = motions.keys();
            while (states.hasNext()) {
                String state = states.next();
                if (state.length() > 80) throw new JSONException("Invalid motion name");
                JSONArray frames = motions.getJSONArray(state);
                if (frames.length() < 1 || frames.length() > 64) throw new JSONException("Invalid animation length");
                List<Frame> sequence = new ArrayList<>();
                for (int f = 0; f < frames.length(); f++) {
                    if (++frameCount > 512) throw new JSONException("Too many animation frames");
                    JSONObject frame = frames.getJSONObject(f);
                    String path = frame.getString("file");
                    int duration = frame.getInt("durationMs");
                    if (duration < 20 || duration > 60000 || !path.endsWith(".png")) {
                        throw new JSONException("Invalid animation frame");
                    }
                    File file = SafeFrameArchive.resolve(directory, path);
                    if (!file.isFile()) throw new IOException("Missing animation frame");
                    if (!validated.containsKey(path)) {
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inJustDecodeBounds = true;
                        BitmapFactory.decodeFile(file.getAbsolutePath(), options);
                        if (options.outWidth < 1 || options.outHeight < 1 || options.outWidth > 256 || options.outHeight > 256
                                || !"image/png".equals(options.outMimeType)) {
                            throw new IOException("Frame must be a PNG no larger than 256 pixels");
                        }
                        Bitmap decoded = BitmapFactory.decodeFile(file.getAbsolutePath());
                        if (decoded == null) throw new IOException("Could not decode animation frame");
                        decoded.recycle();
                        validated.put(path, true);
                    }
                    sequence.add(new Frame(file, duration));
                }
                parsed.put(state, sequence);
            }
            result.add(new Skin(id, name, parsed));
        }
        return new WatchSkinPack(directory, result);
    }

    Skin skin(String id, int index) {
        for (Skin skin : pets) if (skin.id.equals(id)) return skin;
        if (!id.isEmpty()) return null;
        return pets.get(index % pets.size());
    }

    Bitmap frame(Skin skin, String state, long elapsedMs) {
        List<Frame> sequence = skin.motions.get(state);
        if (sequence == null) sequence = skin.motions.get("stop");
        if (sequence == null) sequence = skin.motions.get("idle");
        if (sequence == null) sequence = skin.motions.values().iterator().next();
        long total = 0;
        for (Frame frame : sequence) total += frame.durationMs;
        long time = elapsedMs % Math.max(1, total);
        Frame selected = sequence.get(sequence.size()-1);
        for (Frame frame : sequence) {
            if (time < frame.durationMs) { selected = frame; break; }
            time -= frame.durationMs;
        }
        String key = selected.file.getAbsolutePath();
        Bitmap image = cache.get(key);
        if (image == null) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            image = BitmapFactory.decodeFile(key, options);
            if (image != null) cache.put(key, image);
        }
        return image;
    }

    void release() { cache.evictAll(); }

    static void cleanup(Context context, File keep) {
        File root = new File(context.getFilesDir(), "watch-frames");
        File[] entries = root.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            if (!entry.equals(keep) && (entry.getName().startsWith("pack-") || entry.getName().startsWith("pending-"))) {
                SafeFrameArchive.deleteTree(entry);
            }
        }
    }
}
