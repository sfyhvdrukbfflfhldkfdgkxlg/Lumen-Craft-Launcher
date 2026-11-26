package dev.lumencraft.client;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Small independent reader/writer of the FCL v21 controller interchange format. */
final class ControlProfile {
    JSONObject document;
    final ArrayList<Button> buttons=new ArrayList<>();
    int skipped;
    private static JSONObject object() { return new JSONObject(); }
    static final class Button {
        final JSONObject original, base;
        final int[] press,click,hold,twice;
        int[][] directions;
        String text,visibility,id;
        boolean follows,repeat;
        Button(JSONObject o,boolean stick)throws JSONException {
            original=o; id=o.getString("id"); base=o.getJSONObject("baseInfo");
            visibility=base.optString("visibilityType","ALWAYS"); text=o.optString("text","键");
            JSONObject event=o.getJSONObject("event"); follows=event.optBoolean("pointerFollow");
            press=codes(event.optJSONObject("pressEvent")); click=codes(event.optJSONObject("clickEvent"));
            hold=codes(event.optJSONObject("longPressEvent")); twice=codes(event.optJSONObject("doubleClickEvent"));
            JSONObject p=event.optJSONObject("pressEvent"); repeat=p!=null&&p.optBoolean("autoClick");
            if (stick) {
                directions=new int[4][];
                int i=0;
                for(String k:new String[]{"upKeycode","downKeycode","leftKeycode","rightKeycode"})
                    directions[i++]=codes(event.optJSONArray(k));
            }
        }
        boolean active(){ if(directions!=null) for(int[] dir:directions) if(dir.length>0)return true;
            return press.length+click.length+hold.length+twice.length>0; }
        private static int[] codes(JSONObject event){ return codes(event==null?null:event.optJSONArray("outputKeycodes")); }
        private static int[] codes(JSONArray array){
            if(array==null)return new int[0];
            ArrayList<Integer> valid=new ArrayList<>();
            for(int i=0;i<Math.min(array.length(),16);i++){int code=array.optInt(i,0); if(toLwjgl(code)!=0&&!valid.contains(code))valid.add(code);}
            int[] out=new int[valid.size()];for(int i=0;i<out.length;i++)out[i]=valid.get(i);return out;
        }
        int x(){return clamp(base.optInt("xPosition",0),0,1000);}
        int y(){return clamp(base.optInt("yPosition",0),0,1000);}
        int dim(boolean horizontal,int w,int h,float density){
            String axis=horizontal?"Width":"Height";
            if("ABSOLUTE".equals(base.optString("sizeType")))
                return (int)(clamp(base.optInt("absolute"+axis,48),8,800)*density);
            JSONObject def=base.optJSONObject("percentage"+axis);
            if(def==null)return (int)(48*density);
            int ref="SCREEN_WIDTH".equals(def.optString("reference"))?w:h;
            return Math.max(1,ref*clamp(def.optInt("size",120),1,1000)/1000);
        }
    }

