package com.wwm.midiplayer.pro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

data class KeyCoordinate(
    val noteIndex: Int,
    val label: String,
    val octaveRow: Int,
    val isSharp: Boolean,
    var x: Float,
    var y: Float
)

data class RawMidiNote(
    val startMs: Long,
    val durationMs: Long,
    val midiNote: Int,
    val channel: Int,
    val trackIndex: Int,
    val velocity: Int
)

data class ScheduledChord(
    val timeMs: Long,         // Thời điểm phát tính từ đầu bài (ms)
    val delayAfterMs: Long,   // Khoảng nghỉ tới hợp âm tiếp theo (ms)
    val keyIndices: List<Int>,
    val durationMs: Long
)

object TouchPointManager {
    private const val PREF_NAME = "wwm_xiaomi15_touch_prefs"
    private const val KEY_LAYOUT_21 = "xiaomi15_layout_21"
    private const val KEY_LAYOUT_36_STAGGERED = "xiaomi15_layout_36_staggered"
    private const val KEY_LAYOUT_36_LINEAR = "xiaomi15_layout_36_linear"

    const val XIAOMI15_LANDSCAPE_WIDTH = 2670f
    const val XIAOMI15_LANDSCAPE_HEIGHT = 1200f

    private val XIAOMI15_7COLS_X = floatArrayOf(441f, 739f, 1037f, 1335f, 1633f, 1931f, 2229f)
    private val XIAOMI15_3ROWS_Y = floatArrayOf(648f, 834f, 1020f)

    val NOTE_LABELS_21 = listOf(
        "1̇", "2̇", "3̇", "4̇", "5̇", "6̇", "7̇",
        "1",  "2",  "3",  "4",  "5",  "6",  "7",
        "1̣", "2̣", "3̣", "4̣", "5̣", "6̣", "7̣"
    )

    private val SEMITONE_NAMES = listOf("1", "#1", "2", "#2", "3", "4", "#4", "5", "#5", "6", "#6", "7")
    private val NATURAL_COL_MAP = mapOf(0 to 0, 2 to 1, 4 to 2, 5 to 3, 7 to 4, 9 to 5, 11 to 6)
    private val SHARP_COL_MAP = mapOf(1 to 0.5f, 3 to 1.5f, 6 to 3.5f, 8 to 4.5f, 10 to 5.5f)

    // 7 nốt trắng tự nhiên trong 1 quãng tám: C(0), D(2), E(4), F(5), G(7), A(9), B(11)
    private val WHITE_PITCH_CLASSES = setOf(0, 2, 4, 5, 7, 9, 11)
    private val PITCH_CLASS_TO_WHITE_COL = intArrayOf(
        0, // 0: C  -> Phím 1 (Đô)
        0, // 1: C# -> Chuyển thành Phím 1 (Đô) hoặc Rê tùy ngữ cảnh
        1, // 2: D  -> Phím 2 (Rê)
        2, // 3: D# -> Chuyển thành Phím 3 (Mi)
        2, // 4: E  -> Phím 3 (Mi)
        3, // 5: F  -> Phím 4 (Fa)
        4, // 6: F# -> Chuyển thành Phím 5 (Sol)
        4, // 7: G  -> Phím 5 (Sol)
        5, // 8: G# -> Chuyển thành Phím 6 (La)
        5, // 9: A  -> Phím 6 (La)
        6, // 10: A# -> Chuyển thành Phím 7 (Si)
        6  // 11: B  -> Phím 7 (Si)
    )

    @Volatile
    var currentSongTitle: String = "Lan Đình Tự (Lanting Xu)"

    @Volatile
    var currentSongRawNotes: List<RawMidiNote> = buildBuiltinLantingXu()

    @Volatile
    var currentSongSchedule: List<ScheduledChord> = emptyList()

    @Volatile
    var playbackSpeedMultiplier: Float = 1.0f

    @Volatile
    var currentPresetMode: Int = 0 // 0 = 21 phím, 1 = 36 phím lệch #, 2 = 36 phím lưới

    @Volatile
    var adaptiveKeyMapping: Boolean = true // Tự động chuyển tông & đưa nốt đen về nốt trắng ở 21 phím

    @Volatile
    var simplifyChords: Boolean = false // Đơn giản hóa hợp âm phức tạp

    @Volatile
    var respectNoteOff: Boolean = false // Ngắt nhịp theo nốt tắt

    @Volatile
    var loopPlayback: Boolean = false // Không tự lặp lại vô tận trừ khi người dùng bật

