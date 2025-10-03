package com.samsung.android.scan3d.serv

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Parcelable
import android.util.Log
import android.util.Size
import android.view.Surface
import com.samsung.android.scan3d.fragments.CameraFragment
import com.samsung.android.scan3d.http.HttpService
import com.samsung.android.scan3d.util.Selector
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.parcelize.Parcelize
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class CamEngine(val context: Context) {

    var http: HttpService? = null
    var resW = 1280
    var resH = 720

    var insidePause = false

    var isShowingPreview: Boolean = false
    private var isRestartingStream: Boolean = false

    private var cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraList: List<Selector.SensorDesc> =
        Selector.enumerateCameras(cameraManager)

    val camOutPutFormat = ImageFormat.JPEG // ImageFormat.YUV_420_888// ImageFormat.JPEG

    val executor = Executors.newSingleThreadExecutor()

    fun getEncoder(mimeType: String, resW: Int, resH: Int): MediaCodec? {
        fun selectCodec(mimeType: String, needEncoder: Boolean): MediaCodecInfo? {
            val list = MediaCodecList(0).getCodecInfos()
            list.forEach {
                if (it.isEncoder) {
                    Log.i(
                        "CODECS",
                        "We got type " + it.name + " " + it.supportedTypes.contentToString()
                    )
                    if (it.supportedTypes.any { e -> e.equals(mimeType, ignoreCase = true) }) {
                        return it
                    }
                }
            }
            return null
        }

        val colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatCbYCrY;
        val codec = selectCodec("video/avc", true) ?: return null
        val format = MediaFormat.createVideoFormat("video/avc", resW, resH)
        format.setString(MediaFormat.KEY_MIME, "video/avc");
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000);
        Log.i("CODECS", "video/avc: " + codec)
        val encoder = MediaCodec.createByCodecName(codec.getName());
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();
        return encoder
    }


    var viewState: CameraFragment.Companion.ViewState = CameraFragment.Companion.ViewState(
        true,
        stream = false,
        cameraId = cameraList.first().cameraId,
        quality = 80,
        resolutionIndex = null,
        zoomLevel = 1.0f
    )

    /** [CameraCharacteristics] corresponding to the provided Camera ID */
    var characteristics: CameraCharacteristics =
        cameraManager.getCameraCharacteristics(viewState.cameraId)

    var sizes = characteristics.get(
        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
    )!!.getOutputSizes(camOutPutFormat).reversed()


    private lateinit var imageReader: ImageReader


    private val cameraThread = HandlerThread("CameraThread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)

    private lateinit var camera: CameraDevice

    var previewSurface: Surface? = null

    private var session: CameraCaptureSession? = null

    private fun stopRunning() {
        try {
            if (session != null) {
                Log.i("CAMERA", "Stopping camera session")
                session!!.stopRepeating()
                session!!.close()
                session = null
            }
            if (::camera.isInitialized) {
                Log.i("CAMERA", "Closing camera")
                camera.close()
            }
            if (::imageReader.isInitialized) {
                Log.i("CAMERA", "Closing image reader")
                imageReader.close()
            }
        } catch (e: Exception) {
            Log.w("CAMERA", "Error stopping camera: ${e.message}")
        }
    }

    fun restart() {
        stopRunning()
        runBlocking { initializeCamera() }

    }

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
        manager: CameraManager,
        cameraId: String,
        logicalCameraId: String?,
        handler: Handler? = null
    ): CameraDevice = suspendCancellableCoroutine { cont ->

        val idToOpen = logicalCameraId ?: cameraId;
        manager.openCamera(idToOpen, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) = cont.resume(device)

            override fun onDisconnected(device: CameraDevice) {
                Log.w("CamEngine", "Camera $cameraId has been disconnected")
            }

            override fun onError(device: CameraDevice, error: Int) {
                val msg = when (error) {
                    ERROR_CAMERA_DEVICE -> "Fatal (device)"
                    ERROR_CAMERA_DISABLED -> "Device policy"
                    ERROR_CAMERA_IN_USE -> "Camera in use"
                    ERROR_CAMERA_SERVICE -> "Fatal (service)"
                    ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                    else -> "Unknown"
                }
                val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
                Log.e("CamEngine", exc.message, exc)
                if (cont.isActive) cont.resumeWithException(exc)
            }
        }, handler)
    }

    /**
     * Starts a [CameraCaptureSession] and returns the configured session (as the result of the
     * suspend coroutine
     */
    private suspend fun createCaptureSession(
        device: CameraDevice,
        physicalCamId: String?,
        targets: List<Surface>,
        handler: Handler? = null
    ): CameraCaptureSession = suspendCoroutine { cont ->
        val outputConfigs = mutableListOf<OutputConfiguration>();
        targets.forEach {
            outputConfigs.add(OutputConfiguration(it).apply {
                // If physical camera id is not null, it's a logical cam, you should set it
                if (physicalCamId != null) {
                    setPhysicalCameraId(physicalCamId)
                }
            })
        }

        // Create a capture session using the predefined targets; this also involves defining the
        // session state callback to be notified of when the session is ready
        val sessionConfiguration = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputConfigs,
            Executors.newSingleThreadExecutor(),
            object : CameraCaptureSession.StateCallback() {

                override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    val exc = RuntimeException("Camera ${device.id} session configuration failed")
                    Log.e("CamEngine", exc.message, exc)
                    cont.resumeWithException(exc)
                }
            }
        )

        device.createCaptureSession(sessionConfiguration)
    }

    suspend fun initializeCamera() {
        Log.i("CAMERA", "initializeCamera - preview: ${viewState.preview}, surface: ${previewSurface != null}")

        val showLiveSurface = viewState.preview && !insidePause && previewSurface != null
        isShowingPreview = showLiveSurface

        // Stop current session before reinitializing
        stopRunning()

        // Small delay to ensure previous session is fully closed
        kotlinx.coroutines.delay(100)

        characteristics = cameraManager.getCameraCharacteristics(viewState.cameraId)
        
        // Check zoom capabilities
        val maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
        Log.i("CAMERA", "Max digital zoom supported: $maxZoom")
        sizes = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        )!!.getOutputSizes(camOutPutFormat).reversed()

        if (viewState.resolutionIndex == null) {

            var selIndex = 0
            for (formatIndex in 0 until sizes.size) {
                if (sizes[formatIndex].height <= 720) {
                    selIndex = formatIndex
                }
            }
            viewState.resolutionIndex = selIndex
        }
        resW = sizes[viewState.resolutionIndex!!].width
        resH = sizes[viewState.resolutionIndex!!].height


        val sensor = cameraList.find { it.cameraId == viewState.cameraId }!!
        camera = openCamera(cameraManager, sensor.cameraId, sensor.logicalCameraId, cameraHandler)
        imageReader = ImageReader.newInstance(
            resW, resH, camOutPutFormat, 4
        )
        var targets = listOf(imageReader.surface)
        if (showLiveSurface) {
            targets = targets.plus(previewSurface!!)
        }
        session = createCaptureSession(
            camera,
            if (sensor.logicalCameraId == null) null else sensor.cameraId,
            targets,
            cameraHandler
        )
        val captureRequest = camera.createCaptureRequest(
            CameraDevice.TEMPLATE_RECORD //TEMPLATE_PREVIEW
        )
        if (showLiveSurface) {
            captureRequest.addTarget(previewSurface!!)
        }
        captureRequest.addTarget(imageReader.surface)
        captureRequest.set(CaptureRequest.JPEG_QUALITY, viewState.quality.toByte())
        
        // Apply zoom level using crop region (API 28 compatible)
        try {
            val zoomLevel = viewState.zoomLevel
            Log.i("CAMERA", "Setting zoom level: $zoomLevel")
            
            if (zoomLevel > 1.0f) {
                val activeArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                if (activeArraySize != null) {
                    val cropWidth = (activeArraySize.width() / zoomLevel).toInt()
                    val cropHeight = (activeArraySize.height() / zoomLevel).toInt()
                    val cropX = (activeArraySize.width() - cropWidth) / 2
                    val cropY = (activeArraySize.height() - cropHeight) / 2
                    val zoomRect = android.graphics.Rect(cropX, cropY, cropX + cropWidth, cropY + cropHeight)
                    captureRequest.set(CaptureRequest.SCALER_CROP_REGION, zoomRect)
                    Log.i("CAMERA", "Applied crop region: $zoomRect")
                }
            }
        } catch (e: Exception) {
            Log.w("CAMERA", "Zoom not supported on this camera: ${e.message}")
        }
        var lastTime = System.currentTimeMillis()


        var kodd = 0
        var aquired = AtomicInteger(0)
        session!!.setRepeatingRequest(
            captureRequest.build(),
            object : CameraCaptureSession.CaptureCallback() {

                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    super.onCaptureCompleted(session, request, result)


                    var lastImg = imageReader.acquireNextImage()

                    if (aquired.get() > 1 && lastImg != null) {
                        lastImg.close()
                        Log.i("COM", "EARLY CLOSE")
                        lastImg = null
                    }

                    val img = lastImg ?: return
                    aquired.incrementAndGet()
                    var curTime = System.currentTimeMillis()
                    val delta = curTime - lastTime
                    lastTime = curTime
                    kodd += 1

                    if (camOutPutFormat == ImageFormat.JPEG) {
                        // executor.execute(Runnable {
                        val buffer = img.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining()).apply { buffer.get(this) }

                        if (kodd % 10 == 0) {
                            updateViewQuick(
                                DataQuick(
                                    delta.toInt(),
                                    (30 * bytes.size / 1000)
                                )
                            )
                        }

                        img.close()
                        aquired.decrementAndGet()
                        if (viewState.stream) {

                            http?.channel?.trySend(
                                bytes
                            )

                        }

                    }
                }
            },
            cameraHandler
        )
        // Note: Stream restart is handled separately in the surface change callback
        // to avoid conflicts during camera initialization
        
        updateView()
    }
    
    fun updateZoomLevel() {
        if (session != null) {
            try {
                Log.i("CAMERA", "Updating zoom level: ${viewState.zoomLevel}")
                
                // For significant zoom changes, restart the camera session to ensure proper preview
                val shouldRestart = Math.abs(viewState.zoomLevel - 1.0f) > 0.5f
                
                if (shouldRestart) {
                    Log.i("CAMERA", "Restarting camera for zoom change")
                    runBlocking { initializeCamera() }
                } else {
                    // For small zoom changes, just update the capture request
                    val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    if (isShowingPreview && previewSurface != null) {
                        captureRequest.addTarget(previewSurface!!)
                    }
                    captureRequest.addTarget(imageReader.surface)
                    captureRequest.set(CaptureRequest.JPEG_QUALITY, viewState.quality.toByte())
                    
                    // Apply zoom level using crop region (API 28 compatible)
                    val zoomLevel = viewState.zoomLevel
                    
                    if (zoomLevel > 1.0f) {
                        val activeArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                        if (activeArraySize != null) {
                            val cropWidth = (activeArraySize.width() / zoomLevel).toInt()
                            val cropHeight = (activeArraySize.height() / zoomLevel).toInt()
                            val cropX = (activeArraySize.width() - cropWidth) / 2
                            val cropY = (activeArraySize.height() - cropHeight) / 2
                            val zoomRect = android.graphics.Rect(cropX, cropY, cropX + cropWidth, cropY + cropHeight)
                            captureRequest.set(CaptureRequest.SCALER_CROP_REGION, zoomRect)
                            Log.i("CAMERA", "Applied crop region: $zoomRect")
                        }
                    }
                    
                    session!!.setRepeatingRequest(captureRequest.build(), null, cameraHandler)
                }
            } catch (e: Exception) {
                Log.w("CAMERA", "Failed to update zoom: ${e.message}")
                // If zoom update fails, restart the camera
                runBlocking { initializeCamera() }
            }
        }
    }

    fun restartStream() {
        if (isRestartingStream) {
            Log.i("CAMERA", "Stream restart already in progress, skipping")
            return
        }
        
        try {
            isRestartingStream = true
            Log.i("CAMERA", "Restarting stream in camera engine")
            // Stop current HTTP service if it exists
            if (http != null) {
                http?.engine?.stop(500, 500)
                Log.i("CAMERA", "Stopped existing HTTP service")
            }
            // Start new HTTP service
            http = HttpService()
            http?.main()
            Log.i("CAMERA", "Stream restarted successfully")
        } catch (e: Exception) {
            Log.e("CAMERA", "Failed to restart stream: ${e.message}")
        } finally {
            isRestartingStream = false
        }
    }

    fun destroy() {
        stopRunning()
        cameraThread.quitSafely()
    }

    fun updateView() {
        val intent = Intent("UpdateFromCameraEngine") //FILTER is a string to identify this intent
        intent.putExtra(
            "data",
            Data(
                cameraList,
                cameraList.find { it.cameraId == viewState.cameraId }!!,
                resolutions = sizes,
                resolutionSelected = viewState.resolutionIndex!!
            )
        )
        context.sendBroadcast(intent)
    }

    fun updateViewQuick(dq: DataQuick) {
        val intent = Intent("UpdateFromCameraEngine") //FILTER is a string to identify this intent
        intent.putExtra(
            "dataQuick", dq
        )
        context.sendBroadcast(intent)
    }

    companion object {
        @Parcelize
        data class Data(
            val sensors: List<Selector.SensorDesc>,
            val sensorSelected: Selector.SensorDesc,
            val resolutions: List<Size>,
            val resolutionSelected: Int,
        ) : Parcelable

        @Parcelize
        data class DataQuick(
            val ms: Int,
            val rateKbs: Int
        ) : Parcelable
    }


}