package com.ewan.glyphguard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModelProvider
import com.ewan.glyphguard.ui.AppPatternEditorScreen
import com.ewan.glyphguard.ui.AppPatternListScreen
import com.ewan.glyphguard.ui.GalleryScreen
import com.ewan.glyphguard.ui.KeyActionsScreen
import com.ewan.glyphguard.ui.MainScreen
import com.ewan.glyphguard.ui.MusicScreen
import com.ewan.glyphguard.ui.theme.GlyphGuardTheme
import com.ewan.glyphguard.viewmodel.MainViewModel

private enum class Tab(val label: String) {
    MAIN("Main"), GALLERY("Gallery"), MUSIC("Music"), APPS("Apps"), KEY("Key")
}

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        enableEdgeToEdge()
        setContent {
            GlyphGuardTheme {
                var selectedTab by remember { mutableStateOf(Tab.MAIN) }
                // Per-app drill-in — covers the whole screen (including the
                // tab bar) while active, same as a detail screen over a root.
                var appEditorTarget by remember { mutableStateOf<Pair<String, String>?>(null) }

                BackHandler(enabled = appEditorTarget != null || selectedTab != Tab.MAIN) {
                    if (appEditorTarget != null) appEditorTarget = null else selectedTab = Tab.MAIN
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        if (appEditorTarget == null) {
                            NavigationBar {
                                NavigationBarItem(
                                    selected = selectedTab == Tab.MAIN,
                                    onClick = { selectedTab = Tab.MAIN },
                                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                                    label = { Text(Tab.MAIN.label) }
                                )
                                NavigationBarItem(
                                    selected = selectedTab == Tab.GALLERY,
                                    onClick = { selectedTab = Tab.GALLERY },
                                    icon = { Icon(Icons.Filled.Star, contentDescription = null) },
                                    label = { Text(Tab.GALLERY.label) }
                                )
                                NavigationBarItem(
                                    selected = selectedTab == Tab.MUSIC,
                                    onClick = { selectedTab = Tab.MUSIC },
                                    icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                                    label = { Text(Tab.MUSIC.label) }
                                )
                                NavigationBarItem(
                                    selected = selectedTab == Tab.APPS,
                                    onClick = { selectedTab = Tab.APPS },
                                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                                    label = { Text(Tab.APPS.label) }
                                )
                                NavigationBarItem(
                                    selected = selectedTab == Tab.KEY,
                                    onClick = { selectedTab = Tab.KEY },
                                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                                    label = { Text(Tab.KEY.label) }
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    val target = appEditorTarget
                    if (target != null) {
                        AppPatternEditorScreen(
                            viewModel = viewModel,
                            packageName = target.first,
                            label = target.second,
                            onDone = { appEditorTarget = null },
                            modifier = Modifier.padding(innerPadding)
                        )
                    } else {
                        when (selectedTab) {
                            Tab.MAIN -> MainScreen(
                                viewModel = viewModel,
                                modifier = Modifier.padding(innerPadding)
                            )
                            Tab.GALLERY -> GalleryScreen(
                                viewModel = viewModel,
                                modifier = Modifier.padding(innerPadding)
                            )
                            Tab.MUSIC -> MusicScreen(
                                viewModel = viewModel,
                                modifier = Modifier.padding(innerPadding)
                            )
                            Tab.APPS -> AppPatternListScreen(
                                viewModel = viewModel,
                                onAppSelected = { pkg, label -> appEditorTarget = pkg to label },
                                modifier = Modifier.padding(innerPadding)
                            )
                            Tab.KEY -> KeyActionsScreen(
                                viewModel = viewModel,
                                modifier = Modifier.padding(innerPadding)
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Don't leave the app "holding" the matrix in app-mode preview
        // once you leave — the AOD toy service takes over from here.
        viewModel.stopPreview()
    }
}
