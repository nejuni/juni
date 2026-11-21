package com.nejuni.juni.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import com.nejuni.juni.R
import com.nejuni.juni.data.settings.AppSettings
import com.nejuni.juni.ui.components.IosSwitch
import com.nejuni.juni.ui.icons.SfIcons

/** Last.fm-only account controls exposed by the local-only fork. */
@Composable
fun LastFmSettingsOnly(
    onOpenLogin: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val enabled by AppSettings.lastfmEnabled.collectAsStateWithLifecycle()
    val username by AppSettings.lastfmUsername.collectAsStateWithLifecycle()
    val sessionKey by AppSettings.lastfmSessionKey.collectAsStateWithLifecycle()
    val scrobble by AppSettings.lastfmScrobbleEnabled.collectAsStateWithLifecycle()
    val nowPlaying by AppSettings.lastfmNowPlaying.collectAsStateWithLifecycle()
    val primaryArtistOnly by AppSettings.lastfmPrimaryArtistOnly.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
    ) {
        SettingsPageTitle(stringResource(R.string.lastfm_login))
        SettingsGroup(
            header = stringResource(R.string.scrobbling),
        ) {
            SettingsRow(
                icon = SfIcons.History,
                title = "Last.fm",
                subtitle = if (sessionKey.isNotBlank()) {
                    stringResource(R.string.signed_in_as, username)
                } else {
                    stringResource(R.string.tap_to_sign_in)
                },
                trailing = {
                    IosSwitch(
                        checked = enabled,
                        onCheckedChange = { checked ->
                            if (checked && sessionKey.isBlank()) onOpenLogin()
                            else AppSettings.setLastfmEnabled(checked)
                        },
                    )
                },
                onClick = {
                    if (sessionKey.isNotBlank()) {
                        AppSettings.setLastfmSessionKey("")
                        AppSettings.setLastfmUsername("")
                        AppSettings.setLastfmEnabled(false)
                        AppSettings.setLastfmScrobbleEnabled(false)
                        AppSettings.setLastfmNowPlaying(false)
                    } else onOpenLogin()
                },
            )
            if (enabled && sessionKey.isNotBlank()) {
                RowDivider()
                SettingsRow(
                    icon = SfIcons.GraphicEq,
                    title = stringResource(R.string.scrobble_tracks),
                    subtitle = stringResource(R.string.scrobble_tracks_subtitle),
                    trailing = {
                        IosSwitch(checked = scrobble, onCheckedChange = AppSettings::setLastfmScrobbleEnabled)
                    },
                    onClick = { AppSettings.setLastfmScrobbleEnabled(!scrobble) },
                )
                RowDivider()
                SettingsRow(
                    icon = SfIcons.GraphicEq,
                    title = stringResource(R.string.scrobble_primary_artist_only),
                    subtitle = stringResource(R.string.scrobble_primary_artist_only_subtitle),
                    trailing = {
                        IosSwitch(checked = primaryArtistOnly, onCheckedChange = AppSettings::setLastfmPrimaryArtistOnly)
                    },
                    onClick = { AppSettings.setLastfmPrimaryArtistOnly(!primaryArtistOnly) },
                )
                RowDivider()
                SettingsRow(
                    icon = SfIcons.GraphicEq,
                    title = stringResource(R.string.now_playing),
                    subtitle = stringResource(R.string.lastfm_now_playing_subtitle),
                    trailing = {
                        IosSwitch(checked = nowPlaying, onCheckedChange = AppSettings::setLastfmNowPlaying)
                    },
                    onClick = { AppSettings.setLastfmNowPlaying(!nowPlaying) },
                )
            }
        }
    }
}
