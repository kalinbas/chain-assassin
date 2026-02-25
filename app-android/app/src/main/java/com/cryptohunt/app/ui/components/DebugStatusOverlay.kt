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
import com.cryptohunt.app.domain.ble.BleAdvertiser
import com.cryptohunt.app.domain.location.LocationTracker
import com.cryptohunt.app.domain.server.ConnectionState
import com.cryptohunt.app.domain.server.DebugEchoWebSocketClient
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
    fun debugEchoWebSocketClient(): DebugEchoWebSocketClient
    fun locationTracker(): LocationTracker
    fun bleAdvertiser(): BleAdvertiser
}

@Composable
fun DebugStatusOverlay() {
    val context = LocalContext.current
    val entryPoint = remember {
        EntryPointAccessors.fromApplication(context.applicationContext, DebugStatusEntryPoint::class.java)
    }

    val serverClient = remember { entryPoint.gameServerClient() }
    val debugEchoClient = remember { entryPoint.debugEchoWebSocketClient() }
    val locationTracker = remember { entryPoint.locationTracker() }
    val bleAdvertiser = remember { entryPoint.bleAdvertiser() }

    val connectionState by serverClient.connectionState.collectAsState()
    val debugConnectionState by debugEchoClient.connectionState.collectAsState()
    val locationState by locationTracker.state.collectAsState()
    val bleAdvertiseState by bleAdvertiser.state.collectAsState()

    // Check if Bluetooth adapter is enabled
    val btEnabled = run {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter ?: return@run false
        try {
            adapter.isEnabled
        } catch (_: SecurityException) {
            false
        }
    }

    // Check if location services are enabled
    val gpsEnabled = run {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
    }

    val off = TextDim.copy(alpha = 0.3f)

    val effectiveConnectionState = if (debugConnectionState != ConnectionState.DISCONNECTED) {
        debugConnectionState
    } else {
        connectionState
    }
    val serverColor = if (effectiveConnectionState == ConnectionState.CONNECTED) Primary else off

    val gpsColor = if (locationState.isTracking && locationState.gpsLostSeconds == 0) Primary else off

    val bleColor = if (btEnabled && bleAdvertiseState.isAdvertising) Primary else off

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
