package com.ewan.glyphguard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ewan.glyphguard.ui.AppPatternEditorScreen
import com.ewan.glyphguard.ui.AppPatternListScreen
import com.ewan.glyphguard.ui.MainScreen
import com.ewan.glyphguard.ui.theme.GlyphGuardTheme
import com.ewan.glyphguard.viewmodel.MainViewModel

private sealed class Screen {
    object Main : Screen()
    object AppList : Screen()
    data class AppEditor(val packageName: String, val label: String) : Screen()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GlyphGuardTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val viewModel: MainViewModel = viewModel()
                    var screen by remember { mutableStateOf<Screen>(Screen.Main) }

                    BackHandler(enabled = screen != Screen.Main) {
                        screen = when (screen) {
                            is Screen.AppEditor -> Screen.AppList
                            else -> Screen.Main
                        }
                    }

                    when (val current = screen) {
                        is Screen.Main -> MainScreen(
                            viewModel = viewModel,
                            onOpenAppPatterns = { screen = Screen.AppList },
                            modifier = Modifier.padding(innerPadding)
                        )
                        is Screen.AppList -> AppPatternListScreen(
                            viewModel = viewModel,
                            onBack = { screen = Screen.Main },
                            onAppSelected = { pkg, label -> screen = Screen.AppEditor(pkg, label) },
                            modifier = Modifier.padding(innerPadding)
                        )
                        is Screen.AppEditor -> AppPatternEditorScreen(
                            viewModel = viewModel,
                            packageName = current.packageName,
                            label = current.label,
                            onDone = { screen = Screen.AppList },
                            modifier = Modifier.padding(innerPadding)
                        )
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Don't leave the app "holding" the matrix in app-mode preview
        // once you leave — the AOD toy service takes over from here.
    }
}
