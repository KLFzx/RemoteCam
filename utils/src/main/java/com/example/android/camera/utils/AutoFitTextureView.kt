package com.example.android.camera.utils

import android.content.Context
import android.content.res.Configuration
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.TextureView
import kotlin.math.max
import kotlin.math.min

/**
 * A [TextureView] that sizes itself to match the device orientation and applies a
 * rotation transform so the camera preview appears upright regardless of device
 * orientation.
 */
class AutoFitTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : TextureView(context, attrs, defStyle) {

    private var bufferWidth = 0
    private var bufferHeight = 0
    private var totalRotation = 0

    fun setPreviewConfig(bufferWidth: Int, bufferHeight: Int, totalRotation: Int) {
        require(bufferWidth > 0 && bufferHeight > 0)
        val normalizedRotation = ((totalRotation % 360) + 360) % 360
        val changed = this.bufferWidth != bufferWidth ||
                this.bufferHeight != bufferHeight ||
                this.totalRotation != normalizedRotation
        this.bufferWidth = bufferWidth
        this.bufferHeight = bufferHeight
        this.totalRotation = normalizedRotation
        if (changed) {
            requestLayout()
            applyTransform()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        if (bufferWidth == 0 || bufferHeight == 0) {
            setMeasuredDimension(w, h)
            return
        }
        val portrait = context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val longer = max(bufferWidth, bufferHeight).toFloat()
        val shorter = min(bufferWidth, bufferHeight).toFloat()
        val targetAspect = if (portrait) shorter / longer else longer / shorter
        val containerAspect = w.toFloat() / h.toFloat()
        val newW: Int
        val newH: Int
        if (containerAspect > targetAspect) {
            newH = h
            newW = (h * targetAspect).toInt()
        } else {
            newW = w
            newH = (w / targetAspect).toInt()
        }
        setMeasuredDimension(newW, newH)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyTransform()
    }

    private fun applyTransform() {
        val viewW = width
        val viewH = height
        if (viewW == 0 || viewH == 0 || bufferWidth == 0 || bufferHeight == 0) return

        val matrix = Matrix()
        val centerX = viewW / 2f
        val centerY = viewH / 2f

        matrix.postScale(
            bufferWidth.toFloat() / viewW,
            bufferHeight.toFloat() / viewH,
            centerX, centerY
        )
        matrix.postRotate(totalRotation.toFloat(), centerX, centerY)
        val rotated = totalRotation == 90 || totalRotation == 270
        val displayedW = if (rotated) bufferHeight.toFloat() else bufferWidth.toFloat()
        val displayedH = if (rotated) bufferWidth.toFloat() else bufferHeight.toFloat()
        val fit = min(viewW / displayedW, viewH / displayedH)
        matrix.postScale(fit, fit, centerX, centerY)

        setTransform(matrix)
    }
}
