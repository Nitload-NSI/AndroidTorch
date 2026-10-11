package com.example.androidtorch

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.graphics.drawable.GradientDrawable
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private val cameraManager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    private var cameraId: String? = null
    private var maxTorchStrength = 1
    private var torchEnabled = false
    private var pendingTurnOn = false
    private var callbackRegistered = false

    private lateinit var statusView: TextView
    private lateinit var percentView: TextView
    private lateinit var slider: VerticalBrightnessSlider
    private lateinit var toggleButton: Button

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(id: String, enabled: Boolean) {
            if (id == cameraId) {
                torchEnabled = enabled
                renderState()
            }
        }

        override fun onTorchModeUnavailable(id: String) {
            if (id == cameraId) {
                torchEnabled = false
                statusView.text = "闪光灯暂不可用"
                renderState()
            }
        }

        override fun onTorchStrengthLevelChanged(id: String, newStrengthLevel: Int) {
            if (id == cameraId && maxTorchStrength > 1) {
                val percent = (newStrengthLevel * 100f / maxTorchStrength).roundToInt()
                slider.setProgress(percent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = color(0x101613)
        window.navigationBarColor = color(0x101613)
        buildInterface()

        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            statusView.text = "此设备没有闪光灯"
            toggleButton.isEnabled = false
            slider.isEnabled = false
        } else if (hasCameraPermission()) {
            findFlashCamera()
        }
    }

    override fun onStart() {
        super.onStart()
        registerTorchCallback()
    }

    override fun onStop() {
        if (callbackRegistered) {
            cameraManager.unregisterTorchCallback(torchCallback)
            callbackRegistered = false
        }
        super.onStop()
    }

    @Deprecated("Deprecated in Android API; retained for the platform permission callback")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CAMERA_PERMISSION_REQUEST) return

        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            findFlashCamera()
            registerTorchCallback()
            if (pendingTurnOn && cameraId != null) setTorch(true)
        } else {
            pendingTurnOn = false
            statusView.text = "需要相机权限才能开启手电筒"
        }
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(26), dp(18), dp(26), dp(22))
            setBackgroundColor(color(0x101613))
        }

        val eyebrow = TextView(this).apply {
            text = "TORCH CONTROL"
            setTextColor(color(0x9AA79F))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            letterSpacing = 0.14f
        }
        root.addView(eyebrow, LinearLayout.LayoutParams(-1, dp(24)))

        val title = TextView(this).apply {
            text = "手电筒"
            setTextColor(color(0xF2F1E9))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        root.addView(title, LinearLayout.LayoutParams(-1, dp(43)))

        val infoRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        val brightnessLabel = TextView(this).apply {
            text = "亮度"
            setTextColor(color(0xC3CCC5))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        percentView = TextView(this).apply {
            text = "60%"
            setTextColor(color(0xF4C65E))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.END
        }
        infoRow.addView(brightnessLabel, LinearLayout.LayoutParams(0, dp(36), 1f))
        infoRow.addView(percentView, LinearLayout.LayoutParams(0, dp(36), 1f))
        root.addView(infoRow)

        slider = VerticalBrightnessSlider(this).apply {
            setProgress(60)
            onProgressChanged = { progress ->
                percentView.text = "$progress%"
                if (torchEnabled) applyTorchStrength(progress)
            }
        }
        root.addView(slider, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(6)
            bottomMargin = dp(10)
        })

        statusView = TextView(this).apply {
            text = "拖动滑条设定亮度"
            setTextColor(color(0x9AA79F))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
        }
        root.addView(statusView, LinearLayout.LayoutParams(-1, dp(34)))

        toggleButton = Button(this).apply {
            text = "开启手电筒"
            setTextColor(color(0x182018))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            isAllCaps = false
            minHeight = dp(62)
            background = roundedBackground(0xF4C65E, dp(18).toFloat())
            setOnClickListener { toggleTorch() }
        }
        root.addView(toggleButton, LinearLayout.LayoutParams(-1, dp(62)))

        setContentView(root)
    }

    private fun toggleTorch() {
        if (torchEnabled) {
            setTorch(false)
            return
        }

        if (!hasCameraPermission()) {
            pendingTurnOn = true
            statusView.text = "允许相机权限以使用闪光灯"
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
            return
        }

        if (cameraId == null) findFlashCamera()
        setTorch(true)
    }

    private fun findFlashCamera() {
        try {
            cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
            val id = cameraId
            if (id == null) {
                statusView.text = "找不到可用的后置闪光灯"
                percentView.text = "不可用"
                toggleButton.isEnabled = false
                slider.isEnabled = false
                return
            }

            maxTorchStrength = cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
            slider.isEnabled = maxTorchStrength > 1
            percentView.text = if (maxTorchStrength > 1) "${slider.progress}%" else "固定"
            statusView.text = if (maxTorchStrength > 1) {
                "拖动滑条设定亮度"
            } else {
                "此设备的闪光灯仅支持固定亮度"
            }
            toggleButton.isEnabled = true
        } catch (_: CameraAccessException) {
            statusView.text = "无法访问相机闪光灯"
        } catch (_: SecurityException) {
            statusView.text = "需要相机权限才能使用闪光灯"
        }
    }

    private fun setTorch(enabled: Boolean) {
        val id = cameraId ?: run {
            statusView.text = "请先允许相机权限"
            return
        }

        try {
            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && maxTorchStrength > 1) {
                val level = (slider.progress * maxTorchStrength / 100f).roundToInt()
                    .coerceIn(1, maxTorchStrength)
                cameraManager.turnOnTorchWithStrengthLevel(id, level)
            } else {
                cameraManager.setTorchMode(id, enabled)
            }
            pendingTurnOn = false
            torchEnabled = enabled
            renderState()
        } catch (_: CameraAccessException) {
            statusView.text = "闪光灯被占用或暂不可用"
        } catch (_: IllegalArgumentException) {
            statusView.text = "设备不支持当前亮度设置"
        } catch (_: SecurityException) {
            statusView.text = "需要相机权限才能使用闪光灯"
        }
    }

    private fun applyTorchStrength(percent: Int) {
        val id = cameraId ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || maxTorchStrength <= 1) return
        try {
            val level = (percent * maxTorchStrength / 100f).roundToInt()
                .coerceIn(1, maxTorchStrength)
            cameraManager.turnOnTorchWithStrengthLevel(id, level)
        } catch (_: CameraAccessException) {
            statusView.text = "无法调整闪光灯亮度"
        } catch (_: IllegalArgumentException) {
            statusView.text = "设备不支持当前亮度设置"
        }
    }

    private fun renderState() {
        if (!::toggleButton.isInitialized) return
        toggleButton.text = if (torchEnabled) "关闭手电筒" else "开启手电筒"
        statusView.text = if (torchEnabled) {
            if (maxTorchStrength > 1) "正在照亮 · ${slider.progress}%" else "正在照亮"
        } else {
            if (maxTorchStrength > 1 || cameraId == null) "拖动滑条设定亮度" else "闪光灯仅支持固定亮度"
        }
        toggleButton.background = roundedBackground(
            if (torchEnabled) 0xD4DECF else 0xF4C65E,
            dp(18).toFloat()
        )
    }

    private fun registerTorchCallback() {
        if (callbackRegistered || cameraId == null || !hasCameraPermission()) return
        try {
            cameraManager.registerTorchCallback(torchCallback, Handler(Looper.getMainLooper()))
            callbackRegistered = true
        } catch (_: SecurityException) {
            statusView.text = "需要相机权限才能使用闪光灯"
        }
    }

    private fun hasCameraPermission() =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun roundedBackground(argb: Long, radius: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(color(argb))
    }

    private fun color(rgb: Long) = (0xFF000000L or rgb).toInt()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 31
    }
}

