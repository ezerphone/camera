package com.mediatech.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.app.KeyguardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileDescriptorOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.mediatech.camera.databinding.ActivityMainBinding
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

enum class Mode(val labelRes: Int) {
    NORMAL(R.string.mode_normal),
    VIDEO(R.string.mode_video),
    PRO(R.string.mode_pro),
    QR(R.string.mode_qr)
}

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class, ExperimentalGetImage::class)
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val sp by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val enhanceExec = Executors.newSingleThreadExecutor()   // תיקון איכות לא חוסם תמונות ממוזערות

    // צילומים שעדיין נשמרים ברקע (אפשר להמשיך לצלם בזמן שהם נשמרים)
    private var pendingSaves = 0
    private var idleAction: (() -> Unit)? = null
    private var bindRetries = 0
    private var proShown = true

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var recordingPfd: ParcelFileDescriptor? = null
    private var recordingUri: Uri? = null
    private var paused = false

    private var mode = Mode.NORMAL
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flash = "off"           // off | auto | on
    private var counting = false
    private var needRebind = false

    // סיבוב אוטומטי של אייקונים לפי כיוון המכשיר (המסך עצמו נעול לפורטרט, כמו במקור)
    private var surfaceRotation = Surface.ROTATION_0
    private var uiAngle = 0f
    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o != ORIENTATION_UNKNOWN) onDeviceOrientation(o)
            }
        }
    }

    // כשאפליקציה אחרת מבקשת תמונה/סרטון (IMAGE_CAPTURE / VIDEO_CAPTURE) - מחזירים תוצאה וסוגרים
    private var extAction: String? = null          // "image" | "video" | null
    private var extOutput: Uri? = null

    // סדר המצבים ברצועה שמעל כפתור הצילום (משמאל לימין)
    private val stripModes = listOf(Mode.PRO, Mode.VIDEO, Mode.NORMAL, Mode.QR)
    private var multiTouch = false

    // thumbnail
    private var lastUri: Uri? = null
    private var lastIsVideo = false

    // QR
    @Volatile private var qrBusy = false
    private val scanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        )
    }

    // pro
    private var isoMin = 100
    private var isoMax = 100
    private var tMin = 1_000_000L
    private var tMax = 1_000_000L

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasPerm(Manifest.permission.CAMERA)) startCamera()
            else { toast("נדרשת הרשאת מצלמה"); finish() }
        }

    // ------------------------------------------------------------------ lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        flash = sp.getString("flash", "off") ?: "off"
        readExternalRequest()

        b.preview.scaleType = PreviewView.ScaleType.FIT_CENTER
        b.thumbnail.clipToOutline = true
        b.thumbnail.setImageResource(R.drawable.ic_photo_library)

        b.btnShutter.setOnClickListener { onShutter() }
        b.btnSwitch.setOnClickListener { switchCamera() }
        b.btnPause.setOnClickListener { togglePause() }
        b.btnGear.setOnClickListener {
            if (recording != null || deviceLocked()) return@setOnClickListener
            whenIdle {
                needRebind = true
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }
        b.btnFlash.setOnClickListener { cycleFlash() }
        b.thumbnail.setOnClickListener { if (!deviceLocked()) openLast() }
        b.reviewImage.setOnClickListener { hideReview() }

        setupTouch()
        setupProListeners()
        buildModeStrip()
        updateUi()

        if (hasPerm(Manifest.permission.CAMERA)) startCamera()
        else {
            val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT <= 28) perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            permLauncher.launch(perms.toTypedArray())
        }
    }

    override fun onResume() {
        super.onResume()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        applyRotationPref()
        if (needRebind && provider != null) { needRebind = false; bindUseCases() }
    }

    override fun onPause() {
        super.onPause()
        orientationListener.disable()
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        enhanceExec.shutdown()
    }

    // ------------------------------------------------------------------ לחצני עוצמת קול = צילום
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_CAMERA) {
            if (event.repeatCount == 0 && mode != Mode.QR) onShutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_CAMERA) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun deviceLocked() =
        (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

    /** מריץ פעולה שמשנה את חיבור המצלמה רק אחרי שכל התמונות שבדרך נשמרו (מונע שיבוש שמירה). */
    private fun whenIdle(action: () -> Unit) {
        if (pendingSaves == 0) { action(); return }
        idleAction = action
        handler.postDelayed({ runIdleAction() }, 4000)   // רשת ביטחון
    }

    private fun runIdleAction() {
        val a = idleAction ?: return
        idleAction = null
        a()
    }

    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ device orientation
    private fun rotatables(): List<View> = listOf(
        b.btnFlash, b.btnGear, b.btnSwitch, b.btnPause,
        b.thumbnail, b.btnShutter, b.recTimer, b.countdown, b.zoomLabel
    )

    /** כשסיבוב אוטומטי כבוי: הכול נשאר בפורטרט (תמונות, סרטונים ואייקונים). */
    private fun autoRotateOn() = sp.getBoolean("auto_rotate", true)

    private fun applyRotationPref() {
        if (autoRotateOn()) return
        surfaceRotation = Surface.ROTATION_0
        imageCapture?.targetRotation = Surface.ROTATION_0
        videoCapture?.targetRotation = Surface.ROTATION_0
        rotateUi(0f)
    }

    private fun onDeviceOrientation(o: Int) {
        if (!autoRotateOn()) return
        val snapped = ((o + 45) / 90 * 90) % 360          // 0 / 90 / 180 / 270
        val rot = when (snapped) {
            90 -> Surface.ROTATION_270
            180 -> Surface.ROTATION_180
            270 -> Surface.ROTATION_90
            else -> Surface.ROTATION_0
        }
        if (rot == surfaceRotation) return
        surfaceRotation = rot
        // כדי שתמונות וסרטונים יישמרו בכיוון הנכון
        imageCapture?.targetRotation = rot
        videoCapture?.targetRotation = rot
        rotateUi(((360 - snapped) % 360).toFloat())
    }

    private fun rotateUi(target: Float) {
        val diff = ((target - uiAngle) % 360f + 540f) % 360f - 180f   // הדרך הקצרה ביותר
        uiAngle += diff
        for (v in rotatables()) v.animate().rotation(uiAngle).setDuration(250).start()
        for (i in 0 until b.indicators.childCount) b.indicators.getChildAt(i).rotation = uiAngle
    }

    // ------------------------------------------------------------------ camera binding
    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({ provider = f.get(); b.preview.post { bindUseCases() } }, ContextCompat.getMainExecutor(this))
    }

    private fun selector(aspect: Int, res: ResolutionStrategy? = null): ResolutionSelector =
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy(aspect, AspectRatioStrategy.FALLBACK_RULE_AUTO))
            .also { if (res != null) it.setResolutionStrategy(res) }
            .build()

    private fun aspectPref() = sp.getString("aspect", "full") ?: "full"

    /** איכות צילום: (מגבלת רזולוציה, איכות JPEG) */
    private fun photoQuality(): Pair<ResolutionStrategy?, Int> = when (sp.getString("photo_quality", "high")) {
        "max" -> Pair(null, 100)
        "medium" -> Pair(ResolutionStrategy(Size(3264, 2448), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER), 90)
        "low" -> Pair(ResolutionStrategy(Size(1920, 1440), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER), 80)
        else -> Pair(null, 95)
    }

    private fun bindUseCases() {
        val p = provider ?: return
        p.unbindAll()
        imageCapture = null; videoCapture = null

        val cs = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val fullScreen = isPhotoMode() && aspectPref() == "full"
        val userAspect =
            if (aspectPref() == "4_3") AspectRatio.RATIO_4_3 else AspectRatio.RATIO_16_9
        val previewAspect = when (mode) {
            Mode.VIDEO -> AspectRatio.RATIO_16_9
            Mode.NORMAL, Mode.PRO -> userAspect
            else -> AspectRatio.RATIO_4_3
        }
        // מסך מלא: התצוגה ממלאת את המסך, והתמונה נחתכת לאותו יחס בדיוק (ViewPort משותף)
        b.preview.scaleType = if (fullScreen) PreviewView.ScaleType.FILL_CENTER else PreviewView.ScaleType.FIT_CENTER
        val viewPort = if (fullScreen) b.preview.viewPort else null
        if (fullScreen && viewPort == null && bindRetries < 40) {
            bindRetries++
            b.preview.postDelayed({ bindUseCases() }, 50)
            return
        }
        bindRetries = 0
        val preview = Preview.Builder().setResolutionSelector(selector(previewAspect)).build()
            .also { it.setSurfaceProvider(b.preview.surfaceProvider) }

        try {
            camera = when (mode) {
                Mode.VIDEO -> {
                    val q = when (sp.getString("video_quality", "FHD")) {
                        "UHD" -> Quality.UHD; "HD" -> Quality.HD; "SD" -> Quality.SD; else -> Quality.FHD
                    }
                    val rec = Recorder.Builder().setQualitySelector(
                        QualitySelector.from(q, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
                    ).build()
                    videoCapture = VideoCapture.withOutput(rec)
                    p.bindToLifecycle(this, cs, preview, videoCapture)
                }
                Mode.QR -> {
                    val an = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    an.setAnalyzer(analysisExecutor) { proxy ->
                        val media = proxy.image
                        if (media == null || qrBusy) { proxy.close() }
                        else {
                            val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                            scanner.process(input)
                                .addOnSuccessListener { codes ->
                                    val v = codes.firstOrNull { it.rawValue != null }?.rawValue
                                    if (v != null && !qrBusy) { qrBusy = true; runOnUiThread { showQr(v) } }
                                }
                                .addOnCompleteListener { proxy.close() }
                        }
                    }
                    p.bindToLifecycle(this, cs, preview, an)
                }
                else -> { // NORMAL / PRO
                    val (resStrategy, jpegQ) = photoQuality()
                    val ic = ImageCapture.Builder()
                        .setResolutionSelector(selector(userAspect, resStrategy))
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setJpegQuality(jpegQ)
                        .setFlashMode(flashModeInt())
                        .build()
                    imageCapture = ic
                    if (viewPort != null) {
                        val group = UseCaseGroup.Builder().setViewPort(viewPort)
                            .addUseCase(preview).addUseCase(ic).build()
                        p.bindToLifecycle(this, cs, group)
                    } else p.bindToLifecycle(this, cs, preview, ic)
                }
            }
        } catch (e: Exception) {
            toast("שגיאה בהפעלת המצלמה: ${e.message}")
            return
        }
        imageCapture?.targetRotation = surfaceRotation
        videoCapture?.targetRotation = surfaceRotation
        applyTorch()
        if (mode == Mode.PRO) initPro()
        applyCamera2Options()
        updateUi()
    }

    // ------------------------------------------------------------------ camera2 options
    private fun <T> chars(key: CameraCharacteristics.Key<T>): T? = try {
        camera?.let { Camera2CameraInfo.from(it.cameraInfo).getCameraCharacteristic(key) }
    } catch (e: Exception) { null }

    private fun applyCamera2Options() {
        val cam = camera ?: return
        val o = CaptureRequestOptions.Builder()
        val photoish = mode == Mode.NORMAL || mode == Mode.PRO

        // anti-flicker
        val abWanted = when (sp.getString("antibanding", "auto")) {
            "off" -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_OFF
            "50" -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ
            "60" -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_60HZ
            else -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO
        }
        if (chars(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)?.contains(abWanted) == true)
            o.setCaptureRequestOption(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, abWanted)

        // white balance
        val awb = when (sp.getString("wb", "auto")) {
            "incandescent" -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
            "fluorescent" -> CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
            "daylight" -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
            "cloudy" -> CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
            "twilight" -> CameraMetadata.CONTROL_AWB_MODE_TWILIGHT
            "shade" -> CameraMetadata.CONTROL_AWB_MODE_SHADE
            "warm" -> CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT
            else -> null
        }
        if (awb != null && chars(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.contains(awb) == true)
            o.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, awb)

        // manual exposure (Pro) or scene mode
        val manualIso = manualIso()
        val manualSs = manualSs()
        if (mode == Mode.PRO && (manualIso != null || manualSs != null)) {
            o.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            o.setCaptureRequestOption(
                CaptureRequest.SENSOR_SENSITIVITY, manualIso ?: 100.coerceIn(isoMin, isoMax)
            )
            o.setCaptureRequestOption(
                CaptureRequest.SENSOR_EXPOSURE_TIME, manualSs ?: 16_666_667L.coerceIn(tMin, tMax)
            )
        } else {
            val scene: Int? = when {
                !photoish -> null
                else -> when (sp.getString("scene", "off")) {
                    "action" -> CameraMetadata.CONTROL_SCENE_MODE_ACTION
                    "beach" -> CameraMetadata.CONTROL_SCENE_MODE_BEACH
                    "candlelight" -> CameraMetadata.CONTROL_SCENE_MODE_CANDLELIGHT
                    "fireworks" -> CameraMetadata.CONTROL_SCENE_MODE_FIREWORKS
                    "landscape" -> CameraMetadata.CONTROL_SCENE_MODE_LANDSCAPE
                    "night" -> CameraMetadata.CONTROL_SCENE_MODE_NIGHT
                    "night_portrait" -> CameraMetadata.CONTROL_SCENE_MODE_NIGHT_PORTRAIT
                    "party" -> CameraMetadata.CONTROL_SCENE_MODE_PARTY
                    "portrait" -> CameraMetadata.CONTROL_SCENE_MODE_PORTRAIT
                    "snow" -> CameraMetadata.CONTROL_SCENE_MODE_SNOW
                    "sports" -> CameraMetadata.CONTROL_SCENE_MODE_SPORTS
                    "steadyphoto" -> CameraMetadata.CONTROL_SCENE_MODE_STEADYPHOTO
                    "sunset" -> CameraMetadata.CONTROL_SCENE_MODE_SUNSET
                    "theatre" -> CameraMetadata.CONTROL_SCENE_MODE_THEATRE
                    else -> null
                }
            }
            if (scene != null) {
                if (chars(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)?.contains(scene) == true) {
                    o.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE)
                    o.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, scene)
                }
            }
        }

        // video stabilization
        if (mode == Mode.VIDEO && sp.getBoolean("eis", false) &&
            chars(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                ?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true
        ) o.setCaptureRequestOption(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
        )

        // noise reduction
        if (sp.getBoolean("nr", false) &&
            chars(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
                ?.contains(CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY) == true
        ) o.setCaptureRequestOption(
            CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
        )

        try { Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(o.build()) }
        catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ flash
    // פלאש בסלפי: כשאין פלאש חומרה בחזית, מציגים מסך לבן בבהירות מלאה בזמן הצילום
    private var savedBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    private var screenFlashUsers = 0     // כמה צילומים משתמשים כרגע במסך הלבן

    private fun showScreenFlash() {
        if (screenFlashUsers++ == 0) {
            savedBrightness = window.attributes.screenBrightness
            window.attributes = window.attributes.also { it.screenBrightness = 1f }
            b.flashOverlay.visibility = View.VISIBLE
        }
    }

    private fun hideScreenFlash() {
        if (screenFlashUsers > 0 && --screenFlashUsers == 0) {
            b.flashOverlay.visibility = View.GONE
            window.attributes = window.attributes.also { it.screenBrightness = savedBrightness }
        }
    }

    private fun flashModeInt() = when (flash) {
        "on" -> ImageCapture.FLASH_MODE_ON
        "auto" -> ImageCapture.FLASH_MODE_AUTO
        else -> ImageCapture.FLASH_MODE_OFF
    }

    private fun isPhotoMode() = mode == Mode.NORMAL || mode == Mode.PRO

    private fun setFlash(s: String) {
        flash = s
        sp.edit().putString("flash", s).apply()
        imageCapture?.flashMode = flashModeInt()
        applyTorch()
        updateUi()
    }

    private fun applyTorch() {
        val c = camera ?: return
        val want = !isPhotoMode() && flash == "on"
        if (want && !c.cameraInfo.hasFlashUnit()) { toast("אין פנס במצלמה הזו"); return }
        try { c.cameraControl.enableTorch(want) } catch (_: Exception) {}
    }

    /** כפתור פלאש יחיד: בצילום אוטו' -> דלוק -> כבוי, בווידאו (פנס) כבוי <-> דלוק. */
    private fun cycleFlash() {
        val next: String
        if (isPhotoMode()) {
            next = when (flash) {
                "auto" -> "on"
                "on" -> "off"
                else -> "auto"
            }
        } else {
            next = if (flash == "on") "off" else "on"
        }
        setFlash(next)
    }

    // ------------------------------------------------------------------ touch: zoom + focus
    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val cam = camera ?: return true
                val z = cam.cameraInfo.zoomState.value ?: return true
                val nz = (z.zoomRatio * d.scaleFactor).coerceIn(z.minZoomRatio, z.maxZoomRatio)
                cam.cameraControl.setZoomRatio(nz)
                showZoom(nz)
                return true
            }
        })
        val tap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean { focusAt(e.x, e.y); return true }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || multiTouch) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) > dp(80) && abs(dx) > abs(dy) * 1.5f) { swipeMode(if (dx < 0) 1 else -1); return true }
                return false
            }
        })
        b.preview.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) multiTouch = false
            if (ev.pointerCount > 1) multiTouch = true
            scale.onTouchEvent(ev)
            if (!scale.isInProgress) tap.onTouchEvent(ev)
            true
        }
    }

    private val hideZoom = Runnable { b.zoomLabel.visibility = View.GONE }

    private fun showZoom(ratio: Float) {
        b.zoomLabel.text = String.format(Locale.US, "%.1fx", ratio)
        b.zoomLabel.visibility = View.VISIBLE
        handler.removeCallbacks(hideZoom)
        handler.postDelayed(hideZoom, 1000)
    }

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val pt = b.preview.meteringPointFactory.createPoint(x, y)
        try { cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(pt).build()) }
        catch (_: Exception) {}
        val r = b.focusRing
        r.translationX = x - dp(36) ; r.translationY = y - dp(36)
        r.visibility = View.VISIBLE
        handler.removeCallbacks(hideFocus)
        handler.postDelayed(hideFocus, 1200)
    }
    private val hideFocus = Runnable { b.focusRing.visibility = View.GONE }

    // ------------------------------------------------------------------ shutter flow
    private fun onShutter() {
        if (counting) return
        when (mode) {
            Mode.VIDEO -> if (recording != null) recording?.stop() else withTimer { startRecording() }
            Mode.QR -> toast("כוון את המצלמה אל קוד QR")
            else -> withTimer { takePhoto() }
        }
    }

    private fun withTimer(block: () -> Unit) {
        val sec = (sp.getString("timer", "0") ?: "0").toIntOrNull() ?: 0
        if (sec == 0) { block(); return }
        counting = true
        b.countdown.visibility = View.VISIBLE
        var left = sec
        val tick = object : Runnable {
            override fun run() {
                if (left > 0) { b.countdown.text = left.toString(); left--; handler.postDelayed(this, 1000) }
                else { b.countdown.visibility = View.GONE; counting = false; block() }
            }
        }
        tick.run()
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    // ------------------------------------------------------------------ saving (תוספת: מיקום שמירה מותאם)
    private fun createOutputUri(name: String, mime: String): Uri? {
        val ext = if (mime.startsWith("video")) "mp4" else "jpg"
        val tree = sp.getString("tree", null)
        return try {
            if (tree != null) {
                val t = Uri.parse(tree)
                val parent = DocumentsContract.buildDocumentUriUsingTree(t, DocumentsContract.getTreeDocumentId(t))
                DocumentsContract.createDocument(contentResolver, parent, mime, "$name.$ext")
            } else {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.$ext")
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    if (Build.VERSION.SDK_INT >= 29) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
                    } else {
                        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")
                        dir.mkdirs()
                        @Suppress("DEPRECATION")
                        put(MediaStore.MediaColumns.DATA, File(dir, "$name.$ext").absolutePath)
                    }
                }
                val coll = if (mime.startsWith("video")) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                contentResolver.insert(coll, cv)
            }
        } catch (e: Exception) { null }
    }

    private fun takePhoto() {
        val ic = imageCapture ?: return
        if (extAction == "image" && pendingSaves > 0) return      // בקשה חיצונית = תמונה אחת בלבד
        val uri = (if (extAction == "image") extOutput else null) ?: createOutputUri("IMG_${stamp()}", "image/jpeg")
        val os: OutputStream? = try { uri?.let { contentResolver.openOutputStream(it) } } catch (e: Exception) { null }
        if (uri == null || os == null) { toast("אין גישה למיקום השמירה. בחר אותו מחדש בהגדרות."); return }
        val opts = ImageCapture.OutputFileOptions.Builder(os).build()

        // בסלפי בלי פלאש חומרה: פלאש מסך (כשהפלאש "פעיל" או "אוטו׳")
        val useScreenFlash = lensFacing == CameraSelector.LENS_FACING_FRONT &&
            camera?.cameraInfo?.hasFlashUnit() != true && flash != "off"

        pendingSaves++
        shutterFeedback()

        val done = {
            if (useScreenFlash) hideScreenFlash()   // המסך הלבן נכבה רק אחרי שהצילום באמת בוצע
            runCatching { os.close() }
            pendingSaves = (pendingSaves - 1).coerceAtLeast(0)
            if (pendingSaves == 0) runIdleAction()
        }

        val capture = {
            ic.takePicture(opts, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                    done()
                    if (extAction == "image") { finishImageCapture(uri); return }
                    updateThumb(uri, false)
                    if (sp.getBoolean("enhance", false)) enhanceAsync(uri)
                }
                override fun onError(e: ImageCaptureException) {
                    done()
                    toast("שגיאה בשמירה: ${e.message}")
                }
            })
        }
        if (useScreenFlash) {
            showScreenFlash()
            handler.postDelayed({ capture() }, 250)   // זמן קצר לחשיפה להסתגל לאור המסך
        } else capture()
    }

    /** משוב מיידי ללחיצה: הבהוב קצר בתצוגה, כדי שיהיה ברור שהתמונה צולמה. */
    private fun shutterFeedback() {
        b.preview.animate().cancel()
        b.preview.alpha = 0.35f
        b.preview.animate().alpha(1f).setDuration(120).start()
    }

    // ------------------------------------------------------------------ video
    private fun startRecording() {
        val vc = videoCapture ?: return
        val uri = (if (extAction == "video") extOutput else null) ?: createOutputUri("VID_${stamp()}", "video/mp4")
        val pfd = try { uri?.let { contentResolver.openFileDescriptor(it, "w") } } catch (e: Exception) { null }
        if (uri == null || pfd == null) { toast("אין גישה למיקום השמירה. בחר אותו מחדש בהגדרות."); return }
        recordingPfd = pfd; recordingUri = uri; paused = false
        var pending = vc.output.prepareRecording(this, FileDescriptorOutputOptions.Builder(pfd).build())
        if (sp.getBoolean("mic", true) && hasPerm(Manifest.permission.RECORD_AUDIO)) pending = pending.withAudioEnabled()
        recording = pending.start(ContextCompat.getMainExecutor(this)) { ev ->
            when (ev) {
                is VideoRecordEvent.Start -> setRecordingUi(true)
                is VideoRecordEvent.Status -> {
                    val s = ev.recordingStats.recordedDurationNanos / 1_000_000_000L
                    b.recTimer.text = String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
                }
                is VideoRecordEvent.Finalize -> {
                    setRecordingUi(false)
                    recording = null
                    runCatching { recordingPfd?.close() }; recordingPfd = null
                    if (ev.hasError()) toast("שגיאת וידאו (${ev.error})")
                    else if (extAction == "video") {
                        setResult(RESULT_OK, Intent().setData(recordingUri)); finish()
                    } else {
                        // בווידאו לא מציגים את מה שהוסרט - רק מעדכנים את התמונה הממוזערת וממשיכים כרגיל
                        recordingUri?.let { updateThumb(it, true) }
                    }
                }
                else -> {}
            }
        }
    }

    private fun setRecordingUi(on: Boolean) {
        b.recTimer.visibility = if (on) View.VISIBLE else View.GONE
        if (on) b.recTimer.text = "00:00"
        b.btnPause.visibility = if (on) View.VISIBLE else View.GONE
        b.btnPause.setImageResource(R.drawable.ic_pause_recording)
        b.btnSwitch.visibility = if (on) View.GONE else View.VISIBLE
        b.btnGear.alpha = if (on) 0.3f else 1f
        b.modeStrip.visibility = if (on) View.INVISIBLE else if (extAction != null) View.GONE else View.VISIBLE
        b.btnShutter.setImageResource(if (on) R.drawable.ic_stop_recording else R.drawable.ic_shutter_video)
    }

    private fun togglePause() {
        val r = recording ?: return
        if (paused) { r.resume(); b.btnPause.setImageResource(R.drawable.ic_pause_recording) }
        else { r.pause(); b.btnPause.setImageResource(R.drawable.ic_resume_recording) }
        paused = !paused
    }

    // ------------------------------------------------------------------ QR
    private fun showQr(value: String) {
        val isUrl = value.startsWith("http://") || value.startsWith("https://")
        val d = AlertDialog.Builder(this)
            .setTitle("תוכן קוד QR")
            .setMessage(value)
            .setNeutralButton("העתק") { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("qr", value))
                toast("הועתק")
            }
            .setNegativeButton("ביטול", null)
        if (isUrl) d.setPositiveButton("פתח בדפדפן") { _, _ ->
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value))) } catch (_: Exception) {}
        }
        d.setOnDismissListener { handler.postDelayed({ qrBusy = false }, 800) }
        d.show()
    }

    // ------------------------------------------------------------------ pro mode
    private fun setupProListeners() {
        val l = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                if (s === b.proEv) {
                    val r = camera?.cameraInfo?.exposureState?.exposureCompensationRange ?: return
                    camera?.cameraControl?.setExposureCompensationIndex(r.lower + p)
                } else applyCamera2Options()
                updateProLabels()
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        }
        b.proEv.setOnSeekBarChangeListener(l)
        b.proIso.setOnSeekBarChangeListener(l)
        b.proSs.setOnSeekBarChangeListener(l)
    }

    private fun initPro() {
        chars(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { isoMin = it.lower; isoMax = it.upper }
        chars(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let {
            tMin = it.lower; tMax = min(it.upper, 2_000_000_000L)
        }
        val es = camera?.cameraInfo?.exposureState
        if (es != null) {
            val r = es.exposureCompensationRange
            b.proEv.max = r.upper - r.lower
            b.proEv.progress = es.exposureCompensationIndex - r.lower
            b.proEv.isEnabled = es.isExposureCompensationSupported
        }
        b.proIso.progress = 0; b.proSs.progress = 0
        updateProLabels()
    }

    private fun manualIso(): Int? {
        val p = b.proIso.progress
        return if (p <= 0) null else isoMin + ((isoMax - isoMin) * (p - 1) / 99.0).toInt()
    }

    private fun manualSs(): Long? {
        val p = b.proSs.progress
        return if (p <= 0) null else (tMin * (tMax.toDouble() / tMin).pow((p - 1) / 99.0)).toLong()
    }

    private fun fmtSs(ns: Long): String {
        val s = ns / 1e9
        return if (s >= 0.5) String.format(Locale.US, "%.1fs", s) else "1/${(1 / s).roundToInt()}"
    }

    private fun updateProLabels() {
        val es = camera?.cameraInfo?.exposureState
        val ev = if (es != null)
            String.format(Locale.US, "%.1f", (b.proEv.progress + es.exposureCompensationRange.lower) * es.exposureCompensationStep.toFloat())
        else "0"
        b.proEvLabel.text = "EV  $ev"
        b.proIsoLabel.text = "ISO  " + (manualIso()?.toString() ?: "אוטו׳")
        b.proSsLabel.text = "תריס  " + (manualSs()?.let { fmtSs(it) } ?: "אוטו׳")
    }

    // ------------------------------------------------------------------ switch camera
    private fun switchCamera() {
        whenIdle {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            bindUseCases()
        }
    }

    // ------------------------------------------------------------------ mode strip (כמו במצלמת המערכת)
    @SuppressLint("ClickableViewAccessibility")
    private fun buildModeStrip() {
        b.modeStrip.removeAllViews()
        for (m in stripModes) {
            val t = TextView(this)
            t.setText(m.labelRes)
            t.textSize = 14f
            t.gravity = Gravity.CENTER
            t.setShadowLayer(4f, 0f, 1f, 0xAA000000.toInt())
            t.setPadding(dp(14), 0, dp(14), 0)
            t.tag = m
            t.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT
            )
            b.modeStrip.addView(t)
        }
        // הקשה על שם מצב או החלקה ימינה/שמאלה על הרצועה
        val g = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                for (i in 0 until b.modeStrip.childCount) {
                    val c = b.modeStrip.getChildAt(i)
                    if (e.x >= c.left && e.x <= c.right) (c.tag as? Mode)?.let { selectMode(it) }
                }
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vx) <= abs(vy)) return false
                swipeMode(if (vx < 0) 1 else -1)
                return true
            }
        })
        b.modeStrip.setOnTouchListener { _, ev -> g.onTouchEvent(ev) }
        updateModeStrip()
    }

    private fun updateModeStrip() {
        for (i in 0 until b.modeStrip.childCount) {
            val t = b.modeStrip.getChildAt(i) as TextView
            val sel = t.tag == mode
            t.setTextColor(if (sel) Color.parseColor("#FFD400") else Color.WHITE)
            t.setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun swipeMode(dir: Int) {
        val i = stripModes.indexOf(mode) + dir
        if (i in stripModes.indices) selectMode(stripModes[i])
    }

    private fun selectMode(m: Mode) {
        if (recording != null) return
        if (m == mode) {
            // לחיצה נוספת על "מקצועי" מסתירה/מציגה את הגדרות הצילום
            if (m == Mode.PRO) { proShown = !proShown; updateUi() }
            return
        }
        whenIdle {
            mode = m
            if (m == Mode.PRO) proShown = true
            bindUseCases()
        }
    }

    // ------------------------------------------------------------------ UI state
    private fun updateUi() {
        val photo = isPhotoMode()
        b.btnShutter.setImageResource(
            if (mode == Mode.VIDEO) R.drawable.ic_shutter_video else R.drawable.ic_shutter_photo
        )
        b.proPanel.visibility = if (mode == Mode.PRO && proShown) View.VISIBLE else View.GONE
        val f = if (!photo && flash == "auto") "off" else flash
        b.btnFlash.setImageResource(
            when (f) {
                "on" -> R.drawable.ic_flash_status_on_selected
                "auto" -> R.drawable.ic_flash_status_auto
                else -> R.drawable.ic_flash_status_off
            }
        )
        if (extAction != null) b.modeStrip.visibility = View.GONE
        updateModeStrip()
        updateIndicators()
    }

    private fun chip(text: String): TextView = TextView(this).apply {
        this.text = text; setTextColor(Color.WHITE); textSize = 12f; rotation = uiAngle
        setBackgroundResource(R.drawable.bg_pill)
        setPadding(dp(8), dp(3), dp(8), dp(3))
        layoutParams = LinearLayout.LayoutParams(-2, -2).also { it.bottomMargin = dp(4) }
    }

    private fun indicator(res: Int): ImageView = ImageView(this).apply {
        setImageResource(res)
        rotation = uiAngle
        layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).also { it.bottomMargin = dp(6) }
    }

    private fun updateIndicators() {
        b.indicators.removeAllViews()
        when (sp.getString("timer", "0")) {
            "2" -> b.indicators.addView(indicator(R.drawable.ic_selftimer_indicator_2))
            "10" -> b.indicators.addView(indicator(R.drawable.ic_selftimer_indicator_10))
        }
        val scene = when (sp.getString("scene", "off")) {
            "beach" -> R.drawable.ic_scene_mode_beach_indicator
            "candlelight" -> R.drawable.ic_scene_mode_candlelight_indicator
            "fireworks" -> R.drawable.ic_scene_mode_fireworks_indicator
            "landscape" -> R.drawable.ic_scene_mode_landscape_indicator
            "night" -> R.drawable.ic_scene_mode_night_indicator
            "night_portrait" -> R.drawable.ic_scene_mode_night_portrait_indicator
            "party" -> R.drawable.ic_scene_mode_party_indicator
            "portrait" -> R.drawable.ic_scene_mode_portrait_indicator
            "snow" -> R.drawable.ic_scene_mode_snow_indicator
            "sports" -> R.drawable.ic_scene_mode_sports_indicator
            "steadyphoto" -> R.drawable.ic_scene_mode_steady_photo_indicator
            "sunset" -> R.drawable.ic_scene_mode_sunset_indicator
            "theatre" -> R.drawable.ic_scene_mode_theatre_indicator
            else -> null
        }
        if (scene != null && isPhotoMode()) b.indicators.addView(indicator(scene))
        val wb = when (sp.getString("wb", "auto")) {
            "incandescent" -> R.drawable.ic_white_balance_incandescent_indicator
            "fluorescent" -> R.drawable.ic_white_balance_fluorescent_indicator
            "daylight" -> R.drawable.ic_white_balance_daylight_indicator
            "cloudy" -> R.drawable.ic_white_balance_cloudy_indicator
            "twilight" -> R.drawable.ic_white_balance_twilight_indicator
            "shade" -> R.drawable.ic_white_balance_shade_indicator
            "warm" -> R.drawable.ic_white_balance_warm_indicator
            else -> null
        }
        if (wb != null) b.indicators.addView(indicator(wb))
        if (sp.getString("tree", null) != null) b.indicators.addView(chip("📁"))
    }

    // ------------------------------------------------------------------ תיקון איכות (ללא AI)
    private fun enhanceAsync(src: Uri) {
        enhanceExec.execute {
            try {
                val bmp = decodeLimited(src, 12_000_000) ?: throw IllegalStateException("decode")
                val res = ClassicEnhancer.enhance(bmp)
                bmp.recycle()
                val out = createOutputUri("IMG_${stamp()}_ENH", "image/jpeg") ?: throw IllegalStateException("out")
                contentResolver.openOutputStream(out)!!.use { res.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                res.recycle()
                runOnUiThread { updateThumb(out, false) }
            } catch (e: Throwable) {
                runOnUiThread { toast("תיקון האיכות נכשל") }
            }
        }
    }

    // ------------------------------------------------------------------ תצוגה קצרה של התמונה אחרי צילום
    private val hideReviewRunnable = Runnable { hideReview() }

    private fun showReview(uri: Uri) {
        io.execute {
            val bmp: Bitmap? = try { decodeBitmap(uri, 1600) } catch (e: Exception) { null }
            runOnUiThread {
                if (bmp != null) {
                    b.reviewImage.setImageBitmap(bmp)
                    b.reviewImage.visibility = View.VISIBLE
                    handler.removeCallbacks(hideReviewRunnable)
                    handler.postDelayed(hideReviewRunnable, 1000)
                }
            }
        }
    }

    private fun hideReview() {
        handler.removeCallbacks(hideReviewRunnable)
        b.reviewImage.visibility = View.GONE
        b.reviewImage.setImageDrawable(null)
    }

    /** מפענח תמונה עד maxPixels פיקסלים (כדי שהעיבוד יהיה סביר) ומיישר לפי EXIF. */
    private fun decodeLimited(uri: Uri, maxPixels: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while ((o.outWidth.toLong() * o.outHeight) / (s * s) > maxPixels) s *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = s }
        val bm = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) } ?: return null
        val orient = contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val deg = when (orient) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        return if (deg == 0f) bm
        else Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, Matrix().apply { postRotate(deg) }, true)
    }

    // ------------------------------------------------------------------ thumbnail
    private fun updateThumb(uri: Uri, video: Boolean) {
        lastUri = uri; lastIsVideo = video
        io.execute {
            val bmp: Bitmap? = try {
                if (video) {
                    val r = MediaMetadataRetriever()
                    r.setDataSource(this, uri)
                    val f = r.getFrameAtTime(0)
                    r.release(); f
                } else decodeThumb(uri)
            } catch (e: Exception) { null }
            runOnUiThread {
                if (bmp != null) { b.thumbnail.clearColorFilter(); b.thumbnail.setImageBitmap(bmp) }
            }
        }
    }

    private fun decodeThumb(uri: Uri): Bitmap? = decodeBitmap(uri, 256)

    private fun decodeBitmap(uri: Uri, maxSide: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = s }
        val bm = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) } ?: return null
        val orient = contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val deg = when (orient) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        return if (deg == 0f) bm
        else Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, Matrix().apply { postRotate(deg) }, true)
    }

    // ------------------------------------------------------------------ התנהגות כמצלמת מערכת
    @Suppress("DEPRECATION")
    private fun readExternalRequest() {
        when (intent?.action) {
            MediaStore.ACTION_IMAGE_CAPTURE -> extAction = "image"
            MediaStore.ACTION_VIDEO_CAPTURE -> { extAction = "video"; mode = Mode.VIDEO }
            MediaStore.INTENT_ACTION_VIDEO_CAMERA -> mode = Mode.VIDEO
            MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA,
            MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE -> mode = Mode.NORMAL
        }
        if (extAction != null) {
            extOutput = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri::class.java)
            else intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT)
        }
    }

    private fun finishImageCapture(uri: Uri) {
        val res = Intent()
        if (extOutput == null) res.putExtra("data", decodeBitmap(uri, 512))   // לפי הסטנדרט: תמונה קטנה ב-data
        setResult(RESULT_OK, res)
        finish()
    }

    private fun openLast() {
        val u = lastUri
        try {
            if (u != null) startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(u, if (lastIsVideo) "video/*" else "image/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ) else startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY))
        } catch (_: Exception) { toast("לא נמצאה אפליקציית גלריה") }
    }
}
