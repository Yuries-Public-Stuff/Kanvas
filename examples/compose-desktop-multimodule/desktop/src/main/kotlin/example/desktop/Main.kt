package example.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import example.shared.message

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Kanvas Multi-module Example",
    ) {
        Screen()
    }
}

@Composable
private fun Screen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF16181D))
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText("Kanvas multi-module example")
        BasicText(message())
    }
}
