package com.example.navis

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.style.TextAlign
object RouteProgressState {
    val isOffRoute = mutableStateOf(false)

    val routeInstructions =
        mutableStateOf<List<String>>(emptyList())

    val routeDistances =
        mutableStateOf<List<String>>(emptyList())

    val currentInstructionIndex =
        mutableStateOf(0)

    val destinationReached =
        mutableStateOf(false)
}
class RouteProgressActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val destinationName =
            intent.getStringExtra("DESTINATION_NAME")
                ?: "Your destination"

        setContent {
            RouteProgressScreen(
                destinationName = destinationName,
                onBack = {
                    finish()
                }
            )
        }
    }
}

@Composable
fun RouteProgressScreen(
    destinationName: String,
    onBack: () -> Unit
) {
    val isOffRoute = RouteProgressState.isOffRoute.value
    val destinationReached =
        RouteProgressState.destinationReached.value

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF10194A))
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {

        Text(
            text = "ROUTE PROGRESS",
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "Destination",
            color = Color.LightGray,
            fontSize = 16.sp,
            modifier = Modifier.padding(top = 20.dp)
        )

        Text(
            text = destinationName,
            color = Color.White,
            fontSize = 22.sp,
            modifier = Modifier.padding(top = 4.dp)
        )

        Text(
            text = if (isOffRoute) {
                "OFF ROUTE"
            } else {
                "ON ROUTE"
            },
            color = if (isOffRoute) {
                Color(0xFFEF4444)
            } else {
                Color(0xFF22C55E)
            },
            fontSize = 20.sp,
            modifier = Modifier.padding(top = 20.dp)
        )

        Text(
            text = "●",
            color = if (isOffRoute) {
                Color(0xFFEF4444)
            } else {
                Color(0xFF22C55E)
            },
            fontSize = 60.sp,
            modifier = Modifier.padding(top = 20.dp)
        )

        Text(
            text = "Route status indicator",
            color = Color.White,
            fontSize = 18.sp,
            modifier = Modifier.padding(top = 12.dp)
        )

        Spacer(
            modifier = Modifier.height(28.dp)
        )

        if (destinationReached) {
            Text(
                text = "DESTINATION REACHED",
                color = Color(0xFF22C55E),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(
                    top = 24.dp,
                    bottom = 16.dp
                )
            )
        }

        Text(
            text = "TURN-BY-TURN ROUTE",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(
                top = 24.dp,
                bottom = 12.dp
            )
        )

        Text(
            text = if (destinationReached) {
                "ROUTE COMPLETE"
            } else {
                "CURRENT STEP"
            },
            color = if (destinationReached) {
                Color(0xFF22C55E)
            } else {
                Color.LightGray
            },
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        val instructions = RouteProgressState.routeInstructions.value
        val distances = RouteProgressState.routeDistances.value

        if (instructions.isEmpty()) {

            Text(
                text = "No route instructions available",
                color = Color.LightGray,
                fontSize = 16.sp,
                modifier = Modifier.padding(top = 24.dp)
            )

        } else {

            instructions.forEachIndexed { index, instruction ->

                val destinationReached =
                    RouteProgressState.destinationReached.value

                RouteStep(
                    instruction =
                        if (index == instructions.lastIndex) {
                            "Destination"
                        } else {
                            instruction
                        },
                    distance =
                        if (index < distances.size) {
                            distances[index]
                        } else {
                            ""
                        },
                    isCurrent =
                        !destinationReached &&
                                index == RouteProgressState.currentInstructionIndex.value,
                    isCompleted =
                        destinationReached ||
                                index < RouteProgressState.currentInstructionIndex.value,
                    isDestination =
                        index == instructions.lastIndex
                )

                if (index < instructions.lastIndex) {
                    RouteConnector()
                }
            }
            if (destinationReached) {
                Spacer(
                    modifier = Modifier.height(20.dp)
                )

                Text(
                    text = "✓ Destination reached",
                    color = Color(0xFF22C55E),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
    Spacer(
        modifier = Modifier.height(36.dp)
    )


    Button(
        onClick = onBack,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF7C3AED),
            contentColor = Color.White
        )
    ) {
        Text(
            text = "Back",
            fontSize = 16.sp
        )
    }
}

@Composable
fun RouteStep(
    instruction: String,
    distance: String,
    isCurrent: Boolean,
    isCompleted: Boolean,
    isDestination: Boolean
) {

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {

        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .width(if (isCurrent) 50.dp else 42.dp)
                .height(if (isCurrent) 50.dp else 42.dp)
                .background(
                    color = when {
                        isCurrent && RouteProgressState.isOffRoute.value ->
                            Color(0xFFEF4444)

                        isCurrent ->
                            Color(0xFF22C55E)

                        isCompleted ->
                            Color(0xFF555B80)

                        else ->
                            Color(0xFF3A426B)
                    },
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {

            Text(
                text = if (isCompleted) {
                    "✓"
                } else {
                    "●"
                },
                color = Color.White,
                fontSize = 16.sp
            )
        }

        Spacer(
            modifier = Modifier.width(16.dp)
        )

        Column {

            Text(
                text = instruction,
                color = if (isCurrent) {
                    Color.White
                } else {
                    Color(0xFFB8BED6)
                },
                fontSize = if (isCurrent) {
                    22.sp
                } else {
                    18.sp
                },
                fontWeight = if (isCurrent) {
                    FontWeight.Bold
                } else {
                    FontWeight.Normal
                }
            )
            if (distance.isNotEmpty()) {

                Text(
                    text = distance,
                    color = if (isCurrent) {
                        Color.White
                    } else {
                        Color(0xFF9299B5)
                    },
                    fontSize = 16.sp
                )
            }
        }
    }
}

@Composable
fun RouteConnector() {

    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .padding(start = 20.dp)
            .width(2.dp)
            .height(36.dp)
            .background(Color(0xFF555B80))
    )
}