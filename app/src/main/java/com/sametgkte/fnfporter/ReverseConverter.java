package com.sametgkte.fnfporter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * V-Slice / Base Game (Polymod) → Psych Engine.
 * Logic from GkTe Tool (Uktimate FNF Tool) reverse porter.
 */
public class ReverseConverter {

    public static void convert(String vsliceFolder, String resultFolder, JSONObject options) throws Exception {
        long start = System.currentTimeMillis();
        AppLog.banner("V-SLICE -> PSYCH STARTED");
        File modRoot = new File(vsliceFolder);
        String modName = modRoot.getName();
        File outRoot = new File(resultFolder, modName);
        FileOps.folderMake(outRoot.getAbsolutePath());
        AppLog.info("From " + vsliceFolder);
        AppLog.info("To   " + outRoot.getAbsolutePath());

        if (options.optBoolean("charts", true)) convertCharts(modRoot, outRoot);
        if (options.optBoolean("metadata", true)) convertMeta(modRoot, outRoot, modName);
        if (options.optBoolean("characters", true)) convertCharacters(modRoot, outRoot);
        if (options.optBoolean("char_assets", true)) copyCharAssets(modRoot, outRoot);
        if (options.optBoolean("icons", true)) copyIcons(modRoot, outRoot);
        if (options.optBoolean("songs", true)) copySongs(modRoot, outRoot);
        if (options.optBoolean("stages", true)) convertStages(modRoot, outRoot);
        if (options.optBoolean("images", true)) copyImages(modRoot, outRoot);
        if (options.optBoolean("weeks", true)) convertWeeks(modRoot, outRoot);
        if (options.optBoolean("sounds", true)) copySounds(modRoot, outRoot);
        if (options.optBoolean("scripts", true)) copyScripts(modRoot, outRoot);

        AppLog.banner("CONVERSION COMPLETED");
        AppLog.info("Took " + ((System.currentTimeMillis() - start) / 1000.0) + "s");
    }

    private static void convertCharts(File modRoot, File outRoot) {
        AppLog.info("Converting charts...");
        File songsDir = new File(modRoot, "data/songs");
        if (!songsDir.isDirectory()) {
            AppLog.warn("data/songs not found");
            return;
        }
        for (File songFolder : FileOps.listAll(songsDir)) {
            if (!songFolder.isDirectory()) continue;
            List<File> chartFiles = new ArrayList<File>();
            File metaFile = null;
            for (File f : FileOps.listAll(songFolder)) {
                String n = f.getName().toLowerCase(Locale.US);
                String chartPrefix = songFolder.getName().toLowerCase(Locale.US) + "-chart";
                if (f.isFile() && n.endsWith(".json") && n.startsWith(chartPrefix)
                        && (n.length() == chartPrefix.length() + 5 || n.startsWith(chartPrefix + "-"))) chartFiles.add(f);
                else if (f.isFile() && n.endsWith("-metadata.json")) {
                    // Prefer the shared song metadata over difficulty-specific metadata.
                    if (metaFile == null || n.equals(songFolder.getName().toLowerCase(Locale.US) + "-metadata.json")) metaFile = f;
                }
            }
            Collections.sort(chartFiles, new Comparator<File>() {
                @Override public int compare(File a, File b) { return a.getName().compareToIgnoreCase(b.getName()); }
            });
            if (chartFiles.isEmpty()) {
                AppLog.warn(songFolder.getName() + ": no *-chart.json files found");
                continue;
            }
            if (metaFile == null) AppLog.warn(songFolder.getName() + ": metadata missing; using safe defaults");
            File destDir = new File(outRoot, "data/" + songFolder.getName());
            FileOps.folderMake(destDir.getAbsolutePath());
            for (File chartFile : chartFiles) {
                try {
                    JSONObject sourceChart = new JSONObject(FileOps.readText(chartFile));
                    String chartName = chartFile.getName();
                    String chartPrefix = songFolder.getName() + "-chart";
                    String variation = "default";
                    if (chartName.length() > chartPrefix.length() + 5) {
                        String between = chartName.substring(chartPrefix.length(), chartName.length() - ".json".length());
                        if (between.startsWith("-")) variation = between.substring(1);
                    }
                    File variationMeta = new File(songFolder, songFolder.getName() + "-metadata-" + variation + ".json");
                    File selectedMeta = variationMeta.isFile() ? variationMeta : metaFile;
                    JSONObject meta = selectedMeta == null ? null : new JSONObject(FileOps.readText(selectedMeta));
                    JSONObject payload = sourceChart.optJSONObject("chart");
                    if (payload == null) payload = sourceChart;
                    JSONObject notesByDifficulty = payload.optJSONObject("notes");
                    List<String> difficulties = new ArrayList<String>();
                    if (notesByDifficulty != null) {
                        Iterator<String> keys = notesByDifficulty.keys();
                        while (keys.hasNext()) difficulties.add(keys.next());
                        Collections.sort(difficulties, new Comparator<String>() {
                            @Override public int compare(String a, String b) { return a.compareToIgnoreCase(b); }
                        });
                    } else {
                        difficulties.add("normal");
                    }
                    if (difficulties.isEmpty()) difficulties.add("normal");
                    for (String difficulty : difficulties) {
                        JSONObject oneDiff = new JSONObject(sourceChart.toString());
                        JSONObject diffPayload = oneDiff.optJSONObject("chart");
                        if (diffPayload == null) diffPayload = oneDiff;
                        JSONObject notes = diffPayload.optJSONObject("notes");
                        if (notes != null) {
                            JSONArray selected = notes.optJSONArray(difficulty);
                            if (selected == null) continue;
                            diffPayload.put("notes", selected);
                            JSONObject speeds = diffPayload.optJSONObject("scrollSpeed");
                            if (speeds != null) diffPayload.put("scrollSpeed", speeds.optDouble(difficulty, 1.0));
                        }
                        JSONObject psych = chartToPsych(diffPayload, meta);
                        String diffSuffix = "normal".equalsIgnoreCase(difficulty) || "default".equalsIgnoreCase(difficulty)
                                ? "" : "-" + difficulty;
                        // Psych has no V-Slice variation concept; keep variation identity in the filename
                        // rather than overwriting a same-named chart from another variation.
                        String variationSuffix = "default".equalsIgnoreCase(variation) ? "" : "-" + variation;
                        String outputName = songFolder.getName() + variationSuffix + diffSuffix + ".json";
                        FileOps.writeText(new File(destDir, outputName), psych.toString(2));
                        AppLog.info("  OK " + chartFile.getName() + " [" + difficulty + "] -> " + outputName);
                    }
                } catch (Exception e) {
                    AppLog.error("  Failed chart " + chartFile.getName(), e);
                }
            }
        }
    }

