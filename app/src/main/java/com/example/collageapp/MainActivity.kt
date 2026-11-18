package com.example.collageapp

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.example.collageapp.adapter.CollageAdapter
import com.example.collageapp.model.CollageImage
import com.example.collageapp.model.FilterType
import com.example.collageapp.model.ImageEdits
import com.example.collageapp.model.TextOverlay
import com.example.collageapp.utils.CollageExporter
import com.example.collageapp.viewmodel.CollageViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pantalla principal. Se limita a la interfaz: pinta el estado del [CollageViewModel]
 * y le reenvía las acciones del usuario. Toda la lógica está en el ViewModel / utils.
 */
class MainActivity : AppCompatActivity() {

    private val viewModel: CollageViewModel by viewModels()

    private lateinit var recycler: RecyclerView
    private lateinit var layoutManager: GridLayoutManager
    private lateinit var adapter: CollageAdapter
    private lateinit var tvVacio: TextView
    private lateinit var tvContador: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnColumnas: Button
    private lateinit var seekBrillo: SeekBar
    private lateinit var seekSaturacion: SeekBar
    private lateinit var tvBrillo: TextView
    private lateinit var tvSaturacion: TextView

    /** Collage pendiente de guardar mientras se pide el permiso (solo Android 9 o inferior). */
    private var pendingSave: Bitmap? = null

