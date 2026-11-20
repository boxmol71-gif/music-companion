package tools.fmc.musiccompanion

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import tools.fmc.musiccompanion.wire.Link
import tools.fmc.musiccompanion.wire.Uuid
import tools.fmc.musiccompanion.wire.sha256Hex

/** The watch's BLE GATT services, owned by this standalone app. */
class BleError(message: String) : Exception(message) {
    companion object {
        val off = BleError("Bluetooth is off.")
        val noWatch = BleError("No CMF Watch was found.")
        val missing = BleError("The watch does not offer the expected services.")
        val dropped = BleError("The watch disconnected.")
    }
}

/** One GATT client for the watch link. Android allows this next to Nothing X's client. */
@SuppressLint("MissingPermission")
class BleLink(private val context: Context) : Link {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private var gatt: BluetoothGatt? = null
    private val characteristics = mutableMapOf<String, BluetoothGattCharacteristic>()
    private val handlers = CopyOnWriteArrayList<(String, ByteArray) -> Unit>()
    private val operations = Mutex()
    private val connection = ConnectionEpoch<BluetoothGatt>()

    private class Step(val operation: Operation, val done: CompletableDeferred<Unit> = CompletableDeferred())
    private enum class Operation { CONNECT, MTU, SERVICES, DESCRIPTOR, WRITE }

    @Volatile private var step: Step? = null
    @Volatile private var dropped = false
    @Volatile private var mtu = 23

    /** Completes as soon as the watch link drops, so a blocked receive can reconnect promptly. */
    val disconnected = CompletableDeferred<Unit>()

    /** A hash of the watch address, used to keep separate watches' pairing keys apart. */
    var watchId: String? = null
        private set

