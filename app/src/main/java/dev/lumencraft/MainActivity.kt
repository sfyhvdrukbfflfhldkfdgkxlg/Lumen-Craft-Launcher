package dev.lumencraft

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
    private val dark get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private fun color(light: String, night: String) = Color.parseColor(if (dark) night else light)
    private val bg get() = color("#F4F6EF", "#121B17")
    private val paper get() = color("#FFFFFF", "#21302A")
    private val ink get() = color("#1C3228", "#E4EEE8")
    private val muted get() = color("#506458", "#B2C4B8")
    private val accent get() = color("#195D43", "#A3DDB5")
    private val soft get() = color("#DEEDDD", "#324B3B")

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        page = state?.getInt("page")?.coerceIn(0, 4) ?: 0
        address = state?.getString("address") ?: ""
        render()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("page", page)
        outState.putString("address", address)
    }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); render() }
    override fun onRestart() { super.onRestart(); if(activeJob==null) render() }
    override fun onDestroy() { activeJob?.interrupt(); super.onDestroy() }
    private fun dp(v: Int) = (resources.displayMetrics.density * v + .5f).toInt()
    private fun shape(c: Int, radius: Int = 14) = GradientDrawable().apply { setColor(c); cornerRadius = dp(radius).toFloat() }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun label(s: String, size: Int, c: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size.toFloat(); setTextColor(c); includeFontPadding = false
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private fun LinearLayout.place(v: View, gap: Int = 0, height: Int = -2) {
        addView(v, LinearLayout.LayoutParams(-1, if (height < 0) height else dp(height)).apply { topMargin = dp(gap) })
    }
    private fun render() {
        window.statusBarColor = bg; window.navigationBarColor = paper
        window.decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            (if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        val screen = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; setBackgroundColor(bg) }
        if (Build.VERSION.SDK_INT >= 35) screen.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        val wide=resources.configuration.screenWidthDp>=640
        val rail=ScrollView(this).apply {setBackgroundColor(paper);isVerticalScrollBarEnabled=false}
        val nav=column().apply {setPadding(dp(10),dp(16),dp(10),dp(14))}
        val brand=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(dp(4),0,0,0)}
        brand.addView(ImageView(this).apply { setImageResource(R.drawable.mark) },dp(28),dp(28))
        brand.addView(label("微光",21,ink,true),LinearLayout.LayoutParams(-2,-2).apply {leftMargin=dp(7)})
        nav.place(brand)
        nav.place(label("LumenCraft",13,muted).apply {setPadding(dp(4),0,0,0)},5)
        pages.forEachIndexed { i, name ->
            val tab = label(name, 16, if (page == i) accent else muted, page == i)
            tab.gravity = Gravity.CENTER_VERTICAL
            tab.setPadding(dp(16),0,dp(4),0)
            if (i == page) tab.background = shape(soft)
            tab.isSelected = i == page
            tab.isFocusable=true
            tab.setOnClickListener { page = i; render() }
            nav.place(tab,if(i==0) 18 else 3,46)
        }
        rail.addView(nav)
        screen.addView(rail,LinearLayout.LayoutParams(dp(if(wide) 128 else 106),-1))
        val scroll = ScrollView(this).apply { isFillViewport = true }
        body = column().apply { setPadding(dp(if(wide) 24 else 14),dp(16),dp(if(wide) 24 else 14),dp(20)) }
        scroll.addView(body);screen.addView(scroll,LinearLayout.LayoutParams(0,-1,1f))
        body.place(label(headings[page],28,ink,true))
        when (page) { 0 -> instances(false); 1 -> resourcesPage(); 2 -> online(); 3 -> instances(true); 4 -> settings() }
        setContentView(screen)
    }
    private fun card(title: String, notes: String = "", action: (() -> Unit)? = null): LinearLayout {
        val panel = column().apply { background = shape(paper); setPadding(dp(16),dp(16),dp(16),dp(16)) }
        panel.place(label(title, 18, ink, true))
        if (notes.isNotBlank()) panel.place(label(notes, 14, muted), 8)
        if (action != null) panel.setOnClickListener { action() }
        body.place(panel, 12); return panel
    }
    private fun button(s: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = s; textSize = 15f; isAllCaps = false; minHeight = dp(48)
        setTextColor(if (primary) color("#FFFFFF", "#10251A") else accent)
        background = shape(if (primary) accent else soft, 12)
        stateListAnimator = null
        setOnClickListener { action() }
    }
    private fun entry(hintText: String, value: String = "") = EditText(this).apply {
        hint = hintText; setSingleLine(true); setText(value); textSize = 16f
        setTextColor(ink); setHintTextColor(muted)
    }
    private fun section(s: String) = body.place(label(s, 15, muted, true), 22)
    private fun note(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("关闭", null).show()
    }
    private fun options(title: String, items: List<String>, pick: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setItems(items.toTypedArray()) { _, n -> pick(n) }.show()
    }
    private fun ask(title: String, hint: String, initial: String = "", onSubmit: (String) -> Unit) {
        val field = entry(hint, initial)
        val box = column().apply { setPadding(dp(20), dp(8), dp(20), 0); addView(field) }
        AlertDialog.Builder(this).setTitle(title).setView(box).setNegativeButton("取消",null)
            .setPositiveButton("确定") { _,_ -> val value = field.text.toString().trim(); if (value.isNotEmpty()) onSubmit(value) }.show()
    }
    private fun <T> job(title: String, work: ((String) -> Unit) -> T, success: (T) -> Unit) {
        if (activeJob != null) return
        val box = column().apply { setPadding(dp(24),dp(18),dp(24),dp(4)) }
        val info = label("正在处理…", 15, muted)
        box.place(ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }); box.place(info, 8)
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(box).setCancelable(false).setNegativeButton("取消",null).create()
        dialog.show()
        val task = Thread({
            val result = runCatching { work { line -> runOnUiThread { if (dialog.isShowing) info.text = line } } }
            val cancelled = Thread.currentThread().isInterrupted
            runOnUiThread {
                activeJob = null
                if (!isDestroyed && !isFinishing) {
                    dialog.dismiss()
                    if (cancelled) Toast.makeText(this,"任务已中止",Toast.LENGTH_SHORT).show()
                    else result.fold(success) { e -> note(title, e.localizedMessage ?: e.javaClass.simpleName) }
                }
            }
        }, "library-worker")
        activeJob = task
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { task.interrupt(); info.text = "正在结束任务…"; it.isEnabled = false }
        task.start()
    }
    private fun progress(update: (String) -> Unit) = VanillaInstaller.Progress { label, done, total -> update("$label   $done / $total") }
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
        if (!server) body.place(button("导入 Modrinth 整合包") {
            pickFile(false,"*/*") { uri ->
                job("导入整合包",{ update -> contentResolver.openInputStream(uri)!!.use { PackImport.install(root,it,progress(update)) } }) { render() }
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
            if (i.loader == "vanilla" && i.id !in ClassicInstall.versions()) actions.add("安装模组加载器" to { options("选择加载器",listOf("Fabric","Quilt")) { addLoader(i,if(it==0) "fabric" else "quilt") } })
            actions.add("管理本地模组" to { mods(i) })
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
                AlertDialog.Builder(this).setMessage("将 ${i.name} 移出列表？可以在游戏页恢复。")
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
                        val engines=mutableListOf("Vanilla","Fabric")
                        if(ServerDistributions.supportsPurpur(id)) engines.add("Purpur")
                        options("服务端组件",engines) { e -> serverInstall(id,engines[e].lowercase(Locale.ROOT)) }
                    }
                    else options("安装 $id", listOf("原版游戏文件","游戏文件 + Fabric","游戏文件 + Quilt")) { n ->
                        val loader=listOf(null,"fabric","quilt")[n]
                        install(id, if(loader==null) id else "$id-$loader",false,loader)
                    }
                }
            }
        }
    }
    private fun install(id: String, name: String, server: Boolean, loader: String?, display: String? = null) {
        job("安装并校验文件", { update ->
            val dir = installer.install(id,name,server,progress(update))
            if (loader!=null) FabricInstaller.install(root,dir,id,loader,progress(update))
            if (display != null) prefs.edit().putString("title/" + dir.absolutePath, display).apply()
            dir
        }) { page = if (server) 3 else 0; render(); Toast.makeText(this,"文件已就绪",Toast.LENGTH_SHORT).show() }
    }
    private fun addLoader(i: Instance,loader:String) {
        job("安装 $loader", { update -> FabricInstaller.install(root,i.dir,i.id,loader,progress(update)) }) { render(); note("加载器已安装",it.toString()) }
    }
    private fun serverInstall(game:String,engine:String) {
        if(engine=="vanilla") {install(game,game,true,null);return}
        job("安装服务端组件",{update ->
            val service=ServerDistributions(root)
            if(engine=="fabric") service.installFabric(game,progress(update))
            else if(engine=="purpur") service.installPurpur(game,progress(update))
            else error("未知服务端组件")
        }) {page=3;render()}
    }
    private val types = listOf("mod","resourcepack","shader","modpack","datapack")
    private val typeNames = listOf("模组","资源包","光影","整合包","数据包")
    private fun resourcesPage() {
        val search = entry("输入项目名称或关键词",searchQuery)
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
        val field=entry("域名、IP 或 [IPv6]:端口",address)
        body.place(field,18)
        body.place(button("检测服务器",true) { probe(field.text.toString().trim()) },12)
        body.place(button("陶瓦联机房间") {
            try { startActivity(Intent().setClassName("dev.lumencraft.rooms","dev.lumencraft.rooms.TerracottaActivity")) }
            catch (_:android.content.ActivityNotFoundException) { note("安装联机组件","请安装随项目提供的 LumenCraft-Rooms-Alpha1.0.0.apk，然后再次打开陶瓦联机房间。") }
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
            val box=card(s.address,"${s.motd}\n\n${s.versionName}   ·   协议 ${s.protocol}\n${s.online} / ${s.max} 人   ·   ${s.latencyMs} ms" + (s.loaderHint?.takeIf { it.isNotBlank() }?.let { "\n加载器提示 $it" } ?: ""))
            box.place(button("查找服务器整合包") {
                job("读取服务器发布的清单",{ ServerPack.discover(s.address,s.versionName,installer.versions()) }) { pack ->
                    AlertDialog.Builder(this).setTitle(pack.name)
                        .setMessage("Minecraft "+pack.game+"\n来源 "+Uri.parse(pack.url).host+"\n%.1f MiB".format(Locale.ROOT,pack.size/1048576.0)+"\n\n将下载此服务器指定的模组和配置，为该版本创建独立实例。")
                        .setNegativeButton("取消",null).setPositiveButton("安装") { _,_ ->
                            job("同步服务器整合包",{ update ->pack.install(root,progress(update)) }) { dir ->
                                prefs.edit().putString("title/"+dir.absolutePath,s.address+" · "+pack.name).apply()
                                page=0;render()
                            }
                        }.show()
                }
            },12)
            box.place(button("按报告版本准备游戏文件") {
                job("核对官方版本",{ s.exactVersion(installer.versions().map { it.id }) ?: error("服务器没有报告唯一的官方版本。请从游戏页手动选择。") }) { id ->
                    AlertDialog.Builder(this).setTitle("准备 $id 的基础文件？")
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
        card("设备","Android ${Build.VERSION.RELEASE}  ·  API ${Build.VERSION.SDK_INT}\n${Build.MANUFACTURER} ${Build.MODEL}\n${Build.SUPPORTED_ABIS.joinToString(" / ")}")
        card(if(advanced) "收起运行设置" else "展开运行设置",action={advanced=!advanced;render()})
        if(advanced){
            val am=getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mem=android.app.ActivityManager.MemoryInfo().also {am.getMemoryInfo(it)}
            val power=getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val thermal=if(Build.VERSION.SDK_INT>=29) "\n系统热状态 "+(listOf("正常","轻微","中等","严重","临界","紧急","关机").getOrNull(power.currentThermalStatus) ?: "未知") else ""
            card("设备状态","可用内存 %.0f / %.0f MiB".format(Locale.ROOT,mem.availMem/1048576.0,mem.totalMem/1048576.0)+thermal)
            val limited=prefs.getBoolean("low-power",false)
            card("远古版省电模式",if(limited) "已开启，下一次启动上限 15 FPS" else "自动，系统省电或低内存设备上限 15 FPS，其他设备上限 30 FPS"){
                options("远古版帧率",listOf("自动","始终省电 · 15 FPS")){n->prefs.edit().putBoolean("low-power",n==1).apply();render()}
            }
        }
        card("图形能力检测","创建 EGL 上下文并查询设备驱动。") {
            job("读取图形驱动",{ GraphicsProbe.inspect()+"\n"+LegacyGraphics.check() }) { note("设备报告",it) }
        }
        card("存储","应用私有目录  ·  可用 %.1f GiB".format(Locale.ROOT,filesDir.usableSpace / 1073741824.0)) {
            job("统计本地文件",{ root.walkTopDown().filter { it.isFile }.fold(0L) { sum,f -> sum + f.length() } }) { n ->
                note("本地文件","%.1f MiB\n%s".format(Locale.ROOT,n/1048576.0,root.absolutePath))
            }
        }
        card("版本与兼容范围","LumenCraft ${packageManager.getPackageInfo(packageName,0).versionName}") {
            note("此版本的范围","远古客户端适配 "+ClassicInstall.versions().joinToString("、")+"。其他游戏版本目前支持文件准备。服务端使用独立 OpenJDK 进程。\n\nFabric、Quilt 和 Modrinth 已接入。陶瓦房间使用单独安装的联机组件。现代 LWJGL 客户端和完整桌面 OpenGL 仍待实现。")
        }
    }
    private fun mods(i:Instance) {
        val dir = File(i.dir,"mods")
        val files = dir.listFiles().orEmpty().filter { it.isFile && (it.name.endsWith(".jar",true) || it.name.endsWith(".jar.disabled",true)) }.sortedBy {it.name}
        options("实例模组",listOf("导入本地 JAR")+files.map{it.name}) { n ->
            if(n==0) pickFile(false,"application/octet-stream",arrayOf("application/java-archive","application/zip","application/octet-stream","application/x-java-archive","*/*")) { uri ->
                job("导入本地模组",{ contentResolver.openInputStream(uri)!!.use { LocalFiles.importJar(it,dir) } }) {f->note("已导入",f.name)}
            } else {
                val f=files[n-1];val disabled=f.name.endsWith(".disabled")
                options(f.name,listOf(if(disabled) "启用模组" else "停用模组")) {
                    val out=File(dir,if(disabled) f.name.removeSuffix(".disabled") else f.name+".disabled")
                    if(out.exists() || !f.renameTo(out)) note("操作未完成","目标文件已存在或文件不可写。") else mods(i)
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
