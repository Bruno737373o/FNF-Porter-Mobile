package com.sametgkte.fnfporter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Converts Psych stage JSON and the common static-sprite subset of stage.lua. */
public class StageConverter {

    public static JSONObject convert(JSONObject stageJSON, String assetName, JSONArray luaProps) throws Exception {
        JSONObject source = stageJSON == null ? new JSONObject() : stageJSON;
        JSONObject stage = Constants.stageTemplate();
        stage.put("cameraZoom", source.optDouble("defaultZoom", 1.0));
        putCharacterPosition(stage, "bf", source.optJSONArray("boyfriend"));
        putCharacterPosition(stage, "gf", source.optJSONArray("girlfriend"));
        putCharacterPosition(stage, "dad", source.optJSONArray("opponent"));
        stage.put("props", luaProps == null ? new JSONArray() : luaProps);
        stage.put("name", Utils.titleCaseDashed(assetName == null ? "stage" : assetName));
        return stage;
    }

    private static void putCharacterPosition(JSONObject stage, String id, JSONArray position) throws Exception {
        if (position == null || position.length() < 2) return;
        JSONArray safe = new JSONArray();
        safe.put(position.optDouble(0, 0));
        safe.put(position.optDouble(1, 0));
        stage.getJSONObject("characters").getJSONObject(id).put("position", safe);
    }

    public static JSONArray parseStageLua(File luaFile) {
        if (luaFile == null || !luaFile.exists()) return new JSONArray();
        try {
            String src = stripLuaComments(FileOps.readText(luaFile));
            List<Prop> props = new ArrayList<Prop>();
            Map<String, Prop> byTag = new HashMap<String, Prop>();

            for (String call : extractCalls(src, "makeLuaSprite")) addProp(call, false, props, byTag);
            for (String call : extractCalls(src, "makeAnimatedLuaSprite")) addProp(call, true, props, byTag);
            applyPair(src, "scaleObject", byTag, true);
            applyPair(src, "setScrollFactor", byTag, false);
            applyProperties(src, byTag);
            applyAnimations(src, byTag);
            applyOrder(src, props, byTag);
            return toFnfProps(props);
        } catch (Exception e) {
            AppLog.error("Could not complete parsing of " + luaFile.getName(), e);
            return new JSONArray();
        }
    }