    /**
     * Connects to the approved watch. The first connection uses a connected or bonded watch and then a scan;
     * later connections only accept the previously approved device identity.
     */
    suspend fun connect(scan: Boolean = true, approvedId: String? = null) {
        val adapter = manager?.adapter ?: throw BleError.off
        if (!adapter.isEnabled) throw BleError.off
        fun isWatch(device: BluetoothDevice) = device.name?.startsWith(WATCH_NAME) == true
        fun isApproved(device: BluetoothDevice) = approvedId == null || identityOf(device) == approvedId

        val device = manager.getConnectedDevices(BluetoothProfile.GATT).firstOrNull { isWatch(it) && isApproved(it) }
            ?: adapter.bondedDevices.firstOrNull {
                isWatch(it) && it.type != BluetoothDevice.DEVICE_TYPE_CLASSIC && isApproved(it)
            }
            ?: (if (scan) scan(approvedId) else null)
            ?: throw BleError.noWatch

        watchId = identityOf(device)
        dropped = false
        gattStep(Operation.CONNECT) {
            val connected = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            if (connected == null) {
                false
            } else {
                connection.activate(connected)
                gatt = connected
                true
            }
        }
        val gatt = gatt ?: throw BleError.noWatch
        gattStep(Operation.MTU) { gatt.requestMtu(247) }
        gattStep(Operation.SERVICES) { gatt.discoverServices() }
        for (service in gatt.services) {
            for (characteristic in service.characteristics) {
                val name = characteristic.uuid.toString().uppercase()
                if (name in REQUIRED) characteristics[name] = characteristic
            }
        }
        if (REQUIRED.any { it !in characteristics }) throw BleError.missing
        for (name in NOTIFIES) {
            val characteristic = characteristics[name] ?: continue
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CCCD) ?: continue
            gattStep(Operation.DESCRIPTOR) { writeDescriptor(gatt, descriptor) }
        }
    }

    /** Invalidates the current connection so a late GATT callback cannot complete a later operation. */
    fun invalidate() {
        val current = gatt
        connection.invalidate(current)
        gatt = null
        dropped = true
        step?.let {
            this.step = null
            it.done.completeExceptionally(BleError.dropped)
        }
        disconnected.complete(Unit)
        current?.run {
            disconnect()
            close()
        }
    }

    fun disconnect() = invalidate()

    override fun onNotify(handler: (String, ByteArray) -> Unit) {
        handlers += handler
    }

    /** A frame over MTU-3 is one write with response; Android performs the GATT-level splitting. */
    override suspend fun write(uuid: String, data: ByteArray) = operations.withLock {
        val gatt = gatt?.takeUnless { dropped } ?: throw BleError.dropped
        val characteristic = characteristics[uuid] ?: throw BleError.missing
        val noResponse = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val type = if (data.size > mtu - 3 || !noResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        stepLocked(Operation.WRITE) {
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(characteristic, data, type) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = type
                @Suppress("DEPRECATION")
                characteristic.value = data
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
        }
    }

    private fun writeDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor): Boolean {
        val enabled = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeDescriptor(descriptor, enabled) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = enabled
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    private suspend fun gattStep(operation: Operation, start: () -> Any?) =
        operations.withLock { stepLocked(operation, start) }

    private suspend fun stepLocked(operation: Operation, start: () -> Any?) {
        val step = Step(operation)
        this.step = step
        try {
            if (start() == false) throw BleError("The watch did not accept the request.")
            withTimeout(STEP_TIMEOUT_MS) { step.done.await() }
        } catch (timeout: TimeoutCancellationException) {
            invalidate()
            throw timeout
        } catch (cancellation: CancellationException) {
            invalidate()
            throw cancellation
        } finally {
            if (this.step === step) this.step = null
        }
    }

    private suspend fun scan(approvedId: String?): BluetoothDevice? {
        val scanner = manager?.adapter?.bluetoothLeScanner ?: return null
        val found = CompletableDeferred<BluetoothDevice>()
        val callback = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name
                if (name?.startsWith(WATCH_NAME) == true &&
                    (approvedId == null || identityOf(result.device) == approvedId)
                ) {
                    found.complete(result.device)
                }
            }
        }
        scanner.startScan(callback)
        return try {
            withTimeoutOrNull(SCAN_TIMEOUT_MS) { found.await() }
        } finally {
            scanner.stopScan(callback)
        }
    }

    private fun finish(operation: Operation, status: Int, source: BluetoothGatt) {
        if (!connection.accepts(source)) return
        val step = step?.takeIf { it.operation == operation } ?: return
        if (status == BluetoothGatt.GATT_SUCCESS) {
            step.done.complete(Unit)
        } else {
            step.done.completeExceptionally(BleError("The watch rejected a Bluetooth request."))
        }
    }

    private fun dispatch(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        val name = characteristic.uuid.toString().uppercase()
        handlers.forEach { it(name, value) }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, state: Int) {
            if (!connection.accepts(gatt)) return
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> finish(Operation.CONNECT, BluetoothGatt.GATT_SUCCESS, gatt)
                BluetoothProfile.STATE_DISCONNECTED -> invalidate()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (!connection.accepts(gatt)) return
            if (status == BluetoothGatt.GATT_SUCCESS) this@BleLink.mtu = mtu
            finish(Operation.MTU, status, gatt)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) =
            finish(Operation.SERVICES, status, gatt)

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
            finish(Operation.DESCRIPTOR, status, gatt)

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
            finish(Operation.WRITE, status, gatt)

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (connection.accepts(gatt)) dispatch(characteristic, value)
        }

        @Deprecated("Android 12 and older")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (!connection.accepts(gatt)) return
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) dispatch(characteristic, characteristic.value.copyOf())
        }
    }

    private companion object {
        const val WATCH_NAME = "CMF Watch"
        const val STEP_TIMEOUT_MS = 15_000L
        const val SCAN_TIMEOUT_MS = 10_000L
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val REQUIRED = listOf(Uuid.CMD_WRITE, Uuid.CMD_NOTIFY, Uuid.SHELL_WRITE, Uuid.SHELL_NOTIFY)
        val NOTIFIES = listOf(Uuid.CMD_NOTIFY, Uuid.SHELL_NOTIFY)

        fun identityOf(device: BluetoothDevice): String = sha256Hex(device.address).take(16)
    }
}
