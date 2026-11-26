package dev.lumencraft.core;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Local protocol and installer integration tests, with an optional separate live API check. */
public final class NetworkTest {
    private static int passed;
    private static void check(String name,boolean ok) {
        if (!ok) throw new AssertionError(name);
        System.out.println("PASS "+name); passed++;
    }
    interface Action { void run() throws Exception; }
    private static void rejects(String name,Action action) throws Exception {
        try { action.run(); } catch (IOException expected) { check(name,true); return; }
        throw new AssertionError(name);
    }
    public static void main(String[] args) throws Exception {
        endpointAndSrv();
        status();
        installer();
        if (Arrays.asList(args).contains("--live")) live();
        System.out.println("Passed "+passed+" checks");
    }
    private static void endpointAndSrv() throws Exception {
        check("bracketed IPv6",ServerProbe.parse("[::1]:25570").port==25570);
        check("bare domain requests SRV",ServerProbe.parse("play.example.com").lookup);
        check("explicit port bypasses SRV",!ServerProbe.parse("play.example.com:25565").lookup);
        rejects("reject URL",()->ServerProbe.parse("http://example.org/world"));
        rejects("reject invalid port",()->ServerProbe.parse("a:0"));
        rejects("reject invalid ipv6",()->ServerProbe.parse("[1::2::3]"));
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        DataOutputStream d=new DataOutputStream(bytes);
        int id=49111;
        String name="_minecraft._tcp.example.org";
        d.writeShort(id);d.writeShort(0x8180);d.writeShort(1);d.writeShort(1);d.writeShort(0);d.writeShort(0);
        for(String label:name.split("\\.")){d.writeByte(label.length());d.writeBytes(label);}
        d.writeByte(0);d.writeShort(33);d.writeShort(1);
        d.writeShort(0xc00c);d.writeShort(33);d.writeShort(1);d.writeInt(60);
        d.writeShort(22);d.writeShort(5);d.writeShort(11);d.writeShort(25599);
        d.writeByte(2);d.writeBytes("mc");d.writeByte(7);d.writeBytes("example");d.writeByte(3);d.writeBytes("org");d.writeByte(0);
        SrvLookup.Record r=SrvLookup.parse(bytes.toByteArray(),id,name).get(0);
        check("SRV compressed owner, target and port",r.host.equals("mc.example.org")&&r.port==25599);
        List<ServerProbe.Endpoint> order=SrvLookup.order(Arrays.asList(new SrvLookup.Record("later",1,8,100),r));
        check("SRV priority route order",order.get(0).host.equals(r.host));
        rejects("DNS response bound to question",()->SrvLookup.parse(bytes.toByteArray(),id,"different.example"));
        rejects("DNS truncated answer",()->SrvLookup.parse(Arrays.copyOf(bytes.toByteArray(),35),id,name));
    }
    private static void status() throws Exception {
        InetAddress loop=InetAddress.getByName("127.0.0.1");
        try (ServerSocket local=new ServerSocket(0,1,loop)) {
            ExecutorService exec=Executors.newSingleThreadExecutor();
            Future<?> peer=exec.submit(()->{
                try(Socket s=local.accept()){
                    s.setSoTimeout(5000);
                    DataInputStream in=new DataInputStream(s.getInputStream());
                    DataOutputStream out=new DataOutputStream(s.getOutputStream());
                    byte[] handshake=new byte[ServerProbe.varInt(in)];in.readFully(handshake);
                    DataInputStream h=new DataInputStream(new ByteArrayInputStream(handshake));
                    if(ServerProbe.varInt(h)!=0||ServerProbe.varInt(h)!=-1)throw new AssertionError("handshake");
                    byte[] host=new byte[ServerProbe.varInt(h)];h.readFully(host);
                    if(!new String(host,StandardCharsets.UTF_8).equals("127.0.0.1") ||
                        h.readUnsignedShort()!=local.getLocalPort()||h.readByte()!=1)throw new AssertionError("target");
                    if(in.readByte()!=1||in.readByte()!=0)throw new AssertionError("status request");
                    String json="{\"version\":{\"name\":\"1.20.1\",\"protocol\":763},\"players\":{\"online\":2,\"max\":9},\"description\":{\"text\":\"§b你好\",\"extra\":[{\"text\":\"!\"}]},\"forgeData\":{}}";
                    byte[] data=json.getBytes(StandardCharsets.UTF_8);
                    ByteArrayOutputStream frame=new ByteArrayOutputStream();DataOutputStream f=new DataOutputStream(frame);
                    f.writeByte(0);ServerProbe.varInt(f,data.length);f.write(data);
                    ServerProbe.varInt(out,frame.size());frame.writeTo(out);out.flush();
                    if(in.readByte()!=9||in.readByte()!=1)throw new AssertionError("ping");
                    out.writeByte(9);out.writeByte(1);out.writeLong(in.readLong());out.flush();
                    return null;
                }
            });
            try{
                ServerProbe.Status st=ServerProbe.probe("127.0.0.1:"+local.getLocalPort());
                peer.get(5,TimeUnit.SECONDS);
                check("TCP handshake, status, ping/pong",st.protocol==763&&st.online==2&&st.max==9&&st.latencyMs>=0);
                check("formatted nested MOTD",st.motd.equals("你好!"));
                check("Forge status hint",st.loaderHint.equals("Forge/FML"));
                check("exact official id match","1.20.1".equals(st.exactVersion(Arrays.asList("1.20.1"))));
                check("no version guessed",st.exactVersion(Arrays.asList("1.20","1.20.2"))==null);
            }finally{exec.shutdownNow();}
        }
    }