    // Photo Picker de Android: selección múltiple, sin necesidad de permisos de lectura.
    // En dispositivos sin Photo Picker, androidx usa automáticamente ACTION_OPEN_DOCUMENT.
    private val pickImages =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(CollageViewModel.MAX_IMAGES)) { uris ->
            viewModel.addImages(uris)
        }

    private val requestWritePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val bmp = pendingSave
            pendingSave = null
            if (granted && bmp != null) saveBitmap(bmp) else toast(getString(R.string.msg_permiso_denegado))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        applySystemBarInsets()
        setupGrid()
        setupButtons()
        setupSliders()
        observeViewModel()
    }

    // ---------------------------------------------------------------------------------
    // Configuración de la interfaz
    // ---------------------------------------------------------------------------------

    private fun bindViews() {
        recycler = findViewById(R.id.recyclerCollage)
        tvVacio = findViewById(R.id.tvVacio)
        tvContador = findViewById(R.id.tvContador)
        progress = findViewById(R.id.progress)
        btnColumnas = findViewById(R.id.btnColumnas)
        seekBrillo = findViewById(R.id.seekBrillo)
        seekSaturacion = findViewById(R.id.seekSaturacion)
        tvBrillo = findViewById(R.id.tvBrillo)
        tvSaturacion = findViewById(R.id.tvSaturacion)
    }

    /** Android 15+ dibuja de borde a borde: se evita que la barra de estado / navegación tape la UI. */
    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
    }

    private fun setupGrid() {
        adapter = CollageAdapter { image -> viewModel.toggleSelection(image.id) }
        layoutManager = GridLayoutManager(this, effectiveColumns())
        recycler.layoutManager = layoutManager
        recycler.adapter = adapter
        recycler.setHasFixedSize(true)
        // Sin animación de "parpadeo" al cambiar una foto (importante al mover el slider de brillo)
        (recycler.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
    }

    private fun setupButtons() {
        click(R.id.btnAnadir) { openPhotoPicker() }
        click(R.id.btnEliminar) { viewModel.deleteSelected() }
        click(R.id.btnEliminarTodas) { confirmDeleteAll() }
        click(R.id.btnBlancoNegro) { viewModel.applyFilter(FilterType.GRAYSCALE) }
        click(R.id.btnSepia) { viewModel.applyFilter(FilterType.SEPIA) }
        click(R.id.btnRotar) { viewModel.rotateSelected() }
        click(R.id.btnRestaurar) { viewModel.restoreSelected() }
        click(R.id.btnTexto) { showTextDialog() }
        click(R.id.btnColumnas) { viewModel.cycleColumns(effectiveColumns()) }
        click(R.id.btnCompartir) { shareCollage() }
        click(R.id.btnGuardar) { saveCollage() }

        // Pulsación larga en «Restaurar» = restaurar TODAS las fotos
        findViewById<Button>(R.id.btnRestaurar).setOnLongClickListener {
            confirmRestoreAll(); true
        }
    }

    private fun setupSliders() {
        seekBrillo.setOnSeekBarChangeListener(sliderListener { progress ->
            // progress 0..200  ->  brillo -100..100 (100 = brillo original)
            val value = progress + ImageEdits.BRIGHTNESS_MIN
            tvBrillo.text = getString(R.string.label_brillo, value)
            viewModel.setBrightness(value)
        })
        seekSaturacion.setOnSeekBarChangeListener(sliderListener { progress ->
            tvSaturacion.text = getString(R.string.label_saturacion, progress)
            viewModel.setSaturation(progress)
        })
        updateSliders(null)
    }

    /** Listener común para los dos sliders (evita duplicar código). */
    private fun sliderListener(onUserChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser && viewModel.selectedImage != null) onUserChange(progress)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) {
            if (viewModel.selectedImage == null) {
                viewModel.post(if (viewModel.hasImages()) R.string.msg_sin_seleccion else R.string.msg_collage_vacio_corto)
            }
        }

        override fun onStopTrackingTouch(seekBar: SeekBar?) {
            // Si no había foto seleccionada, el slider vuelve a su valor por defecto
            if (viewModel.selectedImage == null) updateSliders(null)
        }
    }

    // ---------------------------------------------------------------------------------
    // Observación del estado
    // ---------------------------------------------------------------------------------

    private fun observeViewModel() {
        viewModel.images.observe(this) { list ->
            adapter.submit(list)
            val empty = list.isEmpty()
            tvVacio.visibility = if (empty) View.VISIBLE else View.GONE
            updateCounter()
            updateSliders(viewModel.selectedImage)
        }

        viewModel.selectedId.observe(this) { id ->
            adapter.selectedId = id
            updateSliders(viewModel.selectedImage)
        }

        viewModel.columns.observe(this) {
            layoutManager.spanCount = effectiveColumns()
            updateCounter()
        }

        viewModel.loading.observe(this) { loading ->
            progress.visibility = if (loading) View.VISIBLE else View.GONE
        }

        viewModel.message.observe(this) { event ->
            event.getContentIfNotHandled()?.let { msg ->
                toast(getString(msg.resId, *msg.args.toTypedArray()))
            }
        }
    }

    private fun updateCounter() {
        val cols = effectiveColumns()
        btnColumnas.text = getString(R.string.btn_columnas, cols)
        tvContador.text = getString(R.string.subtitulo_contador, viewModel.images.value.orEmpty().size, cols)
    }

    /** Coloca los sliders en los valores de la foto seleccionada (o en los valores por defecto). */
    private fun updateSliders(image: CollageImage?) {
        val edits = image?.edits ?: ImageEdits()
        val brightnessProgress = edits.brightness - ImageEdits.BRIGHTNESS_MIN
        if (seekBrillo.progress != brightnessProgress) seekBrillo.progress = brightnessProgress
        if (seekSaturacion.progress != edits.saturation) seekSaturacion.progress = edits.saturation
        tvBrillo.text = getString(R.string.label_brillo, edits.brightness)
        tvSaturacion.text = getString(R.string.label_saturacion, edits.saturation)
    }

    /** Columnas elegidas por el usuario o, si no eligió, las adecuadas a la pantalla. */
    private fun effectiveColumns(): Int =
        viewModel.columns.value ?: resources.getInteger(R.integer.columnas_por_defecto)

    // ---------------------------------------------------------------------------------
    // Acciones
    // ---------------------------------------------------------------------------------

    private fun openPhotoPicker() {
        try {
            pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.msg_ninguna_elegida))
        }
    }

    private fun confirmDeleteAll() {
        val count = viewModel.images.value.orEmpty().size
        if (count == 0) {
            toast(getString(R.string.msg_collage_vacio_corto)); return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dlg_vaciar_titulo)
            .setMessage(getString(R.string.dlg_vaciar_msg, count))
            .setPositiveButton(R.string.dlg_si) { _, _ -> viewModel.deleteAll() }
            .setNegativeButton(R.string.dlg_cancelar, null)
            .show()
    }

    private fun confirmRestoreAll() {
        if (!viewModel.hasImages()) {
            toast(getString(R.string.msg_collage_vacio_corto)); return
        }
        AlertDialog.Builder(this)
            .setMessage(R.string.dlg_restaurar_todas_msg)
            .setPositiveButton(R.string.dlg_aceptar) { _, _ -> viewModel.restoreAll() }
            .setNegativeButton(R.string.dlg_cancelar, null)
            .show()
    }

    /** Ampliación: texto sobre la foto con tamaño y posición. Los cambios se ven en directo. */
    private fun showTextDialog() {
        val image = viewModel.selectedImage
        if (image == null) {
            viewModel.post(if (viewModel.hasImages()) R.string.msg_sin_seleccion else R.string.msg_collage_vacio_corto)
            return
        }
        val previous = image.edits.text
        val start = previous ?: TextOverlay("")

        val view = layoutInflater.inflate(R.layout.dialog_texto, null)
        val etTexto = view.findViewById<EditText>(R.id.etTexto)
        val seekTamano = view.findViewById<SeekBar>(R.id.seekTamano)
        val seekX = view.findViewById<SeekBar>(R.id.seekPosX)
        val seekY = view.findViewById<SeekBar>(R.id.seekPosY)

        etTexto.setText(start.text)
        seekTamano.progress = (start.sizeFraction * 100).toInt() - 5   // 5 % .. 30 % del lado menor
        seekX.progress = (start.x * 100).toInt()
        seekY.progress = (start.y * 100).toInt()

        fun currentOverlay() = TextOverlay(
            text = etTexto.text.toString(),
            sizeFraction = (seekTamano.progress + 5) / 100f,
            x = seekX.progress / 100f,
            y = seekY.progress / 100f
        )

        val preview: () -> Unit = { viewModel.setText(currentOverlay()) }
        etTexto.doAfterTextChanged { preview() }
        listOf(seekTamano, seekX, seekY).forEach { seek ->
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) preview() }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.dlg_texto_titulo)
            .setView(view)
            .setPositiveButton(R.string.dlg_aceptar) { _, _ -> viewModel.setText(currentOverlay()) }
            .setNeutralButton(R.string.dlg_texto_quitar) { _, _ -> viewModel.setText(null) }
            .setNegativeButton(R.string.dlg_cancelar) { _, _ -> viewModel.setText(previous) }
            .setOnCancelListener { viewModel.setText(previous) }
            .show()
    }

    /** Genera la imagen final y la comparte con un Intent ACTION_SEND + FileProvider. */
    private fun shareCollage() {
        lifecycleScope.launch {
            val bitmap = viewModel.buildCollageBitmap(effectiveColumns()) ?: run {
                if (viewModel.hasImages()) toast(getString(R.string.msg_compartir_error))
                return@launch
            }
            try {
                val uri = withContext(Dispatchers.IO) {
                    CollageExporter.saveToCacheForSharing(applicationContext, bitmap)
                }
                bitmap.recycle()
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, getString(R.string.msg_compartir_titulo)))
            } catch (e: ActivityNotFoundException) {
                toast(getString(R.string.msg_sin_apps))
            } catch (e: Exception) {
                toast(getString(R.string.msg_compartir_error))
            }
        }
    }

    /** Ampliación: guardar el collage en la galería del dispositivo. */
    private fun saveCollage() {
        lifecycleScope.launch {
            val bitmap = viewModel.buildCollageBitmap(effectiveColumns()) ?: run {
                if (viewModel.hasImages()) toast(getString(R.string.msg_guardar_error))
                return@launch
            }
            val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED

            if (needsPermission) {
                pendingSave = bitmap
                requestWritePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                saveBitmap(bitmap)
            }
        }
    }

    private fun saveBitmap(bitmap: Bitmap) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { CollageExporter.saveToGallery(applicationContext, bitmap) }
            bitmap.recycle()
            toast(getString(if (ok) R.string.msg_guardado else R.string.msg_guardar_error))
        }
    }

    // ---------------------------------------------------------------------------------
    // Utilidades
    // ---------------------------------------------------------------------------------

    private fun click(id: Int, action: () -> Unit) {
        findViewById<View>(id).setOnClickListener { action() }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
