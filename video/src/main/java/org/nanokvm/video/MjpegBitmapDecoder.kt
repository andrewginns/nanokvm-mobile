package org.nanokvm.video

import android.graphics.Bitmap
import android.graphics.BitmapFactory

data class MjpegBitmapDecodeOptions(
    val maxWidth: Int = 1920,
    val maxHeight: Int = 1080,
    val bitmapConfig: Bitmap.Config = Bitmap.Config.RGB_565,
) {
    init {
        require(maxWidth > 0 && maxHeight > 0) { "MJPEG bitmap bounds must be positive" }
    }
}

/** Downsamples before allocation and defaults to RGB_565 to halve MJPEG display memory. */
object MjpegBitmapDecoder {
    fun decode(jpeg: ByteArray, options: MjpegBitmapDecodeOptions = MjpegBitmapDecodeOptions()): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > options.maxWidth ||
            bounds.outHeight / sampleSize > options.maxHeight
        ) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inPreferredConfig = options.bitmapConfig
            inSampleSize = sampleSize
        }
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, decodeOptions)
    }
}
