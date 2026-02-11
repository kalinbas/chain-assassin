package com.cryptohunt.app.ui.components

import android.bluetooth.BluetoothManager
import android.content.Context
import android.location.LocationManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cryptohunt.app.domain.location.LocationTracker
import com.cryptohunt.app.domain.server.ConnectionState
import com.cryptohunt.app.domain.server.GameServerClient
import com.cryptohunt.app.ui.theme.*
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DebugStatusEntryPoint {
    fun gameServerClient(): GameServerClient
    fun locationTracker(): LocationTracker
}

@Composable
fun DebugStatusOverlay() {
    val context = LocalContext.current
    val entryPoint = remember {
        EntryPointAccessors.fromApplication(context.applicationContext, DebugStatusEntryPoint::class.java)
    }

    val serverClient = remember { entryPoint.gameServerClient() }
    val locationTracker = remember { entryPoint.locationTracker() }

    val connectionState by serverClient.connectionState.collectAsState()
    val locationState by locationTracker.state.collectAsState()

    // Check if Bluetooth adapter is enabled
    val btEnabled = remember {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        btManager?.adapter?.isEnabled == true
    }

    // Check if location services are enabled
    val gpsEnabled = remember {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
    }

    val serverColor = when (connectionState) {
        ConnectionState.CONNECTED -> Primary
        ConnectionState.CONNECTING, ConnectionState.AUTHENTICATING, ConnectionState.RECONNECTING -> Warning
        ConnectionState.DISCONNECTED -> Danger
    }

    val gpsColor = when {
        locationState.isTracking && locationState.gpsLostSeconds == 0 -> Primary
        locationState.isTracking -> Warning
        gpsEnabled -> TextDim
        else -> Danger
    }

    val bleColor = if (btEnabled) TextDim else Danger

    Row(
        modifier = Modifier
            .background(Background.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusDot("S", serverColor)
        StatusDot("G", gpsColor)
        StatusDot("B", bleColor)
    }
}

@Composable
private fun StatusDot(label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}
