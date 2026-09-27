package com.imnotahuman.codingagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.lifecycle.viewmodel.compose.viewModel
import com.imnotahuman.codingagent.ui.AgentViewModel
import com.imnotahuman.codingagent.ui.AppRoot
import com.imnotahuman.codingagent.ui.theme.AgentTheme

class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AgentTheme {
                val vm: AgentViewModel = viewModel()
                AppRoot(vm)
            }
        }
    }
}
