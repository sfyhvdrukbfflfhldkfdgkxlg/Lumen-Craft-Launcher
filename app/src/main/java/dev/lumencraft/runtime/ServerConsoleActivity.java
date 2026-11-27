package dev.lumencraft.runtime;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.widget.*;
import dev.lumencraft.core.CrashNotes;
import dev.lumencraft.core.LocalFiles;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class ServerConsoleActivity extends Activity {
    private static final int PICK_JRE = 17;
    private static final int PICK_PLUGIN = 18;
    private File jar;
    private int major;
    private boolean installing;
    private String installStatus = "";
    private boolean available;
    private final Handler handler = new Handler();
    private TextView runtime, metricsView, output;
    private ScrollView scroll;
    private Button download, pick, start, stop, manageBtn, send;
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
        int sideWidth = Math.min(dp(236), getResources().getDisplayMetrics().widthPixels / 3);
        page.addView(sidebar, wide ? new LinearLayout.LayoutParams(sideWidth, -1) : new LinearLayout.LayoutParams(-1, 0, .44f));
        LinearLayout tools = new LinearLayout(this);
        tools.setPadding(dp(2), 0, dp(10), 0);
        tools.setOrientation(LinearLayout.VERTICAL);
        sidebar.addView(tools);
        TextView title = new TextView(this);
        title.setText("服务端控制台");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tools.addView(title);
        runtime = new TextView(this);
        runtime.setPadding(0, dp(5), 0, dp(4));
        tools.addView(runtime);
        metricsView = new TextView(this);
        metricsView.setTextSize(12);
        metricsView.setPadding(0, 0, 0, dp(6));
        tools.addView(metricsView);
        try { available = RuntimeStore.downloadEntry(this, major) != null; }
        catch (IOException ignored) { available = false; }
        download = button(tools, "下载并验证 Java " + major, v -> installRuntime(null));
        pick = button(tools, "导入 OpenJDK ZIP", v -> confirmImport());
        LinearLayout memory = row(tools);
        TextView unit = new TextView(this);
        unit.setText("堆内存 MiB");
        memory.addView(unit, new LinearLayout.LayoutParams(0, -2, 1));
        heap = new EditText(this);
        heap.setSingleLine(true);
        heap.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        heap.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        heap.setText(Integer.toString(prefs().getInt(prefKey("heap"), 512)));
        heap.setContentDescription("最大堆内存 MiB");
        memory.addView(heap, new LinearLayout.LayoutParams(dp(64), -2));
        LinearLayout actions = row(tools);
        start = button(actions, "启动", v -> confirmStart());
        stop = button(actions, "停止", v -> ServerService.command(jar.getPath(), "stop"));
        for (Button action : new Button[]{start, stop}) {
            action.setMinWidth(0);
            action.setMinimumWidth(0);
            action.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        }
        manageBtn = button(tools, "配置 / 插件 / 备份 / 玩家管理", v -> manageMenu());

        LinearLayout console = new LinearLayout(this);
        console.setOrientation(LinearLayout.VERTICAL);
        page.addView(console, wide ? new LinearLayout.LayoutParams(0, -1, 1f) : new LinearLayout.LayoutParams(-1, 0, .56f));
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(25, 31, 29));
        output = new TextView(this);
        output.setTextColor(Color.rgb(220, 238, 229));
        output.setPadding(dp(10), dp(8), dp(10), dp(8));
        output.setTextSize(13);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextIsSelectable(true);
        scroll.addView(output);
        console.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout bottom = row(console);
        command = new EditText(this);
        command.setSingleLine(true);
        command.setHint("服务端指令");
        command.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        bottom.addView(command, new LinearLayout.LayoutParams(0, -2, 1f));
        send = button(bottom, "发送", v -> sendCommand());
        command.setOnEditorActionListener((v, action, event) -> { sendCommand(); return true; });
        refresh();
    }

    private SharedPreferences prefs() { return getSharedPreferences("server_opts", MODE_PRIVATE); }
    private String prefKey(String name) { return jar.getParentFile().getName() + "/" + name; }

    private void manageMenu() {
        boolean autoRestart = prefs().getBoolean(prefKey("autoRestart"), false);
        int backupMin = prefs().getInt(prefKey("backupMin"), 0);
        String[] items = {
                "编辑 server.properties",
                "玩家管理（白名单 / 管理员 / 封禁）",
                "管理服务端插件 / 模组",
                "立即备份世界存档",
                "定时备份：" + (backupMin == 0 ? "关闭" : ("每 " + backupMin + " 分钟")),
                "崩溃自动重启：" + (autoRestart ? "已开启" : "关闭"),
                "分析当前控制台日志"
        };
        new AlertDialog.Builder(this).setTitle("服务端管理").setItems(items, (d, which) -> {
            switch (which) {
                case 0: editServerProperties(); break;
                case 1: playerAdminDialog(); break;
                case 2: manageAddons(); break;
                case 3: runManualBackup(); break;
                case 4: pickBackupInterval(); break;
                case 5:
                    prefs().edit().putBoolean(prefKey("autoRestart"), !autoRestart).apply();
                    Toast.makeText(this, !autoRestart ? "已开启崩溃自动重启（最多 3 次）" : "已关闭崩溃自动重启", Toast.LENGTH_SHORT).show();
                    break;
                case 6:
                    CrashNotes.Diagnosis diag = CrashNotes.diagnose(ServerService.output(jar.getPath()));
                    String msg = diag.summary.isEmpty() ? "当前日志中未识别到已知崩溃或错误特征。" : diag.summary;
                    new AlertDialog.Builder(this).setTitle("服务端日志诊断").setMessage(msg).setPositiveButton("关闭", null).show();
                    break;
            }
        }).show();
    }

    private void editServerProperties() {
        File propFile = new File(jar.getParentFile(), "server.properties");
        Properties p = new Properties();
        if (propFile.isFile()) {
            try (InputStream in = new FileInputStream(propFile)) { p.load(in); }
            catch (IOException ignored) {}
        }
        String[][] fields = {
                {"server-port", "端口", p.getProperty("server-port", "25565")},
                {"max-players", "最大人数", p.getProperty("max-players", "10")},
                {"gamemode", "游戏模式 (survival/creative/adventure)", p.getProperty("gamemode", "survival")},
                {"difficulty", "难度 (peaceful/easy/normal/hard)", p.getProperty("difficulty", "normal")},
                {"online-mode", "正版验证 (true/false)", p.getProperty("online-mode", "true")},
                {"white-list", "白名单 (true/false)", p.getProperty("white-list", "false")},
                {"view-distance", "视距 (2-32)", p.getProperty("view-distance", "8")},
                {"motd", "服务器标语", p.getProperty("motd", "A LumenCraft Minecraft Server")}
        };
        ScrollView sv = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(8), dp(18), dp(4));
        EditText[] inputs = new EditText[fields.length];
        for (int i = 0; i < fields.length; i++) {
            TextView lbl = new TextView(this);
            lbl.setText(fields[i][1]);
            lbl.setPadding(0, dp(6), 0, 0);
            form.addView(lbl);
            EditText et = new EditText(this);
            et.setSingleLine(true);
            et.setText(fields[i][2]);
            form.addView(et);
            inputs[i] = et;
        }
        sv.addView(form);
        new AlertDialog.Builder(this).setTitle("server.properties").setView(sv)
                .setNegativeButton("取消", null).setPositiveButton("保存", (d, w) -> {
                    for (int i = 0; i < fields.length; i++) {
                        p.setProperty(fields[i][0], inputs[i].getText().toString().trim());
                    }
                    try (OutputStream out = new FileOutputStream(propFile)) {
                        p.store(out, "Updated by LumenCraft");
                        Toast.makeText(this, "已保存 server.properties（重启生效）", Toast.LENGTH_SHORT).show();
                    } catch (IOException e) {
                        Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private void playerAdminDialog() {
        String[] ops = {"添加白名单 (whitelist add)", "移除白名单 (whitelist remove)",
                "授予管理员 (op)", "撤销管理员 (deop)", "封禁玩家 (ban)", "解封玩家 (pardon)", "查看在线玩家 (list)"};
        String[] cmds = {"whitelist add ", "whitelist remove ", "op ", "deop ", "ban ", "pardon ", "list"};
        new AlertDialog.Builder(this).setTitle("玩家与权限指令").setItems(ops, (d, i) -> {
            if (!ServerService.controls(jar.getPath())) {
                Toast.makeText(this, "请先启动服务端后再发送玩家管理指令", Toast.LENGTH_SHORT).show();
                return;
            }
            if (i == 6) { ServerService.command(jar.getPath(), "list"); return; }
            EditText name = new EditText(this);
            name.setSingleLine(true);
            name.setHint("玩家名称");
            new AlertDialog.Builder(this).setTitle(ops[i]).setView(name)
                    .setNegativeButton("取消", null).setPositiveButton("执行", (dd, ww) -> {
                        String target = name.getText().toString().trim();
                        if (target.matches("[A-Za-z0-9_]{1,32}")) ServerService.command(jar.getPath(), cmds[i] + target);
                        else Toast.makeText(this, "玩家名称格式无效", Toast.LENGTH_SHORT).show();
                    }).show();
        }).show();
    }

    private File addonDir() {
        String name = jar.getParentFile().getName().toLowerCase(Locale.ROOT);
        boolean pluginEngine = name.startsWith("paper-") || name.startsWith("purpur-") || name.startsWith("spigot-");
        return new File(jar.getParentFile(), pluginEngine ? "plugins" : "mods");
    }

    private void manageAddons() {
        File dir = addonDir();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".jar") || n.endsWith(".jar.disabled"));
        List<File> list = files == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(files));
        Collections.sort(list, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        String[] labels = new String[list.size() + 1];
        labels[0] = "＋ 导入 JAR 到 " + dir.getName() + "/";
        for (int i = 0; i < list.size(); i++) labels[i + 1] = list.get(i).getName();
        new AlertDialog.Builder(this).setTitle("服务端 " + dir.getName()).setItems(labels, (d, idx) -> {
            if (idx == 0) {
                Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                try { startActivityForResult(picker, PICK_PLUGIN); }
                catch (Exception e) { Toast.makeText(this, "缺少文件选择器", Toast.LENGTH_SHORT).show(); }
            } else {
                File f = list.get(idx - 1);
                boolean disabled = f.getName().endsWith(".disabled");
                new AlertDialog.Builder(this).setTitle(f.getName())
                        .setItems(new String[]{disabled ? "启用" : "停用", "删除"}, (dd, act) -> {
                            if (act == 0) {
                                File dst = new File(dir, disabled ? f.getName().substring(0, f.getName().length() - 9) : f.getName() + ".disabled");
                                if (!f.renameTo(dst)) Toast.makeText(this, "重命名失败", Toast.LENGTH_SHORT).show();
                            } else {
                                f.delete();
                            }
                            manageAddons();
                        }).show();
            }
        }).show();
    }

    private void runManualBackup() {
        new Thread(() -> {
            String msg;
            try {
                File zip = ServerService.backupWorld(jar);
                msg = "世界已备份：" + zip.getName();
            } catch (IOException e) { msg = "备份失败：" + e.getMessage(); }
            String finalMsg = msg;
            runOnUiThread(() -> Toast.makeText(this, finalMsg, Toast.LENGTH_LONG).show());
        }, "server-backup").start();
    }

    private void pickBackupInterval() {
        String[] labels = {"关闭定时备份", "每 30 分钟", "每 60 分钟"};
        int[] values = {0, 30, 60};
        new AlertDialog.Builder(this).setTitle("定时世界备份").setItems(labels, (d, i) -> {
            prefs().edit().putInt(prefKey("backupMin"), values[i]).apply();
            Toast.makeText(this, "下次启动生效：" + labels[i], Toast.LENGTH_SHORT).show();
        }).show();
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
                .setNegativeButton("取消", null).setPositiveButton("选择 ZIP", (d, which) -> {
                    Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                    startActivityForResult(picker, PICK_JRE);
                }).show();
    }
    @Override public void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (request == PICK_JRE) {
            installRuntime(data.getData());
        } else if (request == PICK_PLUGIN) {
            Uri uri = data.getData();
            new Thread(() -> {
                String msg;
                try {
                    File dir = addonDir();
                    if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建目录");
                    if ("mods".equals(dir.getName())) {
                        try (InputStream in = getContentResolver().openInputStream(uri)) {
                            File saved = LocalFiles.importJar(in, dir);
                            msg = "已导入服务端模组：" + saved.getName();
                        }
                    } else {
                        File dst = new File(dir, "plugin-" + System.currentTimeMillis() + ".jar");
                        try (InputStream in = getContentResolver().openInputStream(uri);
                             OutputStream out = new BufferedOutputStream(new FileOutputStream(dst))) {
                            if (in == null) throw new IOException("无法读取文件");
                            byte[] buf = new byte[32768]; long total = 0; int n;
                            while ((n = in.read(buf)) != -1) {
                                total += n; if (total > 256L << 20) throw new IOException("插件 JAR 过大");
                                out.write(buf, 0, n);
                            }
                        }
                        msg = "已导入服务端插件：" + dst.getName();
                    }
                } catch (Exception e) { msg = "导入失败：" + e.getMessage(); }
                String finalMsg = msg;
                runOnUiThread(() -> Toast.makeText(this, finalMsg, Toast.LENGTH_LONG).show());
            }, "server-addon-import").start();
        }
    }
    private void installRuntime(Uri selected) {
        installing = true; installStatus = "准备运行时"; refresh();
        new Thread(() -> {
            String message;
            try { RuntimeStore.install(this, selected, major, (phase, done, total) -> {
                String text = phase + (total > 0 ? " " + (done >> 20) + "/" + (total >> 20) + " MiB" : done > 0 ? " " + (done >> 20) + " MiB" : "");
                runOnUiThread(() -> { installStatus = text; refresh(); });
            }); message = "已安装，JVM 自检通过"; }
            catch (IOException | RuntimeException e) { message = e.getMessage(); }
            String resultText = message;
            runOnUiThread(() -> {
                installing = false;
                if (!isFinishing() && !isDestroyed()) {
                    refresh();
                    new AlertDialog.Builder(this).setMessage(resultText).setPositiveButton("确定", null).show();
                }
            });
        }, "lumen-import-runtime").start();
    }
    private void confirmStart() {
        int memory;
        try { memory = Integer.parseInt(heap.getText().toString()); }
        catch (NumberFormatException e) { memory = 0; }
        if (memory < 128 || memory > 8192) { heap.setError("128–8192 MiB"); return; }
        prefs().edit().putInt(prefKey("heap"), memory).apply();
        final int max = memory;
        if (ServerService.eula(jar)) { launch(max); return; }
        new AlertDialog.Builder(this).setTitle("Minecraft 服务端 EULA")
            .setMessage("开服需要同意 Minecraft 最终用户许可协议。确认后将写入此服务器的 eula.txt。")
            .setNeutralButton("阅读协议", (d, w) -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.minecraft.net/eula"))))
            .setNegativeButton("取消", null).setPositiveButton("同意并启动", (d, w) -> {
                try { ServerService.accept(jar); launch(max); }
                catch (IOException e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); }
            }).show();
    }
    private void launch(int memory) {
        Intent intent = new Intent(this, ServerService.class)
            .putExtra(ServerService.JAR, jar.getPath())
            .putExtra(ServerService.JAVA, major)
            .putExtra(ServerService.HEAP, memory)
            .putExtra(ServerService.AUTO_RESTART, prefs().getBoolean(prefKey("autoRestart"), false))
            .putExtra(ServerService.BACKUP_MINUTES, prefs().getInt(prefKey("backupMin"), 0));
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
        start.setEnabled(false);
    }
    private void refresh() {
        if (jar == null || output == null) return;
        boolean installed = RuntimeStore.runtime(this, major).isDirectory();
        boolean controls = ServerService.controls(jar.getPath());
        String state = controls ? "运行中" : ServerService.busy() ? "已有服务端进程" : "已停止";
        runtime.setText("Java " + major + (installing ? " · " + installStatus : installed ? " · 已验证运行时" : " · 需要 Android 运行时") + "   /   " + state);
        String m = ServerService.metrics(jar.getPath());
        metricsView.setText(m.isEmpty() ? "支持独立运行且不占用图形渲染器" : m);
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
