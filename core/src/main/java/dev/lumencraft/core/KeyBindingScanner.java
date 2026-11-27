package dev.lumencraft.core;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Discovers Minecraft & mod keybindings from options.txt, runtime bridge dumps, and mod JAR lang files. */
public final class KeyBindingScanner {
    public static final class DiscoveredKey {
        public final String keyId, displayName, category;
        public final int fclCode;
        public final boolean runtimeConfirmed;

        public DiscoveredKey(String keyId, String displayName, String category, int fclCode, boolean runtimeConfirmed) {
            this.keyId = keyId;
            this.displayName = displayName;
            this.category = category;
            this.fclCode = fclCode;
            this.runtimeConfirmed = runtimeConfirmed;
        }
    }

    private KeyBindingScanner() {}

    /** Scans mod JARs for zh_cn/en_us key.* localization labels so generated virtual buttons have readable names. */
    public static Map<String, String> scanModKeyLabels(File instanceDir) {
        Map<String, String> labels = new LinkedHashMap<>();
        File modsDir = new File(instanceDir, "mods");
        File[] jars = modsDir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (jars == null) return labels;
        Arrays.sort(jars);
        for (File jar : jars) {
            try (ZipFile zip = new ZipFile(jar)) {
                Enumeration<? extends ZipEntry> en = zip.entries();
                List<ZipEntry> zh = new ArrayList<>();
                List<ZipEntry> enUs = new ArrayList<>();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    String name = e.getName().toLowerCase(Locale.ROOT);
                    if (e.isDirectory() || e.getSize() > 2 * 1024 * 1024) continue;
                    if (name.startsWith("assets/") && name.endsWith("/lang/zh_cn.json")) zh.add(e);
                    else if (name.startsWith("assets/") && name.endsWith("/lang/en_us.json")) enUs.add(e);
                }
                for (ZipEntry e : enUs) parseLangJson(zip, e, labels, false);
                for (ZipEntry e : zh) parseLangJson(zip, e, labels, true);
            } catch (Exception ignored) {
                // Skip unreadable mod archives safely.
            }
        }
        return labels;
    }

    /** Reads runtime KeyBinding dumps written by a live JVM bridge (.lumen-keybindings.json), if present. */
    public static List<DiscoveredKey> readRuntimeBindings(File instanceDir) {
        File dump = new File(instanceDir, ".lumen-keybindings.json");
        if (!dump.isFile() || dump.length() > 2 * 1024 * 1024) return Collections.emptyList();
        List<DiscoveredKey> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONObject(new String(readBytes(dump), StandardCharsets.UTF_8)).optJSONArray("bindings");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject b = arr.getJSONObject(i);
                String id = b.getString("id");
                String label = b.optString("label", shortName(id));
                String category = b.optString("category", categorize(id));
                int fclCode = b.optInt("fclCode", 0);
                if (fclCode > 0) out.add(new DiscoveredKey(id, label, category, fclCode, true));
            }
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
        return out;
    }

    public static String categorize(String keyId) {
        String low = keyId == null ? "" : keyId.toLowerCase(Locale.ROOT);
        if (low.contains("forward") || low.contains("back") || low.contains("left") || low.contains("right")
                || low.contains("jump") || low.contains("sneak") || low.contains("sprint")) return "移动";
        if (low.contains("attack") || low.contains("use") || low.contains("pick") || low.contains("drop")
                || low.contains("swap") || low.contains("hotbar") || low.contains("inventory")) return "动作与背包";
        if (low.contains("chat") || low.contains("command") || low.contains("playerlist")
                || low.contains("screenshot") || low.contains("fullscreen") || low.contains("perspective")) return "界面与视角";
        int dot = low.indexOf('.');
        if (dot > 0 && !low.startsWith("key.")) return "模组 · " + low.substring(0, dot);
        String[] parts = low.split("\\.");
        if (parts.length >= 3 && "key".equals(parts[0])) return "模组 · " + parts[1];
        return "其他按键";
    }

    public static String shortName(String keyId) {
        if (keyId == null || keyId.isEmpty()) return "键";
        int dot = keyId.lastIndexOf('.');
        String s = dot >= 0 && dot + 1 < keyId.length() ? keyId.substring(dot + 1) : keyId;
        return s.length() > 12 ? s.substring(0, 12) : s;
    }

    private static void parseLangJson(ZipFile zip, ZipEntry entry, Map<String, String> out, boolean preferOverwrite) {
        try (InputStream in = zip.getInputStream(entry); ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            VerifiedDownloads.transfer(in, buf, 2 * 1024 * 1024);
            JSONObject obj = new JSONObject(new String(buf.toByteArray(), StandardCharsets.UTF_8));
            for (Iterator<?> it = obj.keys(); it.hasNext(); ) {
                String k = (String) it.next();
                if (!k.startsWith("key.")) continue;
                String v = obj.optString(k, "").trim();
                if (!v.isEmpty() && (preferOverwrite || !out.containsKey(k))) {
                    out.put(k, v);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static byte[] readBytes(File f) throws IOException {
        try (InputStream in = new FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            VerifiedDownloads.transfer(in, out, 2 * 1024 * 1024);
            return out.toByteArray();
        }
    }
}
