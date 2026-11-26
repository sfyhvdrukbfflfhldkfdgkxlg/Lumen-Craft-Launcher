package dev.lumencraft.runtime;

import android.content.Context;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Official server-side configuration updates for affected numbered Mojang releases. */
final class ServerLogging {
    private ServerLogging() {}
    static void configure(Context c, File jar, ProcessBuilder command) throws IOException {
        String option = "-Dlog4j2.formatMsgNoLookups=true";
        File receipt = new File(jar.getParentFile(),"install.json");
        if (receipt.isFile()) {
            String id;
            try (InputStream in = new FileInputStream(receipt); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] block = new byte[4096]; int n;
                while ((n = in.read(block)) != -1) {
                    if (bytes.size()+n > 128*1024) throw new IOException("服务端安装记录过大");
                    bytes.write(block,0,n);
                }
                id = new JSONObject(bytes.toString("UTF-8")).getString("id");
            } catch (JSONException e) { throw new IOException("服务端安装记录损坏",e); }
            Matcher m = Pattern.compile("1\\.(\\d{1,2})(?:\\.\\d+)?").matcher(id);
            if (m.matches()) {
                int minor = Integer.parseInt(m.group(1));
                if (minor >= 7 && minor <= 16) option += " -Dlog4j.configurationFile=" + profile(c,minor>=12).getPath();
            }
        }
        command.environment().put("JAVA_TOOL_OPTIONS",option);
    }

    private static File profile(Context c, boolean middle) throws IOException {
        String name = middle ? "log4j2_112-116.xml" : "log4j2_17-111.xml";
        String object = middle ? "02937d122c86ce73319ef9975b58896fc1b491d1" : "4bb89a97a66f350bc9f73b3ca8509632682aea2e";
        String hash = middle ? "29534615b561487bccd3f2ec859b172bc642096cef4f8606754ead7eca5050dd" : "c04eb15a3b6b0cc5642a439793c295172491a86da34c176403752fac61bafd72";
        int size = middle ? 1131 : 1219;
        File dst = new File(c.getFilesDir(),name);
        if (dst.isFile() && dst.length()==size && digest(dst).equals(hash)) return dst;
        File staging = File.createTempFile("log-config-", ".xml", c.getFilesDir());
        try {
            JSONObject download = new JSONObject().put("url","https://launcher.mojang.com/v1/objects/" + object + "/" + name)
                    .put("size",size).put("sha256",hash);
            RuntimeCatalog.fetch(c,download,staging);
            if (!staging.renameTo(dst)) throw new IOException("无法保存服务端日志配置");
            return dst;
        } catch (JSONException e) { throw new IOException(e); }
        finally { if (staging.exists()) staging.delete(); }
    }
    private static String digest(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = new byte[2048]; int n;
            while ((n=in.read(bytes)) != -1) md.update(bytes,0,n);
            StringBuilder hash = new StringBuilder();
            for (byte b:md.digest()) hash.append(String.format(Locale.ROOT,"%02x",b&255));
            return hash.toString();
        } catch (NoSuchAlgorithmException e) { throw new IOException(e); }
    }
}
