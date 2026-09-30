package com.eldroid.facelock.domain.usecase

import com.eldroid.facelock.data.model.Locker
import com.eldroid.facelock.data.model.Role
import com.eldroid.facelock.data.model.User
import com.eldroid.facelock.data.repo.LockerRepository

/** Who may receive a locker, and the move itself. */
class AssignLockerUseCase(private val lockerRepo: LockerRepository) {

    /** Only active members hold lockers; staff accounts never do. */
    fun isEligible(user: User): Boolean = user.active && user.roleEnum == Role.USER

    /** [holder] null frees the locker. Out-of-service lockers can only be freed. */
    suspend operator fun invoke(locker: Locker, holder: User?): Result<Unit> {
        if (holder != null && !locker.isInService) {
            return Result.failure(
                IllegalStateException("${locker.id} is out of service. Put it back in service first.")
            )
        }
        return lockerRepo.assign(locker, holder)
    }
}
