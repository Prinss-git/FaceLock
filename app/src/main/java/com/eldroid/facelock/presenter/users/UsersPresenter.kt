package com.eldroid.facelock.presenter.users

import com.eldroid.facelock.data.model.AdminActionType
import com.eldroid.facelock.data.model.Role
import com.eldroid.facelock.data.model.User
import com.eldroid.facelock.data.repo.UserRepository
import com.eldroid.facelock.domain.usecase.AdminTrail
import com.eldroid.facelock.presenter.base.CoroutinePresenter
import com.eldroid.facelock.presenter.common.CollapseState
import com.eldroid.facelock.presenter.common.Group
import com.eldroid.facelock.presenter.users.UsersContract.EmptyKind
import com.eldroid.facelock.presenter.users.UsersContract.Filter
import com.eldroid.facelock.util.OFFLINE_ACTION_MESSAGE
import com.eldroid.facelock.util.catchFirestore
import com.eldroid.facelock.util.messageForWrite
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class UsersPresenter(
    private val userRepo: UserRepository,
    private val trail: AdminTrail,
    /** Section heading per role ("Administrators"…), from resources. */
    private val sectionTitle: (Role) -> String,
    /** Role name for messages ("Security"…), from resources. */
    private val roleName: (Role) -> String,
    /** Deleting runs a transaction, which can't wait for the network. */
    private val isOnline: () -> Boolean = { true }
) : CoroutinePresenter<UsersContract.View>(), UsersContract.Presenter {

    private var users: List<User> = emptyList()
    private var loaded = false
    private var query = ""
    private var filter = Filter.ALL
    private var searchJob: Job? = null

    /** Role sections, all open until tapped. */
    private val sections = CollapseState()
    private var groups: List<Group<User>> = emptyList()

    /** The last suspend/restore, so Undo can reverse it. */
    private var lastToggle: Pair<User, Boolean>? = null

    override fun onViewAttached() {
        view?.showFilter(filter)
        if (loaded) render() else view?.showLoading()
        scope.launch {
            userRepo.observeUsers()
                .catchFirestore("the user list") { view?.showLoadError(it) }
                .collect {
                    users = it
                    loaded = true
                    render()
                }
        }
    }

    // ----------------------------------------------------------- list ----

    override fun onSearchChanged(query: String) {
        val trimmed = query.trim()
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(SEARCH_DELAY_MS)
            if (trimmed == this@UsersPresenter.query) return@launch
            this@UsersPresenter.query = trimmed
            render()
        }
    }

    override fun onFilterSelected(filter: Filter) {
        if (filter == this.filter) return
        this.filter = filter
        render()
    }

    override fun onSectionToggled(groupKey: String) {
        sections.toggle(groupKey, groups)
        render()
    }

    private fun render() {
        val view = view ?: return
        if (!loaded) return

        val searched = if (query.isEmpty()) users else users.filter(::matches)
        view.showFilterCounts(
            all = searched.size,
            suspended = searched.count { it.passes(Filter.SUSPENDED) },
            noFace = searched.count { it.passes(Filter.NO_FACE) },
            noLocker = searched.count { it.passes(Filter.NO_LOCKER) }
        )
        val shown = searched.filter { it.passes(filter) }

        // Staff first: they are few and the ones an admin looks for most.
        groups = listOf(Role.ADMIN, Role.SECURITY, Role.USER).mapNotNull { role ->
            shown.filter { it.roleEnum == role }.takeIf { it.isNotEmpty() }
                ?.let { Group(role.name, sectionTitle(role), it) }
        }
        when {
            shown.isNotEmpty() -> view.showUsers(
                // A search or filter shows every match, whatever was folded.
                sections.flatten(groups, forceOpen = query.isNotEmpty() || filter != Filter.ALL) { it.uid },
                shown.size
            )
            users.isEmpty() -> view.showEmpty(EmptyKind.NO_USERS)
            else -> view.showEmpty(EmptyKind.NO_MATCH)
        }
    }

    private fun matches(user: User) =
        user.fullName.contains(query, true) ||
            user.email.contains(query, true) ||
            user.role.contains(query, true) ||
            user.lockerId?.contains(query, true) == true

    private fun User.passes(filter: Filter) = when (filter) {
        Filter.ALL -> true
        Filter.SUSPENDED -> !active
        Filter.NO_FACE -> roleEnum == Role.USER && !faceEnrolled
        Filter.NO_LOCKER -> roleEnum == Role.USER && lockerId.isNullOrBlank()
    }

    // -------------------------------------------------------- actions ----

    override fun onToggleActiveClicked(uid: String) {
        val user = find(uid) ?: return
        // Suspending is the one that locks someone out, so it gets a confirm.
        if (user.active) view?.confirmSuspend(user) else setActive(user, true)
    }

    override fun onSuspendConfirmed(uid: String) {
        find(uid)?.let { setActive(it, false) }
    }

    private fun setActive(user: User, active: Boolean) {
        scope.launch {
            userRepo.setActive(user.uid, active)
                .onSuccess {
                    lastToggle = user to active
                    trail.record(
                        if (active) AdminActionType.USER_RESTORED else AdminActionType.USER_SUSPENDED,
                        user.fullName
                    )
                    view?.showUndo(
                        if (active) "${user.fullName} reactivated" else "${user.fullName} suspended"
                    )
                }
                .onFailure {
                    view?.showMessage(messageForWrite(it, "Could not update ${user.fullName}"))
                }
        }
    }

    override fun onUndoClicked() {
        val (user, wasActivated) = lastToggle ?: return
        lastToggle = null
        scope.launch {
            userRepo.setActive(user.uid, !wasActivated)
                .onSuccess {
                    trail.record(
                        if (wasActivated) AdminActionType.USER_SUSPENDED else AdminActionType.USER_RESTORED,
                        user.fullName, "undo"
                    )
                }
                .onFailure { view?.showMessage(messageForWrite(it, "Undo failed")) }
        }
    }

    override fun onChangeRoleClicked(uid: String) {
        find(uid)?.let { view?.showRolePicker(it) }
    }

    override fun onRolePicked(uid: String, role: Role) {
        val user = find(uid) ?: return
        if (role == user.roleEnum) return
        scope.launch {
            userRepo.updateUser(user.uid, mapOf("role" to role.name))
                .onSuccess {
                    view?.showMessage("${user.fullName} is now ${roleName(role)}")
                    trail.record(
                        AdminActionType.USER_ROLE_CHANGED, user.fullName,
                        "${user.roleEnum.name} → ${role.name}"
                    )
                }
                .onFailure { view?.showMessage(messageForWrite(it, "Could not change the role")) }
        }
    }

    override fun onDeleteClicked(uid: String) {
        find(uid)?.let { view?.confirmDelete(it) }
    }

    override fun onDeleteConfirmed(uid: String) {
        val user = find(uid) ?: return
        if (!isOnline()) {
            view?.showMessage(OFFLINE_ACTION_MESSAGE)
            return
        }
        scope.launch {
            userRepo.deleteUser(user)
                .onSuccess {
                    view?.showMessage("${user.fullName} removed")
                    trail.record(
                        AdminActionType.USER_DELETED, user.fullName,
                        user.lockerId?.let { "freed $it" }
                    )
                }
                .onFailure { view?.showMessage(messageForWrite(it, "Delete failed")) }
        }
    }

    private fun find(uid: String) = users.firstOrNull { it.uid == uid }

    private companion object {
        const val SEARCH_DELAY_MS = 250L
    }
}