    private static void convertMeta(File modRoot, File outRoot, String modName) {
        AppLog.info("Converting metadata...");
        File polymod = new File(modRoot, "_polymod_meta.json");
        try {
            JSONObject pack = new JSONObject();
            JSONObject sourceMeta = null;
            if (polymod.exists()) {
                sourceMeta = new JSONObject(FileOps.readText(polymod));
                pack.put("name", sourceMeta.optString("title", modName));
                pack.put("description", sourceMeta.optString("description", "Converted by FNF Porter For Mobile"));
            } else {
                pack.put("name", modName);
                pack.put("description", "Converted by FNF Porter For Mobile");
                AppLog.warn("default pack.json");
            }
            pack.put("runsGlobally", false);
            FileOps.writeText(new File(outRoot, "pack.json"), pack.toString(4));
            if (sourceMeta != null) {
                // Retain Polymod compatibility/attribution metadata alongside Psych's pack.json.
                // Keep unknown/future Polymod keys too; metadata consumers can ignore fields
                // they do not understand, while a converter should avoid destroying them.
                FileOps.writeText(new File(outRoot, "_polymod_meta.json"), sourceMeta.toString(4));
            }
            AppLog.info("  pack.json");
        } catch (Exception e) {
            AppLog.error("pack.json failed", e);
        }
        File icon = new File(modRoot, "_polymod_icon.png");
        if (icon.exists()) {
            FileOps.fileCopy(icon.getAbsolutePath(), new File(outRoot, "pack.png").getAbsolutePath());
        }
    }

    private static void convertCharacters(File modRoot, File outRoot) {
        AppLog.info("Converting characters...");
        File src = new File(modRoot, "data/characters");
        File dst = new File(outRoot, "characters");
        if (!src.isDirectory()) return;
        FileOps.folderMake(dst.getAbsolutePath());
        for (File f : FileOps.listFiles(src, ".json")) {
            try {
                JSONObject psych = charToPsych(new JSONObject(FileOps.readText(f)));
                FileOps.writeText(new File(dst, f.getName()), psych.toString(4));
                AppLog.info("  OK " + f.getName());
            } catch (Exception e) {
                AppLog.error("  Failed " + f.getName(), e);
            }
        }
    }

    private static void copyCharAssets(File modRoot, File outRoot) {
        AppLog.info("Copying character assets...");
        File src = new File(modRoot, "shared/images/characters");
        File dst = new File(outRoot, "images/characters");
        if (!src.isDirectory()) return;
        FileOps.folderMake(dst.getAbsolutePath());
        for (File f : FileOps.listAll(src)) {
            if (f.isFile()) FileOps.fileCopy(f.getAbsolutePath(), new File(dst, f.getName()).getAbsolutePath());
        }
    }

    private static void copyIcons(File modRoot, File outRoot) {
        AppLog.info("Copying icons...");
        File src = new File(modRoot, "images/icons");
        File dst = new File(outRoot, "images/icons");
        if (!src.isDirectory()) return;
        FileOps.folderMake(dst.getAbsolutePath());
        for (File f : FileOps.listFiles(src, ".png")) {
            FileOps.fileCopy(f.getAbsolutePath(), new File(dst, f.getName()).getAbsolutePath());
        }
    }

    private static void copySongs(File modRoot, File outRoot) {
        AppLog.info("Copying songs...");
        File src = new File(modRoot, "songs");
        if (!src.isDirectory()) return;
        for (File sf : FileOps.listAll(src)) {
            if (!sf.isDirectory()) continue;
            File dp = new File(outRoot, "songs/" + sf.getName());
            FileOps.folderMake(dp.getAbsolutePath());
            for (File f : FileOps.listAll(sf)) {
                if (f.isFile()) FileOps.fileCopy(f.getAbsolutePath(), new File(dp, f.getName()).getAbsolutePath());
            }
            AppLog.info("  OK " + sf.getName());
        }
    }

