package dev.lumencraft

import dev.lumencraft.ui.Appearance
import dev.lumencraft.ui.NavSymbol
import dev.lumencraft.ui.PageDeck
import dev.lumencraft.ui.JobProgress
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.view.inputmethod.InputMethodManager
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import dev.lumencraft.core.*
import dev.lumencraft.client.ClientActivity
import dev.lumencraft.runtime.GraphicsProbe
import dev.lumencraft.runtime.ServerConsoleActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

class MainActivity : Activity() {
    private val pages = listOf("游戏", "资源", "联机", "服务端", "设置")
    private val headings = listOf("游戏实例", "资源库", "联机", "本地服务端", "设置")
    private val root by lazy { File(filesDir, "library") }
    private val installer by lazy { VanillaInstaller(root) }
    private val prefs by lazy { getSharedPreferences("library", MODE_PRIVATE) }
    private var page = 0
    private lateinit var body: LinearLayout
    private var activeJob: Thread? = null
    private var searchQuery = ""
    private var category = 0
    private var advanced = false
    private var results: List<Modrinth.Project> = emptyList()
    private var resourceSearched = false
    private var status: ServerProbe.Status? = null
    private var address = ""
    private var fileResult: ((Uri) -> Unit)? = null
    private val dark get() = Appearance.isDark(this)
    private fun color(light: String, night: String) = Color.parseColor(if (dark) night else light)
    private val bg get() = color("#FAF9F5", "#18201D")
    private val ink get() = color("#182B24", "#EAEFE9")
    private val muted get() = color("#54655A", "#B4C2B8")
    private val accent get() = color("#245E46", "#A8CDA9")
    private val rule get() = color("#CED5CC", "#39483F")
    private val railColor get() = color("#1E3E32", "#10271E")
    private val history = ArrayDeque<Int>()
    private var positions = IntArray(5)
    private lateinit var deck: PageDeck
    private lateinit var scroll: ScrollView
    private lateinit var nav: LinearLayout
    private var sidebar = false
    private var redrawChrome = true
    private var animateUi = true
    private var unregisterBack: (() -> Unit)? = null
    private var jobDialog: AlertDialog? = null

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Appearance.attach(base))
    override fun onCreate(state: Bundle?) {
        Appearance.apply(this)
        super.onCreate(state)
        runCatching {
            val srcName = prefs.getString("download-source", "OFFICIAL") ?: "OFFICIAL"
            val customUrl = prefs.getString("download-custom", "") ?: ""
            MirrorBench.configure(MirrorBench.SourceId.valueOf(srcName), customUrl)
        }
        page = state?.getInt("page")?.coerceIn(0, 4) ?: 0
        address = state?.getString("address").orEmpty()
        searchQuery = state?.getString("query").orEmpty()
        category = state?.getInt("category")?.coerceIn(0, 4) ?: 0
        advanced = state?.getBoolean("advanced") ?: false
        state?.getIntArray("positions")?.takeIf { it.size == pages.size }?.let { positions = it }
        state?.getIntArray("history")?.filter { it in pages.indices }?.let { history.addAll(it) }
        render()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::scroll.isInitialized) positions[page] = scroll.scrollY
        outState.putInt("page", page)
        outState.putString("address", address)
        outState.putString("query", searchQuery)
        outState.putInt("category", category)
        outState.putBoolean("advanced", advanced)
        outState.putIntArray("positions", positions)
        outState.putIntArray("history", history.toIntArray())
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Appearance.apply(this)
        redrawChrome = true
        render()
    }
    override fun onRestart() { super.onRestart(); if (activeJob == null) render() }
    override fun onDestroy() {
        unregisterBack?.invoke()
        unregisterBack = null
        activeJob?.interrupt()
        jobDialog?.dismiss()
        jobDialog = null
        super.onDestroy()
    }
    @Deprecated("Framework callback")
    override fun onBackPressed() {
        if (history.isEmpty()) super.onBackPressed() else back()
    }
    private fun back() {
        if (history.isEmpty()) return
        page = history.removeLast()
        render()
    }
    private fun selectPage(index: Int) {
        if (page == index) {
            if (Appearance.motion(this)) scroll.smoothScrollTo(0,0) else scroll.scrollTo(0,0)
            return
        }
        val ime = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { ime.hideSoftInputFromWindow(it.windowToken, 0) }
        history.remove(page)
        history.remove(index)
        history.addLast(page)
        page = index
        render(true)
    }
    private fun dp(v: Int) = (resources.displayMetrics.density * v + .5f).toInt()
    private fun fill(c: Int, radius: Int = 0) = GradientDrawable().apply {
        setColor(c)
        cornerRadius = dp(radius).toFloat()
    }
    private fun touch(c: Int, ripple: Int, radius: Int = 0): android.graphics.drawable.Drawable =
        if (animateUi) RippleDrawable(ColorStateList.valueOf(ripple), fill(c,radius), null)
        else android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), fill(ripple, radius))
            addState(intArrayOf(), fill(c,radius))
        }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun label(s: String, size: Int, c: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size.toFloat(); setTextColor(c); includeFontPadding = true
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private fun LinearLayout.place(v: View, gap: Int = 0, height: Int = -2) {
        addView(v, LinearLayout.LayoutParams(-1, if (height < 0) height else dp(height)).apply { topMargin = dp(gap) })
    }
    private fun buildChrome() {
        sidebar = resources.configuration.screenWidthDp >= 600 ||
            (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && resources.configuration.screenWidthDp >= 440)
        window.statusBarColor = if (sidebar) bg else railColor
        window.navigationBarColor = if (Build.VERSION.SDK_INT >= 26 || dark) bg else railColor
        window.decorView.systemUiVisibility = (if (!dark && sidebar) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0) or
            (if (!dark && Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        val screen = LinearLayout(this).apply {
            orientation = if (sidebar) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setBackgroundColor(bg)
            isFocusableInTouchMode = true
        }
        if (Build.VERSION.SDK_INT >= 35) screen.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout() or android.view.WindowInsets.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        nav = LinearLayout(this).apply {
            orientation = if (sidebar) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            setBackgroundColor(if(sidebar) railColor else bg)
        }
        deck = PageDeck(this, { history.isNotEmpty() }, { back() })
        if (sidebar) {
            val rail = ScrollView(this).apply {
                setBackgroundColor(railColor)
                isVerticalScrollBarEnabled = false
                addView(nav)
            }
            screen.addView(rail, LinearLayout.LayoutParams(dp(if(resources.configuration.screenWidthDp >= 840) 152 else 118), -1))
            screen.addView(deck, LinearLayout.LayoutParams(0,-1,1f))
        } else {
            val masthead = LinearLayout(this).apply {
                setBackgroundColor(railColor); gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20),dp(8),dp(20),dp(8))
            }
            masthead.addView(ImageView(this).apply { setImageResource(R.drawable.mark) },dp(24),dp(24))
            masthead.addView(label("微光   LumenCraft",16,Color.WHITE,true), LinearLayout.LayoutParams(-2,-2).apply {leftMargin=dp(8)})
            screen.addView(masthead)
            screen.addView(deck,LinearLayout.LayoutParams(-1,0,1f))
            screen.addView(View(this).apply{setBackgroundColor(rule)}, -1, 1)
            screen.addView(nav)
        }
        setContentView(screen)
        redrawChrome = false
    }
    private fun drawNav() {
        nav.removeAllViews()
        val white = Color.WHITE
        if (sidebar) {
            val brand = column().apply { setPadding(dp(16),dp(18),dp(8),dp(12)) }
            val title = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            title.addView(ImageView(this).apply { setImageResource(R.drawable.mark) },dp(25),dp(25))
            title.addView(label("微光",20,white,true),LinearLayout.LayoutParams(-2,-2).apply{leftMargin=dp(6)})
            brand.place(title)
            brand.place(label("LumenCraft",13,Color.rgb(195,217,202)),2)
            nav.place(brand)
        }
        pages.forEachIndexed { i,name ->
            val selected = i == page
            val foreground = if(sidebar) (if(selected) white else Color.rgb(195,217,202)) else (if(selected) accent else muted)
            val surface = if(sidebar) (if(selected) Color.rgb(52,89,70) else railColor) else bg
            val container = FrameLayout(this).apply {
                isSelected = selected; isFocusable = true; contentDescription = name
                background = touch(surface, if(sidebar) Color.rgb(67,104,84) else rule)
                setOnClickListener { selectPage(i) }
            }
            val inner = LinearLayout(this).apply {
                gravity = if(sidebar) Gravity.CENTER_VERTICAL else Gravity.CENTER
                orientation = if(sidebar) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                if(sidebar) setPadding(dp(16),dp(5),dp(4),dp(5)) else setPadding(0,dp(6),0,dp(4))
            }
            inner.addView(NavSymbol(this,i,foreground), dp(19),dp(19))
            inner.addView(label(name, if(sidebar) 15 else 12, foreground,selected),LinearLayout.LayoutParams(-2,-2).apply{
                if(sidebar) leftMargin = dp(9) else topMargin = dp(1)
            })
            container.addView(inner, FrameLayout.LayoutParams(-1,-2,Gravity.CENTER_VERTICAL))
            if(selected) container.addView(View(this).apply {setBackgroundColor(if(sidebar) Color.rgb(187,220,164) else accent)},
                if(sidebar) FrameLayout.LayoutParams(dp(3),dp(22),Gravity.LEFT or Gravity.CENTER_VERTICAL)
                else FrameLayout.LayoutParams(dp(24),dp(2),Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            container.minimumHeight = dp(if(sidebar) 48 else 56)
            if(sidebar) nav.place(container) else nav.addView(container, LinearLayout.LayoutParams(0,-2,1f))
        }
    }
    private fun render(animate: Boolean = false) {
        animateUi = Appearance.motion(this)
        if (::deck.isInitialized && ::scroll.isInitialized && deck.pageIndex >= 0) positions[deck.pageIndex] = scroll.scrollY
        if (redrawChrome || !::deck.isInitialized) buildChrome()
        drawNav()
        scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(bg) }
        val wide = resources.configuration.screenWidthDp >= 700
        body = column().apply {
            val side = dp(if(wide) 28 else 20)
            setPadding(side,dp(if(wide) 18 else 16),side,dp(26))
            isFocusableInTouchMode = true
        }
        scroll.addView(body)
        body.place(label(headings[page], if(wide) 30 else 27, ink,true))
        body.place(View(this).apply {setBackgroundColor(ink)},8,1)
        when (page) { 0 -> instances(false); 1 -> resourcesPage(); 2 -> online(); 3 -> instances(true); 4 -> settings() }
        deck.display(scroll,page,history.lastOrNull(),animate)
        if (Build.VERSION.SDK_INT >= 33) {
            if (history.isEmpty()) { unregisterBack?.invoke(); unregisterBack = null }
            else if (unregisterBack == null) unregisterBack = dev.lumencraft.ui.SystemBack.register(this,{deck},{back()})
        }
        val target = positions[page]
        val shown = scroll
        shown.post { if (scroll === shown) shown.scrollTo(0,target) }
    }
    private fun card(title: String, notes: String = "", action: (() -> Unit)? = null): LinearLayout {
        val panel = column().apply { setPadding(0,dp(11),0,dp(11)) }
        panel.place(label(title,17,ink,true))
        if(notes.isNotBlank()) panel.place(label(notes,14,muted),3)
        if(action != null) {
            panel.background = touch(bg,rule)
            panel.isFocusable = true
            panel.setOnClickListener {action()}
        }
        body.place(panel,3)
        body.addView(View(this).apply {setBackgroundColor(rule)},-1,1)
        return panel
    }
    private fun button(s: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = s; textSize = 15f; isAllCaps = false; minHeight = dp(46)
        typeface = Typeface.create("sans-serif-medium",Typeface.NORMAL)
        setTextColor(if(primary) color("#FFFFFF","#13241A") else accent)
        background = touch(if(primary) accent else color("#E9EDE5","#26372E"),
            color("#B9CEC0","#5A705E"), 3)
        setPadding(dp(14),dp(7),dp(14),dp(7))
        stateListAnimator = null
        setOnClickListener {action()}
    }
    private fun entry(hintText: String, value: String = "", changed: ((String)->Unit)? = null) = EditText(this).apply {
        hint = hintText; setSingleLine(true); setText(value); textSize = 16f
        setTextColor(ink); setHintTextColor(muted)
        if (changed != null) addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = changed(s.toString())
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }
    private fun section(s: String) = body.place(label(s, 15, muted, true), 22)
    private fun dialogBuilder() = if (Appearance.motion(this)) AlertDialog.Builder(this)
        else AlertDialog.Builder(this, if (dark) R.style.LumenDialogDarkStill else R.style.LumenDialogLightStill)
    private fun note(title: String, message: String) {
        dialogBuilder().setTitle(title).setMessage(message).setPositiveButton("关闭", null).show()
    }
    private fun options(title: String, items: List<String>, pick: (Int) -> Unit) {
        dialogBuilder().setTitle(title).setItems(items.toTypedArray()) { _, n -> pick(n) }.show()
    }
    private fun ask(title: String, hint: String, initial: String = "", onSubmit: (String) -> Unit) {
        val field = entry(hint, initial)
        val box = column().apply { setPadding(dp(20), dp(8), dp(20), 0); addView(field) }
        dialogBuilder().setTitle(title).setView(box).setNegativeButton("取消",null)
            .setPositiveButton("确定") { _,_ -> val value = field.text.toString().trim(); if (value.isNotEmpty()) onSubmit(value) }.show()
    }
    private fun <T> job(title: String, work: (JobProgress) -> T, success: (T) -> Unit) {
        if (activeJob != null) return
        val motion = Appearance.motion(this)
        val box = column().apply { setPadding(dp(24),dp(18),dp(24),dp(4)) }
        val info = label("正在处理…", 15, muted)
        val bar = ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            isIndeterminate = motion
            if(!motion) visibility = View.INVISIBLE
        }
        box.place(bar); box.place(info, 8)
        val reporter = JobProgress(bar,info,motion)
        val dialog = dialogBuilder().setTitle(title).setView(box).setCancelable(false).setNegativeButton("取消",null).create()
        jobDialog = dialog
        dialog.setOnDismissListener { reporter.close() }
        dialog.show()
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val task = Thread({
            val result = runCatching { work(reporter) }
            runOnUiThread {
                activeJob = null
                jobDialog = null
                reporter.close()
                if (!isDestroyed && !isFinishing) {
                    dialog.dismiss()
                    if (cancelled.get()) Toast.makeText(this,"任务已中止",Toast.LENGTH_SHORT).show()
                    else result.fold(success) { e -> note(title, e.localizedMessage ?: e.javaClass.simpleName) }
                }
            }
        }, "library-worker")
        activeJob = task
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            cancelled.set(true); task.interrupt(); reporter.close()
            info.text = "正在结束任务…"; it.isEnabled = false
        }
        task.start()
    }
    private fun progress(update: JobProgress) = update.files
    private data class Instance(val dir: File, val meta: JSONObject, val name: String) {
        val id get() = meta.getString("id")
        val java get() = meta.getInt("javaMajor")
        val loader get() = runCatching { JSONObject(File(dir,"loader.json").readText()).getString("loader") }.getOrDefault("vanilla")
    }
    private fun records(server: Boolean): List<Instance> {
        val folder = File(root,if(server) "servers" else "instances")
        return folder.listFiles().orEmpty().filter { !it.name.startsWith("pack-staging-") }.mapNotNull { dir ->
            runCatching {
                val packName = runCatching { JSONObject(File(dir,"pack.json").readText()).getString("name") }.getOrDefault(dir.name)
                Instance(dir, JSONObject(File(dir,"install.json").readText()), prefs.getString("title/" + dir.absolutePath, packName) ?: packName)
            }.getOrNull()
        }.sortedBy { it.name }
    }
    private fun instances(server: Boolean) {
        val all = records(server)
        val content=body
        var installedPanel=body
        if(resources.configuration.screenWidthDp>=660){
            val row=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL;isBaselineAligned=false}
            val launchPanel=column()
            installedPanel=column()
            row.addView(launchPanel,LinearLayout.LayoutParams(0,-2,1.05f))
            row.addView(installedPanel,LinearLayout.LayoutParams(0,-2,1f).apply {leftMargin=dp(16)})
            content.place(row)
            body=launchPanel
        }
        if (!server) {
            val game=prefs.getString("classic-version",ClassicInstall.VERSION)?.takeIf {it in ClassicInstall.versions()} ?: ClassicInstall.VERSION
            val panel=card("Minecraft · 远古版",game+"  /  Java 8")
            panel.place(button("启动远古版",true) {
                job("准备 "+game,{ update -> ClassicInstall.install(root,game,game+"-mobile",progress(update)) }) { ClientActivity.open(this,it) }
            },14)
            panel.place(button("切换远古版本") {
                val builds=ClassicInstall.versions()
                options("远古客户端",builds) { n -> prefs.edit().putString("classic-version",builds[n]).apply();render() }
            },6)
        }
        body.place(button(if (server) "＋  安装服务端" else "＋  安装游戏文件", server) { catalog(server) },12)
        if (!server) body.place(button("导入整合包（.mrpack / CurseForge / MultiMC / HMCL）") {
            pickFile(false,"*/*") { uri ->
                job("导入整合包",{ update -> contentResolver.openInputStream(uri)!!.use { InstanceMigration.importAnyPack(root,it,progress(update)) } }) { summary ->
                    render()
                    val extra = if (summary.pendingExternalMods > 0) "\n\nCurseForge 列表含 ${summary.pendingExternalMods} 个外部模组，请在实例模组页补全或导入。" else ""
                    note("整合包已导入", "${summary.name}\n格式 ${summary.format} · Minecraft ${summary.game} · ${summary.loader}\n已提取 ${summary.copiedFiles} 个文件$extra")
                }
            }
        },8)
        body=installedPanel
        body.place(label("已安装  ·  " + all.size,15,muted,true),16)
        if (all.isEmpty()) card(if (server) "还没有服务端" else "还没有游戏实例", if (server) "选择版本后，可在控制台配置 Java 环境并开服。" else "可以从官方清单安装游戏文件，也可以导入 .mrpack 整合包。")
        all.forEach { i -> card(i.name,"Minecraft ${i.id}   ·   Java ${i.java}   ·   ${i.loader}") { manage(i, server) } }
        if (!server) {
            val archived=File(root,"archive").listFiles().orEmpty().filter {File(it,"install.json").isFile}
            if (archived.isNotEmpty()) body.place(button("恢复已移除的实例  ·  "+archived.size) {
                options("恢复实例",archived.map { prefs.getString("title/"+File(File(root,"instances"),it.name).absolutePath,it.name) ?: it.name }) { n ->
                    job("恢复实例",{InstanceFiles.move(root,archived[n],true)}) {render()}
                }
            },12)
        }
        body=content
    }
    private fun manage(i: Instance, server: Boolean) {
        val actions = mutableListOf<Pair<String,() -> Unit>>()
        if (server) actions.add("打开服务端控制台" to { ServerConsoleActivity.open(this,File(i.dir,"server.jar"),i.java) })
        if (!server && i.id in ClassicInstall.versions() && i.loader=="vanilla") actions.add("启动游戏" to { ClientActivity.open(this,i.dir) })
        actions.add("校验并修复官方文件" to {
            if (i.meta.optString("adapter")=="rubydung") job("校验游戏文件",{ update -> ClassicInstall.install(root,i.id,i.dir.name,progress(update)) }) { render() }
            else if(server && i.loader!="vanilla") serverInstall(i.id,i.loader)
            else install(i.id,i.dir.name,server,null)
        })
        actions.add("重命名" to { ask("显示名称","实例名称",i.name) { prefs.edit().putString("title/" + i.dir.absolutePath,it.take(80)).apply(); render() } })
        if (!server) {
            if (i.id !in ClassicInstall.versions()) {
                actions.add("启动参数与兼容检查" to {
                    job("装配启动计划",{
                        val am=getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                        val mem=android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
                        val rec=DeviceProfile.recommend(mem.totalMem,mem.availMem,am.isLowRamDevice,false,"",true)
                        LaunchPlan.build(root,i.dir,null,rec.heapMiB,1280,720)
                    }) { plan ->
                        note(i.name + " · 启动检查",
                            "Minecraft ${plan.gameId} · ${plan.loader}\n主类 ${plan.mainClass}\nJava ${plan.javaMajor} · 推荐堆 ${plan.heapMiB} MiB\n类路径 ${plan.classpath.size} 个 JAR · JVM 参数 ${plan.jvmArgs.size} 项 · 游戏参数 ${plan.gameArgs.size} 项\n\n${plan.readinessNote}")
                    }
                })
                actions.add("安装或切换模组加载器" to {
                    val loaders = mutableListOf("Fabric","Quilt","NeoForge","Forge","LiteLoader","导入本地 OptiFine JAR")
                    if (i.id == "1.12.2") loaders.add("Cleanroom")
                    options("选择加载器", loaders) { n ->
                        when (loaders[n]) {
                            "导入本地 OptiFine JAR" -> pickFile(false,"*/*") { uri ->
                                job("校验并安装 OptiFine",{ update -> contentResolver.openInputStream(uri)!!.use { ModLoaderInstaller.installLocalOptiFine(root,i.dir,it,progress(update)) } }) { ver ->
                                    render(); note("OptiFine 已安装", ver)
                                }
                            }
                            else -> addLoader(i, loaders[n].lowercase(Locale.ROOT))
                        }
                    }
                })
            }
            actions.add("模组管理与依赖补全" to { mods(i) })
            actions.add("导出存档备份" to { exportWorlds(i) })
            actions.add("恢复存档备份" to { restoreWorlds(i) })
            actions.add("复制实例" to {
                ask("副本名称","名称",i.name+" 副本") { name ->
                    job("复制实例文件",{InstanceFiles.duplicate(root,i.dir)}) { dir ->
                        prefs.edit().putString("title/"+dir.absolutePath,name.take(80)).apply();render()
                    }
                }
            })
            actions.add("移除实例" to {
                dialogBuilder().setMessage("将 ${i.name} 移出列表？可以在游戏页恢复。")
                    .setNegativeButton("取消",null).setPositiveButton("移除") { _,_ ->
                        job("移除实例",{InstanceFiles.move(root,i.dir,false)}) {render()}
                    }.show()
            })
        }
        actions.add("安装信息" to { note(i.name, "Minecraft ${i.id}\nJava ${i.java}\n实例目录 ${i.dir}\n元数据 SHA-1\n${i.meta.optString("metadataSha1")}") })
        options(i.name, actions.map { it.first }) { actions[it].second() }
    }
    private fun catalog(server: Boolean) {
        job("读取官方版本清单", { installer.versions() }) { versions ->
            val types = listOf("release", "snapshot", "old_beta", "old_alpha")
            options("版本类型", listOf("正式版","快照与预发布版","远古 Beta","远古 Alpha")) { type ->
                val ids = versions.filter { it.type == types[type] }.map { it.id }
                if (ids.isEmpty()) note("版本清单","官方清单中没有此类型。")
                else options("选择 Minecraft 版本",ids) { idx ->
                    val id = ids[idx]
                    if (server) {
                        val engines=mutableListOf("Vanilla","Fabric","Paper")
                        if(ServerDistributions.supportsPurpur(id)) engines.add("Purpur")
                        engines.add("NeoForge"); engines.add("Forge")
                        options("服务端组件",engines) { e -> serverInstall(id,engines[e].lowercase(Locale.ROOT)) }
                    }
                    else {
                        val choices = mutableListOf("原版游戏文件","游戏文件 + Fabric","游戏文件 + Quilt","游戏文件 + NeoForge","游戏文件 + Forge","游戏文件 + LiteLoader")
                        val idsLoader = mutableListOf<String?>(null,"fabric","quilt","neoforge","forge","liteloader")
                        if (id == "1.12.2") { choices.add("游戏文件 + Cleanroom"); idsLoader.add("cleanroom") }
                        options("安装 $id", choices) { n ->
                            val loader=idsLoader[n]
                            install(id, if(loader==null) id else "$id-$loader",false,loader)
                        }
                    }
                }
            }
        }
    }
    private fun install(id: String, name: String, server: Boolean, loader: String?, display: String? = null) {
        job("安装并校验文件", { update ->
            val dir = installer.install(id,name,server,progress(update))
            if (loader!=null) ModLoaderInstaller.install(root,dir,id,loader,null,progress(update))
            if (display != null) prefs.edit().putString("title/" + dir.absolutePath, display).apply()
            dir
        }) { page = if (server) 3 else 0; render(); Toast.makeText(this,"文件已就绪",Toast.LENGTH_SHORT).show() }
    }
    private fun addLoader(i: Instance,loader:String) {
        job("安装 $loader", { update -> ModLoaderInstaller.install(root,i.dir,i.id,loader,null,progress(update)) }) { render(); note("加载器已安装",it.toString()) }
    }
    private fun serverInstall(game:String,engine:String) {
        if(engine=="vanilla") {install(game,game,true,null);return}
        job("安装服务端组件",{update ->
            val service=ServerDistributions(root)
            when (engine) {
                "fabric" -> service.installFabric(game,progress(update))
                "paper" -> service.installPaper(game,progress(update))
                "purpur" -> service.installPurpur(game,progress(update))
                "neoforge", "forge" -> service.installModdedServer(game,engine,progress(update))
                else -> error("未知服务端组件")
            }
        }) {page=3;render()}
    }
    private val types = listOf("mod","resourcepack","shader","modpack","datapack")
    private val typeNames = listOf("模组","资源包","光影","整合包","数据包")
    private fun resourcesPage() {
        val search = entry("输入项目名称或关键词",searchQuery) { searchQuery = it }
        body.place(search,16)
        body.place(button("类别  ·  " + typeNames[category]) { options("选择资源类别",typeNames) { category=it; results=emptyList();resourceSearched=false;searchQuery=search.text.toString();render() } },10)
        body.place(button("搜索 Modrinth",true) {
            searchQuery = search.text.toString().trim()
            job("检索 Modrinth",{ Modrinth.search(searchQuery,types[category]) }) { results=it;resourceSearched=true;render() }
        },8)
        if (resourceSearched) section("搜索结果  ·  " + results.size)
        if (resourceSearched && results.isEmpty()) card("没有找到匹配项目","可修改关键词或资源类别后重试。")
        results.forEach { p -> card(p.title,p.description.take(180)) { resource(p) } }
    }
    private fun resource(p: Modrinth.Project) {
        val url = Uri.Builder().scheme("https").authority("modrinth.com").appendPath(p.type).appendPath(p.slug).build()
        val choices = mutableListOf("查看项目介绍")
        if (p.type in listOf("mod","resourcepack","shader")) choices.add("安装到游戏实例")
        if (p.type=="modpack") choices.add("下载并创建整合包实例")
        options(p.title, choices) { n ->
            if (n == 0) runCatching { startActivity(Intent(Intent.ACTION_VIEW,url)) }.onFailure { note("项目地址",url.toString()) }
            else if(p.type=="modpack") {
                job("安装整合包",{ update ->
                    val archive=Modrinth.downloadPack(p.id,cacheDir)
                    try { archive.inputStream().use { PackImport.install(root,it,progress(update)) } } finally { archive.delete() }
                }) { page=0;render() }
            } else {
                val all = records(false).filter { p.type != "mod" || it.loader != "vanilla" }
                if (all.isEmpty()) note("缺少匹配实例", if(p.type == "mod") "先在游戏页安装 Fabric 实例。" else "先在游戏页安装游戏文件。")
                else options("选择实例", all.map { it.name }) { k ->
                    val i=all[k]
                    job("解析依赖并校验下载",{ Modrinth.install(p.id,p.type,i.id,i.loader,i.dir) }) { files -> note("资源已安装",files.joinToString("\n") { it.name }.ifBlank { "实例中的资源已是所需版本。" }) }
                }
            }
        }
    }
    private fun online() {
        val field=entry("域名、IP 或 [IPv6]:端口",address) { address = it }
        body.place(field,18)
        body.place(button("检测服务器",true) { probe(field.text.toString().trim()) },12)
        body.place(button("陶瓦联机房间") {
            startActivity(Intent(this, dev.lumencraft.rooms.TerracottaActivity::class.java))
        },8)
        body.place(button("发现局域网世界") {
            job("监听局域网广播",{ update ->
                update("等待局域网世界公告，约 5 秒")
                val cm=getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val iface=cm.getLinkProperties(cm.activeNetwork)?.interfaceName ?: error("未连接局域网")
                val wifi=applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                val lock=wifi.createMulticastLock("lumencraft-lan")
                lock.acquire()
                try { LanDiscovery.listen(java.net.NetworkInterface.getByName(iface)) } finally {lock.release()}
            }) { worlds ->
                if(worlds.isEmpty()) note("没有收到世界公告","请确认设备连接同一局域网，并已在游戏中开放世界。")
                else options("选择世界",worlds.map {it.title+" · "+it.address}) { probe(worlds[it].address) }
            }
        },8)
        status?.let { s ->
            val modSummary = if (s.advertisedMods.isNotEmpty()) "\n服务端声明模组 ${s.advertisedMods.size} 个" else ""
            val box=card(s.address,"${s.motd}\n\n${s.versionName}   ·   协议 ${s.protocol}\n${s.online} / ${s.max} 人   ·   ${s.latencyMs} ms" + (s.loaderHint?.takeIf { it.isNotBlank() }?.let { "\n加载器提示 $it" } ?: "") + modSummary)
            box.place(button("查找服务器整合包") {
                job("读取服务器发布的清单",{ ServerPack.discover(s.address,s.versionName,installer.versions()) }) { pack ->
                    dialogBuilder().setTitle(pack.name)
                        .setMessage("Minecraft "+pack.game+"\n来源 "+Uri.parse(pack.url).host+"\n%.1f MiB".format(Locale.ROOT,pack.size/1048576.0)+"\n\n将下载此服务器指定的模组和配置，为该版本创建独立实例。")
                        .setNegativeButton("取消",null).setPositiveButton("安装") { _,_ ->
                            job("同步服务器整合包",{ update ->pack.install(root,progress(update)) }) { dir ->
                                prefs.edit().putString("title/"+dir.absolutePath,s.address+" · "+pack.name).apply()
                                page=0;render()
                            }
                        }.show()
                }
            },12)
            box.place(button("同步 AutoModpack 清单") {
                ask("AutoModpack 清单地址","https://服务器地址/automodpack/content.json") { url ->
                    job("解析 AutoModpack 清单",{ AutoModpackSync.fetchManifest(url) }) { manifest ->
                        dialogBuilder().setTitle(manifest.modpackName)
                            .setMessage("Minecraft ${manifest.mcVersion} · ${manifest.loader}\n共 ${manifest.files.size} 个客户端同步文件。")
                            .setNegativeButton("取消",null).setPositiveButton("同步") { _,_ ->
                                job("同步 AutoModpack",{ update -> AutoModpackSync.sync(root,s.address,manifest,progress(update)) }) { res ->
                                    prefs.edit().putString("title/"+res.instanceDir.absolutePath,s.address+" · "+manifest.modpackName).apply()
                                    page=0;render()
                                    note("同步完成","已下载 ${res.downloaded} 个文件，复用 ${res.reused} 个，跳过纯服务端 ${res.skippedServerOnly} 个。")
                                }
                            }.show()
                    }
                }
            },8)
            box.place(button("按报告版本准备游戏文件") {
                job("核对官方版本",{ s.exactVersion(installer.versions().map { it.id }) ?: error("服务器没有报告唯一的官方版本。请从游戏页手动选择。") }) { id ->
                    dialogBuilder().setTitle("准备 $id 的基础文件？")
                        .setMessage("将为 ${s.address} 建立独立实例。状态协议没有提供完整客户端模组清单。")
                        .setNegativeButton("取消",null).setPositiveButton("准备文件") { _,_ ->
                            val name = "server-" + UUID.nameUUIDFromBytes((s.address + "/" + id).toByteArray())
                            install(id,name,false,null,s.address + " · " + id)
                        }.show()
                }
            },12)
            box.place(button(if(saved("favorites").contains(s.address)) "移除收藏" else "收藏服务器") {
                val fav=saved("favorites").toMutableList()
                if(!fav.remove(s.address)) fav.add(0,s.address)
                save("favorites",fav);render()
            },8)
        }
        for ((key,title) in listOf("favorites" to "收藏","history" to "最近连接")) {
            val items=saved(key)
            if (items.isNotEmpty()) { section(title); items.forEach { card(it,action={ probe(it) }) } }
        }
    }
    private fun probe(input:String) {
        address=input
        job("读取服务器状态", {
            val cm=getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            ServerProbe.probe(input,cm.getLinkProperties(cm.activeNetwork)?.dnsServers.orEmpty())
        }) { status=it; save("history",(listOf(it.address)+saved("history")).distinct().take(20)); render() }
    }
    private fun saved(key:String):List<String> {
        val a=JSONArray(prefs.getString(key,"[]")); return (0 until a.length()).map { a.getString(it) }
    }
    private fun save(key:String, values:List<String>) { prefs.edit().putString(key,JSONArray(values).toString()).apply() }
    private fun settings() {
        card("账户管理","离线档案与 Microsoft 账户") {
            startActivity(Intent(this,dev.lumencraft.account.AccountsActivity::class.java))
        }
        val modes = listOf("跟随系统","浅色","深色")
        card("界面主题",modes[Appearance.mode(this).coerceIn(0,2)]) {
            options("界面主题",modes) { n ->
                if (n != Appearance.mode(this)) {
                    prefs.edit().putInt("appearance",n).apply()
                    recreate()
                }
            }
        }
        val motion = prefs.getBoolean("motion",true)
        card("界面动画",if(motion) "自动 · 遵循系统动画、省电及低内存设置" else "关闭") {
            options("界面动画",listOf("自动","关闭")) { n ->
                prefs.edit().putBoolean("motion",n==0).apply()
                render()
            }
        }
        val group = column()
        lateinit var toggle: LinearLayout
        toggle = card(if(advanced) "收起运行设置" else "展开运行设置",action={
            android.transition.TransitionManager.endTransitions(body)
            if (Appearance.motion(this)) {
                val change = android.transition.AutoTransition().apply {
                    duration = 140
                    interpolator = android.view.animation.DecelerateInterpolator()
                }
                android.transition.TransitionManager.beginDelayedTransition(body,change)
            }
            advanced = !advanced
            (toggle.getChildAt(0) as TextView).text = if(advanced) "收起运行设置" else "展开运行设置"
            populateAdvanced(group)
        })
        body.place(group)
        populateAdvanced(group)
        card("版本与兼容范围","LumenCraft " + packageManager.getPackageInfo(packageName,0).versionName) {
            note("此版本的范围","远古客户端适配 "+ClassicInstall.versions().joinToString("、")+"。其他游戏版本支持完整文件校验、加载器安装与启动计划装配。服务端支持 Vanilla、Fabric、Paper、Purpur、NeoForge 与 Forge 独立 OpenJDK 进程。\n\nFabric、Quilt、NeoForge、Forge、LiteLoader、Cleanroom、本地 OptiFine、Modrinth、AutoModpack 与多格式整合包已接入。图形层包含扩展 GLES 2 固定管线、Rust GLSL 转换器与动态 Vulkan 探测。现代 LWJGL 3 实机窗口渲染仍待后续适配。")
        }
    }
    private fun populateAdvanced(group: LinearLayout) {
        group.removeAllViews()
        if(!advanced) return
        val originalBody = body
        body = group
        try {
            card("设备","Android " + Build.VERSION.RELEASE + "  ·  API " + Build.VERSION.SDK_INT + "\n" +
                    Build.MANUFACTURER + " " + Build.MODEL + "\n" + Build.SUPPORTED_ABIS.joinToString(" / "))
            val am=getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mem=android.app.ActivityManager.MemoryInfo().also {am.getMemoryInfo(it)}
            val power=getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val thermal=if(Build.VERSION.SDK_INT>=29) "\n系统热状态 "+(listOf("正常","轻微","中等","严重","临界","紧急","关机").getOrNull(power.currentThermalStatus) ?: "未知") else ""
            card("设备状态","可用内存 %.0f / %.0f MiB".format(Locale.ROOT,mem.availMem/1048576.0,mem.totalMem/1048576.0)+thermal)
            card("下载源与测速",MirrorBench.activeSource().label + if(MirrorBench.activeSource()==MirrorBench.SourceId.CUSTOM) " · "+MirrorBench.customBaseUrl() else "") {
                options("下载源配置",listOf("自动测速并选择最快源","官方源 (Mojang)","BMCLAPI 镜像（自动校对官方摘要）","设置自定义 HTTPS 源")) { n ->
                    when (n) {
                        0 -> job("测速下载源",{ MirrorBench.benchmark(prefs.getString("download-custom","")) }) { list ->
                            val best = list.firstOrNull { it.reachable }
                            if (best != null) {
                                MirrorBench.configure(best.id, prefs.getString("download-custom",""))
                                prefs.edit().putString("download-source", best.id.name).apply()
                            }
                            render()
                            note("下载源测速结果", list.joinToString("\n") {
                                it.id.label + "：" + if (it.reachable) "${it.latencyMs} ms" else "不可达 (${it.error})"
                            } + if (best != null) "\n\n已自动选用：${best.id.label}" else "")
                        }
                        1 -> { MirrorBench.configure(MirrorBench.SourceId.OFFICIAL,""); prefs.edit().putString("download-source","OFFICIAL").apply(); render() }
                        2 -> { MirrorBench.configure(MirrorBench.SourceId.BMCLAPI,""); prefs.edit().putString("download-source","BMCLAPI").apply(); render() }
                        3 -> ask("自定义下载源","https://your-mirror.example",prefs.getString("download-custom","")?:"") { url ->
                            runCatching {
                                MirrorBench.configure(MirrorBench.SourceId.CUSTOM, url)
                                prefs.edit().putString("download-source","CUSTOM").putString("download-custom",MirrorBench.customBaseUrl()).apply()
                                render()
                            }.onFailure { note("下载源无效", it.localizedMessage ?: "格式错误") }
                        }
                    }
                }
            }
            val limited=prefs.getBoolean("low-power",false)
            card("性能推荐与配置回滚",if(limited) "省电模式 · 15 FPS" else "自动按硬件与热状态调整") {
                options("性能与配置",listOf("查看硬件推荐参数","切换省电模式（当前：" + (if(limited)"15 FPS" else "自动") + "）","保存当前配置快照","回滚到上次配置快照")) { n ->
                    when (n) {
                        0 -> job("评估设备性能与图形后端",{
                            val probe = GraphicsProbe.inspect()
                            val rec = DeviceProfile.recommend(mem.totalMem,mem.availMem,am.isLowRamDevice,power.isPowerSaveMode,probe,true)
                            "推荐堆内存：${rec.heapMiB} MiB\n目标帧率：${rec.maxFps} FPS\n分辨率比例：${(rec.resolutionScale*100).toInt()}%\n推荐后端：${rec.rendererBackend}\n说明：${rec.reason}"
                        }) { note("硬件性能评估",it) }
                        1 -> { prefs.edit().putBoolean("low-power",!limited).apply(); render() }
                        2 -> runCatching {
                            val snap = JSONObject().put("appearance",Appearance.mode(this)).put("motion",prefs.getBoolean("motion",true))
                                .put("low-power",limited).put("download-source",MirrorBench.activeSource().name)
                                .put("download-custom",MirrorBench.customBaseUrl())
                            DeviceProfile.saveSnapshot(File(root,"config-snapshot.json"), snap)
                            Toast.makeText(this,"已保存安全配置快照",Toast.LENGTH_SHORT).show()
                        }.onFailure { note("保存失败",it.localizedMessage ?: "错误") }
                        3 -> runCatching {
                            val snap = DeviceProfile.loadSnapshot(File(root,"config-snapshot.json"))
                            prefs.edit().putInt("appearance",snap.optInt("appearance",0)).putBoolean("motion",snap.optBoolean("motion",true))
                                .putBoolean("low-power",snap.optBoolean("low-power",false))
                                .putString("download-source",snap.optString("download-source","OFFICIAL"))
                                .putString("download-custom",snap.optString("download-custom","")).apply()
                            MirrorBench.configure(MirrorBench.SourceId.valueOf(snap.optString("download-source","OFFICIAL")), snap.optString("download-custom",""))
                            recreate()
                        }.onFailure { note("回滚失败",it.localizedMessage ?: "没有可用快照") }
                    }
                }
            }
            card("glibc 兼容层",if(dev.lumencraft.runtime.RuntimeStore.hasGlibcCompat(this)) "已配置独立 glibc 兼容目录" else "未导入（可选，点击导入兼容包 ZIP）") {
                pickFile(false,"application/zip") { uri ->
                    job("导入 glibc 兼容包",{ dev.lumencraft.runtime.RuntimeStore.importGlibcCompat(this,uri) }) { count ->
                        render(); note("glibc 兼容目录已就绪","已提取 $count 项到独立运行目录，将在后续进程中挂载。")
                    }
                }
            }
            card("图形能力检测","检测 EGL/GLES、Vulkan 硬件支持与 LumenGL 像素/着色器转换。") {
                job("读取图形驱动",{ GraphicsProbe.inspect()+"\n\n"+LegacyGraphics.check() }) { note("设备图形报告",it) }
            }
            card("存储","应用私有目录  ·  可用 %.1f GiB".format(Locale.ROOT,filesDir.usableSpace / 1073741824.0)) {
                job("统计本地文件",{ root.walkTopDown().filter { it.isFile }.fold(0L) { sum,f -> sum + f.length() } }) { n ->
                    note("本地文件","%.1f MiB\n%s".format(Locale.ROOT,n/1048576.0,root.absolutePath))
                }
            }
        } finally { body = originalBody }
    }
    private fun mods(i:Instance) {
        val dir = File(i.dir,"mods")
        val list = runCatching { ModManager.listMods(i.dir) }.getOrDefault(emptyList())
        val rollbacks = ModManager.listRollbacks(i.dir)
        val header = mutableListOf("＋ 导入本地 JAR","诊断冲突与缺失前置","一键补全已安装模组前置","批量更新未锁定模组")
        if (rollbacks.isNotEmpty()) header.add("回滚上次批量更新（${rollbacks.size} 个记录）")
        val items = header + list.map { m ->
            val state = (if(m.enabled) "" else "[已停用] ") + (if(m.locked) "[锁定] " else "")
            val ver = if(m.version.isNotEmpty()) " ${m.version}" else ""
            "$state${m.name}$ver (${m.environment})"
        }
        options("实例模组 · ${list.size}", items) { n ->
            if (n == 0) pickFile(false,"application/octet-stream",arrayOf("application/java-archive","application/zip","application/octet-stream","application/x-java-archive","*/*")) { uri ->
                job("导入本地模组",{ contentResolver.openInputStream(uri)!!.use { LocalFiles.importJar(it,dir) } }) { f -> note("已导入",f.name) }
            } else if (n == 1) {
                job("诊断实例模组",{ ModManager.auditMods(i.dir).summary() }) { note("模组诊断报告",it) }
            } else if (n == 2) {
                if (i.loader == "vanilla") note("缺少加载器","请先为此实例安装模组加载器。")
                else job("补全缺失前置",{ ModManager.completeDependencies(i.dir,i.id,i.loader) }) { added ->
                    note("前置补全完成", if(added.isEmpty()) "所有可识别模组的前置依赖均已满足。" else added.joinToString("\n") { it.name })
                }
            } else if (n == 3) {
                if (i.loader == "vanilla") note("缺少加载器","请先为此实例安装模组加载器。")
                else job("批量更新模组",{ ModManager.updateAll(i.dir,i.id,i.loader) }) { res ->
                    note("批量更新结果", "已更新 ${res.updated} 个，已是最新 ${res.upToDate} 个，跳过锁定 ${res.skippedLocked} 个" +
                            if(res.details.isNotEmpty()) "\n\n" + res.details.joinToString("\n") else "")
                }
            } else if (rollbacks.isNotEmpty() && n == 4) {
                options("选择回滚点", rollbacks) { k ->
                    job("回滚模组",{ ModManager.rollback(i.dir, rollbacks[k]) }) { count ->
                        note("已回滚","已恢复 $count 个模组文件及索引快照。")
                    }
                }
            } else {
                val m = list[n - header.size]
                val actions = mutableListOf(if(m.enabled) "停用模组" else "启用模组", "查看模组详情")
                if (m.modrinthProject.isNotEmpty()) actions.add(if(m.locked) "解除版本锁定" else "锁定当前版本")
                actions.add("删除模组")
                options(m.name, actions) { act ->
                    when (actions[act]) {
                        "启用模组", "停用模组" -> {
                            val out=File(dir,if(!m.enabled) m.file.name.removeSuffix(".disabled") else m.file.name+".disabled")
                            if(out.exists() || !m.file.renameTo(out)) note("操作未完成","目标文件已存在或文件不可写。") else mods(i)
                        }
                        "查看模组详情" -> note(m.name, "ID: ${m.modId}\n版本: ${m.version}\n加载器: ${m.loaderType}\n环境: ${m.environment}\n文件: ${m.file.name}\n\n${m.description}")
                        "锁定当前版本", "解除版本锁定" -> runCatching { ModManager.setLocked(i.dir,m.modrinthProject,!m.locked); mods(i) }.onFailure { note("操作失败",it.localizedMessage ?: "错误") }
                        "删除模组" -> runCatching { ModManager.deleteMod(i.dir,m.file); mods(i) }.onFailure { note("删除失败",it.localizedMessage ?: "错误") }
                    }
                }
            }
        }
    }
    private fun exportWorlds(i:Instance) {
        if(i.id in ClassicInstall.versions()){
            val level=File(i.dir,"level.dat")
            if(!level.isFile){note("没有存档","在游戏中保存世界后即可备份。");return}
            pickFile(true,"application/gzip",name=i.id+"-level.dat.gz") { uri ->
                job("导出远古存档",{ contentResolver.openOutputStream(uri,"wt")!!.use { out ->level.inputStream().use {it.copyTo(out)} } }) {note("备份完成","已保存 RubyDung 世界。")}
            }
            return
        }
        val saves=File(i.dir,"saves")
        if(saves.listFiles().isNullOrEmpty()) { note("没有可备份的存档","此实例的 saves 目录为空。"); return }
        pickFile(true,"application/zip",name=i.dir.name+"-saves.zip") { uri ->
            job("导出存档",{ contentResolver.openOutputStream(uri,"wt")!!.use { LocalFiles.backup(saves,it) } }) { note("备份完成","已导出 $it 个文件。") }
        }
    }
    private fun restoreWorlds(i:Instance) {
        if(i.id in ClassicInstall.versions()){
            val history=File(i.dir,"world-backups").listFiles().orEmpty().filter {it.name.endsWith(".dat")}.sortedByDescending{it.lastModified()}
            options("恢复远古存档",listOf("从文件导入")+history.map { java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it.lastModified())) }) { n ->
                if(n==0) pickFile(false,"*/*") { uri ->
                    job("恢复存档",{contentResolver.openInputStream(uri)!!.use {ClassicSaves.restore(i.dir,it)}}) {note("存档已恢复","下次启动时载入。")}
                } else job("恢复自动备份",{history[n-1].inputStream().use {ClassicSaves.restore(i.dir,it)}}) {note("存档已恢复","下次启动时载入。")}
            }
            return
        }
        pickFile(false,"application/zip") { uri ->
            job("恢复到新存档",{ contentResolver.openInputStream(uri)!!.use { LocalFiles.restore(it,File(i.dir,"saves")) } }) { note("存档已恢复",it.joinToString("\n")) }
        }
    }
    private fun pickFile(create:Boolean,mime:String,extra:Array<String>?=null,name:String?=null,done:(Uri)->Unit) {
        val intent=Intent(if(create) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
        intent.type=if(extra!=null) "*/*" else mime
        if(extra!=null) intent.putExtra(Intent.EXTRA_MIME_TYPES,extra)
        if(name!=null) intent.putExtra(Intent.EXTRA_TITLE,name)
        fileResult=done
        runCatching { startActivityForResult(intent,14) }.onFailure { fileResult=null;note("文件选择器",it.localizedMessage ?: "无法打开文件选择器") }
    }
    @Deprecated("Framework callback")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==14) { val callback=fileResult;fileResult=null;if(resultCode==RESULT_OK) data?.data?.let { callback?.invoke(it) } }
    }
}
