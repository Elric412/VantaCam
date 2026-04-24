package com.leica.cam

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideIntoContainer
import androidx.compose.animation.slideOutOfContainer
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
import com.leica.cam.feature.camera.permissions.PermissionGate
import com.leica.cam.feature.camera.ui.CameraScreen
import com.leica.cam.feature.camera.ui.CameraScreenDeps
import com.leica.cam.feature.gallery.ui.GalleryMetadataEngine
import com.leica.cam.feature.gallery.ui.GalleryScreen
import com.leica.cam.feature.settings.ui.SettingsScreen
import com.leica.cam.ui_components.theme.LeicaPalette
import com.leica.cam.ui_components.theme.LeicaTheme
import com.leica.cam.ui_components.theme.LeicaTokens
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var cameraDeps: CameraScreenDeps
    @Inject lateinit var galleryEngine: GalleryMetadataEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            LeicaTheme {
                val navController = rememberNavController()
                val backStack by navController.currentBackStackEntryAsState()
                val currentDestination = backStack?.destination

                Scaffold(
                    bottomBar = { BottomBar(navController, currentDestination) },
                    containerColor = LeicaTokens.colors.background,
                ) { innerPadding ->
                    NavGraph(navController, Modifier.padding(innerPadding))
                }
            }
        }
    }

    @Composable
    private fun BottomBar(navController: NavHostController, currentDestination: NavDestination?) {
        NavigationBar(containerColor = LeicaPalette.Surface0, contentColor = Color.White) {
            listOf(
                Triple("camera", "CAMERA", Icons.Default.Home),
                Triple("gallery", "GALLERY", Icons.Default.List),
                Triple("settings", "SETTINGS", Icons.Default.Settings),
            ).forEach { (route, label, icon) ->
                NavigationBarItem(
                    icon = { Icon(icon, contentDescription = label) },
                    label = { Text(label) },
                    selected = currentDestination?.hierarchy?.any { it.route == route } == true,
                    onClick = {
                        navController.navigate(route) {
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = LeicaPalette.Red,
                        selectedTextColor = LeicaPalette.Red,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray,
                        indicatorColor = Color.Transparent,
                    ),
                )
            }
        }
    }

    @Composable
    private fun NavGraph(navController: NavHostController, modifier: Modifier) {
        val motion = LeicaTokens.motion
        NavHost(
            navController = navController,
            startDestination = "camera",
            modifier = modifier,
            enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(motion.standard, easing = motion.enter)) + fadeIn(tween(motion.standard)) },
            exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(motion.standard, easing = motion.exit)) + fadeOut(tween(motion.fast)) },
            popEnterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(motion.standard, easing = motion.enter)) + fadeIn(tween(motion.standard)) },
            popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(motion.standard, easing = motion.exit)) + fadeOut(tween(motion.fast)) },
        ) {
            composable("camera") {
                PermissionGate { CameraScreen(cameraDeps, onOpenGallery = { navController.navigate("gallery") }) }
            }
            composable("gallery") { GalleryScreen(galleryEngine) }
            composable("settings") { SettingsScreen() }
        }
    }
}