    @Volatile
    var lastAutoTransposeSemitones: Int = 0

    init {
        rebuildActiveSchedule()
    }

    /**
     * Thuật toán Thông Minh:
     * 1. Tìm số bán cung dịch chuyển (Transpose) tối ưu để:
     *    - Ở chế độ 21 phím: Chuyển toàn bộ âm giai có nốt đen (F#, C#, G#, Eb, Bb...) về âm giai Đô Trưởng / La Thứ (100% phím trắng),
     *      đồng thời gom tối đa nốt vào 3 quãng tám của game (C3=48 đến B5=83).
     *    - Ở chế độ 36 phím (Master Mode): Giữ nguyên đủ 12 bán cung (trắng + đen) và tự động dịch quãng tám tối ưu vào khung 48..83.
     * 2. Gấp quãng tám thông minh (Octave Folding) cho các nốt quá trầm (<48) hoặc quá cao (>83).
     * 3. Chuyển các nốt đen bất thường còn sót sang phím trắng hài hòa gần nhất ở chế độ 21 phím.
     * 4. Gom hợp âm trong cửa sổ 20ms, khử trùng lặp phím và giới hạn số ngón chạm cùng lúc để game không bị đơ/mất nốt.
     */
    @Synchronized
    fun rebuildActiveSchedule() {
        val raw = currentSongRawNotes.filter { it.channel != 9 } // Loại bỏ kênh trống (Channel 10) gây loạn nốt
        if (raw.isEmpty()) {
            currentSongSchedule = emptyList()
            return
        }

        val master36 = currentPresetMode > 0
        val bestShift = if (adaptiveKeyMapping) {
            findOptimalTranspose(raw, master36)
        } else {
            0
        }
        lastAutoTransposeSemitones = bestShift

        // Gom các nốt phát cùng lúc (trong khoảng 22ms) thành 1 cụm hợp âm (Chord Bucket)
        val sorted = raw.sortedBy { it.startMs }
        val buckets = mutableListOf<MutableList<RawMidiNote>>()
        for (note in sorted) {
            if (buckets.isEmpty()) {
                buckets.add(mutableListOf(note))
            } else {
                val lastBucket = buckets.last()
                val bucketStart = lastBucket.first().startMs
                if (note.startMs - bucketStart <= 22L) {
                    lastBucket.add(note)
                } else {
                    buckets.add(mutableListOf(note))
                }
            }
        }

        val result = mutableListOf<ScheduledChord>()
        for (i in buckets.indices) {
            val bucket = buckets[i]
            val chordTimeMs = bucket.first().startMs
            val nextTimeMs = if (i + 1 < buckets.size) buckets[i + 1].first().startMs else (chordTimeMs + 400L)
            val stepDelay = (nextTimeMs - chordTimeMs).coerceAtLeast(35L)

            // Nếu bật đơn giản hóa hợp âm: ưu tiên giữ nốt giai điệu cao nhất + nốt trầm nền
            val filteredNotes = if (simplifyChords && bucket.size > 2) {
                val highest = bucket.maxByOrNull { it.midiNote }!!
                val lowest = bucket.minByOrNull { it.midiNote }!!
                if (highest.midiNote != lowest.midiNote) listOf(highest, lowest) else listOf(highest)
            } else {
                // Sắp xếp ưu tiên nốt cao (giai điệu chính) và giới hạn tối đa 4 ngón chạm cùng lúc
                bucket.sortedByDescending { it.midiNote }.take(4)
            }

            val keyIndices = mutableListOf<Int>()
            var maxDur = 38L
            for (n in filteredNotes) {
                val mappedIdx = mapSingleNoteSmart(n.midiNote + bestShift, master36)
                if (mappedIdx != null && !keyIndices.contains(mappedIdx)) {
                    keyIndices.add(mappedIdx)
                }
                if (respectNoteOff) {
                    maxDur = maxOf(maxDur, n.durationMs.coerceIn(35L, stepDelay - 10L))
                }
            }

            if (keyIndices.isNotEmpty()) {
                result.add(
                    ScheduledChord(
                        timeMs = chordTimeMs,
                        delayAfterMs = stepDelay,
                        keyIndices = keyIndices,
                        durationMs = if (respectNoteOff) maxDur.coerceIn(35L, 1500L) else 38L
                    )
                )
            }
        }
        currentSongSchedule = result
    }

