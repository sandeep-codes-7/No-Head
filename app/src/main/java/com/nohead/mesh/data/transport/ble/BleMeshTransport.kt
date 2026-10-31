package com.nohead.mesh.data.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.nohead.mesh.BuildConfig
import com.nohead.mesh.domain.crypto.CryptoEngine
import com.nohead.mesh.domain.model.HandshakePayload
import com.nohead.mesh.domain.model.MeshEnvelope
import com.nohead.mesh.domain.model.PacketFragment
import com.nohead.mesh.domain.model.PacketType
import com.nohead.mesh.domain.model.PeerAnnouncementPayload
import com.nohead.mesh.domain.model.ProtocolConstants
import com.nohead.mesh.domain.model.TransportCapabilities
import com.nohead.mesh.domain.model.TransportType
import com.nohead.mesh.domain.service.IdentityManager
import com.nohead.mesh.domain.transport.InboundTransportPacket
import com.nohead.mesh.domain.transport.PacketFragmenter
import com.nohead.mesh.domain.transport.Transport
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production native Android Bluetooth Low Energy (BLE) Mesh Transport.
 *
 * Implements:
 * - Peripheral Mode: BLE Advertising + BluetoothGattServer with Packet & Handshake characteristics
 * - Central Mode: Filtered BLE Scanning + BluetoothGatt client pool with MTU 512 negotiation
 * - Handshake & Capability Negotiation on connect
 * - Packet Fragmentation & Reassembly via [PacketFragmenter]
 * - Bluetooth Adapter state BroadcastReceiver recovery
 * - Adaptive Scan intervals for Battery Saver mode
 */
