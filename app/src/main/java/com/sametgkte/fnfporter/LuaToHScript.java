package com.sametgkte.fnfporter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Psych Lua → V-Slice HScript (.hxc). Best-effort, same spirit as GkTe UFT.
 */
public class LuaToHScript {
    public enum Context { GENERIC, SONG, NOTE_KIND, SONG_EVENT }

    public final List<String> warnings = new ArrayList<String>();
    private int currentLineNumber = 1;
    private Context context = Context.GENERIC;
    private String sourceId = "";
    private String currentSourceCallback = "";

    private static final Map<String, String> CALLBACKS = new HashMap<String, String>();
    private static final Map<String, String> PARAMS = new HashMap<String, String>();

    static {
        String[] same = {
                "onCreate", "onCreatePost", "onUpdate", "onUpdatePost", "onBeatHit", "onStepHit",
                "onSectionHit", "onSongStart", "onEndSong", "onCountdownTick", "onEvent",
                "noteMiss", "goodNoteHit", "opponentNoteHit", "onKeyPress", "onKeyRelease",
                "onGhostTap", "onMoveCamera", "onGameOver", "onPause", "onResume", "onDestroy"
        };
        for (String s : same) CALLBACKS.put(s, s);
        CALLBACKS.put("onStartCountdown", "onCountdownStart");
        CALLBACKS.put("onEndSong", "onSongEnd");
        CALLBACKS.put("onEvent", "onSongEvent");
        CALLBACKS.put("onCountdownTick", "onCountdownStep");
        CALLBACKS.put("onSpawnNote", "onNoteIncoming");
        CALLBACKS.put("goodNoteHit", "onNoteHit");
        CALLBACKS.put("noteMiss", "onNoteMiss");
        CALLBACKS.put("noteMissPress", "onNoteGhostMiss");
        PARAMS.put("onCountdownStart", "event:CountdownScriptEvent");
        PARAMS.put("onUpdate", "elapsed:Float");
        PARAMS.put("onUpdatePost", "elapsed:Float");
        PARAMS.put("onEvent", "name:String, value1:String, value2:String");
        PARAMS.put("noteMiss", "id:Int, direction:Int, noteType:String, isSustain:Bool");
        PARAMS.put("goodNoteHit", "id:Int, direction:Int, noteType:String, isSustain:Bool");
        PARAMS.put("opponentNoteHit", "id:Int, direction:Int, noteType:String, isSustain:Bool");
        PARAMS.put("onKeyPress", "key:Int");
        PARAMS.put("onKeyRelease", "key:Int");
        PARAMS.put("onGhostTap", "key:Int");
        PARAMS.put("onMoveCamera", "focus:String");
    }

    private static class Func {
        String params;
        List<String> body = new ArrayList<String>();
    }

    public String convert(String lua) {
        return convert(lua, Context.GENERIC, "");
    }

    public String convert(String lua, Context context, String sourceId) {
        warnings.clear();
        this.context = context == null ? Context.GENERIC : context;
        this.sourceId = sourceId == null ? "" : sourceId;
        currentSourceCallback = "";
        if (this.context == Context.SONG_EVENT) {
            warnings.add("Custom event payloads are engine-specific. The generated ScriptedSongEvent wrapper preserves basic name/value/time access; review each event schema and behavior.");
        } else {
            warnings.add("Lua APIs and runtime behavior do not map completely to V-Slice; review generated class/callback logic and this script's warning sidecar.");
        }
        if (this.context == Context.NOTE_KIND && Pattern.compile("\\bid\\b").matcher(lua).find()) {
            warnings.add("Psych note callback uses an id index; V-Slice note events have no equivalent note-group index. Review references to id.");
        }
        if (this.context == Context.SONG && lua.contains("onSpawnNote")) {
            warnings.add("Psych onSpawnNote and V-Slice onNoteIncoming may run at different points in the note lifecycle; review timing and note direction.");
            if (Pattern.compile("\\bid\\b").matcher(lua).find())
                warnings.add("Psych onSpawnNote id is a note-group index with no direct V-Slice event equivalent; review references to id.");
        }
        Map<String, Func> functions = new LinkedHashMap<String, Func>();
        List<String> globals = new ArrayList<String>();
        parse(stripLuaComments(lua).split("\\n", -1), functions, globals);
        String converted = build(functions, globals);
        return wrapContextClass(converted);
    }