    private fun findOptimalTranspose(notes: List<RawMidiNote>, master36: Boolean): Int {
        var bestShift = 0
        var bestScore = Double.NEGATIVE_INFINITY

        // Thử dịch từ -24 đến +24 bán cung
        for (shift in -24..24) {
            // Ở chế độ 36 phím, ưu tiên dịch nguyên quãng tám (-24, -12, 0, +12, +24) để giữ nguyên tông gốc
            val isOctaveMultiple = (shift % 12 == 0)
            var inRangeCount = 0.0
            var whiteKeyCount = 0.0
            var totalWeight = 0.0

            for (n in notes) {
                val weight = (n.durationMs.coerceIn(50L, 1000L) / 100.0)
                val shifted = n.midiNote + shift
                val pitchClass = ((shifted % 12) + 12) % 12

                if (shifted in 48..83) {
                    inRangeCount += weight
                }
                if (WHITE_PITCH_CLASSES.contains(pitchClass)) {
                    whiteKeyCount += weight
                }
                totalWeight += weight
            }

            if (totalWeight <= 0.0) continue
            val inRangeRatio = inRangeCount / totalWeight
            val whiteKeyRatio = whiteKeyCount / totalWeight

            // Tính điểm:
            // - Chế độ 21 phím: Hệ số khớp phím trắng (whiteKeyRatio) cực kỳ quan trọng (x100) + nằm trong 3 quãng tám (x60) - phạt nhẹ nếu dịch quá xa
            // - Chế độ 36 phím: Ưu tiên nằm trọn trong 3 quãng tám (x100) + giữ nguyên tông gốc (isOctaveMultiple)
            val score = if (!master36) {
                (whiteKeyRatio * 100.0) + (inRangeRatio * 65.0) - (abs(shift) * 0.35)
            } else {
                (inRangeRatio * 100.0) + (if (isOctaveMultiple) 15.0 else 0.0) - (abs(shift) * 0.4)
            }

            if (score > bestScore) {
                bestScore = score
                bestShift = shift
            }
        }
        return bestShift
    }

    /**
     * Chuyển 1 nốt MIDI (đã cộng bestShift) thành chỉ số phím cảm ứng:
     * - Gấp các nốt vượt quá phạm vi (<48 hoặc >83) vào đúng quãng tám C3(48)..B5(83).
     * - Ở 21 phím: Chuyển mọi nốt đen còn sót sang phím trắng chuẩn xác (0..20).
     * - Ở 36 phím: Ánh xạ chuẩn xác cả nốt trắng và nốt đen (0..35).
     */
    fun mapSingleNoteSmart(shiftedMidiNote: Int, master36: Boolean): Int? {
        var n = shiftedMidiNote
        while (n < 48) n += 12
        while (n > 83) n -= 12

        val octaveFromC3 = ((n - 48) / 12).coerceIn(0, 2) // 0 = Thấp(C3), 1 = Trung(C4), 2 = Cao(C5)
        val semitone = ((n - 48) % 12 + 12) % 12
        // Hàng trên màn hình: Hàng 0 = Quãng Cao (C5), Hàng 1 = Quãng Trung (C4), Hàng 2 = Quãng Thấp (C3)
        val screenRow = (2 - octaveFromC3).coerceIn(0, 2)

        return if (master36) {
            screenRow * 12 + semitone // 0..35 (đầy đủ 12 phím trắng & đen mỗi hàng)
        } else {
            val whiteCol = PITCH_CLASS_TO_WHITE_COL[semitone] // 0..6 (7 phím trắng mỗi hàng)
            screenRow * 7 + whiteCol // 0..20
        }
    }

