import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import java.util.*;
/** Local QA tool. Only returns non-secret status fields, never the room code. */
public final class ReadRoomState {
    public static void main(String[] args)throws Exception{
        AttachingConnector conn=Bootstrap.virtualMachineManager().attachingConnectors().stream()
            .filter(c->c.name().equals("com.sun.jdi.SocketAttach")).findFirst().get();
        Map<String,Connector.Argument> a=conn.defaultArguments();a.get("hostname").setValue("127.0.0.1");
        a.get("port").setValue(args[0]);a.get("timeout").setValue("8000");
        VirtualMachine vm=conn.attach(a);
        try{
            List<ReferenceType> classes=vm.classesByName("dev.lumencraft.rooms.RoomService");
            if(classes.isEmpty())throw new IllegalStateException("RoomService not loaded");
            ReferenceType t=classes.get(0);
            for(String name:new String[]{"state","metadata","error"}){
                Field field=t.fieldByName(name);Value v=t.getValue(field);
                System.out.println(name+"="+v);
            }
            StringReference invite=(StringReference)t.getValue(t.fieldByName("room"));
            System.out.println("invitePresent="+(invite!=null&&!invite.value().isEmpty()));
            ReferenceType jni=vm.classesByName("dev.lumencraft.rooms.NativeRooms").get(0);
            ObjectReference service=(ObjectReference)jni.getValue(jni.fieldByName("service"));
            System.out.println("vpnDescriptorPresent="+(service!=null && service.getValue(t.fieldByName("tunnel"))!=null));
        }finally{vm.dispose();}
    }
}
