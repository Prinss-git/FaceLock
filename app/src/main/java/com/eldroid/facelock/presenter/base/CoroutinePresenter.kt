package com.eldroid.facelock.presenter.base

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * [BasePresenter] with a coroutine scope that lives exactly as long as the
 * attached view.
 *
 * The course's RxJava version clears a CompositeDisposable in detachView();
 * cancelling [scope] is the coroutine equivalent, so no Firestore listener or
 * pending write can call back into a view that is gone. Work runs on the main
 * thread, which is where view callbacks must land.
 */
abstract class CoroutinePresenter<V> : BasePresenter<V> {

    protected var view: V? = null
        private set

    protected var scope: CoroutineScope = newScope()
        private set

    override fun attachView(view: V) {
        this.view = view
        scope = newScope()
        onViewAttached()
    }

    override fun detachView() {
        scope.cancel()
        view = null
    }

    override fun onDestroy() = Unit

    /** Start loading here: called on every attach, including after rotation. */
    protected open fun onViewAttached() = Unit

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}