    /**
     * Bộ giải mã file MIDI Chuẩn (Standard MIDI File - SMF Format 0 & 1) trực tiếp trên Android
     * Hỗ trợ đọc Header MThd, đa Track MTrk, Variable-Length Delta Time, Running Status và Tempo FF 51 03
     */
    fun parseStandardMidiBytes(bytes: ByteArray): List<RawMidiNote> {
        if (bytes.size < 14) return emptyList()
        // Kiểm tra chữ ký "MThd"
        if (bytes[0] != 'M'.code.toByte() || bytes[1] != 'T'.code.toByte() ||
            bytes[2] != 'h'.code.toByte() || bytes[3] != 'd'.code.toByte()
        ) {
            return emptyList()
        }

        val headerLen = readInt32(bytes, 4)
        val numTracks = readInt16(bytes, 10)
        val division = readInt16(bytes, 12)
        val ticksPerQuarter = if ((division and 0x8000) == 0 && division > 0) division else 480

        data class TempoChange(val tick: Long, val microsPerQuarter: Long)
        data class TickNoteEvent(
            val startTick: Long,
            val endTick: Long,
            val note: Int,
            val channel: Int,
            val track: Int,
            val velocity: Int
        )

        val tempoChanges = mutableListOf(TempoChange(0L, 500_000L)) // Mặc định 120 BPM
        val rawTickNotes = mutableListOf<TickNoteEvent>()

        var pos = 8 + headerLen
        var trackIdx = 0

        while (pos + 8 <= bytes.size && trackIdx < numTracks) {
            // Tìm chunk "MTrk"
            if (bytes[pos] != 'M'.code.toByte() || bytes[pos + 1] != 'T'.code.toByte() ||
                bytes[pos + 2] != 'r'.code.toByte() || bytes[pos + 3] != 'k'.code.toByte()
            ) {
                pos++
                continue
            }
            val trackLen = readInt32(bytes, pos + 4)
            val trackEnd = (pos + 8 + trackLen).coerceAtMost(bytes.size)
            var p = pos + 8
            var currentTick = 0L
            var runningStatus = 0

            // Lưu nốt đang mở: key = (channel shl 8) or note -> Pair(startTick, velocity)
            val activeNotes = HashMap<Int, Pair<Long, Int>>()

            while (p < trackEnd) {
                // 1. Đọc Variable-Length Quantity (VLQ) Delta Tick
                var delta = 0L
                while (p < trackEnd) {
                    val b = bytes[p++].toInt() and 0xFF
                    delta = (delta shl 7) or (b and 0x7F).toLong()
                    if ((b and 0x80) == 0) break
                }
                currentTick += delta
                if (p >= trackEnd) break

                var status = bytes[p].toInt() and 0xFF
                if (status < 0x80) {
                    // Sử dụng Running Status
                    status = runningStatus
                } else {
                    p++
                    if (status < 0xF0) {
                        runningStatus = status
                    }
                }

                when (status and 0xF0) {
                    0x90 -> { // Note On
                        if (p + 1 >= trackEnd) break
                        val ch = status and 0x0F
                        val note = bytes[p++].toInt() and 0x7F
                        val vel = bytes[p++].toInt() and 0x7F
                        val key = (ch shl 8) or note
                        if (vel > 0) {
                            activeNotes[key] = Pair(currentTick, vel)
                        } else {
                            // Note On với velocity = 0 tương đương Note Off
                            activeNotes.remove(key)?.let { (st, v) ->
                                rawTickNotes.add(
                                    TickNoteEvent(st, currentTick.coerceAtLeast(st + 20L), note, ch, trackIdx, v)
                                )
                            }
                        }
                    }
                    0x80 -> { // Note Off
                        if (p + 1 >= trackEnd) break
                        val ch = status and 0x0F
                        val note = bytes[p++].toInt() and 0x7F
                        p++ // release velocity
                        val key = (ch shl 8) or note
                        activeNotes.remove(key)?.let { (st, v) ->
                            rawTickNotes.add(
                                TickNoteEvent(st, currentTick.coerceAtLeast(st + 20L), note, ch, trackIdx, v)
                            )
                        }
                    }
                    0xA0, 0xB0, 0xE0 -> {
                        p += 2
                    }
                    0xC0, 0xD0 -> {
                        p += 1
                    }
                    0xF0 -> {
                        if (status == 0xFF) { // Meta Event
                            if (p >= trackEnd) break
                            val metaType = bytes[p++].toInt() and 0xFF
                            var metaLen = 0
                            while (p < trackEnd) {
                                val b = bytes[p++].toInt() and 0xFF
                                metaLen = (metaLen shl 7) or (b and 0x7F)
                                if ((b and 0x80) == 0) break
                            }
                            if (metaType == 0x51 && metaLen == 3 && p + 3 <= trackEnd) {
                                val mpq = ((bytes[p].toLong() and 0xFF) shl 16) or
                                          ((bytes[p + 1].toLong() and 0xFF) shl 8) or
                                          (bytes[p + 2].toLong() and 0xFF)
                                if (mpq > 0) {
                                    tempoChanges.add(TempoChange(currentTick, mpq))
                                }
                            }
                            p += metaLen
                        } else if (status == 0xF0 || status == 0xF7) { // SysEx Event
                            var sysexLen = 0
                            while (p < trackEnd) {
                                val b = bytes[p++].toInt() and 0xFF
                                sysexLen = (sysexLen shl 7) or (b and 0x7F)
                                if ((b and 0x80) == 0) break
                            }
                            p += sysexLen
                        } else {
                            p++
                        }
                    }
                    else -> {
                        p++
                    }
                }
            }

            // Đóng các nốt còn treo ở cuối track
            for ((key, pair) in activeNotes) {
                val ch = (key shr 8) and 0x0F
                val note = key and 0x7F
                rawTickNotes.add(TickNoteEvent(pair.first, pair.first + (ticksPerQuarter / 2), note, ch, trackIdx, pair.second))
            }

            pos = trackEnd
            trackIdx++
        }

        if (rawTickNotes.isEmpty()) return emptyList()

        // Sắp xếp bảng Tempo để đổi từ Tick sang Mili-giây (ms) chuẩn xác
        val sortedTempos = tempoChanges.sortedBy { it.tick }
        fun tickToMs(targetTick: Long): Long {
            var totalMicros = 0L
            var prevTick = 0L
            var currentMpq = 500_000L
            for (tc in sortedTempos) {
                if (tc.tick >= targetTick) break
                if (tc.tick > prevTick) {
                    totalMicros += ((tc.tick - prevTick) * currentMpq) / ticksPerQuarter
                    prevTick = tc.tick
                }
                currentMpq = tc.microsPerQuarter
            }
            if (targetTick > prevTick) {
                totalMicros += ((targetTick - prevTick) * currentMpq) / ticksPerQuarter
            }
            return totalMicros / 1000L
        }

        val converted = rawTickNotes.map { ev ->
            val stMs = tickToMs(ev.startTick)
            val endMs = tickToMs(ev.endTick)
            RawMidiNote(
                startMs = stMs,
                durationMs = (endMs - stMs).coerceIn(35L, 2500L),
                midiNote = ev.note,
                channel = ev.channel,
                trackIndex = ev.track,
                velocity = ev.velocity
            )
        }.sortedBy { it.startMs }

        // Dịch mốc thời gian nốt đầu tiên về 0ms (bỏ khoảng lặng thừa đầu bài)
        val firstMs = converted.first().startMs
        return converted.map { it.copy(startMs = (it.startMs - firstMs).coerceAtLeast(0L)) }
    }