    private static void convertStages(File modRoot, File outRoot) {
        AppLog.info("Converting stages...");
        File src = new File(modRoot, "data/stages");
        File dst = new File(outRoot, "stages");
        if (!src.isDirectory()) return;
        FileOps.folderMake(dst.getAbsolutePath());
        for (File f : FileOps.listFiles(src, ".json")) {
            try {
                String rawText = FileOps.readText(f);
                JSONObject raw;
                String trimmed = rawText.trim();
                if (trimmed.startsWith("[")) {
                    JSONArray arr = new JSONArray(trimmed);
                    if (arr.length() == 1 && arr.optJSONObject(0) != null) raw = arr.getJSONObject(0);
                    else {
                        raw = new JSONObject();
                        raw.put("props", arr);
                    }
                } else {
                    raw = new JSONObject(trimmed);
                }
                String sn = f.getName().replace(".json", "");
                FileOps.writeText(new File(dst, f.getName()), stageToPsych(raw).toString(4));
                String lua = stageToLua(raw, sn);
                if (lua != null) FileOps.writeText(new File(dst, sn + ".lua"), lua);
                AppLog.info("  OK " + f.getName());
            } catch (Exception e) {
                AppLog.error("  Failed " + f.getName(), e);
            }
        }
    }

    private static void copyImages(File modRoot, File outRoot) {
        AppLog.info("Copying images...");
        File src = new File(modRoot, "shared/images");
        File dst = new File(outRoot, "images");
        if (!src.isDirectory()) return;
        copyTreeSkipCharacters(src, src, dst);
    }

