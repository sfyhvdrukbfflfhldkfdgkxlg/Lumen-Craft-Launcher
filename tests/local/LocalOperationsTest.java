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

            // CrashNotes structured diagnosis
            CrashNotes.Diagnosis oom=CrashNotes.diagnose("java.lang.OutOfMemoryError: Java heap space");
            check("OOM".equals(oom.category) && oom.action==CrashNotes.RepairAction.LOWER_HEAP,"diagnose OOM");
            CrashNotes.Diagnosis missing=CrashNotes.diagnose("java.lang.NoClassDefFoundError: com/example/MissingClass");
            check("MISSING_CLASS".equals(missing.category) && missing.culprit.contains("MissingClass"),"diagnose missing class");
            CrashNotes.Diagnosis gl=CrashNotes.diagnose("GLFW error 65542: WGL/EGL failed");
            check("GRAPHICS".equals(gl.category) && gl.action==CrashNotes.RepairAction.FALLBACK_RENDERER,"diagnose graphics fallback");

            // DeviceProfile recommendations + snapshot rollback
            DeviceProfile.Recommendation rec=DeviceProfile.recommend(8L<<30,4L<<30,false,false,
                    "GL: OpenGL ES 3.2\n1px render/readback: PASS\nVulkan: 1.3.250 · Adreno (integrated)",true);
            check(rec.heapMiB>=512 && rec.rendererBackend.contains("GLES 3.0") && rec.rendererBackend.contains("Vulkan"),"device profile GLES3+Vulkan");
            DeviceProfile.Recommendation low=DeviceProfile.recommend(2L<<30,512L<<20,true,true,"EGL failed",true);
            check(low.maxFps==15 && low.rendererBackend.contains("软件"),"device profile low-ram fallback");
            File snap=root.resolve("config-snap.json").toFile();
            DeviceProfile.saveSnapshot(snap,new org.json.JSONObject().put("low-power",true));
            check(DeviceProfile.loadSnapshot(snap).getBoolean("low-power"),"config snapshot rollback");

            // KeyBindingScanner mod lang + runtime dump
            File modDir=new File(data,"mods"); modDir.mkdirs();
            try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(new File(modDir,"sample.jar")))){
                z.putNextEntry(new ZipEntry("assets/demo/lang/zh_cn.json"));
                z.write("{\"key.demo.fly\":\"飞行开关\"}".getBytes("UTF-8"));
                z.closeEntry();
            }
            Map<String,String> labels=KeyBindingScanner.scanModKeyLabels(data);
            check("飞行开关".equals(labels.get("key.demo.fly")),"scan mod zh_cn keybinding labels");
            Files.write(new File(data,".lumen-keybindings.json").toPath(),
                    "{\"bindings\":[{\"id\":\"key.demo.fly\",\"label\":\"飞行开关\",\"category\":\"模组\",\"fclCode\":33}]}".getBytes("UTF-8"));
            check(KeyBindingScanner.readRuntimeBindings(data).size()==1,"read runtime keybinding bridge dump");
        } finally {
            try(java.util.stream.Stream<Path> files=Files.walk(root)){for(Path p:(Iterable<Path>)files.sorted(Comparator.reverseOrder())::iterator)Files.delete(p);}
        }
    }
}
