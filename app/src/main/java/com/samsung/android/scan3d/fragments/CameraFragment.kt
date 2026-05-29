/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.samsung.android.scan3d.fragments

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.NavController
import androidx.navigation.Navigation
import com.samsung.android.scan3d.CameraActivity
import com.samsung.android.scan3d.R
import com.samsung.android.scan3d.databinding.FragmentCameraBinding
import com.samsung.android.scan3d.serv.CamEngine
import com.samsung.android.scan3d.util.ClipboardUtil
import com.samsung.android.scan3d.util.IpUtil
import kotlinx.parcelize.Parcelize

class CameraFragment : Fragment() {

    /** Android ViewBinding */
    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    /** Host's navigation controller */
    private val navController: NavController by lazy {
        Navigation.findNavController(requireActivity(), R.id.fragment_container)
    }

    /** AndroidX navigation arguments */
    //  private val args: CameraFragmentArgs by navArgs()

    var resW = 1280
    var resH = 720

    var viewState =
        ViewState(true, stream = false, cameraId = "0", quality = 80, resolutionIndex = null)

    lateinit var Cac: CameraActivity

    private var cameraSurface: Surface? = null
    private var currentCameraId: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)

        // Get the local ip address
        val localIp = IpUtil.getLocalIpAddress()
        _fragmentCameraBinding!!.textView6.text = "$localIp:8080/cam.mjpeg"
        _fragmentCameraBinding!!.textView6.setOnClickListener {
            // Copy the ip address to the clipboard
            ClipboardUtil.copyToClipboard(context, "ip", _fragmentCameraBinding!!.textView6.text.toString())
            // Toast to notify the user
            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        Cac = (activity as CameraActivity?)!!
        return fragmentCameraBinding.root
    }


    private val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {


            intent.extras?.getParcelable<CamEngine.Companion.DataQuick>("dataQuick")?.apply {
                activity?.runOnUiThread(Runnable {
                    // Stuff that updates the UI
                    fragmentCameraBinding.qualFeedback?.text =
                        " " + this.rateKbs + "kB/sec"
                    fragmentCameraBinding.ftFeedback?.text =
                        " " + this.ms + "ms"
                })

            }


            val data = intent.extras?.getParcelable<CamEngine.Companion.Data>("data") ?: return


            val re = data.resolutions[data.resolutionSelected]
            resW = re.width
            resH = re.height
            currentCameraId = data.sensorSelected.cameraId

            activity?.runOnUiThread(Runnable {
                applyPreviewConfig()
            })


            fragmentCameraBinding.switch1?.setOnCheckedChangeListener(object :
                CompoundButton.OnCheckedChangeListener {
                override fun onCheckedChanged(p0: CompoundButton?, p1: Boolean) {
                    viewState.preview = p1
                    sendViewState()
                }
            })
            fragmentCameraBinding.switch2?.setOnCheckedChangeListener(object :
                CompoundButton.OnCheckedChangeListener {
                override fun onCheckedChanged(p0: CompoundButton?, p1: Boolean) {
                    viewState.stream = p1
                    sendViewState()
                }
            })

            // Setup zoom slider — only push zoom changes through update_zoom (cheap path);
            // never through sendViewState, which would diff the ViewState and trigger a full
            // camera restart on every slider tick.
            fragmentCameraBinding.zoomSeekBar?.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        viewState.zoomLevel = 1.0f + (progress / 100.0f) * 9.0f
                        Cac.sendCam {
                            it.action = "update_zoom"
                            it.putExtra("zoomLevel", viewState.zoomLevel)
                        }
                    }
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
            })

            run {
                val spinner = fragmentCameraBinding.spinnerCam
                val spinnerDataList = ArrayList<Any>()
                data.sensors.forEach { spinnerDataList.add(it.title) }
                val spinnerAdapter =
                    ArrayAdapter(
                        requireContext(),
                        android.R.layout.simple_spinner_item,
                        spinnerDataList
                    )
                spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinner!!.adapter = spinnerAdapter
                spinner.setSelection(data.sensors.indexOf(data.sensorSelected))
                spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long) {

                        if (viewState.cameraId != data.sensors[p2].cameraId) {
                            viewState.resolutionIndex = null
                        }

                        viewState.cameraId = data.sensors[p2].cameraId


                        sendViewState()
                    }

                    override fun onNothingSelected(p0: AdapterView<*>?) {
                    }
                }
            }

            run {
                val spinner = fragmentCameraBinding.spinnerQua
                val spinnerDataList = ArrayList<Any>()
                val quals = arrayOf(1, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100)
                quals.forEach { spinnerDataList.add(it.toString()) }
                // Initialize the ArrayAdapter
                val spinnerAdapter =
                    ArrayAdapter(
                        requireContext(),
                        android.R.layout.simple_spinner_item,
                        spinnerDataList
                    )
                // Set the dropdown layout style
                spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                // Set the adapter for the Spinner
                spinner!!.adapter = spinnerAdapter
                spinner.setSelection(quals.indexOfFirst { it == viewState.quality })
                spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long) {
                        viewState.quality = quals[p2]
                        sendViewState()
                    }

                    override fun onNothingSelected(p0: AdapterView<*>?) {
                    }
                }
            }

            run {

                val outputFormats = data.resolutions

                val spinner = fragmentCameraBinding.spinnerRes
                val spinnerDataList = ArrayList<Any>()
                outputFormats.forEach { spinnerDataList.add(it.toString()) }
                // Initialize the ArrayAdapter
                val spinnerAdapter =
                    ArrayAdapter(
                        requireContext(),
                        android.R.layout.simple_spinner_item,
                        spinnerDataList
                    )
                // Set the dropdown layout style
                spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                // Set the adapter for the Spinner
                spinner!!.adapter = spinnerAdapter


                if (viewState.resolutionIndex == null) {
                    Log.i("DEUIBGGGGGG", "NO PRIOR R, " + data.resolutionSelected)
                    viewState.resolutionIndex = data.resolutionSelected
                }

                spinner.setSelection(viewState.resolutionIndex!!)
                spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long) {
                        resW = outputFormats[p2].width
                        resH = outputFormats[p2].height
                        activity?.runOnUiThread(Runnable {
                            applyPreviewConfig()
                        })
                        if (p2 != viewState.resolutionIndex) {
                            viewState.resolutionIndex = p2
                            sendViewState()
                        }

                    }

                    override fun onNothingSelected(p0: AdapterView<*>?) {
                    }
                }
            }

        }
    }


    private fun applyPreviewConfig() {
        val binding = _fragmentCameraBinding ?: return
        if (resW <= 0 || resH <= 0) return
        binding.viewFinder.surfaceTexture?.setDefaultBufferSize(resW, resH)
        val rotation = computeTotalRotation()
        binding.viewFinder.setPreviewConfig(resW, resH, rotation)
    }

    private fun computeTotalRotation(): Int {
        val ctx = context ?: return 90
        val cameraId = currentCameraId ?: return 90
        val sensorOrientation: Int
        val isFrontFacing: Boolean
        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val ch = cm.getCameraCharacteristics(cameraId)
            sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            isFrontFacing = ch.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_FRONT
        } catch (e: Exception) {
            Log.w("CameraFragment", "Could not read sensor orientation: ${e.message}")
            return 90
        }
        @Suppress("DEPRECATION")
        val displayRotation = activity?.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        val displayDeg = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (isFrontFacing) {
            (sensorOrientation + displayDeg) % 360
        } else {
            (sensorOrientation - displayDeg + 360) % 360
        }
    }

    fun sendViewState() {
        Cac.sendCam {
            it.action = "new_view_state"

            it.putExtra("data", viewState)
        }
    }

    override fun onPause() {
        super.onPause()
        Log.i("onPause", "onPause")
        activity?.unregisterReceiver(receiver)
    }


    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onResume() {
        super.onResume()
        Log.i("onResume", "onResume")
        activity?.registerReceiver(receiver, IntentFilter("UpdateFromCameraEngine"))
    }


    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.i("onViewCreated", "onViewCreated")


        Cac.sendCam {
            it.action = "start_camera_engine"
        }
        // engine.start(requireContext())

        Log.i("CAMMM", "fragmentCameraBinding.buttonKill " + fragmentCameraBinding.buttonKill)
        fragmentCameraBinding.buttonKill.setOnClickListener {
            Log.i("CameraFrag", "KILL")
            val intent = Intent("KILL") //FILTER is a string to identify this intent
            context?.sendBroadcast(intent)
        }

        fragmentCameraBinding.viewFinder.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                Log.i("CameraFragment", "SurfaceTexture available: ${width}x${height}")
                texture.setDefaultBufferSize(resW, resH)
                applyPreviewConfig()
                cameraSurface?.release()
                val surface = Surface(texture)
                cameraSurface = surface
                Cac.sendCam {
                    it.action = "new_preview_surface"
                    it.putExtra("surface", surface)
                }
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                Log.i("CameraFragment", "SurfaceTexture size changed: ${width}x${height}")
                texture.setDefaultBufferSize(resW, resH)
                applyPreviewConfig()
            }

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                Log.i("CameraFragment", "SurfaceTexture destroyed")
                Cac.sendCam {
                    it.action = "surface_destroyed"
                }
                cameraSurface?.release()
                cameraSurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
        }
    }

    override fun onStop() {
        super.onStop()
        try {

        } catch (exc: Throwable) {
            Log.e(TAG, "Error closing camera", exc)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

    }

    override fun onDestroyView() {
        cameraSurface?.release()
        cameraSurface = null
        _fragmentCameraBinding = null
        super.onDestroyView()
    }

    companion object {
        private val TAG = CameraFragment::class.java.simpleName

        @Parcelize
        data class ViewState(
            var preview: Boolean,
            var stream: Boolean,
            var cameraId: String,
            var resolutionIndex: Int?,
            var quality: Int,
            var zoomLevel: Float = 1.0f
        ) : Parcelable

    }
}
