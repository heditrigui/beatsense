package com.beatsense.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beatsense.app.ui.BeatTheme
import com.beatsense.app.ui.BpmScreen

class MainActivity : ComponentActivity() {
    private val vm: BpmViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            BeatTheme {
                // Surface provides the light content colour for text that doesn't set one.
                Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                    val state by vm.state.collectAsStateWithLifecycle()
                    BpmScreen(vm, state)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Don't keep the microphone open in the background.
        if (!isChangingConfigurations && vm.state.value.listening) vm.stop()
    }
}
