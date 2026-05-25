package com.sakurafubuki.yume.core.data.repository

import android.graphics.Bitmap
import android.media.Image
import java.nio.ByteBuffer

object YuvToBitmapBridge {

    init {
        System.loadLibrary("yume_yuv")
    }

    @JvmStatic
    external fun imageToBitmap(
        yBuf: ByteBuffer,
        yRowStride: Int,
        yPixelStride: Int,
        uBuf: ByteBuffer,
        uRowStride: Int,
        uPixelStride: Int,
        vBuf: ByteBuffer,
        vRowStride: Int,
        vPixelStride: Int,
        cropLeft: Int,
        cropTop: Int,
        cropWidth: Int,
        cropHeight: Int,
        colorStandard: Int,
        colorRange: Int,
        forceNV21: Boolean,
    ): Bitmap?

    @JvmStatic
    external fun bufferToBitmap(
        yuvBuffer: ByteBuffer,
        offset: Int,
        colorFormat: Int,
        stride: Int,
        sliceHeight: Int,
        cropLeft: Int,
        cropTop: Int,
        cropWidth: Int,
        cropHeight: Int,
        colorStandard: Int,
        colorRange: Int,
        forceNV21: Boolean,
    ): Bitmap?

    @JvmStatic
    external fun i420Scale(
        srcY: ByteBuffer,
        srcStrideY: Int,
        srcU: ByteBuffer,
        srcStrideU: Int,
        srcV: ByteBuffer,
        srcStrideV: Int,
        srcWidth: Int,
        srcHeight: Int,
        dstY: ByteBuffer,
        dstStrideY: Int,
        dstU: ByteBuffer,
        dstStrideU: Int,
        dstV: ByteBuffer,
        dstStrideV: Int,
        dstWidth: Int,
        dstHeight: Int,
        filterMode: Int,
    ): Boolean

    @JvmStatic
    external fun i420IsMostlySolidColor(
        yBuf: ByteBuffer,
        yRowStride: Int,
        uBuf: ByteBuffer,
        uRowStride: Int,
        vBuf: ByteBuffer,
        vRowStride: Int,
        width: Int,
        height: Int,
        threshold: Float,
        tolerance: Int,
    ): Boolean

    @JvmStatic
    external fun compositeToSheet(
        frameBitmap: Bitmap,
        sheetBitmap: Bitmap,
        col: Int,
        row: Int,
        frameWidth: Int,
        frameHeight: Int,
        cols: Int,
    ): Boolean

    @JvmStatic
    external fun nv12ScaleToI420(
        srcY: ByteBuffer,
        srcStrideY: Int,
        srcUV: ByteBuffer,
        srcStrideUV: Int,
        srcWidth: Int,
        srcHeight: Int,
        dstY: ByteBuffer,
        dstStrideY: Int,
        dstU: ByteBuffer,
        dstStrideU: Int,
        dstV: ByteBuffer,
        dstStrideV: Int,
        dstWidth: Int,
        dstHeight: Int,
        filterMode: Int,
        forceNV21: Boolean,
    ): Boolean

    @JvmStatic
    external fun argbScale(
        srcBitmap: Bitmap,
        dstWidth: Int,
        dstHeight: Int,
        filterMode: Int,
    ): Bitmap?

    @JvmStatic
    external fun argbIsMostlySolidColor(
        bitmap: Bitmap,
        threshold: Float,
        tolerance: Int,
    ): Boolean

    fun scaleTwoPassFromImage(
        image: Image,
        midWidth: Int = 320,
        midHeight: Int = 180,
        dstWidth: Int = 160,
        dstHeight: Int = 90,
        filterMode: Int = FilterMode.BOX,
        forceNV21: Boolean = false,
    ): ScaledYuv? {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val isSemiPlanar = uPlane.pixelStride == 2

        val (midY, midU, midV) = allocateYuvPlanes(midWidth, midHeight)
        val stage1Ok = if (isSemiPlanar) {
            nv12ScaleToI420(
                yPlane.buffer, yPlane.rowStride,
                uPlane.buffer, uPlane.rowStride,
                image.width, image.height,
                midY, midWidth, midU, midWidth / 2, midV, midWidth / 2,
                midWidth, midHeight, filterMode,
                forceNV21,
            )
        } else {
            i420Scale(
                yPlane.buffer, yPlane.rowStride,
                uPlane.buffer, uPlane.rowStride,
                vPlane.buffer, vPlane.rowStride,
                image.width, image.height,
                midY, midWidth, midU, midWidth / 2, midV, midWidth / 2,
                midWidth, midHeight, filterMode,
            )
        }
        if (!stage1Ok) return null

        midY.rewind()
        midU.rewind()
        midV.rewind()
        val (dstY, dstU, dstV) = allocateYuvPlanes(dstWidth, dstHeight)
        if (!i420Scale(
                midY, midWidth, midU, midWidth / 2, midV, midWidth / 2,
                midWidth, midHeight,
                dstY, dstWidth, dstU, dstWidth / 2, dstV, dstWidth / 2,
                dstWidth, dstHeight, filterMode,
            )
        ) {
            return null
        }

        return ScaledYuv(dstY, dstU, dstV, dstWidth, dstHeight)
    }

    fun scaleTwoPass(
        srcY: ByteBuffer,
        srcStrideY: Int,
        srcU: ByteBuffer,
        srcStrideU: Int,
        srcV: ByteBuffer,
        srcStrideV: Int,
        srcWidth: Int,
        srcHeight: Int,
        midWidth: Int = 320,
        midHeight: Int = 180,
        dstWidth: Int = 160,
        dstHeight: Int = 90,
        filterMode: Int = FilterMode.BOX,
    ): ScaledYuv? {
        val (midY, midU, midV) = allocateYuvPlanes(midWidth, midHeight)
        if (!i420Scale(
                srcY, srcStrideY, srcU, srcStrideU, srcV, srcStrideV,
                srcWidth, srcHeight,
                midY, midWidth, midU, midWidth / 2, midV, midWidth / 2,
                midWidth, midHeight, filterMode,
            )
        ) {
            return null
        }

        midY.rewind()
        midU.rewind()
        midV.rewind()
        val (dstY, dstU, dstV) = allocateYuvPlanes(dstWidth, dstHeight)
        if (!i420Scale(
                midY, midWidth, midU, midWidth / 2, midV, midWidth / 2,
                midWidth, midHeight,
                dstY, dstWidth, dstU, dstWidth / 2, dstV, dstWidth / 2,
                dstWidth, dstHeight, filterMode,
            )
        ) {
            return null
        }

        return ScaledYuv(dstY, dstU, dstV, dstWidth, dstHeight)
    }

    private fun allocateYuvPlanes(width: Int, height: Int): Triple<ByteBuffer, ByteBuffer, ByteBuffer> {
        val ySize = width * height
        val uvSize = (width / 2) * ((height + 1) / 2)
        return Triple(
            ByteBuffer.allocateDirect(ySize),
            ByteBuffer.allocateDirect(uvSize),
            ByteBuffer.allocateDirect(uvSize),
        )
    }
}

data class ScaledYuv(
    val y: ByteBuffer,
    val u: ByteBuffer,
    val v: ByteBuffer,
    val width: Int,
    val height: Int,
) {

    val strideY: Int get() = width

    val strideU: Int get() = (width + 1) / 2

    val strideV: Int get() = (width + 1) / 2
}

object FilterMode {

    const val NONE = 0

    const val LINEAR = 1

    const val BILINEAR = 2

    const val BOX = 3
}