    /** Remove Lua line/long comments without treating comment markers inside strings as comments. */
    private static String stripLuaComments(String code) {
        StringBuilder out = new StringBuilder(code.length());
        boolean single = false, dbl = false, escape = false;
        int longStringEquals = -1;
        for (int i = 0; i < code.length();) {
            char c = code.charAt(i);
            char next = i + 1 < code.length() ? code.charAt(i + 1) : '\0';
            if (longStringEquals >= 0) {
                String close = "]" + repeat('=', longStringEquals) + "]";
                if (code.startsWith(close, i)) {
                    out.append(close);
                    i += close.length();
                    longStringEquals = -1;
                } else { out.append(c); i++; }
                continue;
            }
            if (escape) { out.append(c); escape = false; i++; continue; }
            if ((single || dbl) && c == '\\') { out.append(c); escape = true; i++; continue; }
            if (single || dbl) {
                if ((single && c == '\'') || (dbl && c == '"')) { single = false; dbl = false; }
                out.append(c); i++; continue;
            }
            if (c == '\'') { single = true; out.append(c); i++; continue; }
            if (c == '"') { dbl = true; out.append(c); i++; continue; }
            if (c == '[') {
                int k = i + 1, equals = 0;
                while (k < code.length() && code.charAt(k) == '=') { equals++; k++; }
                if (k < code.length() && code.charAt(k) == '[') {
                    longStringEquals = equals;
                    out.append(code, i, k + 1);
                    i = k + 1;
                    continue;
                }
            }
            if (c == '-' && next == '-') {
                int open = i + 2, k = open, equals = 0;
                if (k < code.length() && code.charAt(k) == '[') {
                    k++;
                    while (k < code.length() && code.charAt(k) == '=') { equals++; k++; }
                    if (k < code.length() && code.charAt(k) == '[') {
                        String close = "]" + repeat('=', equals) + "]";
                        int end = code.indexOf(close, k + 1);
                        int stop = end < 0 ? code.length() : end + close.length();
                        out.append(' ');
                        for (int n = i; n < stop; n++) if (code.charAt(n) == '\n') out.append('\n');
                        i = stop;
                        continue;
                    }
                }
                while (i < code.length() && code.charAt(i) != '\n') i++;
                if (i < code.length()) out.append('\n');
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String repeat(char c, int count) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < count; i++) b.append(c);
        return b.toString();
    }

    /** Extract balanced argument lists, so nested calls, multiline calls and semicolons in strings are safe. */
    private static List<String> extractCalls(String source, String function) {
        List<String> calls = new ArrayList<String>();
        int from = 0;
        while (from < source.length()) {
            int at = findCodeOccurrence(source, function, from);
            if (at < 0) break;
            int after = at + function.length();
            if ((at > 0 && Character.isJavaIdentifierPart(source.charAt(at - 1)))
                    || (after < source.length() && Character.isJavaIdentifierPart(source.charAt(after)))) {
                from = after;
                continue;
            }
            int open = after;
            while (open < source.length() && Character.isWhitespace(source.charAt(open))) open++;
            if (open >= source.length() || source.charAt(open) != '(') { from = after; continue; }
            int close = matchingParen(source, open);
            if (close < 0) break;
            calls.add(source.substring(open + 1, close));
            from = close + 1;
        }
        return calls;
    }

    private static int findCodeOccurrence(String source, String needle, int from) {
        boolean single = false, dbl = false, escape = false;
        int longEquals = -1;
        for (int i = 0; i < source.length();) {
            char c = source.charAt(i);
            if (longEquals >= 0) {
                String close = "]" + repeat('=', longEquals) + "]";
                if (source.startsWith(close, i)) { i += close.length(); longEquals = -1; }
                else i++;
                continue;
            }
            if (escape) { escape = false; i++; continue; }
            if ((single || dbl) && c == '\\') { escape = true; i++; continue; }
            if (single) { if (c == '\'') single = false; i++; continue; }
            if (dbl) { if (c == '"') dbl = false; i++; continue; }
            if (c == '\'') { single = true; i++; continue; }
            if (c == '"') { dbl = true; i++; continue; }
            if (c == '[') {
                int k = i + 1, eq = 0;
                while (k < source.length() && source.charAt(k) == '=') { eq++; k++; }
                if (k < source.length() && source.charAt(k) == '[') { longEquals = eq; i = k + 1; continue; }
            }
            int after = i + needle.length();
            if (i >= from && source.startsWith(needle, i)
                    && (i == 0 || !Character.isJavaIdentifierPart(source.charAt(i - 1)))
                    && (after >= source.length() || !Character.isJavaIdentifierPart(source.charAt(after)))) return i;
            i++;
        }
        return -1;
    }

    private static int matchingParen(String source, int open) {
        int depth = 0, longEquals = -1;
        boolean single = false, dbl = false, escape = false;
        for (int i = open; i < source.length();) {
            char c = source.charAt(i);
            if (longEquals >= 0) {
                String close = "]" + repeat('=', longEquals) + "]";
                if (source.startsWith(close, i)) { i += close.length(); longEquals = -1; }
                else i++;
                continue;
            }
            if (escape) { escape = false; i++; continue; }
            if ((single || dbl) && c == '\\') { escape = true; i++; continue; }
            if (single) { if (c == '\'') single = false; i++; continue; }
            if (dbl) { if (c == '"') dbl = false; i++; continue; }
            if (c == '\'') { single = true; i++; continue; }
            if (c == '"') { dbl = true; i++; continue; }
            if (c == '[') {
                int k = i + 1, eq = 0;
                while (k < source.length() && source.charAt(k) == '=') { eq++; k++; }
                if (k < source.length() && source.charAt(k) == '[') { longEquals = eq; i = k + 1; continue; }
            }
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
            i++;
        }
        return -1;
    }

    private static void addProp(String raw, boolean animated, List<Prop> props, Map<String, Prop> byTag) {
        List<String> args = splitArgs(raw);
        if (args.size() < 2) return;
        Prop prop = new Prop();
        prop.tag = unquote(args.get(0));
        prop.sprite = unquote(args.get(1));
        prop.animated = animated;
        if (args.size() >= 4) {
            prop.x = toDouble(args.get(2), 0);
            prop.y = toDouble(args.get(3), 0);
        }
        props.add(prop);
        byTag.put(prop.tag, prop);
    }

    private static void applyPair(String source, String function, Map<String, Prop> byTag, boolean scale) {
        for (String raw : extractCalls(source, function)) {
            List<String> args = splitArgs(raw);
            if (args.size() < 3) continue;
            Prop prop = byTag.get(unquote(args.get(0)));
            if (prop == null) continue;
            double x = toDouble(args.get(1), 1);
            double y = toDouble(args.get(2), 1);
            if (scale) { prop.sx = x; prop.sy = y; }
            else { prop.scx = x; prop.scy = y; }
        }
    }

    private static void applyProperties(String source, Map<String, Prop> byTag) {
        for (String raw : extractCalls(source, "setProperty")) {
            List<String> args = splitArgs(raw);
            if (args.size() < 2) continue;
            String path = unquote(args.get(0));
            int dot = path.lastIndexOf('.');
            if (dot <= 0) continue;
            Prop prop = byTag.get(path.substring(0, dot));
            if (prop == null) continue;
            String field = path.substring(dot + 1).toLowerCase(Locale.US);
            String value = args.get(1).trim();
            switch (field) {
                case "x": prop.x = toDouble(value, prop.x); break;
                case "y": prop.y = toDouble(value, prop.y); break;
                case "alpha": prop.alpha = toDouble(value, prop.alpha); break;
                case "angle": prop.angle = toDouble(value, prop.angle); break;
                case "flipx": prop.flipX = toBool(value, prop.flipX); break;
                case "flipy": prop.flipY = toBool(value, prop.flipY); break;
                case "antialiasing": prop.isPixel = !toBool(value, !prop.isPixel); break;
                case "color":
                    String literal = unquote(value);
                    if (literal.startsWith("#") || literal.startsWith("0x") || literal.startsWith("0X"))
                        prop.color = normalizeColor(literal);
                    break;
                default: break;
            }
        }
    }

    private static String normalizeColor(String color) {
        String value = color.trim();
        if (value.startsWith("0x") || value.startsWith("0X")) {
            String hex = value.substring(2);
            if (hex.length() == 8) hex = hex.substring(2);
            return "#" + hex;
        }
        return value;
    }

    private static void applyAnimations(String source, Map<String, Prop> byTag) {
        for (String raw : extractCalls(source, "addAnimationByPrefix")) {
            List<String> args = splitArgs(raw);
            if (args.size() < 3) continue;
            Prop prop = byTag.get(unquote(args.get(0)));
            if (prop == null) continue;
            Anim anim = new Anim();
            anim.name = unquote(args.get(1));
            anim.prefix = unquote(args.get(2));
            if (args.size() >= 4) anim.fps = toInt(args.get(3), 24);
            if (args.size() >= 5) anim.loop = toBool(args.get(4), true);
            prop.anims.add(anim);
        }
    }

    private static void applyOrder(String source, List<Prop> props, Map<String, Prop> byTag) {
        List<String> order = new ArrayList<String>();
        List<Boolean> front = new ArrayList<Boolean>();
        for (String raw : extractCalls(source, "addLuaSprite")) {
            List<String> args = splitArgs(raw);
            if (args.isEmpty()) continue;
            order.add(unquote(args.get(0)));
            front.add(args.size() > 1 && toBool(args.get(1), false));
        }
        for (int i = 0; i < order.size(); i++) {
            Prop prop = byTag.get(order.get(i));
            if (prop == null) continue;
            prop.z = front.get(i) ? 301 + i : i - order.size();
        }
    }

    private static JSONArray toFnfProps(List<Prop> props) throws Exception {
        JSONArray out = new JSONArray();
        for (Prop prop : props) {
            JSONObject tmpl = prop.animated ? Constants.stagePropAnimated() : Constants.stagePropImage();
            tmpl.put("name", prop.tag);
            tmpl.put("assetPath", prop.sprite);
            JSONArray pos = new JSONArray(); pos.put(prop.x); pos.put(prop.y);
            tmpl.put("position", pos);
            tmpl.put("zIndex", prop.z);
            JSONArray scale = new JSONArray(); scale.put(prop.sx); scale.put(prop.sy);
            tmpl.put("scale", scale);
            JSONArray scroll = new JSONArray(); scroll.put(prop.scx); scroll.put(prop.scy);
            tmpl.put("scroll", scroll);
            tmpl.put("alpha", prop.alpha);
            tmpl.put("angle", prop.angle);
            tmpl.put("flipX", prop.flipX);
            tmpl.put("flipY", prop.flipY);
            tmpl.put("isPixel", prop.isPixel);
            if (prop.color != null) tmpl.put("color", prop.color);
            if (prop.animated) {
                JSONArray animations = new JSONArray();
                for (Anim anim : prop.anims) {
                    JSONObject at = Constants.stagePropAnimation();
                    at.put("frameRate", anim.fps);
                    at.put("looped", anim.loop);
                    at.put("name", anim.name);
                    at.put("prefix", anim.prefix);
                    animations.put(at);
                }
                tmpl.put("animations", animations);
                if (!prop.anims.isEmpty()) tmpl.put("startingAnimation", prop.anims.get(0).name);
            }
            out.put(tmpl);
        }
        return out;
    }

    private static List<String> splitArgs(String raw) {
        List<String> out = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean single = false, dbl = false, escape = false;
        int paren = 0, brace = 0, bracket = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (escape) { current.append(c); escape = false; continue; }
            if ((single || dbl) && c == '\\') { current.append(c); escape = true; continue; }
            if (c == '\'' && !dbl) single = !single;
            else if (c == '"' && !single) dbl = !dbl;
            else if (!single && !dbl) {
                if (c == '(') paren++; else if (c == ')') paren--;
                else if (c == '{') brace++; else if (c == '}') brace--;
                else if (c == '[') bracket++; else if (c == ']') bracket--;
                if (c == ',' && paren <= 0 && brace <= 0 && bracket <= 0) {
                    out.add(current.toString().trim());
                    current.setLength(0);
                    continue;
                }
            }
            current.append(c);
        }
        if (current.length() > 0) out.add(current.toString().trim());
        return out;
    }

