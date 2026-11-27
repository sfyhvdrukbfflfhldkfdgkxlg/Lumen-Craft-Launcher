package dev.lumencraft.client

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.view.Gravity
import dev.lumencraft.runtime.RuntimeStore
import dev.lumencraft.core.ClassicInstall
import dev.lumencraft.core.ClassicSaves
import dev.lumencraft.core.CrashNotes
import org.json.JSONObject
import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class ClientActivity : Activity() {
    companion object {
        fun open(activity:Activity,dir:File) { activity.startActivity(Intent(activity,ClientActivity::class.java).putExtra("instance",dir.name)) }
    }
    private lateinit var controls:ControlOverlay
    private lateinit var screen:GameView
    private lateinit var status:TextView
    private val closing=AtomicBoolean(false)
    private val commands=LinkedBlockingQueue<IntArray>(1024)
    @Volatile private var host:Process?=null
    @Volatile private var listener:ServerSocket?=null
    @Volatile private var connection:Socket?=null
    @Volatile private var writer:Thread?=null
    @Volatile private var receiver:Thread?=null
    private val ready=AtomicBoolean(false)
    private val foreground=AtomicBoolean(false)
    private val token=UUID.randomUUID().toString().replace("-","")
    override fun onCreate(saved:Bundle?) {
        super.onCreate(saved)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility=5894
        val root=File(filesDir,"library").canonicalFile
        val folder=File(root,"instances")
        val dir=File(folder,intent.getStringExtra("instance") ?: "").canonicalFile
        if (dir.parentFile!=folder) { finish();return }
        val layer=FrameLayout(this)
        screen=GameView(this)
        layer.addView(screen,-1,-1)
        controls=ControlOverlay(this,dir) { k,a,b ->
            if (ready.get() && !closing.get() && !commands.offer(intArrayOf(k,a,b)) && k!=2) {
                // A lost release would leave a held key. End the session on sustained input backpressure.
                stopSession()
            }
        }
        layer.addView(controls,-1,-1)
        status=TextView(this).apply { text="准备 Java 8 和游戏进程…";setTextColor(Color.WHITE);gravity=Gravity.CENTER;setBackgroundColor(0xAA13231A.toInt());setPadding(12,8,12,8) }
        layer.addView(status,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
        setContentView(layer)
        receiver=Thread({ runSession(root,dir) },"classic-session").also { it.start() }
    }
    private fun runSession(root:File,dir:File) {
        val log=File(dir,"latest-client.log")
        try {
            val meta=JSONObject(File(dir,"install.json").readText())
            require(meta.getString("id") in ClassicInstall.versions()) { "此版本没有可用客户端适配模块" }
            val jar=File(root,meta.getString("jar")).canonicalFile
            require(jar.path.startsWith(root.path+File.separator) && jar.isFile) { "找不到已安装的游戏文件" }
            ClassicSaves.checkpoint(dir)
            val bridge=File.createTempFile("classic-",".jar",codeCacheDir)
            try {
                assets.open("classic-bridge.jar").use { src -> bridge.outputStream().use { src.copyTo(it) } }
                ServerSocket(0,4,InetAddress.getByName("127.0.0.1")).use { server ->
                    listener=server;server.soTimeout=1500
                    if(closing.get()) return
                    val child=RuntimeStore.startClient(this,dir,bridge,jar,server.localPort,token) { phase,done,total ->
                        val label=when {
                            total>0 -> "%s  %.1f / %.1f MiB".format(java.util.Locale.ROOT,phase,done/1048576.0,total/1048576.0)
                            done>0 -> "%s  %.1f MiB".format(java.util.Locale.ROOT,phase,done/1048576.0)
                            else -> phase
                        }
                        runOnUiThread {if(!isFinishing && !ready.get()) status.text=label}
                    }
                    host=child
                    val drain=Thread({
                        runCatching { child.inputStream.use { input -> log.outputStream().use { out ->
                            val buf=ByteArray(8192); var written=0
                            while(true) { val n=input.read(buf);if(n<0) break
                                val keep=minOf(n,(1024*1024-written).coerceAtLeast(0));if(keep>0) {out.write(buf,0,keep);written+=keep;out.flush()} }
                        } } }
                    },"client-output").also {it.start()}
                    val until=android.os.SystemClock.elapsedRealtime()+90000
                    while(!closing.get() && android.os.SystemClock.elapsedRealtime()<until) {
                        if (runCatching { child.exitValue() }.isSuccess) { drain.join(700);error("游戏进程已退出\n"+logTail(log)) }
                        val peer=try { server.accept() } catch(_:SocketTimeoutException) {continue}
                        peer.use {
                            connection=it;it.soTimeout=2500; it.tcpNoDelay=true
                            val input=DataInputStream(BufferedInputStream(it.getInputStream()))
                            if (!runCatching {input.readUTF()==token}.getOrDefault(false)) return@use
                            require(input.readInt()==320 && input.readInt()==240) { "帧尺寸与协议不匹配" }
                            it.soTimeout=0
                            stream(it,input)
                        }
                        if (ready.get()) break
                    }
                    if (!closing.get() && !ready.get()) error("等待游戏画面超时\n"+logTail(log))
                    if (!closing.get() && ready.get()) {
                        for (i in 0 until 30) {
                            if(runCatching {child.exitValue()}.isSuccess) break
                            Thread.sleep(100)
                        }
                        if(runCatching {child.exitValue()}.getOrNull()==0) runOnUiThread {finish()}
                        else error("游戏连接已关闭")
                    }
                }
            } finally { bridge.delete() }
        } catch(e:Exception) {
            if (!closing.get()) runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    status.text="游戏进程已结束"
                    val cause=e.localizedMessage ?: e.javaClass.simpleName
                    val tail=logTail(log)
                    val report=if(tail.isEmpty() || cause.endsWith(tail)) cause else cause+"\n"+tail
                    val diagnosis=CrashNotes.diagnose(report)
                    val message=listOf(diagnosis.summary,report).filter {it.isNotBlank()}.joinToString("\n\n")
                    val dialog=AlertDialog.Builder(this).setTitle("客户端诊断").setMessage(message)
                        .setPositiveButton("返回游戏页"){ _,_->finish() }.setOnCancelListener {finish()}
                    if (diagnosis.action == CrashNotes.RepairAction.LOWER_HEAP || diagnosis.action == CrashNotes.RepairAction.FALLBACK_RENDERER) {
                        dialog.setNeutralButton(diagnosis.repairLabel) { _,_ ->
                            getSharedPreferences("library", MODE_PRIVATE).edit().putBoolean("low-power", true).apply()
                            finish()
                        }
                    }
                    dialog.show()
                }
            }
        } finally {
            writer?.interrupt()
            val process=host
            if(process!=null && closing.get()) {
                val until=android.os.SystemClock.elapsedRealtime()+3500
                while(runCatching { process.exitValue() }.isFailure && android.os.SystemClock.elapsedRealtime()<until)
                    try { Thread.sleep(80) } catch(_:InterruptedException) {break}
            }
            process?.destroy()
            host=null;listener=null;connection=null
        }
    }
    private fun logTail(file:File)=runCatching { if(file.isFile) file.readText().takeLast(1700) else "" }.getOrDefault("")
    private fun stream(s:Socket,input:DataInputStream) {
        val output=DataOutputStream(BufferedOutputStream(s.getOutputStream()))
        writer=Thread({
            try {
                while(true) {
                    val c=commands.take();output.writeInt(c[0]);output.writeInt(c[1]);output.writeInt(c[2]);output.flush()
                    if(c[0]==4) break
                }
            } catch(_:Exception) { runCatching { s.close() } }
        },"classic-input").also {it.start()}
        val buffer=ByteArray(320*240*4)
        try {
            while(true) {
                if(input.readInt()!=buffer.size) throw IOException("游戏帧长度不符")
                input.readFully(buffer)
                screen.frame(buffer)
                if(ready.compareAndSet(false,true)) runOnUiThread {
                    status.visibility=View.GONE
                    if(!foreground.get()) commands.offer(intArrayOf(5,1,0))
                }
            }
        } catch(e:EOFException) { if(!ready.get() && !closing.get()) throw IOException("进程在首帧前关闭了连接",e) }
    }
    private fun stopSession() {
        if(!closing.compareAndSet(false,true)) return
        if(!ready.get()) { runCatching {listener?.close()}; receiver?.interrupt() }
        commands.clear();commands.offer(intArrayOf(4,0,0))
        Thread({
            try { Thread.sleep(4000) } catch(_:InterruptedException) {}
            host?.destroy();runCatching {connection?.close()}
        },"classic-exit").start()
    }
    override fun onResume() { super.onResume();foreground.set(true);if(ready.get()) commands.offer(intArrayOf(5,0,0)) }
    override fun onWindowFocusChanged(hasFocus:Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if(hasFocus) window.decorView.systemUiVisibility=5894
    }
    override fun onPause() {
        foreground.set(false)
        if(::controls.isInitialized) controls.releaseAll()
        if(ready.get()) commands.offer(intArrayOf(5,1,0))
        super.onPause()
    }
    override fun onDestroy() { stopSession();super.onDestroy() }
    override fun dispatchKeyEvent(event:KeyEvent):Boolean {
        if(::controls.isInitialized && event.keyCode!=KeyEvent.KEYCODE_BACK && controls.key(event)) return true
        return super.dispatchKeyEvent(event)
    }
    @Deprecated("Framework callback")
    override fun onBackPressed() { AlertDialog.Builder(this).setMessage("保存并退出游戏？").setNegativeButton("继续游戏",null).setPositiveButton("退出"){_,_->stopSession();finish()}.show() }
    @Deprecated("Framework callback")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        if(::controls.isInitialized && controls.onDocumentResult(requestCode,resultCode,data)) return
        super.onActivityResult(requestCode,resultCode,data)
    }
    private class GameView(c:Context):View(c) {
        private val bitmap=Bitmap.createBitmap(320,240,Bitmap.Config.ARGB_8888)
        private val dest=Rect()
        private val paint=Paint().apply {isFilterBitmap=false}
        fun frame(bytes:ByteArray) { synchronized(bitmap){bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))};postInvalidateOnAnimation() }
        override fun onDraw(canvas:Canvas) {
            canvas.drawColor(Color.BLACK)
            val scale=minOf(width/320f,height/240f)
            val w=(320*scale).toInt();val h=(240*scale).toInt()
            dest.set((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2)
            synchronized(bitmap){canvas.drawBitmap(bitmap,null,dest,paint)}
        }
    }
}
