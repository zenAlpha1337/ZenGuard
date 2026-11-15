package com.zenguard.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.zenguard.app.ui.navigation.ZenNavGraph
import com.zenguard.app.ui.theme.ZenGuardTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ZenGuardTheme {
                ZenNavGraph()
            }
        }
    }
}
