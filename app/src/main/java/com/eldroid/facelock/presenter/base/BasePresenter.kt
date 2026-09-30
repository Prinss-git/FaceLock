package com.eldroid.facelock.presenter.base

/**
 * Lifecycle every presenter follows (Module 2, lesson 1.3).
 *
 *   view created   -> attachView()
 *   view destroyed -> detachView()
 *   finished for good (ViewModel cleared) -> onDestroy()
 */
interface BasePresenter<V> {
    fun attachView(view: V)
    fun detachView()
    fun onDestroy()
}