    private static void copyTreeSkipCharacters(File root, File current, File dstRoot) {
        File[] kids = current.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) {
                if ("characters".equals(f.getName()) && current.equals(root)) continue;
                copyTreeSkipCharacters(root, f, dstRoot);
            } else {
                String rel = root.toURI().relativize(f.toURI()).getPath();
                File dest = new File(dstRoot, rel);
                FileOps.fileCopy(f.getAbsolutePath(), dest.getAbsolutePath());
            }
        }
    }

    private static void convertWeeks(File modRoot, File outRoot) {
        AppLog.info("Converting weeks...");
        File src = new File(modRoot, "data/levels");
        File dst = new File(outRoot, "weeks");
        if (!src.isDirectory()) return;
        FileOps.folderMake(dst.getAbsolutePath());
        for (File f : FileOps.listFiles(src, ".json")) {
            try {
                FileOps.writeText(new File(dst, f.getName()),
                        levelToPsych(new JSONObject(FileOps.readText(f))).toString(4));
                AppLog.info("  OK " + f.getName());
            } catch (Exception e) {
                AppLog.error("  Failed " + f.getName(), e);
            }
        }
    }

    private static void copySounds(File modRoot, File outRoot) {
        AppLog.info("Copying sounds/music...");
        for (String fn : new String[]{"sounds", "music"}) {
            File src = new File(modRoot, fn);
            if (src.isDirectory()) FileOps.treeCopy(src.getAbsolutePath(), new File(outRoot, fn).getAbsolutePath());
        }
    }

    private static void copyScripts(File modRoot, File outRoot) {
        AppLog.info("Converting scripts (HScript -> Lua)...");
        int[] count = new int[]{0};
        convertScriptsRecursive(new File(modRoot, "data/scripts"), new File(outRoot, "scripts"), count);
        convertScriptsRecursive(new File(modRoot, "scripts"), new File(outRoot, "scripts"), count);
        File songs = new File(modRoot, "data/songs");
        if (songs.isDirectory()) {
            for (File song : FileOps.listAll(songs)) {
                if (!song.isDirectory()) continue;
                convertScriptsRecursive(song, new File(outRoot, "data/" + song.getName()), count);
            }
        }
        if (count[0] == 0) AppLog.warn("No script files found");
        else AppLog.info("  " + count[0] + " script file(s) processed");
    }

    private static void convertScriptsRecursive(File src, File dst, int[] count) {
        if (src == null || !src.exists()) return;
        File[] kids = src.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) {
                convertScriptsRecursive(f, new File(dst, f.getName()), count);
                continue;
            }
            String n = f.getName().toLowerCase(Locale.US);
            try {
                FileOps.folderMake(dst.getAbsolutePath());
                if (n.endsWith(".hx") || n.endsWith(".hxc")) {
                    String hx = FileOps.readText(f);
                    HScriptToLua conv = new HScriptToLua();
                    String lua = conv.convert(hx);
                    if (n.endsWith(".hx")) {
                        conv.warnings.add("Input uses .hx; V-Slice's documented interpreted mod-script extension is .hxc. Verify this is HScript, not compiled Haxe source.");
                    }
                    String outName = f.getName().replaceAll("(?i)\\.hxc?$", ".lua");
                    FileOps.writeText(new File(dst, outName), lua);
                    File staleReport = new File(dst, outName + ".conversion-warnings.txt");
                    if (staleReport.exists()) staleReport.delete();
                    AppLog.info("  " + f.getName() + " -> " + outName);
                    if (conv.warnings.size() > 0) {
                        AppLog.warn("    " + conv.warnings.size() + " review warning(s); see sidecar report");
                        StringBuilder report = new StringBuilder("FNF Porter: manual review required\nSource: ")
                                .append(f.getName()).append("\nDirection: HScript -> Lua (best-effort)\n\n");
                        for (String warning : conv.warnings) report.append("- ").append(warning).append("\n");
                        FileOps.writeText(new File(dst, outName + ".conversion-warnings.txt"), report.toString());
                    }
                    count[0]++;
                } else if (n.endsWith(".lua")) {
                    FileOps.fileCopy(f.getAbsolutePath(), new File(dst, f.getName()).getAbsolutePath());
                    count[0]++;
                }
            } catch (Exception e) {
                AppLog.error("  Failed script " + f.getName(), e);
            }
        }
    }

    static JSONObject chartToPsych(JSONObject chartData, JSONObject metaData) throws Exception {
        String songName = "";
        double bpm = 100;
        double speed = 1.0;
        String player1 = "bf", player2 = "dad", gf = "gf", stage = "stage";
        boolean needsVoices = true;

        if (metaData != null) {
            songName = metaData.optString("songName", "");
            JSONObject play = metaData.optJSONObject("playData");
            if (play != null) {
                JSONObject chars = play.optJSONObject("characters");
                if (chars != null) {
                    player1 = chars.optString("player", player1);
                    player2 = chars.optString("opponent", player2);
                    gf = chars.optString("girlfriend", gf);
                }
                stage = play.optString("stage", stage);
                needsVoices = play.optBoolean("needsVoices", true);
            }
            JSONArray tcs = metaData.optJSONArray("timeChanges");
            if (tcs != null && tcs.length() > 0) {
                JSONObject first = tcs.optJSONObject(0);
                if (first != null) bpm = first.optDouble("bpm", bpm);
            }
            if (songName.length() == 0) songName = metaData.optString("song", "unknown");
        }

        Object ss = chartData.opt("scrollSpeed");
        if (ss instanceof JSONObject) {
            JSONObject sso = (JSONObject) ss;
            speed = firstNum(sso, new String[]{"hard", "normal", "easy", "default"}, speed);
        } else if (ss instanceof Number) {
            speed = ((Number) ss).doubleValue();
        }

        JSONArray notesList = new JSONArray();
        Object notesRaw = chartData.opt("notes");
        if (notesRaw instanceof JSONObject) {
            JSONObject nd = (JSONObject) notesRaw;
            notesList = firstArray(nd, new String[]{"hard", "normal", "easy", "default"});
        } else if (notesRaw instanceof JSONArray) {
            notesList = (JSONArray) notesRaw;
        }

        List<JSONObject> allNotes = new ArrayList<JSONObject>();
        int droppedNoteParams = 0;
        for (int i = 0; i < notesList.length(); i++) {
            JSONObject n = notesList.optJSONObject(i);
            if (n != null) {
                JSONObject o = new JSONObject();
                o.put("time", n.optDouble("t", 0));
                o.put("data", n.optInt("d", 0));
                o.put("length", n.isNull("l") ? 0 : n.optDouble("l", 0));
                o.put("kind", n.optString("k", ""));
                JSONArray params = n.optJSONArray("p");
                if (params != null && params.length() > 0) droppedNoteParams++;
                allNotes.add(o);
            } else {
                JSONArray arr = notesList.optJSONArray(i);
                if (arr != null && arr.length() >= 2) {
                    JSONObject o = new JSONObject();
                    o.put("time", arr.optDouble(0, 0));
                    o.put("data", arr.optInt(1, 0));
                    o.put("length", arr.length() > 2 ? arr.optDouble(2, 0) : 0);
                    o.put("kind", arr.length() > 3 ? arr.optString(3, "") : "");
                    allNotes.add(o);
                }
            }
        }
        if (droppedNoteParams > 0) {
            AppLog.warn("Chart contains " + droppedNoteParams + " note(s) with V-Slice custom parameter arrays; Psych notes cannot represent these generically, so those parameters were omitted.");
        }
        Collections.sort(allNotes, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                return Double.compare(a.optDouble("time"), b.optDouble("time"));
            }
        });

        JSONArray eventsData = chartData.optJSONArray("events");
        if (eventsData == null) eventsData = new JSONArray();
        JSONArray psychEvents = new JSONArray();
        for (int i = 0; i < eventsData.length(); i++) {
            JSONObject ev = eventsData.optJSONObject(i);
            if (ev == null) continue;
            double t = ev.optDouble("t", 0);
            String e = ev.optString("e", "");
            Object v = ev.opt("v");
            String val1 = "", val2 = "";
            if (v instanceof JSONObject) {
                JSONObject vo = (JSONObject) v;
                if ("FocusCamera".equals(e)) val1 = String.valueOf(vo.opt("char"));
                else {
                    val1 = String.valueOf(vo.has("value") ? vo.opt("value") : vo.opt("val"));
                    val2 = String.valueOf(vo.has("value2") ? vo.opt("value2") : vo.opt("val2"));
                    if ("null".equals(val1)) val1 = "";
                    if ("null".equals(val2)) val2 = "";
                }
            } else if (v != null && v != JSONObject.NULL) {
                val1 = String.valueOf(v);
            }
            JSONArray inner = new JSONArray();
            JSONArray triple = new JSONArray();
            triple.put(e);
            triple.put(val1);
            triple.put(val2);
            inner.put(triple);
            JSONArray row = new JSONArray();
            row.put(t);
            row.put(inner);
            psychEvents.put(row);
        }

        String timeFormat = metaData == null ? "ms" : metaData.optString("timeFormat", "ms");
        if (!"ms".equalsIgnoreCase(timeFormat)) AppLog.warn("Song timeFormat is '" + timeFormat + "'; timestamps are interpreted as milliseconds. Review this chart.");
        if (bpm <= 0 || Double.isNaN(bpm) || Double.isInfinite(bpm)) bpm = 100;
        double sectionMs = 60000.0 / bpm * 4;
        double maxTime = 0;
        for (JSONObject n : allNotes) maxTime = Math.max(maxTime, n.optDouble("time") + n.optDouble("length"));
        for (int i = 0; i < psychEvents.length(); i++) {
            JSONArray row = psychEvents.optJSONArray(i);
            if (row != null) maxTime = Math.max(maxTime, row.optDouble(0));
        }
        if (maxTime <= 0) maxTime = sectionMs * 4;
        List<JSONObject> tempoChanges = new ArrayList<JSONObject>();
        JSONArray tcs = metaData == null ? null : metaData.optJSONArray("timeChanges");
        if (tcs != null) for (int i = 0; i < tcs.length(); i++) {
            JSONObject tc = tcs.optJSONObject(i);
            if (tc != null && tc.has("t") && tc.has("bpm")) {
                if (tc.optInt("n", 4) != 4 || tc.optInt("d", 4) != 4) {
                    AppLog.warn("Non-4/4 time signature is not representable in Psych sections; chart timing may differ.");
                }
                tempoChanges.add(tc);
            }
        }
        Collections.sort(tempoChanges, new Comparator<JSONObject>() {
            @Override public int compare(JSONObject a, JSONObject b) { return Double.compare(a.optDouble("t"), b.optDouble("t")); }
        });
        List<Double> sectionStarts = new ArrayList<Double>();
        List<Double> sectionEnds = new ArrayList<Double>();
        List<Double> sectionBpms = new ArrayList<Double>();
        List<Boolean> sectionBpmChanges = new ArrayList<Boolean>();
        double sectionStart = 0;
        double currentTempo = bpm == 0 ? 100 : bpm;
        int tempoIndex = 0;
        while (tempoIndex < tempoChanges.size() && tempoChanges.get(tempoIndex).optDouble("t") <= 0) {
            double value = tempoChanges.get(tempoIndex++).optDouble("bpm", currentTempo);
            if (value > 0 && !Double.isNaN(value) && !Double.isInfinite(value)) currentTempo = value;
        }
        for (int sectionIndex = 0; sectionIndex < 100000; sectionIndex++) {
            boolean changed = false;
            while (tempoIndex < tempoChanges.size() && tempoChanges.get(tempoIndex).optDouble("t") <= sectionStart + 0.001) {
                double nextTempo = tempoChanges.get(tempoIndex++).optDouble("bpm", currentTempo);
                if (nextTempo <= 0 || Double.isNaN(nextTempo) || Double.isInfinite(nextTempo)) {
                    AppLog.warn("Ignoring invalid BPM in song metadata.");
                    continue;
                }
                if (Math.abs(nextTempo - currentTempo) > 0.000001) changed = true;
                currentTempo = nextTempo;
            }
            double duration = 60000.0 / (currentTempo == 0 ? 100 : currentTempo) * 4;
            sectionStarts.add(sectionStart);
            sectionEnds.add(sectionStart + duration);
            sectionBpms.add(currentTempo);
            sectionBpmChanges.add(changed || sectionIndex == 0);
            sectionStart += duration;
            if (sectionStart > maxTime && sectionIndex >= 1) break;
        }
        if (sectionStarts.size() >= 100000) AppLog.warn("Chart section limit reached; remaining tail may be truncated.");
        JSONArray sections = new JSONArray();
        boolean mustHit = false;
        for (int i = 0; i < sectionStarts.size(); i++) {
            double secStart = sectionStarts.get(i);
            double secEnd = sectionEnds.get(i);
            double currentBpm = sectionBpms.get(i);
            boolean changeBpm = sectionBpmChanges.get(i);
            for (int eventIndex = 0; eventIndex < eventsData.length(); eventIndex++) {
                JSONObject focusEvent = eventsData.optJSONObject(eventIndex);
                if (focusEvent == null || !"FocusCamera".equals(focusEvent.optString("e"))) continue;
                double focusTime = focusEvent.optDouble("t", -1);
                if (focusTime < secStart || focusTime >= secEnd) continue;
                JSONObject focusValue = focusEvent.optJSONObject("v");
                if (focusValue != null) mustHit = focusValue.optInt("char", 0) == 1;
            }
            JSONArray sectionNotes = new JSONArray();
            for (JSONObject note : allNotes) {
                double t = note.optDouble("time");
                if (t >= secStart && t < secEnd) {
                    int lane = note.optInt("data");
                    int psychLane = mustHit ? lane : (lane < 4 ? lane + 4 : lane - 4);
                    JSONArray sn = new JSONArray();
                    sn.put(t);
                    sn.put(psychLane);
                    sn.put(note.optDouble("length"));
                    // Psych note arrays support an optional note-type field after sustain length.
                    // Keep V-Slice note kinds instead of silently discarding them.
                    String kind = note.optString("kind", "");
                    if (!kind.isEmpty()) sn.put(kind);
                    sectionNotes.put(sn);
                }
            }
            JSONObject section = new JSONObject();
            section.put("sectionNotes", sectionNotes);
            section.put("typeOfSection", 0);
            section.put("lengthInSteps", 16);
            section.put("mustHitSection", mustHit);
            section.put("bpm", currentBpm);
            section.put("changeBPM", changeBpm || i == 0);
            sections.put(section);
        }
        while (sections.length() > 1) {
            JSONObject last = sections.optJSONObject(sections.length() - 1);
            if (last == null) break;
            JSONArray sns = last.optJSONArray("sectionNotes");
            if (sns != null && sns.length() > 0) break;
            int idx = sections.length() - 1;
            double secStart = sectionStarts.get(idx);
            double secEnd = sectionEnds.get(idx);
            boolean hasEv = false;
            for (int i = 0; i < psychEvents.length(); i++) {
                JSONArray row = psychEvents.optJSONArray(i);
                if (row != null && row.optDouble(0) >= secStart && row.optDouble(0) < secEnd) {
                    hasEv = true;
                    break;
                }
            }
            if (hasEv) break;
            sections.remove(sections.length() - 1);
        }

        JSONObject song = new JSONObject();
        song.put("song", songName);
        song.put("bpm", bpm);
        song.put("speed", speed);
        song.put("needsVoices", needsVoices);
        song.put("player1", player1);
        song.put("player2", player2);
        song.put("gfVersion", gf);
        song.put("stage", stage);
        song.put("validScore", true);
        song.put("notes", sections);
        song.put("events", psychEvents);
        JSONObject root = new JSONObject();
        root.put("song", song);
        return root;
    }

    static JSONObject charToPsych(JSONObject charData) throws Exception {
        String name = charData.optString("name", "character");
        String asset = charData.optString("assetPath", "");
        if (asset.length() == 0) asset = charData.optString("atlasPath", charData.optString("spritePath", ""));
        String image = asset;
        if (image.length() > 0 && !image.startsWith("characters/")) image = "characters/" + image;

        JSONArray animations = new JSONArray();
        JSONArray anims = charData.optJSONArray("animations");
        if (anims == null) anims = charData.optJSONArray("animationData");
        if (anims != null) {
            for (int i = 0; i < anims.length(); i++) {
                JSONObject anim = anims.optJSONObject(i);
                if (anim == null) continue;
                JSONObject a = new JSONObject();
                String animName = anim.optString("name", anim.optString("anim", ""));
                String prefix = anim.optString("prefix", anim.optString("atlasPrefix", ""));
                a.put("anim", animName);
                a.put("name", prefix.length() > 0 ? prefix : animName);
                a.put("fps", anim.has("fps") ? anim.opt("fps") : (anim.has("framerate") ? anim.opt("framerate") : 24));
                a.put("loop", anim.has("looped") ? anim.optBoolean("looped") : anim.optBoolean("loop", false));
                JSONArray indices = anim.optJSONArray("indices");
                if (indices == null) indices = anim.optJSONArray("frameIndices");
                a.put("indices", indices == null ? new JSONArray() : indices);
                JSONArray off = xy(anim.opt("offsets"), 0, 0);
                a.put("offsets", off);
                animations.put(a);
            }
        }

        Object hi = charData.opt("healthIcon");
        String healthIcon;
        if (hi instanceof JSONObject) healthIcon = ((JSONObject) hi).optString("id", name.toLowerCase(Locale.US));
        else if (hi != null && hi != JSONObject.NULL) healthIcon = String.valueOf(hi);
        else healthIcon = charData.optString("icon", name.toLowerCase(Locale.US));

        JSONObject psych = new JSONObject();
        psych.put("image", image);
        psych.put("position", xy(first(charData, "offsets", "position"), 0, 0));
        psych.put("camera_position", xy(first(charData, "cameraOffsets", "cameraPosition"), 0, 0));
        psych.put("healthbar_colors", rgb(first(charData, "healthbarColors", "healthColor")));
        psych.put("healthicon", healthIcon);
        psych.put("flip_x", charData.optBoolean("flipX", charData.optBoolean("isFlipped", false)));
        psych.put("no_antialiasing", charData.optBoolean("noAntialiasing", charData.optBoolean("isPixel", false)));
        psych.put("scale", charData.has("scale") ? charData.opt("scale") : (charData.has("graphicScale") ? charData.opt("graphicScale") : 1));
        Object sing = charData.has("singTime") ? charData.opt("singTime")
                : (charData.has("singDuration") ? charData.opt("singDuration")
                : (charData.has("holdTimer") ? charData.opt("holdTimer") : 4));
        psych.put("sing_duration", sing);
        psych.put("animations", animations);
        psych.put("vocals_file", charData.optString("vocalsFile", ""));
        psych.put("_editor_isPlayer", false);
        return psych;
    }

    static JSONObject stageToPsych(JSONObject stage) throws Exception {
        JSONObject characters = stage.optJSONObject("characters");
        if (characters == null) characters = new JSONObject();
        JSONObject psych = new JSONObject();
        psych.put("directory", stage.optString("directory", ""));
        psych.put("defaultZoom", stage.opt("cameraZoom") != null ? stage.opt("cameraZoom") : 0.9);
        psych.put("isPixelStage", stage.optBoolean("isPixel", false));
        psych.put("boyfriend", charPos(characters, "bf", 770, 100));
        psych.put("girlfriend", charPos(characters, "gf", 400, 130));
        psych.put("opponent", charPos(characters, "dad", 100, 100));
        psych.put("hide_girlfriend", stage.optBoolean("hideGirlfriend", false));
        psych.put("camera_boyfriend", camOff(characters, "bf"));
        psych.put("camera_opponent", camOff(characters, "dad"));
        psych.put("camera_girlfriend", camOff(characters, "gf"));
        psych.put("camera_speed", 1);
        return psych;
    }

    static String stageToLua(JSONObject stage, String stageName) {
        JSONArray props = stage.optJSONArray("props");
        if (props == null || props.length() == 0) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("function onCreate()\n");
        for (int i = 0; i < props.length(); i++) {
            JSONObject prop = props.optJSONObject(i);
            if (prop == null) continue;
            String asset = prop.optString("assetPath", prop.optString("image", ""));
            if (asset.length() == 0) continue;
            String tag = prop.optString("name", "bg_" + i);
            JSONArray pos = xy(prop.opt("position"), 0, 0);
            int x = pos.optInt(0), y = pos.optInt(1);
            JSONArray scale = xy(prop.opt("scale"), 1, 1);
            JSONArray scroll = xy(first(prop, "scroll", "scrollFactor"), 1, 1);
            int z = prop.optInt("zIndex", i);
            JSONArray anims = prop.optJSONArray("animations");
            if (anims == null) anims = prop.optJSONArray("animationData");
            boolean animated = anims != null && anims.length() > 0;
            if (animated) {
                sb.append("    makeAnimatedLuaSprite(\"").append(esc(tag)).append("\", \"")
                        .append(esc(asset)).append("\", ").append(x).append(", ").append(y).append(")\n");
                for (int a = 0; a < anims.length(); a++) {
                    JSONObject anim = anims.optJSONObject(a);
                    if (anim == null) continue;
                    String an = anim.optString("name", anim.optString("anim", "idle"));
                    String prefix = anim.optString("prefix", anim.optString("atlasPrefix", an));
                    int fps = anim.optInt("fps", anim.optInt("framerate", 24));
                    boolean loop = anim.has("looped") ? anim.optBoolean("looped") : anim.optBoolean("loop", true);
                    sb.append("    addAnimationByPrefix(\"").append(esc(tag)).append("\", \"")
                            .append(esc(an)).append("\", \"").append(esc(prefix)).append("\", ")
                            .append(fps).append(", ").append(loop).append(")\n");
                }
                String firstAnim = anims.optJSONObject(0) != null
                        ? anims.optJSONObject(0).optString("name", "idle") : "idle";
                sb.append("    objectPlayAnimation(\"").append(esc(tag)).append("\", \"")
                        .append(esc(firstAnim)).append("\", true)\n");
            } else {
                sb.append("    makeLuaSprite(\"").append(esc(tag)).append("\", \"")
                        .append(esc(asset)).append("\", ").append(x).append(", ").append(y).append(")\n");
            }
            if (scale.optDouble(0) != 1 || scale.optDouble(1) != 1) {
                sb.append("    scaleObject(\"").append(esc(tag)).append("\", ")
                        .append(scale.opt(0)).append(", ").append(scale.opt(1)).append(")\n");
            }
            if (scroll.optDouble(0) != 1 || scroll.optDouble(1) != 1) {
                sb.append("    setScrollFactor(\"").append(esc(tag)).append("\", ")
                        .append(scroll.opt(0)).append(", ").append(scroll.opt(1)).append(")\n");
            }
            if (prop.optDouble("alpha", 1) != 1) {
                sb.append("    setProperty('" ).append(esc(tag)).append(".alpha', ").append(prop.optDouble("alpha", 1)).append(")\n");
            }
            if (prop.optDouble("angle", 0) != 0) {
                sb.append("    setProperty('" ).append(esc(tag)).append(".angle', ").append(prop.optDouble("angle", 0)).append(")\n");
            }
            if (prop.optBoolean("flipX", false)) sb.append("    setProperty('").append(esc(tag)).append(".flipX', true)\n");
            if (prop.optBoolean("flipY", false)) sb.append("    setProperty('").append(esc(tag)).append(".flipY', true)\n");
            if (prop.optBoolean("isPixel", false)) sb.append("    setProperty('").append(esc(tag)).append(".antialiasing', false)\n");
            String color = prop.optString("color", "#FFFFFF");
            if (color.length() == 7 && !"#FFFFFF".equalsIgnoreCase(color)) {
                sb.append("    setProperty('").append(esc(tag)).append(".color', getColorFromHex('").append(esc(color.substring(1))).append("'))\n");
            }
            if (prop.has("blend") && !prop.optString("blend", "").isEmpty()) {
                AppLog.warn("Stage prop '" + tag + "' uses blend mode '" + prop.optString("blend") + "'; blend conversion needs manual verification.");
            }
            sb.append("    addLuaSprite(\"").append(esc(tag)).append("\", ").append(z >= 100).append(")\n\n");
        }
        sb.append("end\n");
        return sb.toString();
    }

    static JSONObject levelToPsych(JSONObject level) throws Exception {
        String name = level.optString("name", level.optString("displayName", "Week"));
        JSONArray songsRaw = level.optJSONArray("songs");
        if (songsRaw == null) songsRaw = level.optJSONArray("songList");
        JSONArray psychSongs = new JSONArray();
        if (songsRaw != null) {
            for (int i = 0; i < songsRaw.length(); i++) {
                String songName = "";
                Object s = songsRaw.opt(i);
                if (s instanceof String) songName = ((String) s).toLowerCase(Locale.US);
                else if (s instanceof JSONObject) {
                    songName = ((JSONObject) s).optString("name", ((JSONObject) s).optString("song", "")).toLowerCase(Locale.US);
                } else if (s instanceof JSONArray && ((JSONArray) s).length() > 0) {
                    songName = String.valueOf(((JSONArray) s).opt(0)).toLowerCase(Locale.US);
                }
                if (songName.length() > 0) {
                    JSONArray pair = new JSONArray();
                    pair.put(songName);
                    pair.put("dad");
                    psychSongs.put(pair);
                }
            }
        }
        JSONArray diffsRaw = level.optJSONArray("difficulties");
        String difficulties = "";
        if (diffsRaw != null && diffsRaw.length() > 0) {
            List<String> ds = new ArrayList<String>();
            for (int i = 0; i < diffsRaw.length(); i++) {
                if (diffsRaw.opt(i) instanceof String) ds.add(diffsRaw.optString(i));
            }
            List<String> low = new ArrayList<String>();
            for (String d : ds) low.add(d.toLowerCase(Locale.US));
            Collections.sort(low);
            if (low.size() == 3 && low.contains("easy") && low.contains("normal") && low.contains("hard")) {
                difficulties = "Easy\nNormal\nHard";
            } else if (!(ds.size() == 1 && "normal".equalsIgnoreCase(ds.get(0)))) {
                StringBuilder db = new StringBuilder();
                for (String d : ds) {
                    if (db.length() > 0) db.append('\n');
                    if (d.length() > 0) db.append(Character.toUpperCase(d.charAt(0))).append(d.substring(1));
                }
                difficulties = db.toString();
            }
        }
        JSONArray props = level.optJSONArray("props");
        JSONArray characters = new JSONArray();
        if (props != null) {
            for (int i = 0; i < props.length(); i++) {
                Object p = props.opt(i);
                if (p instanceof String) characters.put(p);
                else if (p instanceof JSONObject) {
                    characters.put(((JSONObject) p).optString("assetPath", ((JSONObject) p).optString("asset", "")));
                }
            }
        }
        while (characters.length() < 3) characters.put("");
        while (characters.length() > 3) characters.remove(characters.length() - 1);

        String weekBg = "";
        Object bg = first(level, "background", "backgroundColor");
        if (bg instanceof String) weekBg = (String) bg;
        else if (bg instanceof JSONArray && ((JSONArray) bg).length() >= 3) {
            JSONArray c = (JSONArray) bg;
            weekBg = String.format("#%02x%02x%02x", c.optInt(0), c.optInt(1), c.optInt(2));
        }

        JSONObject week = new JSONObject();
        week.put("weekName", name);
        week.put("storyName", name);
        week.put("weekBefore", "");
        week.put("startUnlocked", true);
        week.put("hiddenUntilUnlocked", false);
        week.put("songs", psychSongs);
        week.put("weekCharacters", characters);
        week.put("difficulties", difficulties);
        week.put("weekBackground", weekBg);
        week.put("hideStoryMode", level.optBoolean("hideStoryMode", false));
        week.put("hideFreeplay", level.optBoolean("hideFreeplay", false));
        return week;
    }

    private static Object first(JSONObject o, String a, String b) {
        if (o.has(a) && !o.isNull(a)) return o.opt(a);
        return o.opt(b);
    }

    private static JSONArray xy(Object v, double dx, double dy) {
        JSONArray a = new JSONArray();
        try {
            if (v instanceof JSONArray) {
                JSONArray s = (JSONArray) v;
                a.put(s.length() > 0 ? s.opt(0) : dx);
                a.put(s.length() > 1 ? s.opt(1) : dy);
            } else if (v instanceof JSONObject) {
                JSONObject o = (JSONObject) v;
                a.put(o.opt("x") != null ? o.opt("x") : dx);
                a.put(o.opt("y") != null ? o.opt("y") : dy);
            } else if (v instanceof Number) {
                a.put(v);
                a.put(v);
            } else {
                a.put(dx);
                a.put(dy);
            }
        } catch (Exception e) {
            a = new JSONArray();
            a.put((int) dx);
            a.put((int) dy);
        }
        return a;
    }

    private static JSONArray rgb(Object v) {
        JSONArray a = new JSONArray();
        if (v instanceof String) {
            String hc = ((String) v).replace("#", "");
            try {
                a.put(Integer.parseInt(hc.substring(0, 2), 16));
                a.put(Integer.parseInt(hc.substring(2, 4), 16));
                a.put(Integer.parseInt(hc.substring(4, 6), 16));
                return a;
            } catch (Exception ignored) {
            }
        } else if (v instanceof JSONArray) {
            JSONArray s = (JSONArray) v;
            a.put(s.optInt(0, 150));
            a.put(s.optInt(1, 150));
            a.put(s.optInt(2, 150));
            return a;
        } else if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            a.put(o.optInt("r", 150));
            a.put(o.optInt("g", 150));
            a.put(o.optInt("b", 150));
            return a;
        }
        a.put(150);
        a.put(150);
        a.put(150);
        return a;
    }

    private static JSONArray charPos(JSONObject characters, String key, int dx, int dy) {
        JSONObject c = characters.optJSONObject(key);
        if (c == null) {
            JSONArray a = new JSONArray();
            a.put(dx);
            a.put(dy);
            return a;
        }
        return xy(c.opt("position"), dx, dy);
    }

    private static JSONArray camOff(JSONObject characters, String key) {
        JSONObject c = characters.optJSONObject(key);
        if (c == null) {
            JSONArray a = new JSONArray();
            a.put(0);
            a.put(0);
            return a;
        }
        return xy(c.opt("cameraOffsets"), 0, 0);
    }

    private static double firstNum(JSONObject o, String[] keys, double def) {
        for (String k : keys) {
            if (o.has(k) && !o.isNull(k)) return o.optDouble(k, def);
        }
        Iterator<String> it = o.keys();
        if (it.hasNext()) return o.optDouble(it.next(), def);
        return def;
    }

    private static JSONArray firstArray(JSONObject o, String[] keys) {
        for (String k : keys) {
            JSONArray a = o.optJSONArray(k);
            if (a != null) return a;
        }
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            JSONArray a = o.optJSONArray(it.next());
            if (a != null) return a;
        }
        return new JSONArray();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
