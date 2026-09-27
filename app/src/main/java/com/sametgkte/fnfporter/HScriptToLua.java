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
 * V-Slice HScript / Haxe → Psych Engine Lua.
 * Port of GkTe Tool src/hscript_to_lua.py
 */
public class HScriptToLua {
    public final List<String> warnings = new ArrayList<String>();
    private final Set<String> variables = new HashSet<String>();
    private final Set<String> stringVariables = new HashSet<String>();
    private String className = "";
    private String extendsName = "";
    private String noteKindId = "";
    private String noteKindLabel = "";
    private int currentLineNumber = 1;
    private String currentSourceCallback = "";

    private static final Set<String> NO_PARAM = new HashSet<String>();
    private static final Map<String, String> CALLBACKS = new HashMap<String, String>();
    private static final Map<String, String> CALLBACK_PARAMS = new HashMap<String, String>();

    static {
        String[] np = {
                "onCreate", "onCreatePost", "onBeatHit", "onStepHit", "onSectionHit",
                "onSongStart", "onEndSong", "onStartCountdown", "onCountdownStarted",
                "onGameOver", "onPause", "onResume", "onDestroy"
        };
        for (String s : np) NO_PARAM.add(s);
        CALLBACKS.put("onCountdownStart", "onStartCountdown");
        CALLBACKS.put("onSongEnd", "onEndSong");
        CALLBACKS.put("onSongEvent", "onEvent");
        CALLBACKS.put("onNoteHit", "goodNoteHit");
        CALLBACKS.put("onNoteMiss", "noteMiss");
        CALLBACKS.put("onNoteGhostMiss", "noteMissPress");
        CALLBACKS.put("onNoteIncoming", "onSpawnNote");
        CALLBACKS.put("onCountdownStep", "onCountdownTick");
        CALLBACK_PARAMS.put("onUpdate", "elapsed");
        CALLBACK_PARAMS.put("onUpdatePost", "elapsed");
        CALLBACK_PARAMS.put("onCountdownTick", "counter");
        CALLBACK_PARAMS.put("onEvent", "name, value1, value2, strumTime");
        CALLBACK_PARAMS.put("noteMissPress", "direction");
        CALLBACK_PARAMS.put("onSpawnNote", "id, data, type, isSustainNote, strumTime");
        CALLBACK_PARAMS.put("goodNoteHit", "id, direction, noteType, isSustainNote");
        CALLBACK_PARAMS.put("noteMiss", "id, direction, noteType, isSustainNote");
        CALLBACK_PARAMS.put("opponentNoteHit", "id, direction, noteType, isSustainNote");
    }

    public String convert(String hscript) {
        warnings.clear();
        warnings.add("HScript is not Lua; translated callback names and API calls need runtime verification in Psych Engine.");
        variables.clear();
        stringVariables.clear();
        className = "";
        extendsName = "";
        noteKindId = "";
        noteKindLabel = "";
        currentSourceCallback = "";
        String code = preprocess(hscript);
        String[] lines = code.split("\n", -1);
        Map<String, Func> functions = new LinkedHashMap<String, Func>();
        List<String> globals = new ArrayList<String>();
        parseStructure(lines, functions, globals);
        return buildOutput(functions, globals);
    }

