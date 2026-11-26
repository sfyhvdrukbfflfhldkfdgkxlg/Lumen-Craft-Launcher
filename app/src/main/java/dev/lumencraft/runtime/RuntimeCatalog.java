package dev.lumencraft.runtime;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import org.json.JSONException;
import org.json.JSONObject;
import org.tukaani.xz.XZInputStream;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;

/** Downloads one upstream Android OpenJDK release asset, pinned by both digest and byte count. */
final class RuntimeCatalog {
    private RuntimeCatalog() {}
    static JSONObject entry(Context ctx, int major, String arch) throws IOException {
        try (InputStream in = ctx.getAssets().open("openjdk.json"); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf,0,n);
            JSONObject all = new JSONObject(out.toString("UTF-8"));
            return all.optJSONObject("jre" + major + "-android-" + arch);
        } catch (JSONException e) { throw new IOException("运行时目录损坏",e); }
    }
    static void fetch(Context c, JSONObject entry, File result) throws IOException {
        fetch(c,entry,result,new RuntimeStore.Reporter(null));
    }
    static void fetch(Context c, JSONObject entry, File result, RuntimeStore.Reporter report) throws IOException {
        try {
            URL url = new URL(entry.getString("url"));
            long expected = entry.getLong("size");
            report.update("下载 Java",0,expected);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int hop = 0; hop < 6; hop++) {
                if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null)
                    throw new IOException("组件来源必须使用 HTTPS");
                HttpsURLConnection connection = RuntimeTls.open(c,url);
                try {
                    int status = connection.getResponseCode();
                    if (status >= 300 && status <= 399) {
                        String next = connection.getHeaderField("Location");
                        if (next == null) throw new IOException("组件源返回了无效的重定向");
                        url = new URL(url,next); continue;
                    }
                    if (status != 200) throw new IOException("组件源 HTTP " + status);
                    try (InputStream in = connection.getInputStream(); OutputStream out = new BufferedOutputStream(new FileOutputStream(result))) {
                        byte[] buf = new byte[64 * 1024]; long bytes = 0; int n;
                        while ((n = in.read(buf)) != -1) {
                            bytes += n;
                            if (bytes > expected) throw new IOException("下载超过清单大小");
                            out.write(buf,0,n); digest.update(buf,0,n);
                            report.update("下载 Java",bytes,expected);
                        }
                        if (bytes != expected) throw new EOFException("组件文件不完整");
                    }
                    StringBuilder hex = new StringBuilder();
                    for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT,"%02x",b & 255));
                    if (!entry.getString("sha256").equals(hex.toString())) throw new IOException("组件 SHA-256 不匹配");
                    return;
                } finally { connection.disconnect(); }
            }
            throw new IOException("组件源重定向过多");
        } catch (JSONException | NoSuchAlgorithmException e) { throw new IOException(e); }
    }

    // Only used on pinned archives. Defer links until ordinary files have been completely written.
    static void extractVerified(File archive, File stage, RuntimeStore.Reporter report) throws IOException {
        report.update("解压 Java",0,-1);
        String prefix = stage.getCanonicalPath() + File.separator;
        List<String[]> links = new ArrayList<>();
        try (InputStream file = new BufferedInputStream(new FileInputStream(archive));
             DataInputStream tar = new DataInputStream(new BufferedInputStream(new XZInputStream(file, 96 * 1024)))) {
            byte[] header = new byte[512], buf = new byte[65536];
            int count = 0; long total = 0;
            while (true) {
                tar.readFully(header);
                if (header[0] == 0) break;
                if (++count > 16000) throw new IOException("TAR 条目过多");
                String base = field(header,345,155), name = field(header,0,100);
                // GNU format uses bytes after offset 345 for non-path data.
                if (header[262] == 0 && !base.isEmpty()) name = base + "/" + name;
                while (name.startsWith("./")) name = name.substring(2);
                long size;
                try { size = Long.parseLong(field(header,124,12).trim(),8); }
                catch (NumberFormatException e) { throw new IOException("TAR 大小字段无效",e); }
                total += size;
                if (size < 0 || total > 768L * 1024 * 1024) throw new IOException("TAR 超出容量限制");
                char type = (char)header[156];
                File dest = new File(stage,name);
                if (!((name.isEmpty() || ".".equals(name)) && type == '5')
                        && (!dest.getCanonicalPath().startsWith(prefix) || name.startsWith("/") || name.indexOf('\\') >= 0 ||
                        Arrays.asList(name.split("/")).contains(".."))) throw new IOException("TAR 路径无效");
                if (type == '5') { mkdir(dest); }
                else if (type == '1' || type == '2') { links.add(new String[]{name,field(header,157,100), Character.toString(type)}); }
                else if (type == 0 || type == '0') {
                    mkdir(dest.getParentFile());
                    if (!dest.createNewFile()) throw new IOException("重复的 TAR 条目 " + name);
                    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(dest))) {
                        long left = size;
                        while (left > 0) {
                            int n = (int)Math.min(left,buf.length);
                            tar.readFully(buf,0,n); out.write(buf,0,n); left -= n;
                            report.update("解压 Java",total-left,-1);
                        }
                    }
                } else { throw new IOException("不支持的 TAR 类型 " + type); }
                if (!(type == 0 || type == '0') && size != 0) throw new IOException("TAR 链接或目录有负载");
                int pad = (int)((512 - size % 512) % 512);
                tar.readFully(buf,0,pad);
            }
        }
        for (String[] link : links) {
            File dest = new File(stage,link[0]);
            File parent = "1".equals(link[2]) ? stage : dest.getParentFile();
            File target = new File(parent,link[1]).getCanonicalFile();
            if (link[1].startsWith("/") || !target.getPath().startsWith(prefix)) throw new IOException("运行时链接越界");
            if (!target.exists()) throw new IOException("无法解析运行时链接 " + link[0]);
            mkdir(dest.getParentFile());
            try {
                if ("1".equals(link[2])) Os.link(target.getPath(),dest.getPath());
                else Os.symlink(link[1],dest.getPath());
            }
            catch (ErrnoException e) { throw new IOException("无法创建运行时文件链接 " + link[0],e); }
        }
    }
    private static String field(byte[] buffer, int start, int length) {
        int end = start;
        while (end < start + length && buffer[end] != 0) end++;
        return new String(buffer,start,end-start,StandardCharsets.UTF_8);
    }
    private static void mkdir(File d) throws IOException {
        if (!d.mkdirs() && !d.isDirectory()) throw new IOException("无法创建 " + d.getName());
    }
}

