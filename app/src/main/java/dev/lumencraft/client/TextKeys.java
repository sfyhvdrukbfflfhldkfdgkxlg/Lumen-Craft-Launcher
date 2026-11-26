package dev.lumencraft.client;

import android.view.KeyEvent;
import java.util.*;

/** Input names from Minecraft options and Android key events, mapped to FCL's evdev-based codes. */
final class TextKeys {
    private static final Map<String,Integer> KEYS=new HashMap<>();
    static {
        row("1 2 3 4 5 6 7 8 9 0 minus equal backspace tab",2);
        row("q w e r t y u i o p left.bracket right.bracket enter left.control",16);
        row("a s d f g h j k l semicolon apostrophe grave.accent left.shift backslash",30);
        row("z x c v b n m comma period slash right.shift keypad.multiply left.alt space caps.lock",44);
        for(int i=1;i<=10;i++)KEYS.put("f"+i,58+i);
        row("f11 f12",87);KEYS.put("escape",1);
        row("keypad.enter right.control keypad.divide print.screen right.alt",96);
        row("home up page.up left right end down page.down insert delete",102);
        KEYS.put("pause",119);KEYS.put("left.win",125);KEYS.put("right.win",126);
    }
    private static void row(String names,int key){for(String name:names.split(" "))KEYS.put(name,key++);}
    static int modern(String name){
        if(name.equals("key.mouse.left"))return 1000;
        if(name.equals("key.mouse.right"))return 1002;
        if(name.equals("key.mouse.middle"))return 1001;
        if(!name.startsWith("key.keyboard."))return 0;
        Integer code=KEYS.get(name.substring(13));return code==null?0:code;
    }
    static int physical(int k) {
        if(k>=KeyEvent.KEYCODE_A && k<=KeyEvent.KEYCODE_Z)return KEYS.get(String.valueOf((char)('a'+k-KeyEvent.KEYCODE_A)));
        if(k>=KeyEvent.KEYCODE_0 && k<=KeyEvent.KEYCODE_9)return KEYS.get(""+(k-KeyEvent.KEYCODE_0));
        if(k>=KeyEvent.KEYCODE_F1 && k<=KeyEvent.KEYCODE_F12)return KEYS.get("f"+(k-KeyEvent.KEYCODE_F1+1));
        switch(k){
            case KeyEvent.KEYCODE_ESCAPE:return 1;case KeyEvent.KEYCODE_SPACE:case KeyEvent.KEYCODE_BUTTON_A:return 57;
            case KeyEvent.KEYCODE_DEL:return 14;case KeyEvent.KEYCODE_ENTER:return 28;
            case KeyEvent.KEYCODE_TAB:return 15;case KeyEvent.KEYCODE_SHIFT_LEFT:return 42;case KeyEvent.KEYCODE_SHIFT_RIGHT:return 54;
            case KeyEvent.KEYCODE_ALT_LEFT:return 56;case KeyEvent.KEYCODE_ALT_RIGHT:return 100;
            case KeyEvent.KEYCODE_CTRL_LEFT:return 29;case KeyEvent.KEYCODE_CTRL_RIGHT:return 97;
            case KeyEvent.KEYCODE_DPAD_UP:return 103;case KeyEvent.KEYCODE_DPAD_DOWN:return 108;
            case KeyEvent.KEYCODE_DPAD_LEFT:return 105;case KeyEvent.KEYCODE_DPAD_RIGHT:return 106;
            case KeyEvent.KEYCODE_FORWARD_DEL:return 111;case KeyEvent.KEYCODE_MOVE_HOME:return 102;case KeyEvent.KEYCODE_MOVE_END:return 107;
            case KeyEvent.KEYCODE_PAGE_UP:return 104;case KeyEvent.KEYCODE_PAGE_DOWN:return 109;case KeyEvent.KEYCODE_INSERT:return 110;
            case KeyEvent.KEYCODE_MINUS:return 12;case KeyEvent.KEYCODE_EQUALS:return 13;case KeyEvent.KEYCODE_COMMA:return 51;
            case KeyEvent.KEYCODE_PERIOD:return 52;case KeyEvent.KEYCODE_GRAVE:return 41;case KeyEvent.KEYCODE_SLASH:return 53;
            case KeyEvent.KEYCODE_BACKSLASH:return 43;case KeyEvent.KEYCODE_LEFT_BRACKET:return 26;case KeyEvent.KEYCODE_RIGHT_BRACKET:return 27;
            case KeyEvent.KEYCODE_SEMICOLON:return 39;case KeyEvent.KEYCODE_APOSTROPHE:return 40;
            case KeyEvent.KEYCODE_BUTTON_B:return 42;case KeyEvent.KEYCODE_BUTTON_X:return 18;case KeyEvent.KEYCODE_BUTTON_Y:return 16;
            case KeyEvent.KEYCODE_BUTTON_START:return 1;
            case KeyEvent.KEYCODE_BUTTON_R1:case KeyEvent.KEYCODE_BUTTON_R2:return 1000;
            case KeyEvent.KEYCODE_BUTTON_L1:case KeyEvent.KEYCODE_BUTTON_L2:return 1002;
            default:return 0;
        }
    }
}
