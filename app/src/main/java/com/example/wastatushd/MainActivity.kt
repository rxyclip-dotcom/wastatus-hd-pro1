package com.example.wastatushd

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.LanczosResample
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.io.FileInputStream
import kotlin.math.min

@OptIn(UnstableApi::class)
class MainActivity : Activity() {
    private val pick = 1001
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private var lastOutput: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 38, 32, 28)
        }
        val title = TextView(this).apply {
            text = "WA Status HD Pro"
            textSize = 29f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        }
        val sub = TextView(this).apply {
            text = "Foto + video • 1080×1920 • 30 FPS • kualitas optimal"
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 24)
        }
        val photo = Button(this).apply {
            text = "Pilih Foto → HD + Sharpen"
            setOnClickListener { choose("image/*", 1001) }
        }
        val video = Button(this).apply {
            text = "Pilih Video → 1080p 30 FPS"
            setOnClickListener { choose("video/*", 1002) }
        }
        val share = Button(this).apply {
            text = "Bagikan Hasil ke WhatsApp"
            isEnabled = false
            setOnClickListener { lastOutput?.let { shareToWhatsApp(it) } }
            tag = "share"
        }
        progress = ProgressBar(this).apply { visibility = View.GONE }
        status = TextView(this).apply {
            text = "\nTarget video: 1080×1920 • H.264 • VBR sekitar 5–7 Mbps\nFoto: 1080×1920 • JPEG 95%"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 0)
        }
        root.addView(title)
        root.addView(sub)
        root.addView(photo, LinearLayout.LayoutParams(-1, 58).apply { bottomMargin = 10 })
        root.addView(video, LinearLayout.LayoutParams(-1, 58).apply { bottomMargin = 10 })
        root.addView(share, LinearLayout.LayoutParams(-1, 58))
        root.addView(progress, LinearLayout.LayoutParams(-1, 52))
        root.addView(status)
        setContentView(root)
    }

    private fun shareButton(): Button = findViewByIdByTag("share")
    private fun findViewByIdByTag(tag: String): Button {
        val root = window.decorView.findViewById<View>(android.R.id.content) as? LinearLayout
        for (i in 0 until (root?.childCount ?: 0)) {
            val v = root?.getChildAt(i)
            if (v is Button && v.tag == tag) return v
        }
        return Button(this)
    }

    private fun choose(type: String, requestCode: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            this.type = type
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(i, requestCode)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        when (requestCode) {
            1001 -> optimizePhoto(data.data!!)
            1002 -> optimizeVideo(data.data!!)
        }
    }

    private fun optimizePhoto(uri: Uri) {
        setBusy(true)
        Thread {
            try {
                val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                val src = contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
                    ?: error("Foto tidak bisa dibaca")
                val targetW = 1080
                val targetH = 1920
                val scale = maxOf(targetW.toFloat() / src.width, targetH.toFloat() / src.height)
                val scaled = Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
                val left = ((scaled.width - targetW) / 2).coerceAtLeast(0)
                val top = ((scaled.height - targetH) / 2).coerceAtLeast(0)
                val out = Bitmap.createBitmap(scaled, left, top, min(targetW, scaled.width - left), min(targetH, scaled.height - top))

                // Detail-preserving unsharp mask: subtle sharpening before JPEG compression.
                val sharpened = Bitmap.createBitmap(out.width, out.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(sharpened)
                canvas.drawBitmap(out, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    alpha = 35
                    colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                        1.12f, -0.06f, -0.06f, 0f, 0f,
                        -0.06f, 1.12f, -0.06f, 0f, 0f,
                        -0.06f, -0.06f, 1.12f, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f
                    )))
                }
                canvas.drawBitmap(out, 0f, 0f, p)

                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "WA_PRO_${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/WA Status HD Pro")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val saved = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Gagal membuat file")
                contentResolver.openOutputStream(saved).use { if (!sharpened.compress(Bitmap.CompressFormat.JPEG, 95, it!!)) error("Gagal menyimpan") }
                values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(saved, values, null, null)
                src.recycle(); scaled.recycle(); out.recycle(); sharpened.recycle()
                done(saved, "Foto siap: 1080×1920 + sharpening ringan + JPEG 95%")
            } catch (e: Exception) { fail(e) }
        }.start()
    }

    private fun optimizeVideo(uri: Uri) {
        setBusy(true)
        val temp = File(cacheDir, "wa_pro_${System.currentTimeMillis()}.mp4")
        val mediaItem = MediaItem.fromUri(uri)
        val presentation = Presentation.createForWidthAndHeight(
            1080, 1920, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
        )
        val effects = listOf<Effect>(
            presentation,
            LanczosResample()
        )
        val edited = EditedMediaItem.Builder(mediaItem)
            .setFrameRate(30)
            .setEffects(Effects(emptyList(), effects))
            .build()

        val encoderSettings = VideoEncoderSettings.Builder()
            .setBitrate(6_000_000)
            .setBitrateMode(VideoEncoderSettings.BitrateMode.BITRATE_MODE_VBR)
            .build()
        val encoderFactory = DefaultEncoderFactory.Builder(this)
            .setRequestedVideoEncoderSettings(encoderSettings)
            .build()
        val transformer = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: androidx.media3.transformer.Composition, exportResult: ExportResult) {
                    try {
                        val saved = saveVideoToMediaStore(temp)
                        temp.delete()
                        done(saved, "Video siap: maksimal 1080×1920 • 30 FPS • H.264 VBR 6 Mbps")
                    } catch (e: Exception) { fail(e) }
                }
                override fun onError(composition: androidx.media3.transformer.Composition, exportResult: ExportResult, exportException: ExportException) {
                    temp.delete(); fail(exportException)
                }
            }).build()
        status.post { status.text = "Memproses video…\nEncoding H.264 1080×1920, maksimum 30 FPS." }
        transformer.start(edited, temp.absolutePath)
    }

    private fun saveVideoToMediaStore(file: File): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "WA_PRO_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/WA Status HD Pro")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("Gagal membuat video")
        FileInputStream(file).use { input -> contentResolver.openOutputStream(uri).use { output -> input.copyTo(output!!) } }
        values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return uri
    }

    private fun shareToWhatsApp(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = contentResolver.getType(uri) ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage("com.whatsapp")
        }
        try { startActivity(intent) }
        catch (_: Exception) {
            val fallback = Intent(Intent.ACTION_SEND).apply {
                type = contentResolver.getType(uri) ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(fallback, "Bagikan hasil"))
        }
    }

    private fun setBusy(busy: Boolean) {
        runOnUiThread {
            progress.visibility = if (busy) View.VISIBLE else View.GONE
            shareButton().isEnabled = !busy && lastOutput != null
        }
    }

    private fun done(uri: Uri, message: String) {
        lastOutput = uri
        runOnUiThread {
            progress.visibility = View.GONE
            shareButton().isEnabled = true
            status.text = "Berhasil!\n$message\n\nTekan 'Bagikan Hasil ke WhatsApp'."
            Toast.makeText(this, "Media HD Pro berhasil dibuat", Toast.LENGTH_LONG).show()
        }
    }

    private fun fail(e: Throwable) {
        runOnUiThread {
            progress.visibility = View.GONE
            status.text = "Gagal memproses: ${e.message ?: e.javaClass.simpleName}"
            Toast.makeText(this, "Gagal memproses media", Toast.LENGTH_SHORT).show()
        }
    }
}