    private fun readInt32(b: ByteArray, offset: Int): Int {
        if (offset + 4 > b.size) return 0
        return ((b[offset].toInt() and 0xFF) shl 24) or
               ((b[offset + 1].toInt() and 0xFF) shl 16) or
               ((b[offset + 2].toInt() and 0xFF) shl 8) or
               (b[offset + 3].toInt() and 0xFF)
    }

    private fun readInt16(b: ByteArray, offset: Int): Int {
        if (offset + 2 > b.size) return 0
        return ((b[offset].toInt() and 0xFF) shl 8) or (b[offset + 1].toInt() and 0xFF)
    }

    private fun buildBuiltinLantingXu(): List<RawMidiNote> {
        // Giai điệu hoàn chỉnh điệp khúc Lan Đình Tự (Lanting Xu - Jay Chou)
        val melody = listOf(
            // Câu 1: Thảo thư lết thướt chữ tình...
            Pair(64, 300L), Pair(67, 300L), Pair(69, 300L), Pair(72, 450L), Pair(71, 150L),
            Pair(69, 300L), Pair(71, 300L), Pair(69, 300L), Pair(67, 300L), Pair(64, 600L),
            // Câu 2:
            Pair(62, 300L), Pair(64, 300L), Pair(67, 300L), Pair(69, 450L), Pair(67, 150L),
            Pair(64, 300L), Pair(62, 300L), Pair(60, 300L), Pair(62, 900L),
            // Câu 3: Điệp khúc cao trào
            Pair(69, 300L), Pair(72, 300L), Pair(74, 300L), Pair(76, 450L), Pair(74, 150L),
            Pair(72, 300L), Pair(74, 300L), Pair(72, 300L), Pair(69, 300L), Pair(67, 600L),
            // Câu 4: Kết câu
            Pair(64, 300L), Pair(67, 300L), Pair(69, 450L), Pair(72, 150L), Pair(71, 300L),
            Pair(69, 300L), Pair(67, 300L), Pair(69, 900L)
        )
        var t = 0L
        return melody.map { (note, dur) ->
            val r = RawMidiNote(t, dur - 30L, note, 0, 0, 100)
            t += dur
            r
        }
    }

