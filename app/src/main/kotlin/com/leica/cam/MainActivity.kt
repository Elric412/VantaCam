package com.leica.cam

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.leica.cam.feature.camera.ui.CameraModeSwitcher
import com.leica.cam.feature.camera.ui.CameraScreen
import com.leica.cam.feature.camera.ui.CameraUiOrchestrator
import com.leica.cam.feature.gallery.ui.GalleryMetadataEngine
import com.leica.cam.feature.gallery.ui.GalleryScreen
import com.leica.cam.feature.settings.ui.SettingsScreen
import com.leica.cam.ui_components.camera.Phase9UiStateCalculator
import com.leica.cam.ui_components.theme.LeicaBlack
import com.leica.cam.ui_components.theme.LeicaRed
import com.leica.cam.ui_components.theme.LeicaTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var orchestrator: CameraUiOrchestrator

    @Inject
    lateinit var modeSwitcher: CameraModeSwitcher

    @Inject
    lateinit var uiStateCalculator: Phase9UiStateCalculator

    @Inject
    lateinit var galleryEngine: GalleryMetadataEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LeicaTheme {
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                Scaffold(
                    bottomBar = { leicaBottomNavigation(navController, currentDestination) },
                ) { innerPadding ->
                    leicaNavHost(navController, Modifier.padding(innerPadding))
                }
            }
        }
    }

    @Composable
    private fun leicaBottomNavigation(
        navController: NavHostController,
        currentDestination: NavDestination?,
    ) {
        NavigationBar(
            containerColor = LeicaBlack,
            contentColor = Color.White,
        ) {
            NavigationBarItem(
                icon = { Icon(Icons.Default.Home, contentDescription = "Camera") },
                label = { Text("CAMERA") },
                selected = currentDestination?.hierarchy?.any { it.route == "camera" } == true,
                onClick = {
                    navController.navigate("camera") {
                        popUpTo("camera") { inclusive = false }
                        launchSingleTop = true
                    }
                },
                colors =
                    NavigationBarItemDefaults.colors(
                        selectedIconColor = LeicaRed,
                        selectedTextColor = LeicaRed,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray,
                        indicatorColor = Color.Transparent,
                    ),
            )
            NavigationBarItem(
                icon = { Icon(Icons.Default.List, contentDescription = "Gallery") },
                label = { Text("GALLERY") },
                selected = currentDestination?.hierarchy?.any { it.route == "gallery" } == true,
                onClick = {
                    navController.navigate("gallery") {
                        launchSingleTop = true
                    }
                },
                colors =
                    NavigationBarItemDefaults.colors(
                        selectedIconColor = LeicaRed,
                        selectedTextColor = LeicaRed,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray,
                        indicatorColor = Color.Transparent,
                    ),
            )
            NavigationBarItem(
                icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                label = { Text("SETTINGS") },
                selected = currentDestination?.hierarchy?.any { it.route == "settings" } == true,
                onClick = {
                    navController.navigate("settings") {
                        launchSingleTop = true
                    }
                },
                colors =
                    NavigationBarItemDefaults.colors(
                        selectedIconColor = LeicaRed,
                        selectedTextColor = LeicaRed,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray,
                        indicatorColor = Color.Transparent,
                    ),
            )
        }
    }

    @Composable
    private fun leicaNavHost(
        navController: NavHostController,
        modifier: Modifier,
    ) {
        NavHost(
            navController = navController,
            startDestination = "camera",
            modifier = modifier,
        ) {
            composable("camera") {
                CameraScreen(orchestrator, uiStateCalculator, modeSwitcher)
            }
            composable("gallery") {
                GalleryScreen(galleryEngine)
            }
            composable("settings") {
                SettingsScreen()
            }
        }
    }
}