    private static String stripLuaComments(String code) {
        StringBuilder out = new StringBuilder(code.length());
        boolean single = false, dbl = false, escape = false, longComment = false;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            char next = i + 1 < code.length() ? code.charAt(i + 1) : '\0';
            if (longComment) {
                if (c == ']' && next == ']') { longComment = false; i++; }
                else if (c == '\n') out.append('\n');
                continue;
            }
            if (escape) { out.append(c); escape = false; continue; }
            if ((single || dbl) && c == '\\') { out.append(c); escape = true; continue; }
            if (c == '\'' && !dbl) single = !single;
            else if (c == '"' && !single) dbl = !dbl;
            if (!single && !dbl && c == '-' && next == '-') {
                if (i + 3 < code.length() && code.charAt(i + 2) == '[' && code.charAt(i + 3) == '[') {
                    longComment = true;
                    i += 3;
                    continue;
                }
                while (i < code.length() && code.charAt(i) != '\n') i++;
                if (i < code.length()) out.append('\n');
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private void parse(String[] lines, Map<String, Func> functions, List<String> globals) {
        boolean inFn = false;
        String name = "", params = "";
        List<String> body = new ArrayList<String>();
        int endDepth = 0;
        for (int i = 0; i < lines.length; i++) {
            currentLineNumber = i + 1;
            String stripped = lines[i].trim();
            if (stripped.length() == 0) {
                if (inFn) body.add(withLine(i + 1, ""));
                continue;
            }
            if (stripped.startsWith("--")) {
                continue;
            }
            Matcher fm = Pattern.compile("^function\\s+(\\w+)\\s*\\((.*?)\\)\\s*$").matcher(stripped);
            if (fm.find()) {
                if (inFn && name.length() > 0) save(functions, name, params, body);
                name = fm.group(1);
                if ((context == Context.SONG || context == Context.NOTE_KIND)
                        && ("opponentNoteHit".equals(name) || "onUpdatePost".equals(name) || "onKeyPress".equals(name)
                        || "onKeyRelease".equals(name) || "onGhostTap".equals(name) || "onMoveCamera".equals(name)
                        || "onCountdownStarted".equals(name))) {
                    warnings.add("line " + (i + 1) + ": Psych callback '" + name + "' has no direct V-Slice callback equivalent; retained as a non-dispatched method.");
                } else if (name.startsWith("on") && !CALLBACKS.containsKey(name)) {
                    warnings.add("line " + (i + 1) + ": callback '" + name + "' is not in the known V-Slice callback map; generated method may not be invoked.");
                }
                params = fm.group(2).trim();
                body = new ArrayList<String>();
                inFn = true;
                endDepth = 0;
                continue;
            }
            if ("end".equals(stripped)) {
                if (inFn) {
                    if (endDepth == 0) {
                        save(functions, name, params, body);
                        inFn = false;
                        name = "";
                        params = "";
                        body = new ArrayList<String>();
                    } else {
                        endDepth--;
                        body.add(withLine(i + 1, "end"));
                    }
                }
                continue;
            }
            if (inFn && (stripped.endsWith(" then") || stripped.endsWith(" do"))) endDepth++;
            if (inFn) body.add(withLine(i + 1, stripped));
            else globals.add(withLine(i + 1, stripped));
        }
        if (inFn && name.length() > 0) save(functions, name, params, body);
    }

    private static void save(Map<String, Func> functions, String name, String params, List<String> body) {
        Func f = new Func();
        f.params = params;
        f.body = body;
        functions.put(name, f);
    }

    private String build(Map<String, Func> functions, List<String> globals) {
        List<String> result = new ArrayList<String>();
        result.add("");
        boolean anyG = false;
        for (String rawLine : globals) {
            String line = stripLineInfo(rawLine).trim();
            if (line.length() == 0) continue;
            String c = convertLine(line);
            if (c != null) {
                if (context != Context.GENERIC && c.matches("^[A-Za-z_][A-Za-z0-9_]*\\s*=.*")) c = "var " + c;
                result.add(c);
                anyG = true;
            }
        }
        if (anyG) result.add("");
        for (Map.Entry<String, Func> e : functions.entrySet()) {
            currentSourceCallback = e.getKey();
            String hs = context == Context.SONG_EVENT && "onEvent".equals(e.getKey())
                    ? "handleEvent" : (CALLBACKS.containsKey(e.getKey()) ? CALLBACKS.get(e.getKey()) : e.getKey());
            String params = context == Context.GENERIC
                    ? (PARAMS.containsKey(hs) ? PARAMS.get(hs) : e.getValue().params)
                    : (context == Context.SONG_EVENT && "onEvent".equals(e.getKey())
                        ? "data:SongEventData" : contextParams(hs, e.getValue().params));
            String access = context == Context.GENERIC ? "function "
                    : (isContextOverride(hs) ? "override function " : "public function ");
            result.add(access + hs + "(" + params + ") {");
            int indent = 1;
            if (context == Context.SONG_EVENT && "onEvent".equals(e.getKey())) {
                result.add(pad(indent) + "var name = data.eventKind;");
                result.add(pad(indent) + "var value1 = data.getDynamic('value');");
                result.add(pad(indent) + "var value2 = data.getDynamic('value2');");
                result.add(pad(indent) + "var strumTime = data.time;");
            }
            for (String raw : e.getValue().body) {
                String stripped = stripLineInfo(raw).trim();
                if (stripped.length() == 0) {
                    result.add("");
                    continue;
                }
                if (stripped.startsWith("--")) {
                    continue;
                }
                if ("end".equals(stripped)) {
                    indent = Math.max(1, indent - 1);
                    result.add(pad(indent) + "}");
                    continue;
                }
                if ("else".equals(stripped)) {
                    indent = Math.max(1, indent - 1);
                    result.add(pad(indent) + "} else {");
                    indent++;
                    continue;
                }
                Matcher em = Pattern.compile("^elseif\\s+(.+)\\s+then$").matcher(stripped);
                if (em.find()) {
                    indent = Math.max(1, indent - 1);
                    result.add(pad(indent) + "} else if (" + toHsCond(em.group(1)) + ") {");
                    indent++;
                    continue;
                }
                Matcher im = Pattern.compile("^if\\s+(.+)\\s+then$").matcher(stripped);
                if (im.find()) {
                    result.add(pad(indent) + "if (" + toHsCond(im.group(1)) + ") {");
                    indent++;
                    continue;
                }
                Matcher frm = Pattern.compile("^for\\s+(\\w+)\\s*=\\s*(\\d+)\\s*,\\s*(\\d+)\\s*do$").matcher(stripped);
                if (frm.find()) {
                    int end = Integer.parseInt(frm.group(3)) + 1;
                    result.add(pad(indent) + "for (" + frm.group(1) + " in " + frm.group(2) + "..." + end + ") {");
                    indent++;
                    continue;
                }
                String c = convertLine(stripped);
                if (c != null) result.add(pad(indent) + c);
            }
            result.add("}");
            result.add("");
        }
        if (warnings.size() > 0) { }
        return join(result);
    }

    private boolean isContextOverride(String callback) {
        if ("handleEvent".equals(callback)) return context == Context.SONG_EVENT;
        String[] supported = {"onCreate", "onDestroy", "onUpdate", "onBeatHit", "onStepHit", "onSongStart",
                "onSongEnd", "onGameOver", "onPause", "onResume", "onNoteIncoming", "onNoteHit", "onNoteMiss",
                "onNoteGhostMiss", "onSongEvent", "onCountdownStart", "onCountdownStep", "onCountdownEnd"};
        for (String name : supported) if (name.equals(callback)) return true;
        return false;
    }

    private String contextParams(String callback, String originalParams) {
        if (context != Context.SONG && context != Context.NOTE_KIND) return originalParams;
        String prefix = "event:";
        if ("onUpdate".equals(callback)) return prefix + "funkin.modding.events.ScriptEvent.UpdateScriptEvent";
        if ("onCreate".equals(callback) || "onDestroy".equals(callback) || "onSongStart".equals(callback)
                || "onSongEnd".equals(callback) || "onGameOver".equals(callback) || "onResume".equals(callback))
            return prefix + "funkin.modding.events.ScriptEvent";
        if ("onBeatHit".equals(callback) || "onStepHit".equals(callback))
            return prefix + "funkin.modding.events.ScriptEvent.SongTimeScriptEvent";
        if ("onPause".equals(callback)) return prefix + "funkin.modding.events.ScriptEvent.PauseScriptEvent";
        if ("onCountdownStart".equals(callback) || "onCountdownStep".equals(callback) || "onCountdownEnd".equals(callback))
            return prefix + "funkin.modding.events.ScriptEvent.CountdownScriptEvent";
        if ("onSongEvent".equals(callback)) return prefix + "funkin.modding.events.ScriptEvent.SongEventScriptEvent";
        if ("onNoteHit".equals(callback)) return prefix + "funkin.modding.events.ScriptEvent.HitNoteScriptEvent";
        if ("onNoteMiss".equals(callback) || "onNoteIncoming".equals(callback))
            return prefix + "funkin.modding.events.ScriptEvent.NoteScriptEvent";
        if ("onNoteGhostMiss".equals(callback)) return prefix + "funkin.modding.events.ScriptEvent.GhostMissNoteScriptEvent";
        return originalParams;
    }

    private String wrapContextClass(String code) {
        if (context != Context.SONG && context != Context.NOTE_KIND && context != Context.SONG_EVENT) return code;
        String safe = sourceId.replaceAll("[^A-Za-z0-9_]", "_");
        if (safe.isEmpty()) safe = "Converted";
        if (Character.isDigit(safe.charAt(0))) safe = "_" + safe;
        String suffix = context == Context.SONG ? "SongScript"
                : (context == Context.NOTE_KIND ? "NoteKindScript" : "EventScript");
        String className = safe + suffix;
        String base = context == Context.SONG ? "Song"
                : (context == Context.NOTE_KIND ? "NoteKind" : "ScriptedSongEvent");
        String imports = context == Context.SONG ? "import funkin.play.song.Song;"
                : (context == Context.NOTE_KIND ? "import funkin.play.notes.notekind.NoteKind;"
                    : "import funkin.play.event.ScriptedSongEvent;\nimport funkin.data.song.SongData.SongEventData;");
        StringBuilder out = new StringBuilder(imports).append("\nclass ").append(className).append(" extends ").append(base).append(" {\n")
                .append("    public function new() {\n");
        String escapedId = sourceId.replace("\\", "\\\\").replace("'", "\\'");
        if (context == Context.NOTE_KIND) out.append("        super('").append(escapedId).append("', 'Converted from Psych Engine');\n");
        else out.append("        super('").append(escapedId).append("');\n");
        out.append("    }\n");
        if (code.trim().length() > 0) out.append(code).append("\n");
        out.append("}\n");
        return out.toString();
    }

    private String replaceIdentifier(String source, String identifier, String replacement) {
        StringBuilder out = new StringBuilder(source.length());
        boolean single = false, dbl = false, escape = false;
        for (int i = 0; i < source.length();) {
            char c = source.charAt(i);
            if (escape) { out.append(c); escape = false; i++; continue; }
            if ((single || dbl) && c == '\\') { out.append(c); escape = true; i++; continue; }
            if (c == '\'' && !dbl) single = !single;
            else if (c == '"' && !single) dbl = !dbl;
            if (!single && !dbl && source.startsWith(identifier, i)
                    && (i == 0 || !Character.isJavaIdentifierPart(source.charAt(i - 1)))
                    && (i + identifier.length() == source.length() || !Character.isJavaIdentifierPart(source.charAt(i + identifier.length())))) {
                out.append(replacement);
                i += identifier.length();
            } else { out.append(c); i++; }
        }
        return out.toString();
    }

    private static String withLine(int lineNumber, String line) {
        return lineNumber + "\t" + line;
    }

    private String stripLineInfo(String raw) {
        int marker = raw.indexOf('\t');
        if (marker < 0) return raw;
        try { currentLineNumber = Integer.parseInt(raw.substring(0, marker)); }
        catch (NumberFormatException ignored) { }
        return raw.substring(marker + 1);
    }

    private String convertLine(String line) {
        if (context == Context.SONG && "onUpdate".equals(currentSourceCallback)) {
            line = replaceIdentifier(line, "elapsed", "event.elapsed");
        }
        if (context == Context.NOTE_KIND || context == Context.SONG) {
            if ("goodNoteHit".equals(currentSourceCallback) || "noteMiss".equals(currentSourceCallback)) {
                line = replaceIdentifier(line, "direction", "event.note.direction");
                line = replaceIdentifier(line, "noteType", "event.note.kind");
                line = replaceIdentifier(line, "isSustainNote", "event.note.isHoldNote");
            } else if ("onSpawnNote".equals(currentSourceCallback)) {
                line = replaceIdentifier(line, "direction", "event.note.direction");
                line = replaceIdentifier(line, "noteType", "event.note.kind");
                line = replaceIdentifier(line, "isSustainNote", "event.note.isHoldNote");
                line = replaceIdentifier(line, "noteData", "event.note.direction");
            } else if ("noteMissPress".equals(currentSourceCallback)) {
                line = replaceIdentifier(line, "direction", "event.dir");
            }
        }
        if (line.startsWith("--")) return null;
        if ("return Function_Stop".equals(line)) return "event.cancel();";
        Matcher m;
        m = Pattern.compile("^startVideo\\(\\s*\"([^\"]+)\"\\s*\\)$").matcher(line);
        if (m.find()) return "VideoCutscene.play(Paths.videos('" + m.group(1) + "'));";
        m = Pattern.compile("^makeLuaSprite\\(\\s*\"([^\"]+)\"\\s*,\\s*\"([^\"]+)\"\\s*,\\s*([^,]+)\\s*,\\s*([^)]+)\\)$").matcher(line);
        if (m.find()) return "var " + m.group(1) + " = new FlxSprite(" + m.group(3).trim() + ", " + m.group(4).trim() + ").loadGraphic(Paths.image('" + m.group(2) + "'));";
        m = Pattern.compile("^makeAnimatedLuaSprite\\(\\s*\"([^\"]+)\"\\s*,\\s*\"([^\"]+)\"\\s*,\\s*([^,]+)\\s*,\\s*([^)]+)\\)$").matcher(line);
        if (m.find()) return "var " + m.group(1) + " = new FlxSprite(" + m.group(3).trim() + ", " + m.group(4).trim() + ").loadGraphic(Paths.image('" + m.group(2) + "'));";
        m = Pattern.compile("^addLuaSprite\\(\\s*\"([^\"]+)\"\\s*(?:,\\s*(true|false))?\\s*\\)$").matcher(line);
        if (m.find()) {
            if ("false".equals(m.group(2))) return "game.insert(0, " + m.group(1) + ");";
            return "game.add(" + m.group(1) + ");";
        }
        m = Pattern.compile("^removeLuaSprite\\(\\s*\"([^\"]+)\".*\\)$").matcher(line);
        if (m.find()) return "game.remove(" + m.group(1) + ");";
        m = Pattern.compile("^addAnimationByPrefix\\(\\s*\"([^\"]+)\"\\s*,\\s*\"([^\"]+)\"\\s*,\\s*\"([^\"]+)\"\\s*(?:,\\s*(\\d+))?\\s*(?:,\\s*(true|false))?\\s*\\)$").matcher(line);
        if (m.find()) {
            String fps = m.group(4) == null ? "24" : m.group(4);
            String loop = m.group(5) == null ? "false" : m.group(5);
            return m.group(1) + ".animation.addByPrefix('" + m.group(2) + "', '" + m.group(3) + "', " + fps + ", " + loop + ");";
        }
        m = Pattern.compile("^objectPlayAnimation\\(\\s*\"([^\"]+)\"\\s*,\\s*\"([^\"]+)\"\\s*(?:,\\s*(true|false))?\\s*\\)$").matcher(line);
        if (m.find()) return m.group(1) + ".animation.play('" + m.group(2) + "', " + (m.group(3) == null ? "false" : m.group(3)) + ");";
        m = Pattern.compile("^characterPlayAnim\\(\\s*([^,]+)\\s*,\\s*(.+)\\s*,\\s*(true|false)\\s*\\)$").matcher(line);
        if (m.find()) {
            String who = m.group(1).trim().replace("\"", "").replace("'", "");
            String getter = "gf".equals(who) ? "getGirlfriend()" : ("dad".equals(who) ? "getDad()" : "getBoyfriend()");
            return "PlayState.instance.currentStage." + getter + ".playAnimation(" + m.group(2).trim() + ");";
        }
        m = Pattern.compile("^scaleObject\\(\\s*\"([^\"]+)\"\\s*,\\s*([^,]+)\\s*,\\s*([^)]+)\\)$").matcher(line);
        if (m.find()) return m.group(1) + ".scale.set(" + m.group(2).trim() + ", " + m.group(3).trim() + ");";
        m = Pattern.compile("^setScrollFactor\\(\\s*\"([^\"]+)\"\\s*,\\s*([^,]+)\\s*,\\s*([^)]+)\\)$").matcher(line);
        if (m.find()) return m.group(1) + ".scrollFactor.set(" + m.group(2).trim() + ", " + m.group(3).trim() + ");";
        m = Pattern.compile("^setProperty\\(\\s*([\'\"])([^\'\"]+)\\1\\s*,\\s*(.+)\\)$").matcher(line);
        if (m.find()) return "game." + m.group(2) + " = " + toHsVal(m.group(3).trim()) + ";";
        m = Pattern.compile("^playSound\\(\\s*\"([^\"]+)\"\\s*(?:,\\s*([^)]+))?\\)$").matcher(line);
        if (m.find()) return "FlxG.sound.play(Paths.sound('" + m.group(1) + "')" + (m.group(2) == null ? "" : ", " + m.group(2).trim()) + ");";
        m = Pattern.compile("^playMusic\\(\\s*\"([^\"]+)\"\\s*(?:,\\s*([^)]+))?\\)$").matcher(line);
        if (m.find()) return "FlxG.sound.playMusic(Paths.music('" + m.group(1) + "')" + (m.group(2) == null ? "" : ", " + m.group(2).trim()) + ");";
        m = Pattern.compile("^debugPrint\\((.+)\\)$").matcher(line);
        if (m.find()) return "trace(" + m.group(1) + ");";
        m = Pattern.compile("^local\\s+(\\w+)\\s*=\\s*(.+)$").matcher(line);
        if (m.find()) return "var " + m.group(1) + " = " + toHsVal(m.group(2).trim()) + ";";
        m = Pattern.compile("^([\\w.]+)\\s*=\\s*(.+)$").matcher(line);
        if (m.find()) return m.group(1) + " = " + toHsVal(m.group(2).trim()) + ";";
        m = Pattern.compile("^return\\s+(.+)$").matcher(line);
        if (m.find()) return "return " + toHsVal(m.group(1).trim()) + ";";
        if ("return".equals(line)) return "return;";
        warnings.add("line " + currentLineNumber + ": " + line);
        return "// TODO: " + line;
    }

    private String toHsVal(String val) {
        if ("nil".equals(val)) return "null";
        val = val.replace(" .. ", " + ");
        val = val.replace("~=", "!=");
        val = val.replaceAll("\\band\\b", "&&");
        val = val.replaceAll("\\bor\\b", "||");
        val = val.replaceAll("\\bnot\\s+", "!");
        val = val.replaceAll("getProperty\\(\\s*[\'\"]([^\'\"]+)[\'\"]\\s*\\)", "game.$1");
        return val;
    }

    private String toHsCond(String cond) {
        return toHsVal(cond);
    }

    private static String pad(int n) {
        String s = "";
        for (int i = 0; i < n; i++) s += "    ";
        return s;
    }

    private static String join(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines.get(i));
        }
        return sb.toString();
    }
}
