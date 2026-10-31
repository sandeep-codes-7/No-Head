package com.nohead.mesh.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Generates and renders a deterministic QR code for out-of-band cryptographic identity
 * verification (`nohead://verify?peerId=...&signPub=...&encPub=...&fp=...`).
 */
@Composable
fun IdentityQrCodeView(
    qrPayload: String,
    size: Dp = 180.dp
) {
    val bitmap = remember(qrPayload) {
        generateQrBitmap(qrPayload, 512)
    }
    if (bitmap != null) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(androidx.compose.ui.graphics.Color.White)
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Cryptographic Identity Verification QR Code",
                modifier = Modifier.size(size)
            )
        }
    }
}

private fun generateQrBitmap(content: String, dimensionPx: Int): Bitmap? {
    return try {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, dimensionPx, dimensionPx)
        val bmp = Bitmap.createBitmap(dimensionPx, dimensionPx, Bitmap.Config.RGB_565)
        for (x in 0 until dimensionPx) {
            for (y in 0 until dimensionPx) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        bmp
    } catch (_: Exception) {
        null
    }
}

