package com.example.collageapp.model

import android.graphics.Bitmap
import android.net.Uri

/** Filtros de color disponibles. */
enum class FilterType { NONE, GRAYSCALE, SEPIA }

/** Texto superpuesto (ampliación). Posición y tamaño relativos a la foto (0..1). */
data class TextOverlay(
    val text: String,
    val sizeFraction: Float = 0.12f,
    val x: Float = 0.5f,
    val y: Float = 0.85f
)

/**
 * Ediciones aplicadas a una foto. Es inmutable: cada cambio crea una copia,
 * lo que permite saber siempre con qué parámetros se generó cada bitmap.
 */
data class ImageEdits(
    val filter: FilterType = FilterType.NONE,
    val rotation: Int = 0,          // 0, 90, 180, 270 (sentido horario)
    val brightness: Int = 0,        // -100..100   (0 = brillo original)
    val saturation: Int = 100,      // 0..200 %    (100 = saturación original)
    val text: TextOverlay? = null
) {
    val isOriginal: Boolean get() = this == ImageEdits()

    companion object {
        const val BRIGHTNESS_MIN = -100
        const val BRIGHTNESS_MAX = 100
        const val SATURATION_MAX = 200
    }
}

/**
 * Una foto del collage.
 * @param original bitmap tal como se cargó: NUNCA se modifica, sirve para restaurar.
 * @param current  bitmap que se muestra (original + ediciones).
 * @param fingerprint huella de los píxeles: detecta la misma foto aunque llegue con otra Uri.
 */
data class CollageImage(
    val id: Long,
    val uri: Uri,
    val original: Bitmap,
    val current: Bitmap,
    val fingerprint: Long,
    val edits: ImageEdits = ImageEdits()
)
