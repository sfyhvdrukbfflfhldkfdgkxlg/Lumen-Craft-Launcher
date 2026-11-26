package dev.lumencraft.core;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
public final class LocalOperationsTest {
    private static void check(boolean ok,String name){ if(!ok)throw new AssertionError(name);System.out.println("PASS "+name); }
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("lumen-local-");
        try {
            File data=root.resolve("instances/original").toFile();
            File world=new File(data,"saves/Test World");if(!world.mkdirs())throw new IOException();
            Files.write(new File(data,"install.json").toPath(),"{}".getBytes("UTF-8"));
            Files.write(new File(world,"level.dat").toPath(),new byte[]{4,5,6});
            ByteArrayOutputStream zip=new ByteArrayOutputStream();
            check(LocalFiles.backup(world.getParentFile(),zip)==1,"save backup");
            check(LocalFiles.restore(new ByteArrayInputStream(zip.toByteArray()),world.getParentFile()).get(0).equals("Test World-restored-1"),"restore without overwrite");
            File copy=InstanceFiles.duplicate(root.toFile(),data);
            check(Arrays.equals(Files.readAllBytes(new File(copy,"saves/Test World/level.dat").toPath()),new byte[]{4,5,6}),"clone preserves world bytes");
            File archived=InstanceFiles.move(root.toFile(),copy,false);
            check(!copy.exists() && archived.isDirectory(),"archive");
            check(InstanceFiles.move(root.toFile(),archived,true).equals(copy),"restore archived instance");
            zip.reset();
            try(ZipOutputStream out=new ZipOutputStream(zip)){out.putNextEntry(new ZipEntry("../escape"));out.write(1);}
            boolean failed=false;try{LocalFiles.restore(new ByteArrayInputStream(zip.toByteArray()),world.getParentFile());}catch(IOException e){failed=true;}
            check(failed && !new File(data,"escape").exists(),"unsafe backup rejected");
            boolean invalid=false;try{LocalFiles.importJar(new ByteArrayInputStream(zip.toByteArray()),new File(data,"mods"));}catch(IOException e){invalid=true;}
            check(invalid,"plain zip rejected as mod");
            zip.reset();try(ZipOutputStream out=new ZipOutputStream(zip)){out.putNextEntry(new ZipEntry("fabric.mod.json"));out.write("{}".getBytes("UTF-8"));}
            File jar=LocalFiles.importJar(new ByteArrayInputStream(zip.toByteArray()),new File(data,"mods"));
            check(jar.equals(LocalFiles.importJar(new ByteArrayInputStream(zip.toByteArray()),new File(data,"mods"))),"same mod imported idempotently");
            zip.reset();
            try(GZIPOutputStream out=new GZIPOutputStream(zip)){out.write(new byte[4194304]);}
            ClassicSaves.restore(data,new ByteArrayInputStream(zip.toByteArray()));
            ClassicSaves.checkpoint(data);
            ClassicSaves.checkpoint(data);
            check(new File(data,"world-backups").list().length==1,"identical RubyDung world rotates once");
            byte[] before=Files.readAllBytes(new File(data,"level.dat").toPath());
            try {ClassicSaves.restore(data,new ByteArrayInputStream(new byte[]{0}));throw new AssertionError("accepted invalid world");}catch(IOException expected){}
            check(Arrays.equals(before,Files.readAllBytes(new File(data,"level.dat").toPath())),"invalid world cannot replace live save");
        } finally {
            try(java.util.stream.Stream<Path> files=Files.walk(root)){for(Path p:(Iterable<Path>)files.sorted(Comparator.reverseOrder())::iterator)Files.delete(p);}
        }
    }
}
