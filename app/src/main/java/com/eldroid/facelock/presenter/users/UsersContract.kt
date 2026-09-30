package com.eldroid.facelock.presenter.users

import com.eldroid.facelock.data.model.Role
import com.eldroid.facelock.data.model.User
import com.eldroid.facelock.presenter.base.BasePresenter
import com.eldroid.facelock.presenter.common.SectionItem

/** Users tab (admins): role sections, search, quick filters and account actions. */
interface UsersContract {

    interface View {
        fun showLoading()
        /** Role sections; [count] is how many people pass the search and filter. */
        fun showUsers(items: List<SectionItem<User>>, count: Int)
        fun showEmpty(kind: EmptyKind)
        fun showLoadError(message: String)
        fun showMessage(message: String)
        /** A message with an Undo action that calls [Presenter.onUndoClicked]. */
        fun showUndo(message: String)

        /** Chip counts, over whatever the search currently matches. */
        fun showFilterCounts(all: Int, suspended: Int, noFace: Int, noLocker: Int)
        /** Keeps the chips in step after rotation. */
        fun showFilter(filter: Filter)

        fun confirmSuspend(user: User)
        fun showRolePicker(user: User)
        fun confirmDelete(user: User)
    }

    interface Presenter : BasePresenter<View> {
        fun onSearchChanged(query: String)
        fun onFilterSelected(filter: Filter)
        fun onSectionToggled(groupKey: String)

        fun onToggleActiveClicked(uid: String)
        fun onSuspendConfirmed(uid: String)
        fun onUndoClicked()
        fun onChangeRoleClicked(uid: String)
        fun onRolePicked(uid: String, role: Role)
        fun onDeleteClicked(uid: String)
        fun onDeleteConfirmed(uid: String)
    }

    /** No face / No locker only count members: staff open nothing with a face. */
    enum class Filter { ALL, SUSPENDED, NO_FACE, NO_LOCKER }

    enum class EmptyKind { NO_USERS, NO_MATCH }
}