@Singleton
@SuppressLint("MissingPermission")
class BleMeshTransport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identityManager: IdentityManager,
    private val cryptoEngine: CryptoEngine
) : Transport {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString(BuildConfig.BLE_SERVICE_UUID)
        val CHAR_PACKET_UUID: UUID = UUID.fromString(BuildConfig.BLE_CHAR_PACKET_UUID)
        val CHAR_HANDSHAKE_UUID: UUID = UUID.fromString(BuildConfig.BLE_CHAR_HANDSHAKE_UUID)
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val MAX_CONCURRENT_GATT_CLIENTS = 7
        private const val PEER_STALE_TIMEOUT_MS = 120_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val fragmenter = PacketFragmenter(ProtocolConstants.BLE_DEFAULT_CHUNK_PAYLOAD_BYTES)
    private val writeMutex = Mutex()

    override val transportType: TransportType = TransportType.BLE_MESH

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _adapterEnabled = MutableStateFlow(false)
    val adapterEnabled: StateFlow<Boolean> = _adapterEnabled.asStateFlow()

    private val _batterySaverMode = MutableStateFlow(false)
    val batterySaverMode: StateFlow<Boolean> = _batterySaverMode.asStateFlow()

    private val _currentScanIntervalMs = MutableStateFlow(15_000L)
    val currentScanIntervalMs: StateFlow<Long> = _currentScanIntervalMs.asStateFlow()

    private val _discoveredPeersCount = MutableStateFlow(0)
    override val discoveredPeersCount: StateFlow<Int> = _discoveredPeersCount.asStateFlow()

    private val _connectedPeersCount = MutableStateFlow(0)
    override val connectedPeersCount: StateFlow<Int> = _connectedPeersCount.asStateFlow()

    private val inboundFlow = MutableSharedFlow<InboundTransportPacket>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private data class DiscoveredBleDevice(
        val address: String,
        val rssi: Int,
        val lastSeenMs: Long,
        var peerId: String? = null
    )

    private val discoveredDevices = ConcurrentHashMap<String, DiscoveredBleDevice>()
    private val activeClientGatts = ConcurrentHashMap<String, BluetoothGatt>()
    private val connectedServerDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val addressToPeerId = ConcurrentHashMap<String, String>()

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null
    private var maintenanceJob: Job? = null
    private var receiverRegistered = false

    private val adapterStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_ON -> {
                        _adapterEnabled.value = true
                        if (_isRunning.value) {
                            scope.launch { restartBleStack() }
                        }
                    }
                    BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                        _adapterEnabled.value = false
                        _isAdvertising.value = false
                        _isScanning.value = false
                        closeActiveConnections()
                    }
                }
            }
        }
    }

    fun hasRequiredBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun setBatterySaverMode(enabled: Boolean) {
        _batterySaverMode.value = enabled
        _currentScanIntervalMs.value = if (enabled) 45_000L else 15_000L
        if (_isRunning.value && _adapterEnabled.value && hasRequiredBlePermissions()) {
            stopScanningInternal()
            startScanningInternal()
        }
    }

    override suspend fun start() {
        if (_isRunning.value) return
        bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        _adapterEnabled.value = bluetoothAdapter?.isEnabled == true
        _isRunning.value = true

        registerAdapterReceiverIfNeeded()

        if (_adapterEnabled.value && hasRequiredBlePermissions()) {
            restartBleStack()
        }

        maintenanceJob?.cancel()
        maintenanceJob = scope.launch {
            while (isActive && _isRunning.value) {
                val interval = _currentScanIntervalMs.value
                delay(interval)
                cleanupStalePeers()
                if (_adapterEnabled.value && hasRequiredBlePermissions()) {
                    broadcastPresenceAnnouncement()
                }
            }
        }
    }

    override suspend fun stop() {
        _isRunning.value = false
        maintenanceJob?.cancel()
        maintenanceJob = null
        stopAdvertisingInternal()
        stopScanningInternal()
        closeActiveConnections()
        gattServer?.close()
        gattServer = null
        unregisterAdapterReceiverIfNeeded()
    }

    override suspend fun discoverPeers() {
        if (!_isRunning.value) {
            start()
            return
        }
        bluetoothManager = bluetoothManager ?: (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
        bluetoothAdapter = bluetoothAdapter ?: bluetoothManager?.adapter
        _adapterEnabled.value = bluetoothAdapter?.isEnabled == true

        if (_adapterEnabled.value && hasRequiredBlePermissions()) {
            if (gattServer == null || !_isAdvertising.value) {
                restartBleStack()
            } else {
                stopScanningInternal()
                startScanningInternal()
                broadcastPresenceAnnouncement()
            }
        }
    }

    private suspend fun restartBleStack() {
        if (!hasRequiredBlePermissions()) return
        setupGattServer()
        startAdvertisingInternal()
        stopScanningInternal()
        startScanningInternal()
        broadcastPresenceAnnouncement()
    }

    private fun setupGattServer() {
        val manager = bluetoothManager ?: return
        if (gattServer != null) return

        val server = try {
            manager.openGattServer(context, gattServerCallback)
        } catch (_: Exception) {
            null
        } ?: return

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val packetCharacteristic = BluetoothGattCharacteristic(
            CHAR_PACKET_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ
        )
        val cccdDescriptor = BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        packetCharacteristic.addDescriptor(cccdDescriptor)

        val handshakeCharacteristic = BluetoothGattCharacteristic(
            CHAR_HANDSHAKE_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        service.addCharacteristic(packetCharacteristic)
        service.addCharacteristic(handshakeCharacteristic)
        server.addService(service)
        gattServer = server
    }

    private fun startAdvertisingInternal() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled || !adapter.isMultipleAdvertisementSupported) {
            _isAdvertising.value = false
            return
        }
        val leAdvertiser = adapter.bluetoothLeAdvertiser ?: return
        advertiser = leAdvertiser

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(
                if (_batterySaverMode.value) AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
                else AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
            )
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        // Include rotating presence token in service data so passive scanners cannot link static identity
        val rotatingTokenBytes = identityManager.identityState.value.rotatingPresenceId
            .take(8)
            .toByteArray(StandardCharsets.UTF_8)

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .addServiceData(ParcelUuid(SERVICE_UUID), rotatingTokenBytes)
            .build()

        try {
            leAdvertiser.startAdvertising(settings, data, advertiseCallback)
        } catch (_: Exception) {
            _isAdvertising.value = false
        }
    }

    private fun stopAdvertisingInternal() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Exception) {
        }
        _isAdvertising.value = false
    }

    private fun startScanningInternal() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return
        val leScanner = adapter.bluetoothLeScanner ?: return
        scanner = leScanner

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(
                if (_batterySaverMode.value) ScanSettings.SCAN_MODE_LOW_POWER
                else ScanSettings.SCAN_MODE_BALANCED
            )
            .build()

        try {
            leScanner.startScan(listOf(filter), scanSettings, scanCallback)
            _isScanning.value = true
        } catch (_: Exception) {
            _isScanning.value = false
        }
    }

    private fun stopScanningInternal() {
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        _isScanning.value = false
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            _isAdvertising.value = true
        }

        override fun onStartFailure(errorCode: Int) {
            _isAdvertising.value = false
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { handleScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            _isScanning.value = false
        }
    }

    private fun handleScanResult(result: ScanResult) {
        val device = result.device ?: return
        val address = device.address ?: return
        val now = System.currentTimeMillis()

        discoveredDevices[address] = DiscoveredBleDevice(
            address = address,
            rssi = result.rssi,
            lastSeenMs = now,
            peerId = addressToPeerId[address]
        )
        _discoveredPeersCount.value = discoveredDevices.size

        if (activeClientGatts.size < MAX_CONCURRENT_GATT_CLIENTS && !activeClientGatts.containsKey(address)) {
            try {
                val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    device.connectGatt(context, false, gattClientCallback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    device.connectGatt(context, false, gattClientCallback)
                }
                if (gatt != null) {
                    activeClientGatts[address] = gatt
                    updateConnectedCount()
                }
            } catch (_: Exception) {
            }
        }
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device?.address ?: return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                updateConnectedCount()
                gatt.requestMtu(512)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                activeClientGatts.remove(address)
                try {
                    gatt.close()
                } catch (_: Exception) {
                }
                updateConnectedCount()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val service = gatt.getService(SERVICE_UUID) ?: return
            val packetChar = service.getCharacteristic(CHAR_PACKET_UUID)
            var descriptorWriteStarted = false
            if (packetChar != null) {
                gatt.setCharacteristicNotification(packetChar, true)
                val cccd = packetChar.getDescriptor(CCCD_UUID)
                if (cccd != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        descriptorWriteStarted = gatt.writeDescriptor(
                            cccd,
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        ) == BluetoothGatt.GATT_SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        descriptorWriteStarted = gatt.writeDescriptor(cccd)
                    }
                }
            }
            // Android BLE only allows one in-flight GATT operation at a time per connection.
            // If CCCD descriptor write was queued, read CHAR_HANDSHAKE_UUID in onDescriptorWrite;
            // otherwise read it immediately.
            if (!descriptorWriteStarted) {
                val handshakeChar = service.getCharacteristic(CHAR_HANDSHAKE_UUID)
                if (handshakeChar != null) {
                    gatt.readCharacteristic(handshakeChar)
                }
            }
            scope.launch {
                broadcastPresenceAnnouncement()
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                val service = gatt.getService(SERVICE_UUID) ?: return
                val handshakeChar = service.getCharacteristic(CHAR_HANDSHAKE_UUID)
                if (handshakeChar != null) {
                    gatt.readCharacteristic(handshakeChar)
                }
            }
        }

        // Android 13+ (API 33+) callback
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == CHAR_HANDSHAKE_UUID) {
                handleIncomingHandshakeBytes(value, gatt.device?.address)
            }
        }

        // Pre-Android 13 (API 26-32) callback
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == CHAR_HANDSHAKE_UUID) {
                    @Suppress("DEPRECATION")
                    val bytes = characteristic.value ?: return
                    handleIncomingHandshakeBytes(bytes, gatt.device?.address)
                }
            }
        }

        // Android 13+ (API 33+) callback
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == CHAR_PACKET_UUID) {
                handleIncomingFragmentBytes(value, gatt.device?.address)
            }
        }

        // Pre-Android 13 (API 26-32) callback
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                if (characteristic.uuid == CHAR_PACKET_UUID) {
                    @Suppress("DEPRECATION")
                    val bytes = characteristic.value ?: return
                    handleIncomingFragmentBytes(bytes, gatt.device?.address)
                }
            }
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val address = device.address ?: return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedServerDevices[address] = device
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedServerDevices.remove(address)
            }
            updateConnectedCount()
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                )
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == CHAR_HANDSHAKE_UUID) {
                val payloadBytes = buildLocalHandshakeBytes()
                val slice = if (offset < payloadBytes.size) {
                    payloadBytes.copyOfRange(offset, payloadBytes.size)
                } else {
                    ByteArray(0)
                }
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (value != null) {
                when (characteristic.uuid) {
                    CHAR_PACKET_UUID -> handleIncomingFragmentBytes(value, device.address)
                    CHAR_HANDSHAKE_UUID -> handleIncomingHandshakeBytes(value, device.address)
                }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }
    }

    internal fun buildLocalHandshakeBytes(): ByteArray {
        val local = identityManager.identityState.value
        val now = System.currentTimeMillis()
        val challenge = cryptoEngine.randomBytes(8)
        val challengeHex = CryptoEngine.run { challenge.toHex() }
        val signBytes = "${local.peerId}|${local.signingPublicKeyHex}|${local.encryptionPublicKeyHex}|$challengeHex".encodeToByteArray()
        val sig = identityManager.signBytes(signBytes)
        val handshake = HandshakePayload(
            peerId = local.peerId,
            nickname = local.nickname,
            signingPublicKeyHex = local.signingPublicKeyHex,
            staticEncryptionPublicKeyHex = local.encryptionPublicKeyHex,
            ephemeralEncryptionPublicKeyHex = local.encryptionPublicKeyHex,
            supportedProtocolVersion = ProtocolConstants.PROTOCOL_VERSION,
            challengeNonceHex = challengeHex,
            signatureOverHandshakeBase64 = CryptoEngine.encodeBase64(sig),
            timestampEpochMs = now
        )
        return json.encodeToString(handshake).toByteArray(StandardCharsets.UTF_8)
    }

    internal fun parseAndVerifyHandshakeToEnvelope(bytes: ByteArray, address: String? = null): MeshEnvelope? {
        return runCatching {
            val handshake = json.decodeFromString<HandshakePayload>(bytes.decodeToString())
            val pubKeyBytes = with(CryptoEngine) { handshake.signingPublicKeyHex.hexToByteArray() }
            if (cryptoEngine.derivePeerId(pubKeyBytes) != handshake.peerId) return null

            val signBytes = "${handshake.peerId}|${handshake.signingPublicKeyHex}|${handshake.staticEncryptionPublicKeyHex}|${handshake.challengeNonceHex}".encodeToByteArray()
            val sigValid = cryptoEngine.verifyEd25519(
                publicKeyBytes = pubKeyBytes,
                message = signBytes,
                signatureBytes = CryptoEngine.decodeBase64(handshake.signatureOverHandshakeBase64)
            )
            if (!sigValid) return null

            if (address != null) {
                addressToPeerId[address] = handshake.peerId
                discoveredDevices[address]?.peerId = handshake.peerId
            }

            val announce = PeerAnnouncementPayload(
                peerId = handshake.peerId,
                nickname = handshake.nickname,
                signingPublicKeyHex = handshake.signingPublicKeyHex,
                encryptionPublicKeyHex = handshake.staticEncryptionPublicKeyHex,
                rotatingPresenceToken = handshake.challengeNonceHex,
                protocolVersion = handshake.supportedProtocolVersion,
                supportedTransports = setOf(TransportType.BLE_MESH),
                timestampEpochMs = handshake.timestampEpochMs
            )
            val payloadBase64 = CryptoEngine.encodeBase64(json.encodeToString(announce).encodeToByteArray())
            MeshEnvelope(
                packetId = "hs_${handshake.peerId}_${handshake.timestampEpochMs}",
                packetType = PacketType.HANDSHAKE_INIT,
                senderPeerId = handshake.peerId,
                senderNickname = handshake.nickname,
                senderSigningPubKeyHex = handshake.signingPublicKeyHex,
                senderEncryptionPubKeyHex = handshake.staticEncryptionPublicKeyHex,
                createdAtEpochMs = handshake.timestampEpochMs,
                expiresAtEpochMs = handshake.timestampEpochMs + 300_000L,
                ttl = 1,
                hopCount = 0,
                payloadBase64 = payloadBase64,
                signatureBase64 = handshake.signatureOverHandshakeBase64,
                viaTransports = setOf(TransportType.BLE_MESH)
            )
        }.getOrNull()
    }

    private fun handleIncomingHandshakeBytes(bytes: ByteArray, address: String?) {
        val verifiedEnvelope = parseAndVerifyHandshakeToEnvelope(bytes, address) ?: return
        inboundFlow.tryEmit(
            InboundTransportPacket(
                envelope = verifiedEnvelope,
                sourceTransport = TransportType.BLE_MESH,
                rssiDbm = address?.let { discoveredDevices[it]?.rssi },
                immediateSenderAddress = address
            )
        )
    }

    private fun handleIncomingFragmentBytes(bytes: ByteArray, address: String?) {
        val fragment = runCatching {
            json.decodeFromString<PacketFragment>(bytes.decodeToString())
        }.getOrNull() ?: return

        val reassembled = fragmenter.onFragmentReceived(fragment) ?: return
        if (address != null) {
            addressToPeerId[address] = reassembled.senderPeerId
        }
        inboundFlow.tryEmit(
            InboundTransportPacket(
                envelope = reassembled.copy(viaTransports = reassembled.viaTransports + TransportType.BLE_MESH),
                sourceTransport = TransportType.BLE_MESH,
                rssiDbm = address?.let { discoveredDevices[it]?.rssi },
                immediateSenderAddress = address
            )
        )
    }

    private suspend fun broadcastPresenceAnnouncement() {
        val local = identityManager.identityState.value
        val now = System.currentTimeMillis()
        val announcePayload = PeerAnnouncementPayload(
            peerId = local.peerId,
            nickname = local.nickname,
            signingPublicKeyHex = local.signingPublicKeyHex,
            encryptionPublicKeyHex = local.encryptionPublicKeyHex,
            rotatingPresenceToken = local.rotatingPresenceId,
            protocolVersion = ProtocolConstants.PROTOCOL_VERSION,
            supportedTransports = setOf(TransportType.BLE_MESH),
            timestampEpochMs = now
        )
        val payloadBytes = json.encodeToString(announcePayload).encodeToByteArray()
        val unsigned = MeshEnvelope(
            packetId = "ann_${local.peerId}_$now",
            packetType = PacketType.PEER_ANNOUNCE,
            senderPeerId = local.peerId,
            senderNickname = local.nickname,
            senderSigningPubKeyHex = local.signingPublicKeyHex,
            senderEncryptionPubKeyHex = local.encryptionPublicKeyHex,
            createdAtEpochMs = now,
            expiresAtEpochMs = now + 180_000L,
            ttl = 2,
            hopCount = 0,
            payloadBase64 = CryptoEngine.encodeBase64(payloadBytes),
            signatureBase64 = "",
            viaTransports = setOf(TransportType.BLE_MESH)
        )
        val signed = identityManager.signEnvelope(unsigned)
        sendPacket(signed, targetPeerId = null)
    }

    override suspend fun sendPacket(envelope: MeshEnvelope, targetPeerId: String?): Result<Unit> = runCatching {
        val fragments = fragmenter.fragmentEnvelope(envelope)
        writeMutex.withLock {
            for (fragment in fragments) {
                val frameBytes = json.encodeToString(fragment).toByteArray(StandardCharsets.UTF_8)

                // Write to connected GATT servers (where we are Central)
                for ((address, gatt) in activeClientGatts) {
                    if (targetPeerId != null && addressToPeerId[address] != null && addressToPeerId[address] != targetPeerId) {
                        continue
                    }
                    val service = gatt.getService(SERVICE_UUID) ?: continue
                    val char = service.getCharacteristic(CHAR_PACKET_UUID) ?: continue
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeCharacteristic(
                                char,
                                frameBytes,
                                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                            @Suppress("DEPRECATION")
                            char.value = frameBytes
                            @Suppress("DEPRECATION")
                            gatt.writeCharacteristic(char)
                        }
                    } catch (_: Exception) {
                    }
                }

                // Notify connected GATT clients (where we are Peripheral)
                val server = gattServer
                val service = server?.getService(SERVICE_UUID)
                val char = service?.getCharacteristic(CHAR_PACKET_UUID)
                if (server != null && char != null) {
                    for ((address, device) in connectedServerDevices) {
                        if (targetPeerId != null && addressToPeerId[address] != null && addressToPeerId[address] != targetPeerId) {
                            continue
                        }
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                server.notifyCharacteristicChanged(device, char, false, frameBytes)
                            } else {
                                @Suppress("DEPRECATION")
                                char.value = frameBytes
                                @Suppress("DEPRECATION")
                                server.notifyCharacteristicChanged(device, char, false)
                            }
                        } catch (_: Exception) {
                        }
                    }
                }

                if (fragments.size > 1) {
                    delay(12L) // Pacing between BLE fragments to prevent GATT congestion drops
                }
            }
        }
    }

    override fun receivePackets(): Flow<InboundTransportPacket> = inboundFlow.asSharedFlow()

    override fun getCapabilities(): TransportCapabilities {
        val hasBleFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        return TransportCapabilities(
            transportType = TransportType.BLE_MESH,
            isSupportedOnDevice = hasBleFeature,
            isEnabled = _adapterEnabled.value && _isRunning.value,
            maxPayloadBytes = ProtocolConstants.MAX_BLE_MEDIA_BYTES,
            supportsMultiHop = true,
            supportsStreamingAudio = true,
            requiresInternet = false
        )
    }

    private fun cleanupStalePeers() {
        val cutoff = System.currentTimeMillis() - PEER_STALE_TIMEOUT_MS
        val iterator = discoveredDevices.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.lastSeenMs < cutoff && !activeClientGatts.containsKey(entry.key)) {
                iterator.remove()
            }
        }
        _discoveredPeersCount.value = discoveredDevices.size
    }

    private fun updateConnectedCount() {
        val uniqueAddresses = (activeClientGatts.keys + connectedServerDevices.keys).toSet()
        _connectedPeersCount.value = uniqueAddresses.size
    }

    private fun closeActiveConnections() {
        activeClientGatts.values.forEach { gatt ->
            try {
                gatt.disconnect()
                gatt.close()
            } catch (_: Exception) {
            }
        }
        activeClientGatts.clear()
        connectedServerDevices.clear()
        updateConnectedCount()
    }

    private fun registerAdapterReceiverIfNeeded() {
        if (!receiverRegistered) {
            try {
                context.registerReceiver(
                    adapterStateReceiver,
                    IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
                )
                receiverRegistered = true
            } catch (_: Exception) {
            }
        }
    }

    private fun unregisterAdapterReceiverIfNeeded() {
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(adapterStateReceiver)
            } catch (_: Exception) {
            }
            receiverRegistered = false
        }
    }
}

