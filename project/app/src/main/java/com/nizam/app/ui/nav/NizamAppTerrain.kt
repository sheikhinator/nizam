package com.nizam.app.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nizam.app.AppContainer
import com.nizam.app.ui.design.LocalTerrain
import com.nizam.app.ui.design.terrain.Dial
import com.nizam.app.ui.design.terrain.ProvideTerrain

@Composable
fun NizamAppTerrain(container: AppContainer) {
    ProvideTerrain {
        val navController = rememberNavController()
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route
        val terrain = LocalTerrain.current
        var dialVisible by remember { mutableStateOf(false) }
        
        Box(Modifier.fillMaxSize().background(terrain.void)) {
            Scaffold(
                containerColor = Color.Transparent,
                bottomBar = {
                    Shoal(
                        currentRoute = currentRoute,
                        onNavigate = { route ->
                            if (route == "core") {
                                dialVisible = true
                            } else {
                                navController.navigate(route)
                            }
                        }
                    )
                }
            ) { padding ->
                NavHost(
                    navController = navController,
                    startDestination = "today",
                    modifier = Modifier.padding(padding)
                ) {
                    // Keep existing routes - just add terrain styling
                    composable("today") { 
                        // This will be replaced by existing TodayScreen but with terrain
                        com.nizam.app.ui.screens.today.TodayScreen(container)
                    }
                }
            }
            Dial(
                visible = dialVisible,
                onDismiss = { dialVisible = false },
                onNavigate = { route -> 
                    navController.navigate(route)
                    dialVisible = false
                }
            )
        }
    }
}
