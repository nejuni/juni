package com.nejuni.juni.ui

import com.nejuni.juni.data.settings.AppSettings

/** The shared pages' settings, answered from the phone's own stores. */
object AndroidAppUiHost : AppUiHost {
    override val swipeToPlayNext = AppSettings.swipeToPlayNext
    override val pinnedPlaylists = AppSettings.pinnedPlaylists
    override val librarySort = AppSettings.librarySort
}
