package com.example.kenoscreenreader

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.ViewGroup
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private val requestCapture = 4107
    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var db: RoundDb
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val processing = AtomicBoolean(false)
    private var lastOcr = ""
    private var lastCaptureAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = RoundDb(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        status = TextView(this).apply { textSize = 16f; text = "Ready. Start screen sharing to scan Keno history." }
        output = TextView(this).apply { textSize = 14f }
        val start = Button(this).apply {
            text = "Start screen scan"
            setOnClickListener {
                val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mgr.createScreenCaptureIntent(), requestCapture)
            }
        }
        val stop = Button(this).apply {
            text = "Stop scan"
            setOnClickListener { stopCapture() }
        }
        val stats = Button(this).apply {
            text = "Show saved history and analysis"
            setOnClickListener { showAnalysis() }
        }
        val export = Button(this).apply {
            text = "Export CSV"
            setOnClickListener { exportCsv() }
        }
        root.addView(status)
        root.addView(start)
        root.addView(stop)
        root.addView(stats)
        root.addView(export)
        root.addView(ScrollView(this).apply {
            addView(output)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
        showAnalysis()
    }

    @Deprecated("Legacy callback is retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != requestCapture) return
        if (resultCode != RESULT_OK || data == null) {
            status.text = "Screen capture permission was not granted."
            return
        }
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(resultCode, data)
        beginCapture()
    }

    private fun beginCapture() {
        // Release previous display resources without stopping the newly approved projection.
        reader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        virtualDisplay = null
        reader?.close()
        reader = null
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val p = projection ?: return
        virtualDisplay = p.createVirtualDisplay(
            "KenoScreenReader", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, handler
        )
        reader!!.setOnImageAvailableListener({ source ->
            val now = System.currentTimeMillis()
            if (now - lastCaptureAt < 1800 || !processing.compareAndSet(false, true)) {
                source.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            lastCaptureAt = now
            val image = source.acquireLatestImage()
            if (image == null) {
                processing.set(false)
                return@setOnImageAvailableListener
            }
            processImage(image)
        }, handler)
        status.text = "Screen scan active. Open Keno results and scroll slowly. Stop to finish."
    }

    private fun processImage(image: Image) {
        val bitmap = imageToBitmap(image)
        image.close()
        if (bitmap == null) {
            processing.set(false)
            return
        }
        val input = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(input)
            .addOnSuccessListener { result ->
                val text = result.text
                if (text != lastOcr) {
                    lastOcr = text
                    val saved = parseAndSave(text)
                    runOnUiThread {
                        status.text = if (saved > 0)
                            "Saved $saved round(s) from visible screen."
                        else
                            "Scanning screen… no new verified round detected in this frame."
                    }
                }
            }
            .addOnFailureListener { e ->
                runOnUiThread { status.text = "OCR error: ${e.localizedMessage ?: "unknown"}" }
            }
            .addOnCompleteListener {
                bitmap.recycle()
                recognizer.close()
                processing.set(false)
            }
    }

    private fun imageToBitmap(image: Image): android.graphics.Bitmap? {
        // Use the RGBA plane and crop row padding safely.
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val padded = android.graphics.Bitmap.createBitmap(
            image.width + rowPadding / pixelStride, image.height,
            android.graphics.Bitmap.Config.ARGB_8888
        )
        padded.copyPixelsFromBuffer(buffer)
        val cropped = android.graphics.Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return cropped
    }

    private fun parseAndSave(text: String): Int {
        // Heuristic parser: round IDs are 5–8 digits; candidate draw numbers are 1–80.
        // It only saves a round if exactly 20 unique candidates can be associated with an ID
        // on the same OCR text block. Layouts vary, so always inspect saved data afterward.
        val blocks = text.split(Regex("""(?=\b\d{5,8}\b)"""))
        var saved = 0
        for (block in blocks) {
            val idMatch = Regex("""\b(\d{5,8})\b""").find(block) ?: continue
            val roundId = idMatch.groupValues[1].toLongOrNull() ?: continue
            val afterId = block.substring(idMatch.range.last + 1)
            val nums = Regex("""\b\d{1,2}\b""").findAll(afterId)
                .mapNotNull { it.value.toIntOrNull() }
                .filter { it in 1..80 }
                .toList()
            val unique = nums.distinct()
            if (unique.size == 20) {
                if (db.insertIfNew(roundId, unique)) saved++
            }
        }
        return saved
    }

    private fun showAnalysis() {
        val rounds = db.allRounds()
        if (rounds.isEmpty()) {
            output.text = "No rounds saved yet.\n\n1. Tap Start screen scan.\n2. Approve Android screen sharing.\n3. Open the Keno results page and scroll slowly.\n4. Stop scan, then inspect the saved rounds."
            return
        }
        val freq = IntArray(81)
        val lastIndex = IntArray(81) { -1 }
        rounds.forEachIndexed { i, r ->
            r.numbers.forEach { n -> if (n in 1..80) { freq[n]++; lastIndex[n] = i } }
        }
        val hot = (1..80).sortedWith(compareByDescending<Int> { freq[it] }.thenBy { it }).take(15)
        val cold = (1..80).sortedWith(compareBy<Int> { freq[it] }.thenBy { it }).take(15)
        val overdue = (1..80).sortedWith(
            compareByDescending<Int> { if (lastIndex[it] < 0) rounds.size else rounds.lastIndex - lastIndex[it] }
                .thenBy { it }
        ).take(15)
        val recent = rounds.takeLast(minOf(30, rounds.size))
        val recentFreq = IntArray(81)
        recent.forEach { r -> r.numbers.forEach { recentFreq[it]++ } }
        val scored = (1..80).map { n ->
            val gap = if (lastIndex[n] < 0) rounds.size else rounds.lastIndex - lastIndex[n]
            val score = 0.5 * recentFreq[n] / maxOf(1, recent.size) +
                0.3 * freq[n] / rounds.size +
                0.2 * gap / maxOf(1, rounds.size)
            n to score
        }.sortedWith(compareByDescending<Pair<Int, Double>> { it.second }.thenBy { it.first }).take(20).map { it.first }.sorted()

        val sb = StringBuilder()
        sb.append("Saved rounds: ${rounds.size}\n")
        sb.append("Round IDs: ${rounds.first().id} → ${rounds.last().id}\n\n")
        sb.append("Hot numbers (top 15): ${hot.joinToString(", ")}\n")
        sb.append("Cold numbers (bottom 15): ${cold.joinToString(", ")}\n")
        sb.append("Longest current gaps (top 15): ${overdue.joinToString(", ")}\n\n")
        sb.append("Heuristic top-20 prediction: ${scored.joinToString(", ")}\n\n")
        sb.append("Recent round history:\n")
        rounds.takeLast(20).asReversed().forEach {
            sb.append("${it.id}: ${it.numbers.joinToString(", ")}\n")
        }
        sb.append("\nNote: OCR and score results need verification. Keno is random; no prediction is guaranteed.")
        output.text = sb.toString()
    }

    private fun exportCsv() {
        val rows = db.allRounds()
        val file = File(getExternalFilesDir(null), "keno_history.csv")
        file.writeText("round_id,numbers\n" + rows.joinToString("\n") {
            "${it.id},\"${it.numbers.joinToString(" ")}\""
        })
        status.text = "CSV exported to: ${file.absolutePath}"
    }

    private fun stopCapture() {
        reader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        virtualDisplay = null
        reader?.close()
        reader = null
        projection?.stop()
        projection = null
        status.text = "Screen scan stopped."
    }

    override fun onDestroy() {
        stopCapture()
        worker.shutdown()
        super.onDestroy()
    }
}

data class KenoRound(val id: Long, val numbers: List<Int>)

class RoundDb(context: Context) : android.database.sqlite.SQLiteOpenHelper(context, "keno_history.db", null, 1) {
    override fun onCreate(db: android.database.sqlite.SQLiteDatabase) {
        db.execSQL("""CREATE TABLE rounds(
            round_id INTEGER PRIMARY KEY,
            numbers TEXT NOT NULL,
            saved_at INTEGER NOT NULL
        )""")
    }
    override fun onUpgrade(db: android.database.sqlite.SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    fun insertIfNew(id: Long, numbers: List<Int>): Boolean {
        if (numbers.size != 20 || numbers.distinct().size != 20 || numbers.any { it !in 1..80 }) return false
        val values = android.content.ContentValues().apply {
            put("round_id", id)
            put("numbers", JSONArray(numbers).toString())
            put("saved_at", System.currentTimeMillis())
        }
        return writableDatabase.insertWithOnConflict("rounds", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }
    fun allRounds(): List<KenoRound> {
        val result = mutableListOf<KenoRound>()
        readableDatabase.rawQuery("SELECT round_id,numbers FROM rounds ORDER BY round_id ASC", null).use { c ->
            while (c.moveToNext()) {
                val arr = JSONArray(c.getString(1))
                val nums = (0 until arr.length()).map { arr.getInt(it) }
                result.add(KenoRound(c.getLong(0), nums))
            }
        }
        return result
    }
}
