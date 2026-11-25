package com.windstrom5.rebit.presentation.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class AppDestination(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Home", Icons.Filled.Home),
    LIBRARY("library", "Library", Icons.Filled.LibraryMusic),
    PLAYER("player", "Player", Icons.Filled.PlayCircle),
    GAMES("games", "Games", Icons.Filled.SportsEsports),
    SETTINGS("settings", "Settings", Icons.Filled.Settings)
}
