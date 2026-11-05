package com.wwm.midiplayer.pro

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val uris = mutableListOf<Uri>()
            if (data?.clipData != null) {
                val count = data.clipData!!.itemCount
                for (i in 0 until count) {
                    uris.add(data.clipData!!.getItemAt(i).uri)
                }
            } else if (data?.data != null) {
                uris.add(data.data!!)
            }

            // Đọc trực tiếp file nhị phân .MID bằng bộ giải mã SMF Native Kotlin
            uris.forEach { uri ->
                loadMidiUriDirectly(uri)
            }
            filePathCallback?.onReceiveValue(uris.toTypedArray())
        } else {
            filePathCallback?.onReceiveValue(null)
        }
        filePathCallback = null
    }

    private fun loadMidiUriDirectly(uri: Uri) {
        try {
            var fileName = "Bai_Nhac_MIDI.mid"
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0 && cursor.moveToFirst()) {
                    fileName = cursor.getString(nameIdx)
                }
            }
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
            val parsedNotes = TouchPointManager.parseStandardMidiBytes(bytes)
            if (parsedNotes.isNotEmpty()) {
                TouchPointManager.currentSongTitle = fileName
                TouchPointManager.currentSongRawNotes = parsedNotes
                TouchPointManager.rebuildActiveSchedule()
                val chordCount = TouchPointManager.currentSongSchedule.size
                val shift = TouchPointManager.lastAutoTransposeSemitones
                val safeName = fileName.replace("'", "\'")
                runOnUiThread {
                    webView.evaluateJavascript(
                        "window.onNativeMidiLoaded('$safeName', ${parsedNotes.size}, $chordCount, $shift);",
                        null
                    )
                    Toast.makeText(
                        this,
                        "✅ Đã nạp '$fileName': ${parsedNotes.size} nốt ($chordCount nhịp, tự động dịch $shift bán cung)!",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                runOnUiThread {
                    Toast.makeText(this, "⚠️ File '$fileName' không đúng chuẩn MIDI (.mid)!", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                filePickerLauncher.launch(intent)
                return true
            }
        }

        webView.addJavascriptInterface(AndroidBridge(), "AndroidNative")
        webView.loadUrl("file:///android_asset/index.html")
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun startFloatingOverlay() {
            runOnUiThread {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(
                        this@MainActivity,
                        "Vui lòng cấp quyền 'Hiển thị trên ứng dụng khác'!",
                        Toast.LENGTH_LONG
                    ).show()
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    val intent = Intent(this@MainActivity, MidiOverlayService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                    Toast.makeText(
                        this@MainActivity,
                        "Đã hiện thanh nổi! Hãy vào game và bấm ▶ Phát.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        @JavascriptInterface
        fun stopFloatingOverlay() {
            runOnUiThread {
                stopService(Intent(this@MainActivity, MidiOverlayService::class.java))
            }
        }

        /**
         * Cập nhật tùy chọn cấu hình thông minh (21/36 phím, Chuyển tông tự động, Đơn giản hóa hợp âm, Ngắt nhịp, Tốc độ)
         */
        @JavascriptInterface
        fun updateSmartOptions(
            presetMode: Int,
            adaptive: Boolean,
            simplify: Boolean,
            noteOff: Boolean,
            loop: Boolean,
            speedPercent: Int
        ): String {
            TouchPointManager.currentPresetMode = presetMode
            TouchPointManager.adaptiveKeyMapping = adaptive
            TouchPointManager.simplifyChords = simplify
            TouchPointManager.respectNoteOff = noteOff
            TouchPointManager.loopPlayback = loop
            TouchPointManager.playbackSpeedMultiplier = (speedPercent / 100f).coerceIn(0.1f, 3.0f)
            TouchPointManager.rebuildActiveSchedule()

            val obj = JSONObject()
            obj.put("songTitle", TouchPointManager.currentSongTitle)
            obj.put("rawNotes", TouchPointManager.currentSongRawNotes.size)
            obj.put("chords", TouchPointManager.currentSongSchedule.size)
            obj.put("transpose", TouchPointManager.lastAutoTransposeSemitones)
            return obj.toString()
        }

        /**
         * Nạp bài hát có sẵn hoặc từ chuỗi JSON nốt nhạc có thời gian thực
         */
        @JavascriptInterface
        fun loadPresetSongJson(title: String, notesJson: String): String {
            try {
                val arr = JSONArray(notesJson)
                val list = mutableListOf<RawMidiNote>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        RawMidiNote(
                            startMs = o.getLong("startMs"),
                            durationMs = o.optLong("durationMs", 240L),
                            midiNote = o.getInt("midiNote"),
                            channel = 0,
                            trackIndex = 0,
                            velocity = 100
                        )
                    )
                }
                if (list.isNotEmpty()) {
                    TouchPointManager.currentSongTitle = title
                    TouchPointManager.currentSongRawNotes = list
                    TouchPointManager.rebuildActiveSchedule()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            val res = JSONObject()
            res.put("chords", TouchPointManager.currentSongSchedule.size)
            res.put("transpose", TouchPointManager.lastAutoTransposeSemitones)
            return res.toString()
        }

        @JavascriptInterface
        fun openAccessibilitySettings() {
            runOnUiThread {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
    }
}
