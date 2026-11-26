package dev.lumencraft.core;

import org.json.*;
import java.io.*;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Per-instance, recoverable publication of a verified dependency set. */
final class ModrinthFiles {
    private ModrinthFiles() {}
    private static final String HEX = "0123456789abcdef";

    static synchronized List<File> install(Modrinth.Source source, String project, String type, String game,
                                            String loader, File instance) throws Exception {
        if (game == null || game.trim().isEmpty()) throw new IOException("请选择已安装的游戏版本");
        if (!instance.isDirectory()) throw new IOException("实例目录不存在");
        File root = instance.getCanonicalFile();
        File state = path(root, ".lumen-modrinth");
        mkdir(state);
        try (RandomAccessFile gate = new RandomAccessFile(path(state,"install.lock"),"rw");
             FileLock ignored = gate.getChannel().lock()) {
            return transact(source, project, type, game, loader, root, state);
        }
    }

    private static List<File> transact(Modrinth.Source source, String project, String type, String game,
                                       String loader, File root, File state) throws Exception {
        File index=path(state,"index.json"), backup=path(state,"previous.json");
        File journal=path(state,"pending.json"), stage=path(state,"staging");
        mkdir(stage);
        if (!index.exists() && backup.exists()) move(backup,index);
        JSONObject previous = index.exists() ? json(index) : new JSONObject().put("projects",new JSONObject());
        if (journal.exists()) recover(root, previous, journal);
        remove(backup);
        clearStage(stage);
        JSONObject installed = previous.getJSONObject("projects");
        Modrinth.Resolver resolver = new Modrinth.Resolver(source,game,loader,installed);
        resolver.add(project,null,type);
        JSONObject merged = new JSONObject(installed.toString());
        for (Modrinth.Entry entry : resolver.entries.values()) merged.put(entry.project,entry.receipt());
        resolver.checkConflicts(merged);
        String transaction = UUID.randomUUID().toString();
        JSONObject next = new JSONObject().put("transaction",transaction).put("projects",merged);
        List<File> targets=new ArrayList<>();
        JSONArray additions = new JSONArray();
        try {
            for (Modrinth.Entry entry : resolver.entries.values()) {
                File target = path(root,entry.path);
                targets.add(target);
                if (target.exists()) {
                    verify(target,entry.hash,entry.size);
                    continue;
                }
                File staged=path(stage,entry.project+".part");
                try (InputStream in=source.file(entry.url); FileOutputStream out=new FileOutputStream(staged)) {
                    String digest = digest(in,out,entry.size);
                    out.getFD().sync();
                    if (!digest.equals(entry.hash) || staged.length()!=entry.size) throw new IOException("SHA-512 或长度不符 "+entry.path);
                }
                additions.put(new JSONObject().put("path",entry.path).put("hash",entry.hash)
                    .put("size",entry.size).put("staged",staged.getName()));
            }
            File pendingTmp=path(stage,"pending.tmp");
            write(pendingTmp,new JSONObject().put("transaction",transaction).put("files",additions));
            move(pendingTmp,journal);
            for (int i=0; i<additions.length(); i++) {
                JSONObject add=additions.getJSONObject(i);
                File target=path(root,add.getString("path"));
                mkdir(target.getParentFile());
                move(path(stage,add.getString("staged")),target);
            }
            File nextIndex=path(stage,"next.tmp");
            write(nextIndex,next);
            if (index.exists()) move(index,backup);
            move(nextIndex,index);
        } finally {
            if (!index.exists() && backup.exists()) move(backup,index);
            if (journal.exists()) recover(root,index.exists()?json(index):previous,journal);
            remove(backup);
            clearStage(stage);
        }
        return Collections.unmodifiableList(targets);
    }

    private static void recover(File root,JSONObject current, File journal) throws Exception {
        JSONObject pending=json(journal);
        if (!pending.getString("transaction").equals(current.optString("transaction"))) {
            JSONArray files=pending.getJSONArray("files");
            for (int i=0; i<files.length(); i++) {
                JSONObject add=files.getJSONObject(i);
                String name=add.getString("path");
                if (!name.matches("(mods|resourcepacks|shaderpacks)/[^/\\\\]+")) throw new IOException("恢复记录路径无效");
                // A staged file disappearing is the evidence our rename was actually published.
                File staged=path(path(journal.getParentFile(),"staging"),add.getString("staged"));
                File target=path(root,name);
                if (!staged.exists() && target.exists()) {
                    verify(target,add.getString("hash"),add.getLong("size"));
                    remove(target);
                }
            }
        }
        remove(journal);
    }

    private static JSONObject json(File file) throws Exception {
        return new JSONObject(Modrinth.read(new FileInputStream(file),16*1024*1024));
    }
    private static void write(File file, JSONObject value) throws IOException {
        try (FileOutputStream out=new FileOutputStream(file)) {
            out.write(value.toString().getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
        }
    }
    private static void mkdir(File file) throws IOException {
        if (!file.isDirectory() && !file.mkdirs() && !file.isDirectory()) throw new IOException("无法创建目录 "+file.getName());
    }
    private static File path(File root,String relative) throws IOException {
        File child=new File(root,relative);
        if (!child.getCanonicalFile().equals(child.getAbsoluteFile()) ||
            !child.getPath().startsWith(root.getPath()+File.separator)) throw new IOException("资源路径超出目录或经过符号链接");
        return child;
    }
    private static void move(File from, File to) throws IOException {
        if (to.exists() || !from.renameTo(to)) throw new IOException("无法提交文件 "+to.getName());
    }
    private static void remove(File file) throws IOException {
        if (file.exists() && !file.delete()) throw new IOException("无法清理 "+file.getName());
    }
    private static void clearStage(File stage) throws IOException {
        File[] files=stage.listFiles();
        if (files==null) throw new IOException("无法读取暂存目录");
        for (File file : files) {
            if (file.isDirectory() || !file.getCanonicalFile().equals(file.getAbsoluteFile()))
                throw new IOException("暂存目录存在未知内容");
            remove(file);
        }
    }
    private static void verify(File target,String hash,long size) throws Exception {
        if (!target.isFile() || target.length()!=size) throw new IOException("目标文件长度冲突 "+target.getName());
        try (InputStream in=new FileInputStream(target)) {
            if (!hash.equals(digest(in,null,size))) throw new IOException("目标文件内容冲突 "+target.getName());
        }
    }
    private static String digest(InputStream in,OutputStream out,long limit) throws Exception {
        MessageDigest md=MessageDigest.getInstance("SHA-512");
        byte[] buffer=new byte[32768]; long count=0; int n;
        while ((n=in.read(buffer))!=-1) {
            count+=n;
            if (count>limit) throw new IOException("下载数据超过声明长度");
            md.update(buffer,0,n);
            if (out!=null) out.write(buffer,0,n);
        }
        StringBuilder hex=new StringBuilder(128);
        for (byte b:md.digest()) hex.append(HEX.charAt((b&255)>>>4)).append(HEX.charAt(b&15));
        return hex.toString();
    }
}
