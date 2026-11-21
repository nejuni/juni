package com.nejuni.juni

import com.nejuni.juni.data.LocalGroups
import com.nejuni.juni.data.OfflinePlaylists
import com.nejuni.juni.data.model.BrowseType
import com.nejuni.juni.ui.MainViewModel
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowseTypeTest {

    @Test
    fun `resolves offline playlist ids as BrowseType PLAYLIST`() {
        assertEquals(BrowseType.PLAYLIST, MainViewModel.browseTypeOf("${OfflinePlaylists.PREFIX}abc"))
        assertEquals(BrowseType.PLAYLIST, MainViewModel.browseTypeOf("local:playlist:abc"))
    }

    @Test
    fun `resolves local group ids through LocalGroups`() {
        val album = LocalGroups.albumPageId("local:all", "Some Album")
        assertEquals(BrowseType.ALBUM, MainViewModel.browseTypeOf(album))

        val artist = LocalGroups.artistPageId("local:all", "Some Artist")
        assertEquals(BrowseType.OTHER, MainViewModel.browseTypeOf(artist))
    }

    @Test
    fun `preserves fallback when browse ID prefix is unknown`() {
        assertEquals(BrowseType.ALBUM, MainViewModel.browseTypeOf("unknown_id", fallback = BrowseType.ALBUM))
        assertEquals(BrowseType.ARTIST, MainViewModel.browseTypeOf("custom_source", fallback = BrowseType.ARTIST))
        assertEquals(BrowseType.OTHER, MainViewModel.browseTypeOf("unrecognized_prefix"))
    }
}