    private static final class Catalog implements Modrinth.Source {
        final Map<String,String> meta=new HashMap<>();
        final Map<String,byte[]> data=new HashMap<>();
        int downloads;
        public String json(String path)throws IOException{
            int q=path.indexOf('?');String body=meta.get(q<0?path:path.substring(0,q));
            if(body==null)throw new IOException("unknown "+path);
            return body;
        }
        public InputStream file(String url)throws IOException{
            downloads++;if(!data.containsKey(url))throw new IOException("unknown "+url);
            return new ByteArrayInputStream(data.get(url));
        }
        JSONObject dependency(String type,String project,String version)throws Exception{
            return new JSONObject().put("dependency_type",type).put("project_id",project).put("version_id",version);
        }
        void entry(String project,String version,String side,JSONArray deps)throws Exception{
            meta.put("/project/"+project,new JSONObject().put("id",project).put("project_type","mod").put("client_side",side).toString());
            byte[] content=version.getBytes(StandardCharsets.UTF_8);data.put(version,content);
            StringBuilder digest=new StringBuilder();
            for(byte b:MessageDigest.getInstance("SHA-512").digest(content))digest.append(String.format("%02x",b));
            JSONObject f=new JSONObject().put("filename",project+".jar").put("size",content.length).put("url",version)
                .put("hashes",new JSONObject().put("sha512",digest.toString())).put("primary",true);
            JSONObject v=new JSONObject().put("id",version).put("project_id",project).put("version_type","release")
                .put("files",new JSONArray().put(f)).put("dependencies",deps)
                .put("game_versions",new JSONArray().put("1.20.1")).put("loaders",new JSONArray().put("fabric"));
            meta.put("/version/"+version,v.toString());
            meta.put("/project/"+project+"/version",new JSONArray().put(v).toString());
        }
        List<File> install(String p,File root)throws Exception{
            return ModrinthFiles.install(this,p,"mod","1.20.1","fabric",root);
        }
    }
    private static void installer()throws Exception{
        Catalog c=new Catalog();
        c.entry("leaf","vLeaf","required",new JSONArray());
        c.entry("base","vBase","required",new JSONArray().put(c.dependency("required","leaf",null)));
        c.entry("app","vApp","optional",new JSONArray().put(c.dependency("required",null,"vBase"))
            .put(c.dependency("optional","ignored",null)));
        Path tmp=Files.createTempDirectory("lumencraft-network-test");
        try{
            File root=tmp.toFile();
            List<File> installed=c.install("app",root);
            check("transitive and pinned client dependencies",installed.size()==3 && c.downloads==3);
            c.install("app",root);
            check("verified repeat install reuses files",c.downloads==3);
            c.entry("bad","vBad","required",new JSONArray().put(c.dependency("incompatible","base",null)));
            rejects("declared project incompatibility",()->c.install("bad",root));
            c.entry("server","vServer","unsupported",new JSONArray());
            rejects("exclude server-only dependency",()->c.install("server",root));
            rejects("reject incompatible game",()->ModrinthFiles.install(c,"app","mod","1.21","fabric",root));
            c.entry("extra","vExtra","optional",new JSONArray().put(c.dependency("required","broken",null)));
            c.entry("broken","vBroken","optional",new JSONArray());
            c.data.put("vBroken","invalid".getBytes(StandardCharsets.UTF_8));
            rejects("SHA-512 integrity failure",()->c.install("extra",root));
            check("failed graph published no files",!new File(root,"mods/extra.jar").exists() && !new File(root,"mods/broken.jar").exists());
            c.entry("clash","vClash","optional",new JSONArray());
            Files.write(tmp.resolve("mods/clash.jar"),new byte[]{1,2});
            rejects("preserve user file on filename conflict",()->c.install("clash",root));
            check("user file retained",Files.size(tmp.resolve("mods/clash.jar"))==2);
        }finally{clean(tmp);}
    }
    private static void live()throws Exception{
        check("live Modrinth search",!Modrinth.search("Sodium","mod").isEmpty());
        Path tmp=Files.createTempDirectory("lumencraft-modrinth-live");
        try{
            List<File> files=Modrinth.install("AANobbMI","mod","1.21.1","fabric",tmp.toFile());
            check("live Sodium client download + SHA-512",files.size()>0 && files.get(0).length()>0);
            System.out.println("Live artifact "+files.get(0).getName()+" "+files.get(0).length()+" bytes");
        }finally{clean(tmp);}
    }
    private static void clean(Path tmp)throws IOException{
        try(java.util.stream.Stream<Path> tree=Files.walk(tmp)){
            Path[] paths=tree.sorted(Comparator.reverseOrder()).toArray(Path[]::new);
            for(Path p:paths)Files.delete(p);
        }
    }
}
