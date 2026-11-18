package com.example.collageapp.utils

import androidx.annotation.StringRes

/**
 * Envoltorio para eventos de un solo uso (mensajes) emitidos por LiveData,
 * para que un Toast no se repita al girar la pantalla.
 */
class Event<out T>(private val content: T) {
    private var handled = false

    fun getContentIfNotHandled(): T? =
        if (handled) null else { handled = true; content }
}

/** Mensaje para el usuario: recurso de texto + argumentos de formato. */
data class UiMessage(@StringRes val resId: Int, val args: List<Any> = emptyList())
