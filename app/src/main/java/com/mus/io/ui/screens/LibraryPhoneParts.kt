package com.nejuni.juni.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.ui.graphics.vector.ImageVector

/** Icon for the on-device music folder. */
fun deviceFolderIcon(browseId: String): ImageVector? = when (browseId) {
    "local:all" -> Icons.Rounded.Folder
    else -> null
}

/** The on-device cache folder route, retained for existing cache controls. */
