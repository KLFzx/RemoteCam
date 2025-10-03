package com.samsung.android.scan3d.util

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Parcelable
import android.util.Log
import kotlinx.parcelize.Parcelize
import java.lang.Exception
import kotlin.math.atan
import kotlin.math.roundToInt

object Selector {
    /** Helper class used as a data holder for each selectable camera format item */
    @Parcelize
    data class SensorDesc(
        val title: String,
        val cameraId: String,
        val logicalCameraId: String?,
        val format: Int
    ) : Parcelable

    /** Helper function used to convert a lens orientation enum into a human-readable string */
    private fun lensOrientationString(value: Int) = when (value) {
        CameraCharacteristics.LENS_FACING_BACK -> "Back"
        CameraCharacteristics.LENS_FACING_FRONT -> "Front"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
        else -> "Unknown"
    }

    /** Helper function used to list all compatible cameras and supported pixel formats */

    fun getCapStringAtIndex(index: Int): String {
        val strings = listOf(
            "BACKWARD_COMPATIBLE",
            "MANUAL_SENSOR",
            "MANUAL_POST_PROCESSING",
            "RAW",
            "PRIVATE_REPROCESSING",
            "READ_SENSOR_SETTINGS",
            "BURST_CAPTURE",
            "YUV_REPROCESSING",
            "DEPTH_OUTPUT",
            "CONSTRAINED_HIGH_SPEED_VIDEO",
            "MOTION_TRACKING",
            "LOGICAL_MULTI_CAMERA",
            "MONOCHROME",
            "SECURE_IMAGE_DATA",
            "SYSTEM_CAMERA",
            "OFFLINE_PROCESSING",
            "ULTRA_HIGH_RESOLUTION_SENSOR",
            "REMOSAIC_REPROCESSING",
            "DYNAMIC_RANGE_TEN_BIT",
            "STREAM_USE_CASE",
            "COLOR_SPACE_PROFILES"
        )

        if (index in 0 until strings.size) {
            return strings[index]
        } else {
            return "Invalid index"
        }
    }

    @SuppressLint("InlinedApi")
    fun enumerateCameras(cameraManager: CameraManager): List<SensorDesc> {
        val availableCameras: MutableList<SensorDesc> = mutableListOf()

        // Get list of all available cameras
        val allCameraIds = cameraManager.cameraIdList
        Log.i("SELECTOR", "Total cameras detected: ${allCameraIds.size}")
        
        allCameraIds.forEach { cameraId ->
            Log.i("SELECTOR", "Processing camera ID: $cameraId")
            try {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                val facingStr = when(facing) {
                    CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                    CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                    else -> "UNKNOWN"
                }
                Log.i("SELECTOR", "Camera $cameraId facing: $facingStr")
            } catch (e: Exception) {
                Log.e("SELECTOR", "Error getting characteristics for camera $cameraId: ${e.message}")
            }
        }

        val cameraIds2 = mutableListOf<SensorDesc>()
        val openableCameraIds = mutableListOf<String>()

        // First pass: Handle logical multi-camera setups (Samsung devices)
        allCameraIds.forEach { id ->
            try {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val capabilities = characteristics.get(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
                ) ?: return@forEach

                if (capabilities.contains(
                        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
                    )
                ) {
                    Log.i("SELECTOR", "Found logical multi-camera: $id")
                    // We got logical camera here, split it up into physical ones
                    characteristics.physicalCameraIds?.forEach { physId ->
                        Log.i("SELECTOR", "Adding physical camera from logical: $physId")
                        cameraIds2.add(SensorDesc("", physId, id, 0))
                    }
                } else {
                    Log.i("SELECTOR", "Found regular camera: $id")
                    cameraIds2.add(SensorDesc("", id, null, 0))
                    openableCameraIds.add(id)
                }
            } catch (e: Exception) {
                Log.e("SELECTOR", "Error processing camera $id: ${e.message}")
            }
        }

        // Second pass: For devices that have individual physical cameras
        // Add all cameras that aren't already in the list
        allCameraIds.forEach { id ->
            if (!cameraIds2.any { it.cameraId == id }) {
                Log.i("SELECTOR", "Adding standalone camera: $id")
                cameraIds2.add(SensorDesc("", id, null, 0))
                openableCameraIds.add(id)
            }
        }

        // Remove duplicates: if a camera is available both as physical and logical, prefer physical
        val finalCameraList = mutableListOf<SensorDesc>()
        val processedIds = mutableSetOf<String>()
        
        // First add all openable cameras (these are direct physical cameras)
        openableCameraIds.forEach { id ->
            if (!processedIds.contains(id)) {
                finalCameraList.add(SensorDesc("", id, null, 0))
                processedIds.add(id)
            }
        }
        
        // Then add logical camera physical cameras that aren't already added
        cameraIds2.forEach { desc ->
            if (desc.logicalCameraId != null && !processedIds.contains(desc.cameraId)) {
                finalCameraList.add(desc)
                processedIds.add(desc.cameraId)
            }
        }

        // Iterate over the final list of cameras and return all the compatible ones
        finalCameraList.forEach { desc ->

            Log.i("SELECTOR", "Camera ${desc.cameraId} @ LogicalCam ${desc.logicalCameraId}")
            val characteristics = cameraManager.getCameraCharacteristics(desc.cameraId)
            val orientation = lensOrientationString(
                characteristics.get(CameraCharacteristics.LENS_FACING)!!
            )

            // Query the available capabilities and output formats
            val capabilities = characteristics.get(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
            )!!

            capabilities.forEach { Log.i("CAP", "" + getCapStringAtIndex(it)) }

            val outputFormats = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )!!.outputFormats

            val outputSizes = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )!!.getOutputSizes(ImageFormat.JPEG)


            val foaclmm = characteristics.get(
                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
            )!![0]
            val foc = ("" + foaclmm + "mm").padEnd(6, ' ')
            val ape = ("f" + characteristics.get(
                CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES
            )!![0] + "").padEnd(4, ' ')

            val sensorSize = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE
            )!!

            val vfov =(( 2.0*(180.0 / 3.141592) * atan(sensorSize.height / (2.0 * foaclmm))).roundToInt().toString()+"°").padEnd(4,' ')

            // All cameras *must* support JPEG output so we don't need to check characteristics

            val camId = if (desc.logicalCameraId == null)
                "${desc.cameraId}"
            else
                "${desc.cameraId}@${desc.logicalCameraId}"

            val title = "$camId vfov:$vfov $foc $ape $orientation"
            if (!availableCameras.any { it -> it.title == title }) {
                availableCameras.add(
                    SensorDesc(
                        title, desc.cameraId, desc.logicalCameraId, ImageFormat.JPEG
                    )
                )
            }
        }

        return availableCameras
    }
}