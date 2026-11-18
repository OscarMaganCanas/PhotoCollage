package com.example.collageapp.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/** Genera la imagen final del collage y la guarda / prepara para compartir. */
object CollageExporter {

    private const val TAG = "CollageExporter"
    private const val CELL_MAX = 600       // px por celda como máximo
    private const val MAX_HEIGHT = 4800    // limita la memoria del bitmap final
    private const val SPACING = 12         // separación entre fotos
    private const val ALBUM = "PhotoCollage"

    /**
     * Dibuja las fotos (ya editadas) en una cuadrícula de [columns] columnas,
     * igual que se ven en pantalla. Cada foto se recorta al centro (sin deformarse).
     */
    fun buildCollage(bitmaps: List<Bitmap>, columns: Int): Bitmap {
        require(bitmaps.isNotEmpty()) { "Collage vacío" }
        val cols = columns.coerceIn(1, bitmaps.size)
        val rows = (bitmaps.size + cols - 1) / cols
        val cell = minOf(CELL_MAX, (MAX_HEIGHT - SPACING * (rows + 1)) / rows).coerceAtLeast(64)

        val width = cols * cell + (cols + 1) * SPACING
        val height = rows * cell + (rows + 1) * SPACING
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        bitmaps.forEachIndexed { index, bmp ->
            val left = SPACING + (index % cols) * (cell + SPACING)
            val top = SPACING + (index / cols) * (cell + SPACING)
            canvas.drawBitmap(bmp, centerCropRect(bmp), Rect(left, top, left + cell, top + cell), paint)
        }
        return result
    }

    /** Zona cuadrada central de la foto (equivalente a scaleType=centerCrop). */
    private fun centerCropRect(bmp: Bitmap): Rect {
        val side = minOf(bmp.width, bmp.height)
        val left = (bmp.width - side) / 2
        val top = (bmp.height - side) / 2
        return Rect(left, top, left + side, top + side)
    }

    /** Guarda en la caché privada y devuelve una Uri content:// segura mediante FileProvider. */
    fun saveToCacheForSharing(context: Context, bitmap: Bitmap): Uri {
        val dir = File(context.cacheDir, "collages").apply { mkdirs() }
        // Borra collages antiguos para no llenar la caché
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "collage_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /** Guarda el collage en la galería (Imágenes/PhotoCollage). Devuelve true si se guardó. */
    fun saveToGallery(context: Context, bitmap: Bitmap): Boolean = try {
        val name = "collage_${System.currentTimeMillis()}.jpg"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveWithMediaStore(context, bitmap, name)
        else saveLegacy(context, bitmap, name)
        true
    } catch (e: Exception) {
        Log.e(TAG, "Error guardando el collage", e)
        false
    }

    /** Android 10+: MediaStore, sin permisos. */
    private fun saveWithMediaStore(context: Context, bitmap: Bitmap, name: String) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("No se pudo crear la entrada en MediaStore")
        try {
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                ?: error("No se pudo abrir el fichero")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null) // no dejar entradas a medias
            throw e
        }
    }

    /** Android 7–9: fichero en Pictures/PhotoCollage (requiere WRITE_EXTERNAL_STORAGE). */
    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, bitmap: Bitmap, name: String) {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM)
        if (!dir.exists() && !dir.mkdirs()) error("No se pudo crear la carpeta")
        val file = File(dir, name)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
    }
}