    fun loadXiaomi15Layout(
        context: Context,
        mode: Int,
        currentScreenWidth: Int = XIAOMI15_LANDSCAPE_WIDTH.toInt(),
        currentScreenHeight: Int = XIAOMI15_LANDSCAPE_HEIGHT.toInt()
    ): MutableList<KeyCoordinate> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val prefKey = when (mode) {
            1 -> KEY_LAYOUT_36_STAGGERED
            2 -> KEY_LAYOUT_36_LINEAR
            else -> KEY_LAYOUT_21
        }
        val savedJson = prefs.getString(prefKey, null)
        if (savedJson != null) {
            try {
                val arr = JSONArray(savedJson)
                val list = mutableListOf<KeyCoordinate>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        KeyCoordinate(
                            noteIndex = obj.getInt("noteIndex"),
                            label = obj.getString("label"),
                            octaveRow = obj.getInt("octaveRow"),
                            isSharp = obj.optBoolean("isSharp", false),
                            x = obj.getDouble("x").toFloat(),
                            y = obj.getDouble("y").toFloat()
                        )
                    )
                }
                return list
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return generateXiaomi15Preset(mode, currentScreenWidth, currentScreenHeight)
    }

    fun generateXiaomi15Preset(
        mode: Int,
        screenWidth: Int = XIAOMI15_LANDSCAPE_WIDTH.toInt(),
        screenHeight: Int = XIAOMI15_LANDSCAPE_HEIGHT.toInt()
    ): MutableList<KeyCoordinate> {
        val scaleX = screenWidth.toFloat() / XIAOMI15_LANDSCAPE_WIDTH
        val scaleY = screenHeight.toFloat() / XIAOMI15_LANDSCAPE_HEIGHT
        val list = mutableListOf<KeyCoordinate>()

        if (mode == 0) {
            for (r in 0..2) {
                for (c in 0..6) {
                    val idx = r * 7 + c
                    list.add(
                        KeyCoordinate(
                            noteIndex = idx,
                            label = NOTE_LABELS_21[idx],
                            octaveRow = r,
                            isSharp = false,
                            x = XIAOMI15_7COLS_X[c] * scaleX,
                            y = XIAOMI15_3ROWS_Y[r] * scaleY
                        )
                    )
                }
            }
        } else if (mode == 1) {
            for (r in 0..2) {
                val baseY = XIAOMI15_3ROWS_Y[r]
                val suffix = if (r == 0) "̇" else if (r == 2) "̣" else ""
                for (s in 0..11) {
                    val idx = r * 12 + s
                    val isSharp = SHARP_COL_MAP.containsKey(s)
                    val rawX = if (!isSharp) {
                        441f + (NATURAL_COL_MAP[s] ?: 0) * 298f
                    } else {
                        441f + (SHARP_COL_MAP[s] ?: 0f) * 298f
                    }
                    val rawY = if (!isSharp) baseY else (baseY - 64f)
                    list.add(
                        KeyCoordinate(
                            noteIndex = idx,
                            label = SEMITONE_NAMES[s] + suffix,
                            octaveRow = r,
                            isSharp = isSharp,
                            x = rawX * scaleX,
                            y = rawY * scaleY
                        )
                    )
                }
            }
        } else {
            for (r in 0..2) {
                val baseY = XIAOMI15_3ROWS_Y[r]
                val suffix = if (r == 0) "̇" else if (r == 2) "̣" else ""
                for (s in 0..11) {
                    val idx = r * 12 + s
                    val rawX = 335f + s * (2000f / 11f)
                    list.add(
                        KeyCoordinate(
                            noteIndex = idx,
                            label = SEMITONE_NAMES[s] + suffix,
                            octaveRow = r,
                            isSharp = SHARP_COL_MAP.containsKey(s),
                            x = rawX * scaleX,
                            y = baseY * scaleY
                        )
                    )
                }
            }
        }
        return list
    }

    fun saveXiaomi15Layout(context: Context, mode: Int, keys: List<KeyCoordinate>) {
        val prefKey = when (mode) {
            1 -> KEY_LAYOUT_36_STAGGERED
            2 -> KEY_LAYOUT_36_LINEAR
            else -> KEY_LAYOUT_21
        }
        val arr = JSONArray()
        keys.forEach { k ->
            val obj = JSONObject()
            obj.put("noteIndex", k.noteIndex)
            obj.put("label", k.label)
            obj.put("octaveRow", k.octaveRow)
            obj.put("isSharp", k.isSharp)
            obj.put("x", k.x.toDouble())
            obj.put("y", k.y.toDouble())
            arr.put(obj)
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(prefKey, arr.toString())
            .apply()
    }
}
