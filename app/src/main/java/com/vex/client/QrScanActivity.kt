package com.vex.client

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * اسکنر تمام‌صفحه‌ی QR با دوربین واقعی (CameraX + ML Kit).
 * نتیجه در Intent با کلید EXTRA_TEXT برگردانده می‌شود.
 * UI به‌صورت برنامه‌ای ساخته می‌شود (بدون فایل layout).
 */
class QrScanActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TEXT = "text"
        private const val RETICLE = 0.72f // نسبت قاب اسکن به عرض صفحه
    }

    private var camera: Camera? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var torchOn = false
    private var found = false
    private lateinit var torchBtn: Button
    private lateinit var previewView: PreviewView

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else finishWithMessage("دسترسی دوربین داده نشد")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        root.addView(previewView)

        // قاب اسکن + راهنما
        val overlay = ScanOverlay(this)
        root.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // نوار بالا: لغو + فلاش
        val topBar = FrameLayout(this).apply {
            setPadding(dp(10), dp(14), dp(10), 0)
        }
        val cancelBtn = chipButton("لغو")
        cancelBtn.setOnClickListener { finishWithMessage(null) }
        topBar.addView(cancelBtn, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.START))
        torchBtn = chipButton("فلاش")
        torchBtn.setOnClickListener {
            torchOn = !torchOn
            camera?.cameraControl?.enableTorch(torchOn)
            torchBtn.text = if (torchOn) "خاموش کردن فلاش" else "فلاش"
        }
        topBar.addView(torchBtn, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END))

        setContentView(root)

        addContentView(topBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        // راهنمای پایین
        val hint = TextView(this).apply {
            text = "QR کد را داخل قاب نگه دار"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Fonts.bold(this)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(0x66000000)
            }
        }
        addContentView(hint, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER).apply { bottomMargin = dp(48) })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) startCamera()
        else notifPermission.launch(Manifest.permission.CAMERA)
    }

    @OptIn(ExperimentalGetImage::class)
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val options = BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .build()
                val scanner = BarcodeScanning.getClient(options)
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { an ->
                        an.setAnalyzer(analysisExecutor) { proxy ->
                            val media = proxy.image
                            if (media == null) {
                                proxy.close()
                            } else {
                                val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                                scanner.process(input)
                                    .addOnSuccessListener { codes ->
                                        val value = codes.firstOrNull { it.rawValue != null }?.rawValue
                                        if (value != null && !found) {
                                            found = true
                                            vibrate()
                                            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_TEXT, value))
                                            finish()
                                        }
                                    }
                                    .addOnCompleteListener { proxy.close() }
                            }
                        }
                    }
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                finishWithMessage("دوربین در دسترس نیست")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** پیام خطا به صفحه‌ی اصلی برگردانده می‌شود (null = فقط بدون نتیجه ببند) */
    private fun finishWithMessage(msg: String?) {
        val i = Intent()
        msg?.let { i.putExtra("error", it) }
        setResult(Activity.RESULT_OK, i)
        finish()
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(60, 90))
            else @Suppress("DEPRECATION") vibrator.vibrate(60)
        } catch (_: Exception) {}
    }

    private fun chipButton(label: String): Button = Button(this).apply {
        text = label
        textSize = 14f
        typeface = Fonts.bold(this)
        setTextColor(Color.WHITE)
        setAllCaps(false)
        setPadding(dp(20), dp(8), dp(20), dp(8))
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(0x66101010)
            setStroke(dp(1), 0x55FFFFFF)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** قاب اسکن با گوشه‌های روشن */
    private inner class ScanOverlay(context: android.content.Context) : View(context) {
        private val paint = android.graphics.Paint().apply {
            color = Color.WHITE
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = dp(4).toFloat()
            strokeCap = android.graphics.Paint.Cap.ROUND
            isAntiAlias = true
        }
        private val dim = android.graphics.Paint().apply {
            color = 0x88000000.toInt()
            style = android.graphics.Paint.Style.FILL
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val side = w * RETICLE
            val left = (w - side) / 2
            val top = (h - side) / 2 - dp(24)
            val corner = side * 0.12f

            // تیرگی بیرون قاب
            val path = android.graphics.Path().apply {
                addRect(0f, 0f, w, h, android.graphics.Path.Direction.CW)
                addRoundRect(left, top, left + side, top + side, dp(18).toFloat(), dp(18).toFloat(),
                    android.graphics.Path.Direction.CW)
                fillType = android.graphics.Path.FillType.EVEN_ODD
            }
            canvas.drawPath(path, dim)

            // چهار گوشه‌ی قاب
            canvas.drawLine(left, top + corner, left, top, paint)
            canvas.drawLine(left, top, left + corner, top, paint)
            canvas.drawLine(left + side - corner, top, left + side, top, paint)
            canvas.drawLine(left + side, top, left + side, top + corner, paint)
            canvas.drawLine(left + side, top + side - corner, left + side, top + side, paint)
            canvas.drawLine(left + side, top + side, left + side - corner, top + side, paint)
            canvas.drawLine(left + corner, top + side, left, top + side, paint)
            canvas.drawLine(left, top + side, left, top + side - corner, paint)
        }
    }
}
