package com.airbooks.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.airbooks.app.ui.AirGramTheme
import com.airbooks.app.ui.ChatsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AirGramTheme { ChatsScreen() } }
    }
}
