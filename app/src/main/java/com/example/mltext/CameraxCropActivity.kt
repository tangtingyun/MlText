package com.example.mltext

import android.annotation.SuppressLint
import android.graphics.*
import android.media.Image
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.Camera
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.concurrent.futures.await
import androidx.lifecycle.lifecycleScope
import com.blankj.utilcode.util.ImageUtils
import com.blankj.utilcode.util.PathUtils
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.lang.Integer.max
import java.lang.Integer.min
import java.lang.Math.abs
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class CameraxCropActivity : AppCompatActivity() {
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val rotation = Surface.ROTATION_0
    private lateinit var cameraExecutor: ExecutorService

    private lateinit var viewFinder: PreviewView

    private lateinit var mSaveBtn: Button
    private lateinit var mCropView: View
    private var mSaveBitmap = false

    val TMEP_DIR = PathUtils.getCachePathExternalFirst() + System.getProperty("file.separator")
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop_main)
        viewFinder = findViewById(R.id.viewFinder)
        mCropView = findViewById(R.id.crop_area)
        mSaveBtn = findViewById(R.id.btn_save)
        cameraExecutor = Executors.newSingleThreadExecutor()
        viewFinder.post {
            lifecycleScope.launch {
                setUpCamera()
            }
        }
        mSaveBtn.setOnClickListener {
            mSaveBitmap = true
        }
    }


    private suspend fun setUpCamera() {
        cameraProvider = ProcessCameraProvider.getInstance(this).await()
        bindCameraUseCases()
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {

        Log.d(TAG, "Screen metrics: ${getScreenSize().x} x ${getScreenSize().y}")
        val screenAspectRatio = aspectRatio(getScreenSize().x, getScreenSize().y)
        Log.d(TAG, "Preview aspect ratio: $screenAspectRatio")

        // CameraProvider
        val cameraProvider =
            cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        cameraProvider.unbindAll()

        // CameraSelector
        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        // Preview
        preview = Preview.Builder()
            // We request aspect ratio but no resolution
            .setTargetAspectRatio(screenAspectRatio)
            // Set initial target rotation
            .setTargetRotation(rotation).build()


        // ImageAnalysis
        imageAnalyzer = ImageAnalysis.Builder()
            // We request aspect ratio but no resolution
            .setTargetAspectRatio(screenAspectRatio).setTargetRotation(rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            // The analyzer can then be assigned to the instance
            .also {
                it.setAnalyzer(cameraExecutor, ImageAnalysis.Analyzer { imageProxy ->



                    if (mSaveBitmap) {
                        mSaveBitmap = false

                        Log.e(TAG, "imageProxy width " + imageProxy.width)
                        Log.e(TAG, "imageProxy height " + imageProxy.height)
                        Log.e(TAG, "imageProxy cropRect " + imageProxy.cropRect)

                        val previewViewWidth = viewFinder.width
                        val previewViewHeight = viewFinder.height

                        Log.e(TAG, "imageProxy previewViewWidth " + previewViewWidth)
                        Log.e(TAG, "imageProxy previewViewHeight " + previewViewHeight)

                        val imageProxyWidth = imageProxy.height
                        val imageProxyHeight = imageProxy.width

                        val xScale = imageProxyWidth.toFloat() / previewViewWidth
                        val yScale = imageProxyHeight.toFloat() / previewViewHeight

                        Log.e(TAG, "imageProxy xScale " + xScale)
                        Log.e(TAG, "imageProxy yScale " + yScale)

                        var height = 0
                        val resourceId = applicationContext.resources.getIdentifier(
                            "status_bar_height",
                            "dimen",
                            "android"
                        )
                        if (resourceId > 0) {
                            height = applicationContext.resources.getDimensionPixelSize(resourceId)
                        }
                        val screenRect =
                            Rect(mCropView.top - height, mCropView.left, mCropView.bottom, mCropView.right);

                        Log.e(TAG, "imageProxy screenRect " + screenRect)
                        val mappedRect = Rect(
                            (screenRect.left * xScale).toInt(),
                            (screenRect.top * yScale).toInt(),
                            (screenRect.right * xScale).toInt(),
                            (screenRect.bottom * yScale).toInt()
                        )

                        Log.e(TAG, "imageProxy mappedRect " + mappedRect)

                        val path1 = "$TMEP_DIR${System.currentTimeMillis()}.jpg"
                        ImageUtils.save(
                            imageProxy.toBitmap(),
                            path1,
                            Bitmap.CompressFormat.JPEG
                        )
                        val path2 = "$TMEP_DIR${System.currentTimeMillis()}_rotate.jpg"
                        imageProxy.setCropRect(mappedRect);
                        val mediaImage2 = imageProxy.image
                        if (mediaImage2 != null && mediaImage2.format == ImageFormat.YUV_420_888) {
                            croppedBitmap(mediaImage2, imageProxy.cropRect).let { bitmap ->
                                var rotateImageBitmap = bitmap.rotate(90)!!
                                ImageUtils.save(
                                    rotateImageBitmap,
                                    path2,
                                    Bitmap.CompressFormat.JPEG
                                )
                                val task = recognizer.process(
                                    InputImage.fromBitmap(
                                        rotateImageBitmap, rotation
                                    )
                                )
                                val result = Tasks.await(task)
                                result.textBlocks.forEach { block ->
                                    Log.e(TAG + "aa", block.text)
                                }
                            }
                        }
                    }

                    imageProxy.close()
                })
            }
        try {
            camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, preview!!, imageAnalyzer!!
            )
            preview?.setSurfaceProvider(viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }


    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }

    private fun getScreenSize(): Point {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val point = Point()
        wm.defaultDisplay.getRealSize(point)
        return point
    }


    private fun croppedBitmap(mediaImage: Image, cropRect: Rect): Bitmap {
        val yBuffer = mediaImage.planes[0].buffer // Y
        val vuBuffer = mediaImage.planes[2].buffer // VU

        val ySize = yBuffer.remaining()
        val vuSize = vuBuffer.remaining()

        val nv21 = ByteArray(ySize + vuSize)

        yBuffer.get(nv21, 0, ySize)
        vuBuffer.get(nv21, ySize, vuSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, mediaImage.width, mediaImage.height, null)
        val outputStream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(cropRect, 100, outputStream)
        val imageBytes = outputStream.toByteArray()

        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Shut down our background executor
        cameraExecutor.shutdown()
    }

    private fun Bitmap.rotate(angle: Int): Bitmap? {
        val m = Matrix()
        m.setRotate(
            90f, width.toFloat() / 2, height.toFloat() / 2
        )
        return Bitmap.createBitmap(
            this, 0, 0, width, height, m, true
        )
    }

    companion object {
        private const val TAG = "CameraXBasic"
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0
    }
}