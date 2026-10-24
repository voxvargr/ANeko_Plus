// SPDX-License-Identifier: LGPL-2.1-or-later
package org.foss.wearneko;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Validated phone settings. Watch size is scaled from the original 16 dp baseline. */
final class WatchPetSettings {
    static final class PetOptions {
        final String id;
        final String name;
        final float sizeScale;
        final float speed;
        final float opacity;

        PetOptions(String id, String name, float sizeScale, float speed, float opacity) {
            this.id = id;
            this.name = name;
            this.sizeScale = sizeScale;
            this.speed = speed;
            this.opacity = opacity;
        }
    }

    final boolean quiet;
    final boolean attention;
    final boolean haptics;
    final PetOptions[] pets;
    final String json;

    private WatchPetSettings(boolean quiet, boolean attention, boolean haptics, PetOptions[] pets, String json) {
        this.quiet = quiet;
        this.attention = attention;
        this.haptics = haptics;
        this.pets = pets;
        this.json = json;
    }

    static WatchPetSettings defaults() {
        return new WatchPetSettings(false, false, false,
                new PetOptions[]{new PetOptions("", "Little cat", 1f, 1f, 1f)}, "");
    }

    static WatchPetSettings parse(String json) throws JSONException {
        if (json == null || json.length() > 64 * 1024) {
            throw new JSONException("Settings are missing or too large");
        }
        JSONObject root = new JSONObject(json);
        if (root.optInt("version", -1) != 1) {
            throw new JSONException("Unsupported settings version");
        }
        int count = root.optInt("count", 1);
        if (count < 1 || count > 6) throw new JSONException("Pet count must be 1 to 6");
        float size = number(root, "sizeScale", 1f, 0.25f, 4f);
        float speed = number(root, "speed", 1f, 0.25f, 4f);
        float opacity = number(root, "opacity", 1f, 0.1f, 1f);
        JSONArray skins = root.optJSONArray("skins");
        PetOptions[] pets = new PetOptions[count];
        for (int i = 0; i < count; i++) {
            JSONObject skin = skins == null ? null : skins.optJSONObject(i);
            if (skin == null) skin = new JSONObject();
            String id = skin.optString("package", "");
            String name = skin.optString("name", "Companion " + (i + 1));
            if (id.length() > 240 || name.length() > 240) {
                throw new JSONException("Pet name is too long");
            }
            pets[i] = new PetOptions(id, name,
                    number(skin, "sizeScale", size, 0.25f, 4f),
                    number(skin, "speed", speed, 0.25f, 4f),
                    number(skin, "opacity", opacity, 0.1f, 1f));
        }
        return new WatchPetSettings(root.optBoolean("quiet", false), root.optBoolean("attention", false),
                root.optBoolean("haptics", false), pets, json);
    }

    private static float number(JSONObject object, String key, float fallback,
            float min, float max) throws JSONException {
        double value = object.has(key) ? object.getDouble(key) : fallback;
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new JSONException("Invalid " + key);
        }
        return (float) value;
    }
}
