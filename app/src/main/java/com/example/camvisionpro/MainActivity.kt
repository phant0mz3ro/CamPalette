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
import android.provider.MediaStore
import android.graphics.drawable.BitmapDrawable
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {

    companion object {
        init {
            System.loadLibrary("opencv_java4")
        }
    }

    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null

    // Holds the captured photo in memory so we can reprocess it repeatedly
    private var capturedMat: Mat? = null
    private var currentMode: String = "moody"
    private var currentIntensity: Int = 50

    private lateinit var bleManager: BleManager

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else Log.e("MainActivity", "Camera permission denied")
        }
    private val bluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.values.all { it }
            if (allGranted) {
                bleManager.startScan()
            } else {
                Log.e("MainActivity", "Bluetooth permissions denied")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        binding.captureButton.setOnClickListener {
            handleShutterAction()
        }

        binding.downloadButton.setOnClickListener {
            saveCurrentResult()
        }

        bleManager = BleManager(
            context = this,
            onUpdate = { mode, intensity ->
                currentMode = mode
                currentIntensity = intensity
                runOnUiThread { reprocessAndShow() }
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
            }
        )
        val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        bluetoothPermissionLauncher.launch(permissionsToRequest)
        //bleManager.startScan()
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

                    runOnUiThread {
                        binding.previewView.visibility = View.GONE
                        binding.resultImageView.visibility = View.VISIBLE
                        binding.downloadButton.visibility = View.VISIBLE
                       // binding.controlsLayout.visibility = View.VISIBLE
                        binding.captureButton.text = "Retake"
                    }
                    reprocessAndShow()
                }
            }
        )
    }

    // Re-runs processing on the already-captured in-memory image —
    // called every time mode or intensity changes, no re-capture needed
    private fun reprocessAndShow() {
        val srcMat = capturedMat ?: return

        CoroutineScope(Dispatchers.Default).launch {
            val resultMat = when (currentMode) {
                "moody" -> applyMoody(srcMat, currentIntensity)
                "colorful" -> applyColorful(srcMat, currentIntensity)
                else -> srcMat
            }

            val outputBitmap = Bitmap.createBitmap(
                resultMat.cols(), resultMat.rows(), Bitmap.Config.ARGB_8888
            )
            Utils.matToBitmap(resultMat, outputBitmap)

            withContext(Dispatchers.Main) {
                binding.resultImageView.setImageBitmap(outputBitmap)
            }
        }
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