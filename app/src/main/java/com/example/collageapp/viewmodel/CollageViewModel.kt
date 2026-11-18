package com.example.collageapp.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.collageapp.R
import com.example.collageapp.model.CollageImage
import com.example.collageapp.model.FilterType
import com.example.collageapp.model.ImageEdits
import com.example.collageapp.model.TextOverlay
import com.example.collageapp.utils.CollageExporter
import com.example.collageapp.utils.Event
import com.example.collageapp.utils.ImageUtils
import com.example.collageapp.utils.UiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Contiene TODO el estado del collage (fotos, selección, columnas) y la lógica de edición.
 * Al estar en un ViewModel, el estado se conserva al girar la pantalla.
 * La Activity solo pinta el estado y reenvía los eventos del usuario.
 */
class CollageViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        const val MAX_IMAGES = 30
        private const val TAG = "CollageViewModel"
    }

    private val _images = MutableLiveData<List<CollageImage>>(emptyList())
    val images: LiveData<List<CollageImage>> = _images

    private val _selectedId = MutableLiveData<Long?>(null)
    val selectedId: LiveData<Long?> = _selectedId

    /** null = usar el valor por defecto según la pantalla. */
    private val _columns = MutableLiveData<Int?>(null)
    val columns: LiveData<Int?> = _columns

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    private val _message = MutableLiveData<Event<UiMessage>>()
    val message: LiveData<Event<UiMessage>> = _message

    private var nextId = 1L

    /** Un trabajo de render por foto: si llega un cambio nuevo (p. ej. al mover el slider) se cancela el anterior. */
    private val renderJobs = mutableMapOf<Long, Job>()

    private val currentList: List<CollageImage> get() = _images.value.orEmpty()

    val selectedImage: CollageImage?
        get() = _selectedId.value?.let { id -> currentList.firstOrNull { it.id == id } }

    // ---------------------------------------------------------------------------------
    // Añadir fotos
    // ---------------------------------------------------------------------------------

    fun addImages(uris: List<Uri>) {
        if (uris.isEmpty()) {
            post(R.string.msg_ninguna_elegida); return
        }
        val existing = currentList.map { it.uri }.toSet()
        val fresh = uris.distinct().filterNot { it in existing }
        val duplicates = uris.size - fresh.size

        val room = MAX_IMAGES - currentList.size
        val toLoad = fresh.take(room.coerceAtLeast(0))

        if (duplicates > 0) post(R.string.msg_duplicadas, duplicates)
        if (fresh.size > toLoad.size) post(R.string.msg_limite, MAX_IMAGES)
        if (toLoad.isEmpty()) return

        _loading.value = true
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                toLoad.map { uri ->
                    val bmp = ImageUtils.loadBitmapFromUri(getApplication(), uri)
                    Triple(uri, bmp, bmp?.let { ImageUtils.fingerprint(it) })
                }
            }
            // Segunda comprobación de duplicados por contenido (misma foto con otra Uri,
            // o la misma foto elegida dos veces muy seguidas)
            val accepted = mutableListOf<CollageImage>()
            var contentDuplicates = 0
            for ((uri, bmp, print) in loaded) {
                if (bmp == null || print == null) continue
                val repeated = (currentList + accepted).any { it.uri == uri || it.fingerprint == print }
                if (repeated) {
                    contentDuplicates++
                    bmp.recycle() // no se va a mostrar: se libera la memoria
                } else {
                    accepted += CollageImage(id = nextId++, uri = uri, original = bmp, current = bmp, fingerprint = print)
                }
            }
            _images.value = currentList + accepted
            _loading.value = false

            val failed = loaded.count { it.second == null }
            if (failed > 0) post(R.string.msg_error_carga, failed)
            if (contentDuplicates > 0) post(R.string.msg_duplicadas, contentDuplicates)
            if (accepted.isNotEmpty()) post(R.string.msg_anadidas, accepted.size)
        }
    }

    // ---------------------------------------------------------------------------------
    // Selección y eliminación
    // ---------------------------------------------------------------------------------

    /** Pulsar una foto la selecciona; pulsarla de nuevo la deselecciona. Solo una a la vez. */
    fun toggleSelection(id: Long) {
        _selectedId.value = if (_selectedId.value == id) null else id
    }

    fun deleteSelected() {
        val selected = requireSelection() ?: return
        cancelRender(selected.id)
        _images.value = currentList.filterNot { it.id == selected.id }
        _selectedId.value = null
        post(R.string.msg_eliminada)
    }

    /** true si el collage tiene alguna foto. */
    fun hasImages(): Boolean = currentList.isNotEmpty()

    fun deleteAll() {
        renderJobs.values.forEach { it.cancel() }
        renderJobs.clear()
        _images.value = emptyList()
        _selectedId.value = null
        post(R.string.msg_limpio)
    }

    // ---------------------------------------------------------------------------------
    // Edición de la foto seleccionada
    // ---------------------------------------------------------------------------------

    fun applyFilter(filter: FilterType) = editSelected { it.copy(filter = filter) }

    /** Cada pulsación suma 90° en sentido horario. */
    fun rotateSelected() = editSelected { it.copy(rotation = (it.rotation + 90) % 360) }

    fun setBrightness(value: Int) = editSelected(showMessage = false) {
        it.copy(brightness = value.coerceIn(ImageEdits.BRIGHTNESS_MIN, ImageEdits.BRIGHTNESS_MAX))
    }

    fun setSaturation(value: Int) = editSelected(showMessage = false) {
        it.copy(saturation = value.coerceIn(0, ImageEdits.SATURATION_MAX))
    }

    /** text = null o vacío quita el texto. */
    fun setText(overlay: TextOverlay?) = editSelected(showMessage = false) {
        it.copy(text = overlay?.takeIf { o -> o.text.isNotBlank() })
    }

    /** Restaura la foto seleccionada: vuelve a mostrar el bitmap original intacto. */
    fun restoreSelected() {
        val selected = requireSelection() ?: return
        cancelRender(selected.id)
        replace(selected.id) { it.copy(edits = ImageEdits(), current = it.original) }
        post(R.string.msg_restaurada)
    }

    fun restoreAll() {
        if (!hasImages()) { post(R.string.msg_collage_vacio_corto); return }
        renderJobs.values.forEach { it.cancel() }
        renderJobs.clear()
        _images.value = currentList.map { it.copy(edits = ImageEdits(), current = it.original) }
        post(R.string.msg_restauradas_todas)
    }

    /**
     * Núcleo de la edición: actualiza los parámetros al instante y genera el nuevo bitmap
     * en segundo plano a partir del ORIGINAL (nunca se encadenan filtros sobre filtros).
     */
    private fun editSelected(showMessage: Boolean = true, change: (ImageEdits) -> ImageEdits) {
        val selected = if (showMessage) requireSelection() else selectedImage
        if (selected == null) return
        val newEdits = change(selected.edits)
        if (newEdits == selected.edits) return

        replace(selected.id) { it.copy(edits = newEdits) }
        cancelRender(selected.id)
        renderJobs[selected.id] = viewModelScope.launch {
            try {
                val rendered = withContext(Dispatchers.Default) {
                    ImageUtils.render(selected.original, newEdits)
                }
                // Solo se aplica si la foto sigue existiendo con esas mismas ediciones
                replace(selected.id) { img ->
                    if (img.edits == newEdits) img.copy(current = rendered) else img
                }
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "Sin memoria aplicando la edición", e)
            } finally {
                // Solo se quita del mapa si sigue siendo el trabajo vigente para esa foto
                if (renderJobs[selected.id] === coroutineContext[Job]) renderJobs.remove(selected.id)
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // Distribución (ampliación)
    // ---------------------------------------------------------------------------------

    /** Cambia 1 -> 2 -> 3 -> 4 -> 1 columnas. */
    fun cycleColumns(current: Int) {
        _columns.value = if (current >= 4) 1 else current + 1
    }

    // ---------------------------------------------------------------------------------
    // Exportar (compartir / guardar)
    // ---------------------------------------------------------------------------------

    /** Genera la imagen final del collage en segundo plano. null si está vacío o hay error. */
    suspend fun buildCollageBitmap(columns: Int): Bitmap? {
        val bitmaps = currentList.map { it.current }
        if (bitmaps.isEmpty()) {
            post(R.string.msg_collage_vacio_corto); return null
        }
        _loading.value = true
        return try {
            withContext(Dispatchers.Default) { CollageExporter.buildCollage(bitmaps, columns) }
        } catch (e: Exception) {
            Log.e(TAG, "Error generando el collage", e); null
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Sin memoria generando el collage", e); null
        } finally {
            _loading.value = false
        }
    }

    // ---------------------------------------------------------------------------------
    // Utilidades privadas
    // ---------------------------------------------------------------------------------

    /** Devuelve la foto seleccionada o muestra el mensaje informativo si no hay ninguna. */
    private fun requireSelection(): CollageImage? {
        if (!hasImages()) { post(R.string.msg_collage_vacio_corto); return null }
        return selectedImage ?: run { post(R.string.msg_sin_seleccion); null }
    }

    private fun replace(id: Long, transform: (CollageImage) -> CollageImage) {
        _images.value = currentList.map { if (it.id == id) transform(it) else it }
    }

    private fun cancelRender(id: Long) {
        renderJobs.remove(id)?.cancel()
    }

    fun post(resId: Int, vararg args: Any) {
        _message.value = Event(UiMessage(resId, args.toList()))
    }
}
