package com.llamaport.app.ui.robot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.llamaport.app.ui.motion.Reveal

/**
 * Debug screen for all [RobotState]s: live robots plus sliders that drive
 * Loading progress and Generating pulse. Hidden entry (long-press the robot
 * in the empty state).
 */
@Composable
fun RobotDemoDialog(onDismiss: () -> Unit) {
    var loadingProgress by remember { mutableFloatStateOf(0.35f) }
    var pulse by remember { mutableFloatStateOf(0.6f) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
        ) {
            Column(
                Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "AsciiRobot",
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(16.dp))

                val states = RobotState.entries.toList()
                states.chunked(2).forEachIndexed { rowIndex, pair ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        pair.forEachIndexed { colIndex, robotState ->
                            Reveal(index = rowIndex * 2 + colIndex) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    AsciiRobot(
                                        state = robotState,
                                        size = 88.dp,
                                        progress = loadingProgress,
                                        pulse = pulse,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = robotState.name,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    "Loading progress: ${loadingProgress.roundPercent()}",
                    style = MaterialTheme.typography.labelMedium,
                )
                Slider(
                    value = loadingProgress,
                    onValueChange = { loadingProgress = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Pulse: ${pulse.roundPercent()}",
                    style = MaterialTheme.typography.labelMedium,
                )
                Slider(
                    value = pulse,
                    onValueChange = { pulse = it },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))
                Row {
                    Button(onClick = onDismiss) {
                        Text("Закрыть")
                    }
                    Spacer(Modifier.width(12.dp))
                }
            }
        }
    }
}

private fun Float.roundPercent(): Int = (this * 100).toInt()
