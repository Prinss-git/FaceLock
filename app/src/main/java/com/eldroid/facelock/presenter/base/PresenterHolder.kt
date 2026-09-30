package com.eldroid.facelock.presenter.base

import androidx.lifecycle.ViewModel

/**
 * Keeps a presenter alive across configuration changes (lesson 1.3,
 * "ViewModel integration"). The view re-attaches after rotation and finds its
 * state — search text, collapsed sections — where it left it.
 */
class PresenterHolder : ViewModel() {

    private var presenter: BasePresenter<*>? = null

    @Suppress("UNCHECKED_CAST")
    fun <P : BasePresenter<*>> getOrCreate(factory: () -> P): P =
        (presenter ?: factory().also { presenter = it }) as P

    override fun onCleared() {
        presenter?.onDestroy()
        presenter = null
    }
}