    static int clamp(int n,int lo,int hi){return Math.max(lo,Math.min(hi,n));}
    static int toLwjgl(int fcl) {
        if(fcl>=1&&fcl<=83 || fcl==87||fcl==88 || fcl>=1000&&fcl<=1002)return fcl;
        switch(fcl){
            case 96:return 156;case 97:return 157;case 98:return 181;case 99:return 183;case 100:return 184;
            case 102:return 199;case 103:return 200;case 104:return 201;case 105:return 203;case 106:return 205;
            case 107:return 207;case 108:return 208;case 109:return 209;case 110:return 210;case 111:return 211;
            case 119:return 197;case 125:return 219;case 126:return 220;default:return 0;
        }
    }
    static int fromLwjgl(int lwjgl){if(lwjgl<=0)return 0;for(int i=1;i<256;i++)if(toLwjgl(i)==lwjgl)return i;return 0;}
    ControlProfile(JSONObject raw)throws Exception {
        if(raw.optInt("controllerVersion")!=21)throw new IOException("当前支持 FCL controllerVersion 21");
        document=raw;JSONArray groups=raw.getJSONArray("viewGroups");
        if(groups.length()>64)throw new IOException("控制分组过多");
        for(int i=0;i<groups.length();i++){
            JSONObject g=groups.getJSONObject(i);
            if(!"VISIBLE".equals(g.optString("visibility","VISIBLE")))continue;
            JSONObject views=g.getJSONObject("viewData");
            for(String key:new String[]{"buttonList","directionList"}){
                JSONArray list=views.optJSONArray(key); if(list==null)continue;
                if(list.length()+buttons.size()>256)throw new IOException("按键过多");
                for(int j=0;j<list.length();j++){
                    Button b=new Button(list.getJSONObject(j),key.equals("directionList"));
                    if(b.active())buttons.add(b);else skipped++;
                }
            }
        }
        if(buttons.isEmpty())throw new IOException("布局中没有支持的按键或方向盘");
    }
    private static JSONObject base(int x,int y,int width,int height)throws JSONException{
        return object().put("visibilityType","ALWAYS").put("xPosition",x).put("yPosition",y)
            .put("sizeType","PERCENTAGE").put("absoluteWidth",48).put("absoluteHeight",48)
            .put("percentageWidth",object().put("reference","SCREEN_HEIGHT").put("size",width))
            .put("percentageHeight",object().put("reference","SCREEN_HEIGHT").put("size",height));
    }
    private static JSONObject action(int... keys)throws JSONException{
        JSONArray a=new JSONArray();for(int k:keys)a.put(k);
        return object().put("autoKeep",false).put("autoClick",false).put("openMenu",false).put("switchTouchMode",false)
            .put("input",false).put("quickInput",false).put("outputText","").put("outputKeycodes",a).put("bindViewGroup",new JSONArray());
    }
    private static JSONObject button(String name,int x,int y,int key)throws JSONException{
        return object().put("id",UUID.randomUUID().toString()).put("text",name).put("style","Lumen")
            .put("baseInfo",base(x,y,147,128)).put("event",object().put("pointerFollow",key>=1000)
            .put("Movable",false).put("pressEvent",action(key)).put("clickEvent",action())
            .put("longPressEvent",action()).put("doubleClickEvent",action()));
    }
    private static JSONObject group()throws JSONException{
        return object().put("id",UUID.randomUUID().toString()).put("name","Lumen").put("visibility","VISIBLE")
            .put("viewData",object().put("buttonList",new JSONArray()).put("directionList",new JSONArray()));
    }
    static ControlProfile defaults()throws Exception{
        JSONObject g=group(),views=g.getJSONObject("viewData");
        JSONArray buttons=views.getJSONArray("buttonList");
        String[] labels={"保存","跳","拆除","放置"};
        int[] keys={28,57,1002,1000};
        int[] x={12,940,737,840}, y={12,800,850,620};
        for(int i=0;i<labels.length;i++)buttons.put(button(labels[i],x[i],y[i],keys[i]));
        JSONObject dir=object().put("id",UUID.randomUUID().toString()).put("style","Lumen")
            .put("baseInfo",base(50,875,385,385)).put("event",object()
            .put("upKeycode",new JSONArray().put(17)).put("downKeycode",new JSONArray().put(31))
            .put("leftKeycode",new JSONArray().put(30)).put("rightKeycode",new JSONArray().put(32))
            .put("followOption","FIXED").put("sneak",false).put("sneakKeycode",42));
        views.getJSONArray("directionList").put(dir);
        JSONObject style=object().put("name","Lumen").put("textSize",12).put("textColor",-1)
            .put("strokeWidth",6).put("strokeColor",0x668ba7a6).put("cornerRadius",240).put("fillColor",0x72233437)
            .put("textSizePressed",12).put("textColorPressed",-1).put("strokeWidthPressed",6).put("strokeColorPressed",-1)
            .put("cornerRadiusPressed",240).put("fillColorPressed",0xbb43766c);
        return new ControlProfile(object().put("id",UUID.randomUUID().toString()).put("name","Lumen")
            .put("version","1.0").put("versionCode",1).put("author","LumenCraft").put("description","")
            .put("controllerVersion",21).put("buttonStyles",new JSONArray().put(style))
            .put("directionStyles",new JSONArray().put(object().put("name","Lumen").put("styleType","BUTTON")
            .put("buttonStyle",new JSONObject(style.toString()).put("interval",40)).put("rockerStyle",object())))
            .put("viewGroups",new JSONArray().put(g)));
    }

    boolean addBinding(String name,int code,int x,int y)throws Exception{
        String id=UUID.nameUUIDFromBytes(("minecraft-options/"+name).getBytes(StandardCharsets.UTF_8)).toString();
        for(Button b:buttons)if(id.equals(b.id))return false;
        if(buttons.size()>=256)throw new IOException("按键过多");
        JSONArray groups=document.getJSONArray("viewGroups");JSONObject target=null;
        for(int i=0;i<groups.length();i++) if("Lumen.bindings".equals(groups.getJSONObject(i).optString("name")))target=groups.getJSONObject(i);
        if(target==null) {target=group().put("name","Lumen.bindings");groups.put(target);}
        JSONObject b=button(name.substring(name.lastIndexOf('.')+1),x,y,code).put("id",id);
        JSONArray styles=document.optJSONArray("buttonStyles");
        if(styles!=null && styles.length()>0)b.put("style",styles.getJSONObject(0).getString("name"));
        target.getJSONObject("viewData").getJSONArray("buttonList").put(b);
        buttons.add(new Button(b,false));return true;
    }

    static Map<String,Integer> bindings(File options)throws IOException{
        Map<String,Integer> out=new LinkedHashMap<>();
        if(!options.isFile())return out;
        if(options.length()>2<<20)throw new IOException("options.txt 过大");
        try(BufferedReader in=new BufferedReader(new InputStreamReader(new FileInputStream(options),StandardCharsets.UTF_8))){
            for(String l;(l=in.readLine())!=null;){
                if(!l.startsWith("key_"))continue; int colon=l.indexOf(':');if(colon<5)continue;
                String name=l.substring(4,colon),value=l.substring(colon+1).trim();
                int key=0;
                try{
                    int n=Integer.parseInt(value);
                    key=n>=0?fromLwjgl(n):n==-100?1000:n==-99?1002:n==-98?1001:0;
                }catch(NumberFormatException ignored){key=TextKeys.modern(value);}
                if(key>0)out.put(name,key);
            }
        }
        return out;
    }
}
