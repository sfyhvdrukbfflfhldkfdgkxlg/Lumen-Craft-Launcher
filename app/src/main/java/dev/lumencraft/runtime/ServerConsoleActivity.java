package dev.lumencraft.runtime;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.widget.*;
import java.io.File;
import java.io.IOException;

public final class ServerConsoleActivity extends Activity {
    private static final int PICK_JRE = 17;
    private File jar;
    private int major;
    private boolean installing;
    private String installStatus = "";
    private boolean available;
    private final Handler handler = new Handler();
    private TextView runtime, output;
    private ScrollView scroll;
    private Button download, pick, start, stop, send;
    private EditText heap, command;
    private int revision = -1;
    private final Runnable tick = new Runnable() {
        @Override public void run() { refresh(); handler.postDelayed(this, 600); }
    };

    public static void open(Activity activity, File jar, int major) {
        activity.startActivity(new Intent(activity, ServerConsoleActivity.class)
                .putExtra(ServerService.JAR, jar.getAbsolutePath()).putExtra(ServerService.JAVA, major));
    }
    @Override protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(dev.lumencraft.ui.Appearance.attach(base));
    }
    @Override public void onCreate(Bundle state) {
        dev.lumencraft.ui.Appearance.apply(this);
        super.onCreate(state);
        major = getIntent().getIntExtra(ServerService.JAVA, 0);
        try {
            jar = ServerService.checkedJar(this, getIntent().getStringExtra(ServerService.JAR));
            if (major < 8 || major > 99) throw new IOException("缺少有效的 Java 版本要求");
        } catch (IOException e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); finish(); return; }
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        LinearLayout page = new LinearLayout(this);
        Configuration config = getResources().getConfiguration();
        boolean wide = config.screenWidthDp >= 600 || config.orientation == Configuration.ORIENTATION_LANDSCAPE;
        page.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        int margin = dp(10);
        page.setPadding(margin, margin, margin, margin);
        page.setFocusableInTouchMode(true);
        setContentView(page);
        if (Build.VERSION.SDK_INT >= 35) {
            page.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets safe = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout() | android.view.WindowInsets.Type.ime());
                v.setPadding(margin + safe.left, margin + safe.top, margin + safe.right, margin + safe.bottom);
                return insets;
            });
        }
        ScrollView sidebar = new ScrollView(this);
        sidebar.setFillViewport(true);
        int sideWidth = Math.min(dp(224), getResources().getDisplayMetrics().widthPixels / 3);
        page.addView(sidebar, wide ? new LinearLayout.LayoutParams(sideWidth,-1) : new LinearLayout.LayoutParams(-1,0,.42f));
        LinearLayout tools = new LinearLayout(this);
        tools.setPadding(dp(2),0,dp(10),0);
        tools.setOrientation(LinearLayout.VERTICAL);
        sidebar.addView(tools);
        TextView title = new TextView(this);
        title.setText("服务端控制台");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tools.addView(title);
        runtime = new TextView(this);
        runtime.setPadding(0,dp(5),0,dp(8));
        tools.addView(runtime);
        try { available = RuntimeStore.downloadEntry(this,major) != null; }
        catch (IOException ignored) { available=false; }
        download=button(tools,"下载并验证 Java "+major,v -> installRuntime(null));
        pick=button(tools,"导入 OpenJDK ZIP",v -> confirmImport());
        LinearLayout memory = row(tools);
        TextView unit = new TextView(this);
        unit.setText("堆内存 MiB");
        memory.addView(unit,new LinearLayout.LayoutParams(0,-2,1));
        heap = new EditText(this);
        heap.setSingleLine(true);
        heap.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        heap.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        heap.setText("512");
        heap.setContentDescription("最大堆内存 MiB");
        memory.addView(heap,new LinearLayout.LayoutParams(dp(62),-2));
        LinearLayout actions=row(tools);
        start=button(actions,"启动",v -> confirmStart());
        stop=button(actions,"停止",v -> ServerService.command(jar.getPath(),"stop"));
        for (Button action : new Button[]{start,stop}) {
            action.setMinWidth(0);
            action.setMinimumWidth(0);
            action.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
        }
        LinearLayout console=new LinearLayout(this);
        console.setOrientation(LinearLayout.VERTICAL);
        page.addView(console, wide ? new LinearLayout.LayoutParams(0,-1,1f) : new LinearLayout.LayoutParams(-1,0,.58f));
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(25,31,29));
        output = new TextView(this);
        output.setTextColor(Color.rgb(220,238,229));
        output.setPadding(dp(10),dp(8),dp(10),dp(8));
        output.setTextSize(13);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextIsSelectable(true);
        scroll.addView(output);
        console.addView(scroll,new LinearLayout.LayoutParams(-1,0,1f));
        LinearLayout bottom=row(console);
        command=new EditText(this);
        command.setSingleLine(true);
        command.setHint("服务端指令");
        command.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        bottom.addView(command,new LinearLayout.LayoutParams(0,-2,1f));
        send=button(bottom,"发送",v -> sendCommand());
        command.setOnEditorActionListener((v,action,event) -> { sendCommand(); return true; });
        refresh();
    }
    private void sendCommand() {
        if (ServerService.controls(jar.getPath())) {
            ServerService.command(jar.getPath(), command.getText().toString());
            command.setText("");
        }
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private LinearLayout row(LinearLayout page) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(row); return row;
    }
    private Button button(LinearLayout group, String label, android.view.View.OnClickListener click) {
        Button result = new Button(this);
        result.setText(label); result.setOnClickListener(click); group.addView(result); return result;
    }
    private void confirmImport() {
        new AlertDialog.Builder(this).setTitle("导入 Java " + major + " 运行时")
                .setMessage("选择与本机 ABI 匹配的 Android/Bionic OpenJDK ZIP，根目录应包含 lib。运行时拥有本应用权限，请确认文件来源。")
                .setNegativeButton("取消", null).setPositiveButton("选择 ZIP", (d,which) -> {
                    Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                    startActivityForResult(picker, PICK_JRE);
                }).show();
    }
    @Override public void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_JRE || result != RESULT_OK || data == null || data.getData() == null) return;
        installRuntime(data.getData());
    }
    private void installRuntime(Uri selected) {
        installing = true; installStatus = "准备运行时"; refresh();
        new Thread(() -> {
            String message;
            try { RuntimeStore.install(this, selected, major, (phase,done,total) -> {
                String text=phase+(total>0 ? " "+(done>>20)+"/"+(total>>20)+" MiB" : done>0 ? " "+(done>>20)+" MiB" : "");
                runOnUiThread(() -> { installStatus=text; refresh(); });
            }); message = "已安装，JVM 自检通过"; }
            catch (IOException | RuntimeException e) { message = e.getMessage(); }
            String resultText = message;
            runOnUiThread(() -> {
                installing = false;
                if (!isFinishing() && !isDestroyed()) {
                    refresh();
                    new AlertDialog.Builder(this).setMessage(resultText).setPositiveButton("确定",null).show();
                }
            });
        }, "lumen-import-runtime").start();
    }
    private void confirmStart() {
        int memory;
        try { memory = Integer.parseInt(heap.getText().toString()); }
        catch (NumberFormatException e) { memory = 0; }
        if (memory < 128 || memory > 8192) { heap.setError("128–8192 MiB"); return; }
        final int max = memory;
        if (ServerService.eula(jar)) { launch(max); return; }
        new AlertDialog.Builder(this).setTitle("Minecraft 服务端 EULA")
            .setMessage("开服需要同意 Minecraft 最终用户许可协议。确认后将写入此服务器的 eula.txt。")
            .setNeutralButton("阅读协议", (d,w) -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.minecraft.net/eula"))))
            .setNegativeButton("取消",null).setPositiveButton("同意并启动", (d,w) -> {
                try { ServerService.accept(jar); launch(max); }
                catch (IOException e) { Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show(); }
            }).show();
    }
    private void launch(int memory) {
        Intent intent = new Intent(this, ServerService.class).putExtra(ServerService.JAR, jar.getPath())
            .putExtra(ServerService.JAVA, major).putExtra(ServerService.HEAP, memory);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
        start.setEnabled(false);
    }
    private void refresh() {
        if (jar == null || output == null) return;
        boolean installed = RuntimeStore.runtime(this,major).isDirectory();
        boolean controls = ServerService.controls(jar.getPath());
        String state = controls ? "运行中" : ServerService.busy() ? "已有服务端进程" : "已停止";
        runtime.setText("Java " + major + (installing ? " · "+installStatus : installed ? " · 已验证运行时" : " · 需要 Android 运行时") + "   /   " + state);
        pick.setVisibility(installed ? android.view.View.GONE : android.view.View.VISIBLE);
        pick.setEnabled(!installing);
        download.setVisibility(installed || !available ? android.view.View.GONE : android.view.View.VISIBLE);
        download.setEnabled(!installing);
        start.setEnabled(installed && !ServerService.busy());
        stop.setEnabled(controls);
        command.setEnabled(controls); send.setEnabled(controls);
        int latest = ServerService.revision();
        if (latest != revision) {
            revision = latest;
            boolean follow = !scroll.canScrollVertically(1);
            output.setText(ServerService.output(jar.getPath()));
            if (follow) scroll.post(() -> scroll.fullScroll(android.view.View.FOCUS_DOWN));
        }
    }
    @Override public void onStart() { super.onStart(); handler.post(tick); }
    @Override public void onStop() { handler.removeCallbacks(tick); super.onStop(); }
}
