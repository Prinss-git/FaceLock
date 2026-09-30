package com.eldroid.facelock.presenter.mylocker

import com.eldroid.facelock.data.model.Building
import com.eldroid.facelock.data.model.Locker
import com.eldroid.facelock.data.repo.BuildingRepository
import com.eldroid.facelock.data.repo.LockerRepository
import com.eldroid.facelock.data.repo.LogRepository
import com.eldroid.facelock.data.repo.UserRepository
import com.eldroid.facelock.domain.usecase.ResolveLogLockersUseCase
import com.eldroid.facelock.presenter.base.CoroutinePresenter
import com.eldroid.facelock.util.catchFirestore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MyLockerPresenter(
    private val userRepo: UserRepository,
    private val lockerRepo: LockerRepository,
    private val logRepo: LogRepository,
    private val buildingRepo: BuildingRepository,
    private val uid: String,
    private val resolveLockers: ResolveLogLockersUseCase = ResolveLogLockersUseCase()
) : CoroutinePresenter<MyLockerContract.View>(), MyLockerContract.Presenter {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onViewAttached() {
        val profile = userRepo.observeUser(uid).filterNotNull()

        scope.launch {
            profile
                .catchFirestore("your profile") { view?.showMessage(it) }
                .collect { view?.showProfile(it.firstName.ifBlank { it.fullName.substringBefore(' ') }, it.faceEnrolled) }
        }

        // Follows the assigned locker: a reassignment switches to the new one.
        scope.launch {
            profile
                .map { it.lockerId?.takeIf(String::isNotBlank) }
                .distinctUntilChanged()
                .flatMapLatest { lockerId ->
                    if (lockerId == null) flowOf(null)
                    else combine(lockerRepo.observeLocker(lockerId), buildingRepo.observeBuildings()) { locker, buildings ->
                        card(lockerId, locker, buildings)
                    }
                }
                .catchFirestore("your locker") { view?.showMessage(it) }
                .collect { card -> if (card == null) view?.showNoLocker() else view?.showLocker(card) }
        }

        // Lockers too, so logs show today's locker ID rather than an old one.
        scope.launch {
            combine(logRepo.observeLogsForUser(uid), lockerRepo.observeLockers(), resolveLockers::invoke)
                .catchFirestore("your access history") { view?.showMessage(it) }
                .collect { view?.showRecent(it.take(RECENT)) }
        }
    }

    private fun card(lockerId: String, locker: Locker?, buildings: List<Building>): LockerCard {
        if (locker == null) return LockerCard(lockerId, null, null, null)
        val building = buildings.firstOrNull { it.code == locker.building }
        val floor = locker.floor
        val place = if (building != null && floor != null) building.locationOf(floor)
        else locker.location.takeIf { it.isNotBlank() }
        return LockerCard(locker.label.ifBlank { locker.id }, locker.status, place, locker.lastOpenedAt)
    }

    override fun onViewAllClicked() {
        view?.openHistory()
    }

    private companion object {
        /** Only the newest few — the History tab has the rest. */
        const val RECENT = 3
    }
}
