package com.example.collageapp.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import com.example.collageapp.model.FilterType
import com.example.collageapp.model.ImageEdits
import com.example.collageapp.model.TextOverlay

/**
 * Operaciones sobre imágenes implementadas íntegramente en Kotlin
 * (sin librerías ni aplicaciones externas).
 */
object ImageUtils {

    private const val TAG = "ImageUtils"

    /** Lado máximo de las fotos en memoria: evita OutOfMemoryError con fotos de 12+ MP. */
    const val MAX_SIDE = 1080

    // ---------------------------------------------------------------------------------
    // Carga
    // ---------------------------------------------------------------------------------

    /**
     * Carga una foto desde su Uri reducida a [maxSide] px y con la orientación EXIF corregida.
     * Devuelve null si no se puede leer (nunca lanza excepción).
     */
    fun loadBitmapFromUri(context: Context, uri: Uri, maxSide: Int = MAX_SIDE): Bitmap? {
        return try {
            val resolver = context.contentResolver

            // 1) Leer solo las dimensiones (no reserva memoria para los píxeles)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            // 2) Calcular el factor de reducción (potencia de 2)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxSide || bounds.outHeight / (sample * 2) >= maxSide) {
                sample *= 2
            }

            // 3) Decodificar reducida
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return null

            // 4) Ajuste fino al tamaño máximo y corrección de orientación
            val scaled = scaleDown(decoded, maxSide)
            val degrees = readExifRotation(context, uri)
            rotate(scaled, degrees)
        } catch (e: Exception) {
            Log.e(TAG, "Error cargando $uri", e)
            null
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Sin memoria cargando $uri", e)
            null
        }
    }

    private fun readExifRotation(context: Context, uri: Uri): Int = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
    } catch (e: Exception) {
        0
    }

    private fun scaleDown(src: Bitmap, maxSide: Int): Bitmap {
        val largest = maxOf(src.width, src.height)
        if (largest <= maxSide) return src
        val ratio = maxSide.toFloat() / largest
        val scaled = Bitmap.createScaledBitmap(
            src, (src.width * ratio).toInt().coerceAtLeast(1), (src.height * ratio).toInt().coerceAtLeast(1), true
        )
        if (scaled !== src) src.recycle() // el intermedio ya no se usa en ningún sitio
        return scaled
    }

    /**
     * Huella rápida de una foto: dimensiones + una muestra de 16x16 píxeles.
     * Dos cargas de la misma foto producen la misma huella, aunque el sistema
     * entregue Uris distintas.
     */
    fun fingerprint(bmp: Bitmap): Long {
        var hash = 1125899906842597L
        hash = 31 * hash + bmp.width
        hash = 31 * hash + bmp.height
        val steps = 16
        for (i in 0 until steps) {
            for (j in 0 until steps) {
                val x = (bmp.width - 1) * i / (steps - 1)
                val y = (bmp.height - 1) * j / (steps - 1)
                hash = 31 * hash + bmp.getPixel(x, y)
            }
        }
        return hash
    }

    // ---------------------------------------------------------------------------------
    // Filtros (matrices de color)
    // ---------------------------------------------------------------------------------

    /** Escala de grises: luminancia estándar (saturación 0). */
    fun grayscaleMatrix(): ColorMatrix = ColorMatrix().apply { setSaturation(0f) }

    /** Sepia clásico (coeficientes de Microsoft / W3C). */
    fun sepiaMatrix(): ColorMatrix = ColorMatrix(
        floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    )

    /** Brillo: suma un desplazamiento a R, G y B. [value] en -100..100. */
    fun brightnessMatrix(value: Int): ColorMatrix {
        val offset = value * 1.5f // hasta ±150 sobre 255
        return ColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, offset,
                0f, 1f, 0f, 0f, offset,
                0f, 0f, 1f, 0f, offset,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }

    /** Saturación: [percent] 0..200 (100 = original). */
    fun saturationMatrix(percent: Int): ColorMatrix =
        ColorMatrix().apply { setSaturation(percent / 100f) }

    /**
     * Combina todas las ediciones de color en UNA sola matriz, para recorrer los
     * píxeles una única vez (más rápido y sin bitmaps intermedios).
     * Orden: saturación -> filtro -> brillo.
     */
    fun buildColorMatrix(edits: ImageEdits): ColorMatrix {
        val matrix = saturationMatrix(edits.saturation)
        when (edits.filter) {
            FilterType.GRAYSCALE -> matrix.postConcat(grayscaleMatrix())
            FilterType.SEPIA -> matrix.postConcat(sepiaMatrix())
            FilterType.NONE -> Unit
        }
        if (edits.brightness != 0) matrix.postConcat(brightnessMatrix(edits.brightness))
        return matrix
    }

    // ---------------------------------------------------------------------------------
    // Render completo
    // ---------------------------------------------------------------------------------

    /**
     * Genera un bitmap NUEVO a partir del original y las ediciones.
     * El original no se modifica nunca, así siempre se puede restaurar.
     */
    fun render(original: Bitmap, edits: ImageEdits): Bitmap {
        if (edits.isOriginal) return original

        // 1) Color (filtro + brillo + saturación) en una pasada
        val colored = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
        Canvas(colored).drawBitmap(original, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(buildColorMatrix(edits))
        })

        // 2) Rotación
        val rotated = rotate(colored, edits.rotation)

        // 3) Texto (se dibuja tras rotar para que siempre se lea derecho)
        val overlay = edits.text ?: return rotated
        val mutable = if (rotated.isMutable) rotated
        else rotated.copy(Bitmap.Config.ARGB_8888, true).also { rotated.recycle() }
        drawText(mutable, overlay)
        return mutable
    }

    /** Gira el bitmap [degrees] grados en sentido horario. Recicla el bitmap de entrada si crea uno nuevo. */
    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return src
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        if (rotated !== src) src.recycle()
        return rotated
    }

    /** Dibuja el texto sobre el bitmap (debe ser mutable). */
    private fun drawText(target: Bitmap, overlay: TextOverlay) {
        if (overlay.text.isBlank()) return
        val canvas = Canvas(target)
        val size = (minOf(target.width, target.height) * overlay.sizeFraction).coerceAtLeast(8f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
            setShadowLayer(size / 8f, 0f, 0f, Color.BLACK)
        }
        // y = línea base: centramos verticalmente el texto en la posición pedida
        val baseline = target.height * overlay.y - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(overlay.text, target.width * overlay.x, baseline, paint)
    }
}