private class VerticalBrightnessSlider(context: Context) : View(context) {
    var progress: Int = 60
        private set
    var onProgressChanged: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2B3832.toInt() }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF4C65E.toInt() }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF4C65E.toInt() }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF101613.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF77847C.toInt() }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9AA79F.toInt()
        textSize = 12f * density
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val railRect = RectF()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        contentDescription = "亮度滑条"
    }

    fun setProgress(value: Int) {
        val next = value.coerceIn(1, 100)
        if (progress == next) return
        progress = next
        contentDescription = "亮度，$progress%"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) stateDescription = "$progress%"
        invalidate()
        onProgressChanged?.invoke(progress)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val top = 42f * density
        val bottom = height - 42f * density
        if (bottom <= top) return

        labelPaint.alpha = if (isEnabled) 255 else 105
        canvas.drawText("MAX", centerX, 18f * density, labelPaint)
        canvas.drawText("MIN", centerX, height - 10f * density, labelPaint)

        val railWidth = 12f * density
        railRect.set(centerX - railWidth / 2, top, centerX + railWidth / 2, bottom)
        canvas.drawRoundRect(railRect, railWidth, railWidth, railPaint)

        val fraction = (progress - 1) / 99f
        val thumbY = bottom - fraction * (bottom - top)
        val fillRect = RectF(centerX - railWidth / 2, thumbY, centerX + railWidth / 2, bottom)
        canvas.drawRoundRect(fillRect, railWidth, railWidth, fillPaint)

        for (tick in 0..10) {
            val y = top + (bottom - top) * tick / 10f
            val tickLength = if (tick % 2 == 0) 10f else 5f
            tickPaint.alpha = if (!isEnabled) 70 else if (y >= thumbY) 210 else 120
            canvas.drawLine(centerX + 25f * density, y, centerX + (25f + tickLength) * density, y, tickPaint)
        }

        val thumbRadius = if (isPressed) 20f else 18f
        thumbPaint.alpha = if (isEnabled) 255 else 90
        canvas.drawCircle(centerX, thumbY, thumbRadius * density, thumbPaint)
        canvas.drawCircle(centerX, thumbY, thumbRadius * density, outlinePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                isPressed = true
                updateFromY(event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateFromY(event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                updateFromY(event.y)
                isPressed = false
                performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        info.isScrollable = true
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,
            1f,
            100f,
            progress.toFloat()
        )
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.ACTION_SET_PROGRESS && arguments != null) {
            val value = arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)
            setProgress(value.roundToInt())
            return true
        }
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
            setProgress(progress + 5)
            return true
        }
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            setProgress(progress - 5)
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }

    private fun updateFromY(y: Float) {
        val top = 42f * density
        val bottom = height - 42f * density
        if (bottom <= top) return
        val fraction = (bottom - y).coerceIn(0f, bottom - top) / (bottom - top)
        setProgress((1 + fraction * 99).roundToInt())
    }
}