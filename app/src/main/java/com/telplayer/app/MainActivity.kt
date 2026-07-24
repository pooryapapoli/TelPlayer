package com.telplayer.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.telplayer.app.databinding.ActivityMainBinding
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Track(val title: String, val path: String, val isSaf: Boolean, val channel: String, var durationMs: Long)

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val all = mutableListOf<Track>()
    private var shown = listOf<Track>()
    private var channels = listOf<String>()
    private var currentChannel = ALL
    private var playlistView: String? = null
    private var voiceFiltered = 0

    private val playlists = linkedMapOf<String, MutableList<String>>()
    private val prefs by lazy { getSharedPreferences("telplayer", MODE_PRIVATE) }

    private var player: MediaPlayer? = null
    private var current = -1
    private var isPlaying = false
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var adapter: TrackAdapter

    companion object {
        const val ALL = "همه"
        val AUDIO_EXTS = listOf("mp3", "m4a", "flac", "wav", "aac", "wma", "webm")
        val VOICE_EXTS = listOf("ogg", "oga", "opus")   // ویس‌های تلگرام، خودکار حذف می‌شن
    }

    private val storagePerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) scanAll() else toast("بدون دسترسی حافظه، اسکن خودکار نمی‌شه — از «انتخاب پوشه» استفاده کن")
    }
    private val folderPick = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { scanSaf(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        loadPlaylists()

        adapter = TrackAdapter({ play(it) }, { openAddToPlaylist(it) })
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter

        b.btnScan.setOnClickListener { ensurePermissionAndScan() }
        b.btnFolder.setOnClickListener { folderPick.launch(null) }
        b.btnPlay.setOnClickListener { toggle() }
        b.btnNext.setOnClickListener { next() }
        b.btnPrev.setOnClickListener { prev() }
        b.btnAddPl.setOnClickListener {
            if (current in shown.indices) openAddToPlaylist(current) else toast("اول یک موزیک پخش کن")
        }
        b.btnPlaylists.setOnClickListener { openPlaylistsDialog() }

        b.seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) { player?.seekTo(p); b.tCur.text = fmt(p.toLong()) }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        ensurePermissionAndScan()
        handler.post(tick)
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager() && all.isEmpty()) scanAll()
    }

    private fun ensurePermissionAndScan() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (Environment.isExternalStorageManager()) scanAll()
            else AlertDialog.Builder(this)
                .setTitle("دسترسی به حافظه")
                .setMessage("برای اسکن خودکار پوشه تلگرام (مخصوصاً اندروید ۱۱ به بالا)، دسترسی «All Files Access» لازمه. در صفحه بعد این دسترسی رو به تل‌پلیر بده.")
                .setPositiveButton("برو به تنظیمات") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")))
                }
                .setNegativeButton("فعلاً نه") { _, _ -> toast("از دکمه «انتخاب پوشه» استفاده کن") }
                .show()
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) scanAll()
            else storagePerm.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun scanAll() {
        all.clear(); voiceFiltered = 0
        val base = Environment.getExternalStorageDirectory()
        listOf(
            "Telegram/Telegram Audio",
            "Android/data/org.telegram.messenger/files/Telegram/Telegram Audio",
            "Android/data/org.telegram.plus/files/Telegram/Telegram Audio"
        ).forEach { p -> val f = File(base, p); if (f.exists()) walkFile(f) }
        refresh()
        toast(if (all.isEmpty()) "موزیکی در پوشه‌های تلگرام پیدا نشد — «انتخاب پوشه» رو امتحان کن"
              else "${fa(all.size.toString())} موزیک پیدا شد ✓")
    }

    private fun walkFile(dir: File) {
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) { walkFile(f); return@forEach }
            val ext = f.extension.lowercase()
            if (ext in VOICE_EXTS) { voiceFiltered++; return@forEach }
            if (ext in AUDIO_EXTS && all.none { it.path == f.absolutePath }) {
                all.add(Track(cleanName(f.name), f.absolutePath, false,
                    f.parentFile?.name ?: "تلگرام", readDuration(f.absolutePath, false)))
            }
        }
    }

    private fun scanSaf(uri: Uri) {
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        val root = DocumentFile.fromTreeUri(this, uri) ?: return
        val before = all.size
        walkDoc(root)
        refresh()
        toast("${fa((all.size - before).toString())} موزیک اضافه شد • ${fa(voiceFiltered.toString())} ویس حذف شد")
    }

    private fun walkDoc(dir: DocumentFile) {
        dir.listFiles().forEach { d ->
            if (d.isDirectory) { walkDoc(d); return@forEach }
            val name = d.name ?: return@forEach
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext in VOICE_EXTS) { voiceFiltered++; return@forEach }
            if ((ext in AUDIO_EXTS || d.type?.startsWith("audio") == true) && all.none { it.path == d.uri.toString() }) {
                all.add(Track(cleanName(name), d.uri.toString(), true,
                    dir.name ?: "تلگرام", readDuration(d.uri.toString(), true)))
            }
        }
    }

    private fun refresh() {
        channels = all.map { it.channel }.distinct()
        buildChips()
        applyFilter()
        b.info.text = "${fa(all.size.toString())} موزیک • ${fa(voiceFiltered.toString())} ویس حذف شد"
    }

    private fun buildChips() {
        b.chips.removeAllViews()
        addChip(ALL)
        channels.forEach { addChip(it) }
    }

    private fun addChip(label: String) {
        b.chips.addView(Chip(this).apply {
            text = label
            isCheckable = true
            isChecked = (playlistView == null && currentChannel == label)
            setOnClickListener {
                playlistView = null
                currentChannel = label
                buildChips(); applyFilter()
            }
        })
    }

    private fun applyFilter() {
        shown = when {
            playlistView != null -> { val paths = playlists[playlistView] ?: emptyList(); all.filter { it.path in paths } }
            currentChannel == ALL -> all.toList()
            else -> all.filter { it.channel == currentChannel }
        }
        adapter.items = shown
        adapter.current = -1
        current = -1
        adapter.notifyDataSetChanged()
    }

    private fun play(i: Int) {
        if (i !in shown.indices) return
        current = i
        val t = shown[i]
        player?.release()
        val mp = MediaPlayer()
        try {
            if (t.isSaf) mp.setDataSource(this, Uri.parse(t.path)) else mp.setDataSource(t.path)
            mp.prepare()
        } catch (e: Exception) { toast("پخش این فایل ممکن نشد"); return }
        player = mp
        mp.start()
        isPlaying = true
        if (t.durationMs <= 0) t.durationMs = mp.duration.toLong()
        b.seek.max = mp.duration.coerceAtLeast(1)
        b.tDur.text = fmt(mp.duration.toLong())
        b.tTitle.text = t.title
        mp.setOnCompletionListener { next() }
        adapter.current = i
        adapter.notifyDataSetChanged()
        updatePlayIcon()
    }

    private fun toggle() {
        val mp = player
        if (mp == null) { if (shown.isNotEmpty()) play(0); return }
        if (isPlaying) mp.pause() else mp.start()
        isPlaying = !isPlaying
        updatePlayIcon()
    }

    private fun next() { if (shown.isNotEmpty()) play((current + 1) % shown.size) }
    private fun prev() {
        val mp = player
        if (mp != null && mp.currentPosition > 3000) { mp.seekTo(0); return }
        if (shown.isNotEmpty()) play(if (current <= 0) shown.size - 1 else current - 1)
    }

    private fun updatePlayIcon() {
        b.btnPlay.setImageResource(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
    }

    private val tick = object : Runnable {
        override fun run() {
            player?.let { mp ->
                b.seek.progress = mp.currentPosition
                b.tCur.text = fmt(mp.currentPosition.toLong())
            }
            handler.postDelayed(this, 500)
        }
    }

    private fun openAddToPlaylist(idx: Int) {
        if (idx !in shown.indices) return
        val t = shown[idx]
        val names = playlists.keys.toList()
        val options = names + "＋ ساخت پلی‌لیست جدید"
        AlertDialog.Builder(this)
            .setTitle("افزودن به پلی‌لیست")
            .setItems(options.toTypedArray()) { _, w ->
                if (w < names.size) addToPlaylist(names[w], t.path)
                else {
                    val input = EditText(this).apply { hint = "نام پلی‌لیست" }
                    AlertDialog.Builder(this)
                        .setTitle("پلی‌لیست جدید")
                        .setView(input)
                        .setPositiveButton("بساز") { _, _ ->
                            val n = input.text.toString().trim()
                            if (n.isNotEmpty()) { playlists.getOrPut(n) { mutableListOf() }; addToPlaylist(n, t.path) }
                        }
                        .setNegativeButton("انصراف", null).show()
                }
            }
            .setNegativeButton("انصراف", null).show()
    }

    private fun addToPlaylist(name: String, path: String) {
        val list = playlists.getOrPut(name) { mutableListOf() }
        if (path !in list) list.add(path)
        savePlaylists()
        toast("به «$name» اضافه شد ✓")
        if (playlistView == name) applyFilter()
    }

    private fun openPlaylistsDialog() {
        if (playlists.isEmpty()) { toast("پلی‌لیستی نداری — از ＋ روی هر موزیک بساز"); return }
        val names = playlists.keys.toList()
        val options = names.map { "$it (${fa(playlists[it]!!.size.toString())})" } + "🗑 حذف پلی‌لیست…"
        AlertDialog.Builder(this)
            .setTitle("پلی‌لیست‌های من")
            .setItems(options.toTypedArray()) { _, w ->
                if (w < names.size) {
                    playlistView = names[w]
                    buildChips(); applyFilter()
                    toast("پلی‌لیست «${names[w]}»")
                } else {
                    val ns = playlists.keys.toList()
                    AlertDialog.Builder(this).setTitle("حذف پلی‌لیست")
                        .setItems(ns.toTypedArray()) { _, i ->
                            playlists.remove(ns[i]); savePlaylists()
                            if (playlistView == ns[i]) { playlistView = null; applyFilter() }
                            toast("حذف شد")
                        }.setNegativeButton("انصراف", null).show()
                }
            }
            .setNegativeButton("بستن", null).show()
    }

    private fun savePlaylists() {
        val arr = JSONArray()
        playlists.forEach { (n, items) -> arr.put(JSONObject().put("n", n).put("i", JSONArray(items))) }
        prefs.edit().putString("pls", arr.toString()).apply()
    }

    private fun loadPlaylists() {
        try {
            val arr = JSONArray(prefs.getString("pls", "[]")!!)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i); val items = o.getJSONArray("i")
                playlists[o.getString("n")] = (0 until items.length()).map { items.getString(it) }.toMutableList()
            }
        } catch (_: Exception) {}
    }

    private fun readDuration(src: String, saf: Boolean): Long = try {
        val r = MediaMetadataRetriever()
        if (saf) r.setDataSource(this, Uri.parse(src)) else r.setDataSource(src)
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } catch (e: Exception) { 0L }

    private fun cleanName(name: String) =
        name.substringBeforeLast('.').replace(Regex("[_-]+"), " ").trim().ifEmpty { "بدون عنوان" }

    private fun fa(s: String) = s.map { if (it.isDigit()) "۰۱۲۳۴۵۶۷۸۹"[it - '0'] else it }.joinToString("")

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return fa("${s / 60}:${(s % 60).toString().padStart(2, '0')}")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        player?.release()
        super.onDestroy()
    }
}
