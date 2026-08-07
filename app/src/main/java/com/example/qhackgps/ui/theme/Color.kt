package com.example.qhackgps.ui.theme

import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

/*
 * HUD palette — deliberately fixed, not theme-derived.
 *
 * The navigator floats over a map, is read at a glance while walking, and is
 * often in sunlight. Following the system light/dark scheme made it unreadable
 * (a dark scheme rendered dark text on a dark card), so the HUD pins its own
 * high-contrast colors: near-white paper, near-black ink, saturated signals.
 */
val HudSurface = Color(0xFFFBFCFE)
val HudInk = Color(0xFF0D1520)
val HudInkMuted = Color(0xFF57626F)
val HudGreen = Color(0xFF00A03C)
val HudRed = Color(0xFFD32029)
val HudOrange = Color(0xFFE05E00)
val HudBlue = Color(0xFF1565C0)
val HudAlertSurface = Color(0xFFFFF1E6)