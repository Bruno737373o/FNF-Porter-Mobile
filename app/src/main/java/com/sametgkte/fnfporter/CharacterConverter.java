package com.sametgkte.fnfporter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/** Converts a Psych Engine character JSON into the current V-Slice CharacterData shape. */
public class CharacterConverter {
    public final String characterName;
    public final String iconID;
    public final String characterJson;
    private final JSONObject character;

    public CharacterConverter(File path) throws Exception {
        this.characterJson = stripExt(path.getName());
        this.characterName = toDisplayName(characterJson);

        JSONObject psych = new JSONObject(FileOps.readText(path));
        character = Constants.characterTemplate();
        AppLog.info("Converting character " + characterName);

        String assetPath = psych.optString("image", "").trim();
        character.put("name", psych.optString("name", characterName));
        character.put("assetPath", assetPath);
        character.put("renderType", "sparrow");
        character.put("singTime", psych.optDouble("sing_duration", 8.0));
        character.put("scale", psych.optDouble("scale", 1.0));
        // Psych's explicit no_antialiasing flag is the actual pixel-art signal; scale is not reliable for this.
        boolean pixel = psych.optBoolean("no_antialiasing", false);
        character.put("isPixel", pixel);
        character.put("flipX", psych.optBoolean("flip_x", false));
        character.put("offsets", pairOrDefault(psych.optJSONArray("position"), 0, 0));
        character.put("cameraOffsets", pairOrDefault(psych.optJSONArray("camera_position"), 0, 0));
        character.put("danceEvery", psych.optBoolean("danceIdle", true) ? 1.0 : 0.0);

        String icon = psych.optString("healthicon", characterJson);
        this.iconID = icon;
        JSONObject healthIcon = character.getJSONObject("healthIcon");
        healthIcon.put("id", icon);
        healthIcon.put("isPixel", pixel);

        JSONArray anims = psych.optJSONArray("animations");
        if (anims == null) anims = new JSONArray();
        JSONArray out = character.getJSONArray("animations");
        String startingAnimation = chooseStartingAnimation(anims);
        character.put("startingAnimation", startingAnimation);

        for (int i = 0; i < anims.length(); i++) {
            JSONObject animation = anims.optJSONObject(i);
            if (animation == null) continue;
            JSONObject converted = Constants.animationTemplate();
            converted.put("name", animation.optString("anim", ""));
            converted.put("prefix", animation.optString("name", ""));
            converted.put("offsets", pairOrDefault(animation.optJSONArray("offsets"), 0, 0));
            converted.put("frameRate", animation.optInt("fps", 24));
            converted.put("frameIndices", animation.optJSONArray("indices") == null
                    ? new JSONArray() : animation.getJSONArray("indices"));
            converted.put("looped", animation.optBoolean("loop", false));
            AppLog.info("[" + characterName + "] Converting animation " + animation.optString("anim"));
            out.put(converted);
        }
        if (out.length() == 0) {
            AppLog.warn("Character " + characterName + " has no animations; current V-Slice character data requires animation entries.");
        }
        if (assetPath.length() == 0) {
            AppLog.warn("Character " + characterName + " is missing Psych image; current V-Slice data requires assetPath.");
        }
        AppLog.info("Character " + characterName + " conversion complete");
    }

    private static JSONArray pairOrDefault(JSONArray input, double x, double y) throws Exception {
        JSONArray result = new JSONArray();
        if (input != null && input.length() >= 2) {
            result.put(input.optDouble(0, x));
            result.put(input.optDouble(1, y));
        } else {
            result.put(x);
            result.put(y);
        }
        return result;
    }

    private static String chooseStartingAnimation(JSONArray animations) {
        String first = "";
        for (int i = 0; i < animations.length(); i++) {
            JSONObject animation = animations.optJSONObject(i);
            if (animation == null) continue;
            String name = animation.optString("anim", "");
            if (name.length() == 0) continue;
            if (first.length() == 0) first = name;
            if ("idle".equalsIgnoreCase(name)) return name;
        }
        if (first.length() == 0) return "idle";
        return first;
    }

    private static String toDisplayName(String id) {
        String[] parts = id.split("-");
        StringBuilder name = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (name.length() > 0) name.append(' ');
            name.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) name.append(part.substring(1));
        }
        return name.toString();
    }

    public void save(File resultDir) throws Exception {
        File out = new File(resultDir, characterJson + ".json");
        FileOps.writeText(out, character.toString(4));
        AppLog.info("Character " + characterName + " saved to " + out.getAbsolutePath());
    }

    private static String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }
}