    private static String preprocess(String code) {
        StringBuilder clean = new StringBuilder(code.length());
        boolean single = false, dbl = false, escape = false, blockComment = false;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            char next = i + 1 < code.length() ? code.charAt(i + 1) : '\0';
            if (blockComment) {
                if (c == '*' && next == '/') { blockComment = false; i++; }
                else if (c == '\n') clean.append('\n');
                continue;
            }
            if (escape) { clean.append(c); escape = false; continue; }
            if ((single || dbl) && c == '\\') { clean.append(c); escape = true; continue; }
            if (c == '\'' && !dbl) single = !single;
            else if (c == '"' && !single) dbl = !dbl;
            if (!single && !dbl && c == '/' && next == '/') {
                while (i < code.length() && code.charAt(i) != '\n') i++;
                if (i < code.length()) clean.append('\n');
                continue;
            }
            if (!single && !dbl && c == '/' && next == '*') { blockComment = true; i++; continue; }
            clean.append(c);
        }
        String result = Pattern.compile(";\\s*$", Pattern.MULTILINE).matcher(clean.toString()).replaceAll("");
        return result.replaceAll("cast\\(\\s*(\\w+)\\s*,\\s*\\w+\\s*\\)", "$1");
    }

    private static class Func {
        String params;
        List<String> body = new ArrayList<String>();
    }

    private void parseStructure(String[] lines, Map<String, Func> functions, List<String> globalLines) {
        int i = 0, braceDepth = 0;
        boolean inClass = false, inFunction = false, inBlockComment = false;
        String funcName = "", funcParams = "";
        List<String> funcBody = new ArrayList<String>();
        Pattern classPat = Pattern.compile("(?:public\\s+)?class\\s+(\\w+)(?:\\s+extends\\s+(\\w+))?");
        Pattern funcPat = Pattern.compile("(?:(?:public|private|override|static|inline)\\s+)*function\\s+(\\w+)\\s*\\(([^)]*)\\)(?:\\s*:\\s*[\\w.<>]+)?\\s*");

        while (i < lines.length) {
            String line = lines[i];
            currentLineNumber = i + 1;
            String stripped = line.trim();
            if (stripped.length() == 0) {
                if (inFunction) funcBody.add("");
                i++;
                continue;
            }
            if (inBlockComment) {
                if (stripped.contains("*/")) inBlockComment = false;
                i++;
                continue;
            }
            if (stripped.startsWith("/*") || stripped.startsWith("/**")) {
                if (!stripped.contains("*/")) inBlockComment = true;
                i++;
                continue;
            }
            if (stripped.startsWith("import ") || stripped.startsWith("package ")) {
                i++;
                continue;
            }
            if (stripped.startsWith("//")) {
                i++;
                continue;
            }
            Matcher cm = classPat.matcher(stripped);
            if (cm.find()) {
                warnings.add("line " + (i + 1) + ": HScript class '" + cm.group(1) + "' is flattened to Psych Lua callbacks; inheritance, constructor state, and scripted-class lifecycle are not equivalent.");
                className = cm.group(1);
                if (cm.groupCount() >= 2 && cm.group(2) != null) extendsName = cm.group(2);
                inClass = true;
                if (stripped.contains("{")) braceDepth++;
                i++;
                continue;
            }
            Matcher fm = funcPat.matcher(stripped);
            if (fm.find()) {
                if (inFunction && funcName.length() > 0) {
                    Func f = new Func();
                    f.params = funcParams;
                    f.body = funcBody;
                    functions.put(funcName, f);
                }
                String rawName = fm.group(1);
                currentSourceCallback = rawName;
                funcName = "new".equals(rawName) ? "__constructor__" : mapCallback(rawName);
                if ((rawName.startsWith("on") || "goodNoteHit".equals(rawName) || "noteMiss".equals(rawName)
                        || "opponentNoteHit".equals(rawName)) && !isKnownPsychCallback(funcName)) {
                    warnings.add("line " + (i + 1) + ": callback '" + rawName + "' has no known Psych Lua equivalent; generated function may never be called.");
                }
                funcParams = cleanParams(fm.group(2));
                funcBody = new ArrayList<String>();
                inFunction = true;
                int inlineOpen = stripped.indexOf('{');
                int inlineClose = stripped.lastIndexOf('}');
                if (inlineOpen >= 0 && inlineClose > inlineOpen) {
                    String inlineBody = stripped.substring(inlineOpen + 1, inlineClose).trim();
                    if (inlineBody.indexOf('{') >= 0 || inlineBody.indexOf('}') >= 0) {
                        warnings.add("line " + (i + 1) + ": nested inline method body requires manual review.");
                    }
                    for (String statement : splitInlineStatements(inlineBody)) {
                        String convertedStatement = convertLine(statement);
                        if (convertedStatement != null) funcBody.add(convertedStatement);
                    }
                    Func f = new Func();
                    f.params = funcParams;
                    f.body = funcBody;
                    functions.put(funcName, f);
                    inFunction = false;
                    currentSourceCallback = "";
                    funcName = "";
                    funcParams = "";
                    funcBody = new ArrayList<String>();
                } else if (inlineOpen >= 0) {
                    braceDepth++;
                }
                i++;
                continue;
            }
            if ("{".equals(stripped)) {
                braceDepth++;
                i++;
                continue;
            }
            if ("}".equals(stripped)) {
                int minDepth = inClass ? 1 : 0;
                braceDepth--;
                if (inFunction) {
                    if (braceDepth <= minDepth) {
                        if (funcName.length() > 0) {
                            Func f = new Func();
                            f.params = funcParams;
                            f.body = funcBody;
                            functions.put(funcName, f);
                        }
                        inFunction = false;
                        currentSourceCallback = "";
                        funcName = "";
                        funcParams = "";
                        funcBody = new ArrayList<String>();
                    } else {
                        funcBody.add("end");
                    }
                } else if (inClass && braceDepth == 0) {
                    inClass = false;
                }
                i++;
                continue;
            }
            int open = countChar(stripped, '{');
            int close = countChar(stripped, '}');
            String converted = convertLine(stripped);
            if (converted != null) {
                if (inFunction) funcBody.add(converted);
                else globalLines.add(converted);
            }
            if (!"{".equals(stripped)) braceDepth += open;
            if (!"}".equals(stripped)) braceDepth -= close;
            i++;
        }
        if (inFunction && funcName.length() > 0) {
            Func f = new Func();
            f.params = funcParams;
            f.body = funcBody;
            functions.put(funcName, f);
        }
    }

    private String buildOutput(Map<String, Func> functions, List<String> globalLines) {
        List<String> result = new ArrayList<String>();
        boolean noteKind = "NoteKind".equals(extendsName) || "ScriptedNoteKind".equals(extendsName) || noteKindId.length() > 0;
        if (noteKind && functions.containsKey("goodNoteHit")) {
            Func hit = functions.get("goodNoteHit");
            hit.params = "id, direction, noteType, isSustainNote";
            List<String> prefixed = new ArrayList<String>();
            if (noteKindId.length() > 0) {
                prefixed.add("if noteType ~= \"" + noteKindId + "\" then return end");
            }
            prefixed.add("local dirs = {\"LEFT\", \"DOWN\", \"UP\", \"RIGHT\"}");
            prefixed.addAll(hit.body);
            hit.body = prefixed;
        }
        Set<String> initialized = new HashSet<String>();
        if (functions.containsKey("__constructor__")) {
            Func c = functions.remove("__constructor__");
            boolean any = false;
            for (String line : c.body) {
                if (line.trim().length() > 0) {
                    result.add(line);
                    any = true;
                    Matcher m = Pattern.compile("^(\\w+)\\s*=").matcher(line.trim());
                    if (m.find()) initialized.add(m.group(1));
                }
            }
            if (any) result.add("");
        }
        boolean hasGlobals = false;
        for (String line : globalLines) {
            if (line.trim().length() == 0) continue;
            Matcher m = Pattern.compile("^local\\s+(\\w+)\\s*=\\s*nil$").matcher(line.trim());
            if (m.find() && initialized.contains(m.group(1))) continue;
            result.add(line);
            hasGlobals = true;
        }
        if (hasGlobals) result.add("");

        for (Map.Entry<String, Func> e : functions.entrySet()) {
            String name = e.getKey();
            String params = e.getValue().params;
            if (CALLBACK_PARAMS.containsKey(name)) {
                params = CALLBACK_PARAMS.get(name);
            } else if (NO_PARAM.contains(name)) {
                params = "";
            }
            String outName = "startVideo".equals(name) ? "playIntroVideo" : name;
            if ("__constructor__".equals(name)) continue;
            result.add("function " + outName + "(" + params + ")");
            List<String> body = optimizeBody(e.getValue().body);
            int indent = 1;
            for (String line : body) {
                String sl = line.trim();
                if (sl.length() == 0) {
                    result.add("");
                    continue;
                }
                if ("end".equals(sl) || "else".equals(sl) || sl.startsWith("elseif ")) {
                    indent = Math.max(1, indent - 1);
                }
                String pad = "";
                for (int k = 0; k < indent; k++) pad += "    ";
                result.add(pad + sl);
                if (sl.endsWith(" then") || sl.endsWith(" do") || "else".equals(sl)) indent++;
            }
            result.add("end");
            result.add("");
        }
        return joinClean(result);
    }

    private List<String> optimizeBody(List<String> body) {
        List<String> result = new ArrayList<String>();
        int i = 0;
        Pattern call = Pattern.compile("^\\w+\\(.*\\)$");
        while (i < body.size()) {
            String line = body.get(i).trim();
            if ("startVideo()".equals(line)) {
                result.add("playIntroVideo()");
                i++;
                continue;
            }
            if ("return Function_Stop".equals(line)) {
                int j = i + 1;
                while (j < body.size() && body.get(j).trim().length() == 0) j++;
                if (j < body.size()) {
                    String next = body.get(j).trim();
                    if (call.matcher(next).matches()) {
                        result.add("startVideo()".equals(next) ? "playIntroVideo()" : next);
                        result.add("return Function_Stop");
                        i = j + 1;
                        continue;
                    }
                }
            }
            result.add(body.get(i));
            i++;
        }
        return result;
    }

    private static boolean isKnownPsychCallback(String name) {
        return NO_PARAM.contains(name) || CALLBACK_PARAMS.containsKey(name) || "onUpdate".equals(name) || "onUpdatePost".equals(name)
                || "onCountdownTick".equals(name) || "onEvent".equals(name)
                || "onKeyPress".equals(name) || "onKeyRelease".equals(name)
                || "onGhostTap".equals(name) || "onMoveCamera".equals(name)
                || "goodNoteHit".equals(name) || "noteMiss".equals(name) || "opponentNoteHit".equals(name);
    }

    private static String mapCallback(String name) {
        return CALLBACKS.containsKey(name) ? CALLBACKS.get(name) : name;
    }

    private static String cleanParams(String raw) {
        if (raw == null || raw.trim().length() == 0) return "";
        List<String> clean = new ArrayList<String>();
        String[] parts = raw.split(",");
        for (String p : parts) {
            p = p.trim();
            if (p.contains(":")) p = p.split(":")[0].trim();
            if (p.length() > 0 && !"Void".equals(p) && !"void".equals(p)) clean.add(p);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < clean.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(clean.get(i));
        }
        return sb.toString();
    }

    private String rewriteCallbackFields(String line) {
        if ("onUpdate".equals(currentSourceCallback) || "onUpdatePost".equals(currentSourceCallback)) {
            line = line.replace("event.elapsed", "elapsed");
        } else if ("onEvent".equals(currentSourceCallback) || "onSongEvent".equals(currentSourceCallback)) {
            line = line.replace("event.eventData.eventKind", "name")
                    .replace("event.eventData.time", "strumTime")
                    .replace("event.eventData.value", "value1");
        } else if ("onNoteHit".equals(currentSourceCallback) || "onNoteMiss".equals(currentSourceCallback)
                || "onNoteIncoming".equals(currentSourceCallback)) {
            line = line.replace("event.note.noteData.kind", "noteType")
                    .replace("event.note.noteData.data", "direction")
                    .replace("event.note.strumTime", "strumTime");
        } else if ("onNoteGhostMiss".equals(currentSourceCallback)) {
            line = line.replace("event.dir", "direction");
        } else if ("onCountdownStep".equals(currentSourceCallback)) {
            line = line.replace("event.step", "counter");
        }
        return line;
    }

    private String convertLine(String line) {
        String original = line;
        line = rewriteCallbackFields(line.trim());
        if (line.endsWith(";")) line = line.substring(0, line.length() - 1).trim();
        String stripped = line;

        if (line.matches("super\\.\\w+\\(.*")) return null;
        Matcher superM = Pattern.compile("super\\s*\\(\\s*[\"']([^\"']+)[\"']\\s*(?:,\\s*[\"']([^\"']*)[\"'])?").matcher(line);
        if (superM.find()) {
            if (noteKindId.length() == 0) {
                noteKindId = superM.group(1);
                if (superM.group(2) != null) noteKindLabel = superM.group(2);
            }
            return null;
        }
        if (line.matches("super\\s*\\(.*")) return null;
        if (line.contains("event.cancel()")) return "return Function_Stop";
        if (Pattern.compile("\\\\bevent\\\\.(?!cancel\\\\b)").matcher(line).find()) {
            warnings.add("line " + currentLineNumber + ": V-Slice ScriptEvent object fields do not map directly to Psych Lua callback arguments: " + original);
        }

        Matcher m;
        // PlayState / stage character lookups
        if (line.contains("PlayState.instance") && line.contains("== null") && line.contains("return")) {
            return null;
        }
        m = Pattern.compile("(?:var\\s+)?(\\w+)(?:\\s*:\\s*\\w+)?\\s*=\\s*PlayState\\.instance\\.currentStage\\.getGirlfriend\\(\\)").matcher(line);
        if (m.find()) {
            variables.add(m.group(1));
            return "local " + m.group(1) + " = \"gf\"";
        }
        m = Pattern.compile("(?:var\\s+)?(\\w+)(?:\\s*:\\s*\\w+)?\\s*=\\s*PlayState\\.instance\\.currentStage\\.getBoyfriend\\(\\)").matcher(line);
        if (m.find()) {
            variables.add(m.group(1));
            return "local " + m.group(1) + " = \"boyfriend\"";
        }
        m = Pattern.compile("(?:var\\s+)?(\\w+)(?:\\s*:\\s*\\w+)?\\s*=\\s*PlayState\\.instance\\.currentStage\\.get(?:Dad|Opponent)\\(\\)").matcher(line);
        if (m.find()) {
            variables.add(m.group(1));
            return "local " + m.group(1) + " = \"dad\"";
        }
        m = Pattern.compile("(\\w+)\\.playAnimation\\(\\s*(.+)\\s*\\)").matcher(line);
        if (m.find()) {
            return "characterPlayAnim(" + m.group(1) + ", " + convertValue(m.group(2).trim()) + ", true)";
        }
        m = Pattern.compile("(?:var\\s+)?(\\w+)(?:\\s*:\\s*\\w+)?\\s*=\\s*NoteKindsHandler\\.DIRECTION_NAMES\\s*\\[(.+)\\]").matcher(line);
        if (m.find()) {
            variables.add(m.group(1));
            return "local " + m.group(1) + " = dirs[(" + convertValue(m.group(2).trim()) + ") + 1]";
        }
        if (line.contains("NoteKindsHandler.HOLD_SUFFIX")) {
            line = line.replace("NoteKindsHandler.HOLD_SUFFIX", "\"-hold\"");
        }
        if (line.contains(".animation.getNameList().contains(")) {
            return null;
        }

        m = Pattern.compile("VideoCutscene\\.play\\(\\s*Paths\\.videos\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)\\s*\\)").matcher(line);
        if (m.find()) return "startVideo(\"" + m.group(1) + "\")";

        m = Pattern.compile("(?:var\\s+)?(\\w+)\\s*=\\s*new\\s+FlxSprite\\(\\s*([^,]*)\\s*,\\s*([^)]*)\\s*\\)\\.loadGraphic\\(\\s*Paths\\.image\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)\\s*\\)").matcher(line);
        if (m.find()) {
            variables.add(m.group(1));
            return "makeLuaSprite(\"" + m.group(1) + "\", \"" + m.group(4) + "\", " + m.group(2).trim() + ", " + m.group(3).trim() + ")";
        }
        m = Pattern.compile("(?:var\\s+)?(\\w+)\\s*=\\s*new\\s+FlxSprite\\(\\s*([^,]*)\\s*,\\s*([^)]*)\\s*\\)").matcher(line);
        if (m.find()) {
            variables.add(m.group(1));
            return "makeLuaSprite(\"" + m.group(1) + "\", \"\", " + m.group(2).trim() + ", " + m.group(3).trim() + ")";
        }
        m = Pattern.compile("game\\.add\\(\\s*(\\w+)\\s*\\)").matcher(line);
        if (m.find()) return "addLuaSprite(\"" + m.group(1) + "\", true)";
        m = Pattern.compile("game\\.insert\\(\\s*.*?,\\s*(\\w+)\\s*\\)").matcher(line);
        if (m.find()) return "addLuaSprite(\"" + m.group(1) + "\", false)";
        m = Pattern.compile("game\\.remove\\(\\s*(\\w+)\\s*\\)").matcher(line);
        if (m.find()) return "removeLuaSprite(\"" + m.group(1) + "\", true)";

        m = Pattern.compile("(\\w+)\\.animation\\.addByPrefix\\(\\s*['\"]([^'\"]+)['\"]\\s*,\\s*['\"]([^'\"]+)['\"]\\s*(?:,\\s*(\\d+))?\\s*(?:,\\s*(true|false))?\\s*\\)").matcher(line);
        if (m.find()) {
            String fps = m.group(4) == null ? "24" : m.group(4);
            String loop = m.group(5) == null ? "false" : m.group(5);
            return "addAnimationByPrefix(\"" + m.group(1) + "\", \"" + m.group(2) + "\", \"" + m.group(3) + "\", " + fps + ", " + loop + ")";
        }
        m = Pattern.compile("(\\w+)\\.animation\\.play\\(\\s*['\"]([^'\"]+)['\"]\\s*(?:,\\s*(true|false))?\\s*\\)").matcher(line);
        if (m.find()) {
            String forced = m.group(3) == null ? "false" : m.group(3);
            return "objectPlayAnimation(\"" + m.group(1) + "\", \"" + m.group(2) + "\", " + forced + ")";
        }
        m = Pattern.compile("(\\w+)\\.scale\\.set\\(\\s*([^,]+)\\s*,\\s*([^)]+)\\s*\\)").matcher(line);
        if (m.find()) return "scaleObject(\"" + m.group(1) + "\", " + m.group(2).trim() + ", " + m.group(3).trim() + ")";
        if (line.matches("\\w+\\.updateHitbox\\(\\)")) return null;
        m = Pattern.compile("(\\w+)\\.scrollFactor\\.set\\(\\s*([^,]+)\\s*,\\s*([^)]+)\\s*\\)").matcher(line);
        if (m.find()) return "setScrollFactor(\"" + m.group(1) + "\", " + m.group(2).trim() + ", " + m.group(3).trim() + ")";

        m = Pattern.compile("game\\.(\\w+(?:\\.\\w+)*)\\s*=\\s*(.+)").matcher(line);
        if (m.find()) return "setProperty(\"" + m.group(1) + "\", " + convertValue(m.group(2).trim()) + ")";
        m = Pattern.compile("(\\w+)\\.cameras\\s*=\\s*\\[\\s*game\\.(\\w+)\\s*\\]").matcher(line);
        if (m.find()) return "setObjectCamera(\"" + m.group(1) + "\", \"" + m.group(2) + "\")";

        m = Pattern.compile("FlxG\\.sound\\.play\\(\\s*Paths\\.sound\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)\\s*(?:,\\s*([^)]+))?\\s*\\)").matcher(line);
        if (m.find()) return "playSound(\"" + m.group(1) + "\", " + (m.group(2) == null ? "1" : m.group(2).trim()) + ")";
        m = Pattern.compile("FlxG\\.sound\\.playMusic\\(\\s*Paths\\.music\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)\\s*(?:,\\s*([^)]+))?\\s*\\)").matcher(line);
        if (m.find()) return "playMusic(\"" + m.group(1) + "\", " + (m.group(2) == null ? "1" : m.group(2).trim()) + ")";

        m = Pattern.compile("trace\\(\\s*(.+)\\s*\\)").matcher(line);
        if (m.find()) {
            String c = m.group(1).trim().replace("'", "\"").replace("`", "'");
            return "debugPrint(" + c + ")";
        }
        m = Pattern.compile("^(\\w+)\\(\\s*\\)$").matcher(line);
        if (m.find()) {
            String fn = m.group(1);
            return "startVideo".equals(fn) ? "playIntroVideo()" : fn + "()";
        }
        m = Pattern.compile("^(\\w+)\\(\\s*(.+)\\s*\\)$").matcher(line);
        if (m.find() && !"super".equals(m.group(1))) {
            String fn = m.group(1);
            String args = convertValue(m.group(2).trim());
            return "startVideo".equals(fn) ? "playIntroVideo(" + args + ")" : fn + "(" + args + ")";
        }

        Matcher typedString = Pattern.compile("(?:var|local)\\s+(\\w+)\\s*:\\s*String\\b").matcher(line);
        if (typedString.find()) stringVariables.add(typedString.group(1));
        m = Pattern.compile("(?:var|local)\\s+(\\w+)(?:\\s*:\\s*[\\w<>]+)?\\s*=\\s*(.+)").matcher(line);
        if (m.find()) {
            variables.add(m.group(1));
            return "local " + m.group(1) + " = " + convertValue(m.group(2).trim());
        }
        m = Pattern.compile("(?:var|local)\\s+(\\w+)(?:\\s*:\\s*[\\w<>]+)?\\s*$").matcher(line);
        if (m.find()) {
            variables.add(m.group(1));
            return "local " + m.group(1) + " = nil";
        }

        m = Pattern.compile("(\\w+)\\s*\\+=\\s*(.+)").matcher(line);
        if (m.find()) {
            String vn = m.group(1);
            String rawRhs = m.group(2).trim();
            String rhs = convertValue(rawRhs);
            boolean stringConcat = stringVariables.contains(vn) || rawRhs.startsWith("\"") || rawRhs.startsWith("'");
            if (!stringConcat) warnings.add("line " + currentLineNumber + ": '+=' numeric/string meaning inferred as arithmetic; verify: " + original);
            return vn + " = " + vn + (stringConcat ? " .. " : " + ") + rhs;
        }
        m = Pattern.compile("if\\s*\\((.+)\\)\\s+return\\s*;?$").matcher(line);
        if (m.find()) return "if " + convertCondition(m.group(1)) + " then return end";
        m = Pattern.compile("if\\s*\\((.+)\\)\\s+(.+)$").matcher(line);
        if (m.find() && !m.group(2).trim().startsWith("{")) {
            String stmt = convertLine(m.group(2).trim());
            if (stmt == null) return "if " + convertCondition(m.group(1)) + " then return end";
            return "if " + convertCondition(m.group(1)) + " then " + stmt + " end";
        }
        m = Pattern.compile("if\\s*\\(\\s*(.+)\\s*\\)\\s*\\{?$").matcher(line);
        if (m.find()) return "if " + convertCondition(m.group(1)) + " then";
        m = Pattern.compile("\\}\\s*else\\s+if\\s*\\(\\s*(.+)\\s*\\)\\s*\\{?").matcher(line);
        if (m.find()) return "elseif " + convertCondition(m.group(1)) + " then";
        if ("} else {".equals(stripped) || "} else".equals(stripped)) return "else";
        if ("}".equals(stripped)) return "end";

        m = Pattern.compile("^([\\w.]+)\\s*=\\s*(.+)$").matcher(line);
        if (m.find()) {
            String vn = m.group(1);
            if (!"end then do else return function if elseif for while repeat".contains(vn)) {
                return vn + " = " + convertValue(m.group(2).trim());
            }
        }
        m = Pattern.compile("return\\s+(.+)").matcher(line);
        if (m.find()) return "return " + convertValue(m.group(1).trim());
        if ("return".equals(stripped)) return "return";

        m = Pattern.compile("for\\s*\\(\\s*(\\w+)\\s+in\\s+(\\d+)\\.\\.\\.(\\d+)\\s*\\)\\s*\\{?").matcher(line);
        if (m.find()) {
            variables.add(m.group(1));
            int end = Integer.parseInt(m.group(3)) - 1;
            return "for " + m.group(1) + " = " + m.group(2) + ", " + end + " do";
        }

        warnings.add("line " + currentLineNumber + ": " + original);
        return null;
    }

    private String convertValue(String val) {
        val = val.trim();
        if (val.endsWith(";")) val = val.substring(0, val.length() - 1).trim();
        if ("null".equals(val)) return "nil";
        if ("true".equals(val) || "false".equals(val)) return val;
        if (val.startsWith("'") && val.endsWith("'") && val.length() >= 2) {
            return "\"" + val.substring(1, val.length() - 1) + "\"";
        }
        if (val.contains("\"") && val.contains("+")) val = val.replace(" + ", " .. ");
        val = val.replace("!=", "~=");
        val = val.replaceAll("&&", "and");
        val = val.replaceAll("\\|\\|", "or");
        val = val.replaceAll("!\\s*(\\w)", "not $1");
        val = Pattern.compile("game\\.(\\w+(?:\\.\\w+)*)").matcher(val).replaceAll("getProperty(\"$1\")");
        val = val.replaceAll("Paths\\.image\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)", "\"$1\"");
        val = val.replaceAll("Paths\\.sound\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)", "\"$1\"");
        val = val.replaceAll("Paths\\.music\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)", "\"$1\"");
        val = val.replaceAll("Paths\\.videos\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)", "\"$1\"");
        return val;
    }

    private String convertCondition(String cond) {
        cond = convertValue(cond);
        return cond.replaceAll("^!\\s*(\\w)", "not $1");
    }

    private static String joinClean(List<String> lines) {
        List<String> out = new ArrayList<String>();
        boolean prevEmpty = false;
        for (String line : lines) {
            boolean empty = line.trim().length() == 0;
            if (empty) {
                if (!prevEmpty) out.add("");
                prevEmpty = true;
            } else {
                out.add(line);
                prevEmpty = false;
            }
        }
        while (out.size() > 0 && out.get(0).trim().length() == 0) out.remove(0);
        while (out.size() > 0 && out.get(out.size() - 1).trim().length() == 0) out.remove(out.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < out.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(out.get(i));
        }
        return sb.toString();
    }

    private static List<String> splitInlineStatements(String body) {
        List<String> out = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean single = false, dbl = false, escape = false;
        int parens = 0, brackets = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (escape) { current.append(c); escape = false; continue; }
            if ((single || dbl) && c == '\\') { current.append(c); escape = true; continue; }
            if (c == '\'' && !dbl) single = !single;
            else if (c == '"' && !single) dbl = !dbl;
            else if (!single && !dbl) {
                if (c == '(') parens++;
                else if (c == ')') parens--;
                else if (c == '[') brackets++;
                else if (c == ']') brackets--;
                else if (c == ';' && parens == 0 && brackets == 0) {
                    if (current.length() > 0) out.add(current.toString().trim());
                    current.setLength(0);
                    continue;
                }
            }
            current.append(c);
        }
        if (current.length() > 0 && current.toString().trim().length() > 0) out.add(current.toString().trim());
        return out;
    }

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }
}