    private static String unquote(String value) {
        if (value == null) return "";
        String s = value.trim();
        if (s.length() >= 2 && ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\""))))
            return s.substring(1, s.length() - 1).replace("\\'", "'").replace("\\\"", "\"").replace("\\\\", "\\");
        return s;
    }

    private static double toDouble(String value, double fallback) {
        try { return Double.parseDouble(unquote(value).trim()); }
        catch (Exception e) { return fallback; }
    }

    private static int toInt(String value, int fallback) {
        try { return (int) Double.parseDouble(unquote(value).trim()); }
        catch (Exception e) { return fallback; }
    }

    private static boolean toBool(String value, boolean fallback) {
        if (value == null) return fallback;
        String s = unquote(value).trim().toLowerCase(Locale.US);
        if ("true".equals(s) || "1".equals(s)) return true;
        if ("false".equals(s) || "0".equals(s)) return false;
        return fallback;
    }

    private static class Prop {
        String tag = "";
        String sprite = "";
        boolean animated, flipX, flipY, isPixel;
        double x, y, sx = 1, sy = 1, scx = 1, scy = 1, alpha = 1, angle;
        int z;
        String color = "#FFFFFF";
        List<Anim> anims = new ArrayList<Anim>();
    }

    private static class Anim {
        String name;
        String prefix;
        int fps = 24;
        boolean loop = true;
    }
}
