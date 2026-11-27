package dev.lumencraft.client;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import org.json.*;

public final class ControlsTest {
    static int count;
    static void check(String name,boolean ok){if(!ok)throw new AssertionError(name);count++;System.out.println("PASS "+name);}
    public static void main(String[] args)throws Exception {
        ControlProfile defaults=ControlProfile.defaults();
        check("RubyDung controls and stick",defaults.buttons.size()==5);
        check("round trip default v21",new ControlProfile(new JSONObject(defaults.document.toString())).buttons.size()==5);
        ControlProfile.Button stick=defaults.buttons.get(4);
        check("direction codes",stick.directions[0][0]==17&&stick.directions[3][0]==32);
        check("FCL proportional size",stick.dim(true,1600,900,1.5f)==346);
        check("extended arrow evdev to LWJGL2",ControlProfile.toLwjgl(103)==200&&ControlProfile.fromLwjgl(200)==103);
        check("physical Android A",TextKeys.physical(29)==30);
        check("GLFW translation keys",TextKeys.modern("key.keyboard.left.shift")==42&&TextKeys.modern("key.keyboard.f12")==88);
        Path options=Files.createTempFile("lumen-input",".txt");
        try{
            Files.write(options,("key_key.forward:17\nkey_mod.feature:key.keyboard.f8\nkey_mod.use:key.mouse.right\nkey_mod.arrow:200\nkey_unbound:0\n")
                .getBytes(StandardCharsets.UTF_8));
            Map<String,Integer> bindings=ControlProfile.bindings(options.toFile());
            check("options.txt recognized only bound inputs",bindings.size()==4&&bindings.get("mod.arrow")==103&&bindings.get("mod.use")==1002);
        }finally{Files.delete(options);}
        JSONObject initial=new JSONObject(defaults.buttons.get(0).original.toString());
        check("add chosen mod binding",defaults.addBinding("mod.feature","飞行开关",67,300,350));
        check("localized label preserved","飞行开关".equals(defaults.buttons.get(defaults.buttons.size()-1).text));
        check("generated IDs prevent duplicate buttons",!defaults.addBinding("mod.feature",67,600,650));
        check("user layout preserved",defaults.buttons.get(0).original.toString().equals(initial.toString()));
        if(args.length==1){
            JSONObject fcl=new JSONObject(new String(Files.readAllBytes(Paths.get(args[0])),StandardCharsets.UTF_8));
            ControlProfile p=new ControlProfile(fcl);
            check("official controllerVersion 21 parse",p.buttons.size()>4&&p.skipped>0);
            check("official file round-trip preserves unknown fields",new JSONObject(p.document.toString()).toString().equals(fcl.toString()));
            System.out.println("Official default recognized "+p.buttons.size()+" active controls, skipped "+p.skipped+" non-key controls.");
        }
        System.out.println("Passed "+count+" control profile checks");
    }
}
