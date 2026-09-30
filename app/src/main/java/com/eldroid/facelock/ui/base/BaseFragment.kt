package com.eldroid.facelock.ui.base

import androidx.fragment.app.Fragment
import com.eldroid.facelock.presenter.coordinator.AdminFragmentCoordinator
import com.eldroid.facelock.presenter.coordinator.CoordinatorHost
import com.eldroid.facelock.presenter.coordinator.CoordinatorListener

/**
 * A tab fragment that talks to its siblings only through the host's
 * coordinator (lesson 1.5). It registers while started and unregisters when
 * stopped, so a fragment that is going away never receives a request.
 */
abstract class BaseFragment : Fragment(), CoordinatorListener {

    /** Null outside a [CoordinatorHost], e.g. if reused in another screen. */
    protected val coordinator: AdminFragmentCoordinator?
        get() = (activity as? CoordinatorHost)?.coordinator

    override fun onStart() {
        super.onStart()
        coordinator?.registerListener(this)
    }

    override fun onStop() {
        coordinator?.unregisterListener(this)
        super.onStop()
    }
}
