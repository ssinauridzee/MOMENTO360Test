package ge.momento.booth

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.EncodeHintType
import java.util.EnumMap

object QrRender {
    fun encode(text: String, px: Int = 700): Bitmap {
        val hints = EnumMap<EncodeHintType,Any>(EncodeHintType::class.java).apply {
            put(EncodeHintType.MARGIN, 3)
            put(EncodeHintType.CHARACTER_SET, "UTF-8")
        }
        val matrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, px, px, hints)
        val pixels = IntArray(px * px)
        for (y in 0 until px) for (x in 0 until px) pixels[y * px + x] = if (matrix[x,y]) Color.BLACK else Color.WHITE
        return Bitmap.createBitmap(pixels,px,px,Bitmap.Config.ARGB_8888)
    }
}
