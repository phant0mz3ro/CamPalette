package com.example.camvisionpro

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.camvisionpro.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import android.content.ContentValues
import android.content.Intent
import android.provider.MediaStore
import android.graphics.drawable.BitmapDrawable
import androidx.annotation.RequiresPermission
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {

    companion object {
        init {
            System.loadLibrary("opencv_java4")
        }
    }

    private lateinit var database: AppDatabase
    private var presetList: MutableList<Preset> = mutableListOf()

    // Menu state
    private enum class MenuMode { BROWSING_PRESETS, TUNING_PARAMETERS }
    private var menuMode = MenuMode.BROWSING_PRESETS

    private val presetNames = mutableListOf<String>()
    private var highlightedPresetIndex = 0

    private val parameterNames = listOf("curve", "saturation", "contrast", "warmth")
    private var highlightedParameterIndex = 0

    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null

    // Holds the captured photo in memory so we can reprocess it repeatedly
    private var capturedMat: Mat? = null
    private var currentMode: String = "moody"
    private lateinit var bleManager: BleManager

    private val allPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val cameraGranted = permissions[Manifest.permission.CAMERA] == true
            val bluetoothGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permissions[Manifest.permission.BLUETOOTH_SCAN] == true &&
                        permissions[Manifest.permission.BLUETOOTH_CONNECT] == true
            } else {
                permissions[Manifest.permission.BLUETOOTH] == true
            }

            if (cameraGranted) startCamera()
            if (bluetoothGranted) bleManager.startScan()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bleManager = BleManager(
            context = this,
            onNavUp = {
                runOnUiThread { handleNav(-1) }
            },
            onNavDown = {
                runOnUiThread { handleNav(1) }
            },
            onSelect = {
                runOnUiThread { handleSelect() }
            },
            onExit = {
                runOnUiThread { handleExit() }
            },
            onEncoderDelta = { delta ->
                runOnUiThread { handleEncoderDelta(delta) }
            },
            onShutter = {
                runOnUiThread { handleShutterAction() }
            },
            onSave = {
                runOnUiThread {
                    if (binding.resultImageView.visibility == View.VISIBLE) {
                        saveCurrentResult()
                    }
                }
            },
            onConnectionChanged = { connected ->
                runOnUiThread {
                    binding.pairButton.visibility = if (connected) View.GONE else View.VISIBLE
                }
            }
        )
        val permissionsToRequest = mutableListOf(Manifest.permission.CAMERA)
        permissionsToRequest.addAll(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            } else {
                listOf(
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            }
        )

        allPermissionsLauncher.launch(permissionsToRequest.toTypedArray())

        database = AppDatabase.getInstance(this)

        CoroutineScope(Dispatchers.IO).launch {
            var saved = database.presetDao().getAll()
            if (saved.isEmpty()) {
                // First launch — seed default presets
                database.presetDao().insert(Preset(name = "moody", curveStrength = 1.8, saturationMultiplier = 0.7, contrastValue = 0, warmthValue = 0))
                database.presetDao().insert(Preset(name = "colorful", curveStrength = 1.0, saturationMultiplier = 1.6, contrastValue = 0, warmthValue = 0))
                saved = database.presetDao().getAll()
            }
            presetList = saved.toMutableList()
            withContext(Dispatchers.Main) {
                presetNames.clear()
                presetNames.addAll(presetList.map { it.name })
                currentMode = presetNames.firstOrNull() ?: "moody"
                updateOledMenu()
            }
        }

        binding.captureButton.setOnClickListener {
            handleShutterAction()
        }

        binding.downloadButton.setOnClickListener {
            saveCurrentResult()
        }

        binding.pairButton.setOnClickListener {
            bleManager.startScan()
        }
        //bleManager.startScan()


        binding.presetManagerButton.setOnClickListener {
            presetManagerLauncher.launch(Intent(this, PresetManagerActivity::class.java))
        }

    }
    private val presetManagerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            val saved = database.presetDao().getAll()
            withContext(Dispatchers.Main) {
                presetList = saved.toMutableList()
                presetNames.clear()
                presetNames.addAll(presetList.map { it.name })

                // Handle case where current preset was deleted (Test 4.1)
                if (presetNames.isEmpty()) {
                    currentMode = ""
                } else if (!presetNames.contains(currentMode)) {
                    currentMode = presetNames[0]
                    highlightedPresetIndex = 0
                }

                updateOledMenu()
                if (capturedMat != null) reprocessAndShow() // refresh live preview immediately
            }
        }
    }
    private fun syncSeekBarToHighlightedParameter() {
        val params = getParamsForPreset(currentMode) ?: return
        val paramName = parameterNames[highlightedParameterIndex]
        val seekValue = when (paramName) {
            "curve" -> ((params.curveStrength - 1.0) * 100 + 100).toInt()
            "saturation" -> (params.saturationMultiplier * 100).toInt()
            "contrast" -> params.contrastValue + 100
            "warmth" -> params.warmthValue + 100
            else -> 100
        }

    }
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            } catch (e: Exception) {
                Log.e("MainActivity", "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }
    private fun handleShutterAction() {
        if (binding.resultImageView.visibility == View.VISIBLE) {
            binding.resultImageView.visibility = View.GONE
            binding.downloadButton.visibility = View.GONE
            binding.previewView.visibility = View.VISIBLE
            binding.captureButton.text = "Capture"
            menuMode = MenuMode.BROWSING_PRESETS
            bleManager.sendDisplayUpdate("Ready to shoot")
            capturedMat?.release()
            capturedMat = null
        } else {
            takePhoto()
        }
    }
    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis())
        val photoFile = File(externalMediaDirs.first(), "$name.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e("MainActivity", "Photo capture failed", exc)
                }

                @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    // Load once into memory, downscaled for fast live tuning
                    val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
                    val scaled = Bitmap.createScaledBitmap(
                        bitmap, bitmap.width / 2, bitmap.height / 2, true
                    )
                    val mat = Mat()
                    Utils.bitmapToMat(scaled, mat)
                    val rgbMat = Mat()
                    Imgproc.cvtColor(mat, rgbMat, Imgproc.COLOR_RGBA2RGB)
                    capturedMat = rgbMat
                    updateOledMenu()
                    runOnUiThread {
                        binding.previewView.visibility = View.GONE
                        binding.resultImageView.visibility = View.VISIBLE
                        binding.downloadButton.visibility = View.VISIBLE
                        binding.captureButton.text = "Retake"

                        // Reset menu state on every new capture
                        menuMode = MenuMode.BROWSING_PRESETS

                        updateOledMenu()
                    }
                    reprocessAndShow()
                }
            }
        )
    }

    private fun handleNav(direction: Int) {
        when (menuMode) {
            MenuMode.BROWSING_PRESETS -> {
                if (presetNames.isEmpty()) return
                highlightedPresetIndex = (highlightedPresetIndex + direction + presetNames.size) % presetNames.size
                currentMode = presetNames[highlightedPresetIndex]
                reprocessAndShow()
            }
            MenuMode.TUNING_PARAMETERS -> {
                highlightedParameterIndex = (highlightedParameterIndex + direction + parameterNames.size) % parameterNames.size
            }
        }
        updateOledMenu()
    }

    private fun handleSelect() {
        if (menuMode == MenuMode.BROWSING_PRESETS && presetNames.isNotEmpty()) {
            menuMode = MenuMode.TUNING_PARAMETERS
            currentMode = presetNames[highlightedPresetIndex]
            highlightedParameterIndex = 0
            updateOledMenu()
            reprocessAndShow()
        }
    }

    private fun handleExit() {
        if (menuMode == MenuMode.TUNING_PARAMETERS) {
            menuMode = MenuMode.BROWSING_PRESETS
            updateOledMenu()
        }
    }

    private fun handleEncoderDelta(delta: Int) {
        if (menuMode != MenuMode.TUNING_PARAMETERS) return
        val preset = presetList.find { it.name == presetNames[highlightedPresetIndex] } ?: return

        when (parameterNames[highlightedParameterIndex]) {
            "curve" -> preset.curveStrength = (preset.curveStrength + delta * 0.05).coerceIn(0.5, 3.0)
            "saturation" -> preset.saturationMultiplier = (preset.saturationMultiplier + delta * 0.05).coerceIn(0.0, 2.0)
            "contrast" -> preset.contrastValue = (preset.contrastValue + delta * 2).coerceIn(-100, 100)
            "warmth" -> preset.warmthValue = (preset.warmthValue + delta * 2).coerceIn(-100, 100)
        }
        updateOledMenu()
        reprocessAndShow()
    }

    // Re-runs processing on the already-captured in-memory image —
    // called every time mode or intensity changes, no re-capture needed
    private fun reprocessAndShow() {
        val srcMat = capturedMat ?: return
        val params = getParamsForPreset(currentMode) ?: ProcessingParams()

        CoroutineScope(Dispatchers.Default).launch {
            val resultMat = applyProcessing(srcMat, params)
            val outputBitmap = Bitmap.createBitmap(resultMat.cols(), resultMat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(resultMat, outputBitmap)
            withContext(Dispatchers.Main) {
                binding.resultImageView.setImageBitmap(outputBitmap)
            }
        }
    }

    data class ProcessingParams(
        var curveStrength: Double = 1.0,
        var saturationMultiplier: Double = 1.0,
        var contrastValue: Int = 0,
        var warmthValue: Int = 0
    )

    private fun getParamsForPreset(name: String): ProcessingParams {
        val preset = presetList.find { it.name == name } ?: return ProcessingParams()
        return ProcessingParams(
            curveStrength = preset.curveStrength,
            saturationMultiplier = preset.saturationMultiplier,
            contrastValue = preset.contrastValue,
            warmthValue = preset.warmthValue
        )
    }

    private fun updateOledMenu() {
        val text = when (menuMode) {
            MenuMode.BROWSING_PRESETS -> {
                presetNames.mapIndexed { index, name ->
                    if (index == highlightedPresetIndex) "> $name" else "  $name"
                }.joinToString("\n")
            }
            MenuMode.TUNING_PARAMETERS -> {
                val preset = presetList.find { it.name == presetNames[highlightedPresetIndex] } ?: return
                parameterNames.mapIndexed { index, name ->
                    val value = when (name) {
                        "curve" -> String.format("%.2f", preset.curveStrength)
                        "saturation" -> String.format("%.2f", preset.saturationMultiplier)
                        "contrast" -> preset.contrastValue.toString()
                        "warmth" -> preset.warmthValue.toString()
                        else -> "0"
                    }
                    val label = "$name: $value"
                    if (index == highlightedParameterIndex) "> $label" else "  $label"
                }.joinToString("\n")
            }
        }
        bleManager.sendDisplayUpdate(text)
    }

    private fun applyProcessing(src: Mat, params: ProcessingParams): Mat {
        var result = src

        // Tone curve (S-curve for shadows/highlights)
        if (params.curveStrength != 1.0) {
            val lut = Mat(1, 256, CvType.CV_8U)
            for (i in 0..255) {
                val x = i / 255.0
                val curved = when {
                    x < 0.5 -> 0.5 * Math.pow(2 * x, params.curveStrength)
                    else -> 1 - 0.5 * Math.pow(2 * (1 - x), params.curveStrength)
                }
                lut.put(0, i, (curved * 255).toInt().coerceIn(0, 255).toDouble())
            }
            val toned = Mat()
            Core.LUT(result, lut, toned)
            result = toned
        }

        // Saturation
        if (params.saturationMultiplier != 1.0) {
            val hsv = Mat()
            Imgproc.cvtColor(result, hsv, Imgproc.COLOR_RGB2HSV)
            val channels = ArrayList<Mat>()
            Core.split(hsv, channels)
            Core.multiply(channels[1], Scalar(params.saturationMultiplier), channels[1])
            Core.merge(channels, hsv)
            val toned = Mat()
            Imgproc.cvtColor(hsv, toned, Imgproc.COLOR_HSV2RGB)
            result = toned
        }

        // Contrast
        if (params.contrastValue != 0) {
            val alpha = 1.0 + (params.contrastValue / 100.0)
            val toned = Mat()
            result.convertTo(toned, -1, alpha, 0.0)
            result = toned
        }

        // Warmth (shift red up, blue down for warm; reverse for cool)
        if (params.warmthValue != 0) {
            val channels = ArrayList<Mat>()
            Core.split(result, channels)
            val shift = params.warmthValue / 2.0
            Core.add(channels[0], Scalar(shift), channels[0])
            Core.subtract(channels[2], Scalar(shift), channels[2])
            val toned = Mat()
            Core.merge(channels, toned)
            result = toned
        }

        return result
    }

    // intensity: 0-100 from the slider, mapped to curve strength
    private fun applyMoody(src: Mat, intensity: Int): Mat {
        val strength = 1.0 + (intensity / 100.0) * 1.5 // maps 0-100 to 1.0-2.5

        val lut = Mat(1, 256, CvType.CV_8U)
        for (i in 0..255) {
            val x = i / 255.0
            val curved = when {
                x < 0.5 -> 0.5 * Math.pow(2 * x, strength)
                else -> 1 - 0.5 * Math.pow(2 * (1 - x), strength)
            }
            lut.put(0, i, (curved * 255).toInt().coerceIn(0, 255).toDouble())
        }
        val toned = Mat()
        Core.LUT(src, lut, toned)

        val hsv = Mat()
        Imgproc.cvtColor(toned, hsv, Imgproc.COLOR_RGB2HSV)
        val channels = ArrayList<Mat>()
        Core.split(hsv, channels)
        val satMultiplier = 1.0 - (intensity / 100.0) * 0.5 // 1.0 down to 0.5
        Core.multiply(channels[1], Scalar(satMultiplier), channels[1])
        Core.merge(channels, hsv)

        val result = Mat()
        Imgproc.cvtColor(hsv, result, Imgproc.COLOR_HSV2RGB)
        return result
    }
    // intensity: 0-100 from the slider, boosts saturation instead of reducing it
    private fun applyColorful(src: Mat, intensity: Int): Mat {
        val hsv = Mat()
        Imgproc.cvtColor(src, hsv, Imgproc.COLOR_RGB2HSV)
        val channels = ArrayList<Mat>()
        Core.split(hsv, channels)
        val satMultiplier = 1.0 + (intensity / 100.0) * 3.0 // 1.0 up to 2.0
        Core.multiply(channels[1], Scalar(satMultiplier), channels[1])
        Core.merge(channels, hsv)

        val result = Mat()
        Imgproc.cvtColor(hsv, result, Imgproc.COLOR_HSV2RGB)
        return result
    }
    private fun saveCurrentResult() {
        val drawable = binding.resultImageView.drawable as? BitmapDrawable ?: return
        val bitmap = drawable.bitmap

        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US)
            .format(System.currentTimeMillis())

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CamVisionPro")
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        uri?.let {
            contentResolver.openOutputStream(it)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            Log.d("MainActivity", "Saved to gallery: $it")
            Snackbar.make(binding.root, "Photo saved to gallery", Snackbar.LENGTH_SHORT).show()
        } ?: run {
            Log.e("MainActivity", "Failed to create MediaStore entry")
            Snackbar.make(binding.root, "Failed to save photo", Snackbar.LENGTH_SHORT).show()
        }
    }

}