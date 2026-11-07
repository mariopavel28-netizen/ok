package com.example.sw2pad

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque
import java.util.UUID

@SuppressLint("MissingPermission") // los permisos se piden en MainActivity
class Switch2Controller(private val ctx: Context, private val listener: Listener) {

    interface Listener {
        fun onLog(msg: String)
        fun onStatus(msg: String)
        fun onState(state: Sw2.State)
    }

    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var gatt: BluetoothGatt? = null
    private val chars = HashMap<Int, BluetoothGattCharacteristic>()

    // ---- cola de operaciones GATT (Android solo admite una a la vez) ----
    private val queue = ArrayDeque<() -> Unit>()
    private var busy = false
    private val watchdog = Runnable { log("(operación sin confirmar, continúo)"); opDone() }

    @Synchronized private fun enqueue(op: () -> Unit) {
        queue.add(op)
        if (!busy) next()
    }

    @Synchronized private fun next() {
        val op = queue.poll()
        if (op == null) { busy = false; return }
        busy = true
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 1500)
        op()
    }

    @Synchronized private fun opDone() {
        main.removeCallbacks(watchdog)
        busy = false
        next()
    }

    // ---- escaneo ----
    private val scanCb = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) {
            val md = r.scanRecord?.getManufacturerSpecificData(Sw2.MANUFACTURER_ID) ?: return
            adapter?.bluetoothLeScanner?.stopScan(this)
            main.post {
                log("Mando encontrado: ${r.device.name ?: "?"} ${r.device.address}")
                log("Datos de anuncio (contienen el product ID): ${md.hex()}")
                connect(r.device)
            }
        }
        override fun onScanFailed(errorCode: Int) = main.post { status("Fallo de escaneo: $errorCode") }.let {}
    }

    fun startScan() {
        if (adapter == null || !adapter.isEnabled) { status("Activa el Bluetooth"); return }
        status("Buscando… pon el mando en modo sincronización (botón SYNC)")
        adapter.bluetoothLeScanner.startScan(scanCb)
    }

    fun disconnect() {
        adapter?.bluetoothLeScanner?.stopScan(scanCb)
        gatt?.close(); gatt = null
    }

    private fun connect(dev: BluetoothDevice) {
        status("Conectando…")
        gatt = dev.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    // ---- callbacks GATT (llegan en hilos binder; todo se reenvía al hilo principal) ----
    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                main.post { log("Conectado, descubriendo servicios"); g.requestMtu(185) }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                main.post { status("Desconectado (código $st)") }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, st: Int) {
            main.post { log("MTU = $mtu"); g.discoverServices() }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            main.post { onServices(g) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) {
            main.post { opDone() }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            main.post { if (st != BluetoothGatt.GATT_SUCCESS) log("Escritura falló: $st"); opDone() }
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val id = c.instanceId
            main.post { onNotify(id, value) }
        }

        // Android 12 y anteriores
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val id = c.instanceId
            val v = c.value ?: return
            main.post { onNotify(id, v.copyOf()) }
        }
    }

    private fun onServices(g: BluetoothGatt) {
        chars.clear()
        log("--- Volcado GATT (por si los handles no coinciden) ---")
        for (s in g.services) {
            for (c in s.characteristics) {
                log("svc=${s.uuid.toString().take(8)} chr=${c.uuid} id=0x%04X props=0x%X".format(c.instanceId, c.properties))
                if (s.uuid in Sw2.SERVICES) chars[c.instanceId] = c
            }
        }
        val missing = listOf(Sw2.H_INPUT, Sw2.H_COMMAND, Sw2.H_RESPONSE).filter { it !in chars }
        if (missing.isNotEmpty()) {
            status("No encuentro handles ${missing.joinToString { "0x%04X".format(it) }}. Mira el volcado y ajusta Protocol.kt")
            return
        }
        enableNotify(Sw2.H_INPUT)
        enableNotify(Sw2.H_RESPONSE)
        enqueue { startPairing(); opDone() }
    }

    private fun enableNotify(handle: Int) = enqueue {
        val c = chars[handle]; val g = gatt
        val d = c?.getDescriptor(CCCD)
        if (c == null || g == null || d == null) { log("Sin CCCD en 0x%04X".format(handle)); opDone(); return@enqueue }
        g.setCharacteristicNotification(c, true)
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION") run {
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(d)
            }
        }
    }

    private fun write(handle: Int, data: ByteArray) = enqueue {
        val c = chars[handle]; val g = gatt
        if (c == null || g == null) { log("No hay característica 0x%04X".format(handle)); opDone(); return@enqueue }
        val noResp = (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 &&
            (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) == 0
        val type = if (noResp) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                   else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        log("TX 0x%04X: %s".format(handle, data.hex()))
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, data, type) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION") run { c.writeType = type; c.value = data; g.writeCharacteristic(c) }
        }
        if (!ok) { log("writeCharacteristic devolvió error"); opDone() }
    }

    // ---- emparejamiento de aplicación (cmd 0x15) ----
    private var steps: List<ByteArray> = emptyList()
    private var stepIdx = 0
    private var pairing = false
    private val stepTimeout = Runnable { log("Sin respuesta al paso ${stepIdx + 1}, sigo igualmente"); advance() }

    private fun startPairing() {
        if (Sw2.keysMissing) {
            status("Faltan PUBLIC_KEY y CHALLENGE en Protocol.kt (ver README)")
            return
        }
        steps = Sw2.pairingSteps(); stepIdx = 0; pairing = true
        status("Emparejando…")
        sendStep()
    }

    private fun sendStep() {
        write(Sw2.H_COMMAND, steps[stepIdx])
        main.postDelayed(stepTimeout, 1500)
    }

    private fun advance() {
        main.removeCallbacks(stepTimeout)
        stepIdx++
        if (stepIdx < steps.size) sendStep() else { pairing = false; finishInit() }
    }

    private fun finishInit() {
        Sw2.featureCommands(0x2F).forEach { write(Sw2.H_COMMAND, it) }
        // La documentación indica que hace falta ~500 ms tras la máscara de funciones.
        main.postDelayed({ status("Listo: mueve los sticks / pulsa botones") }, 500)
    }

    // ---- notificaciones ----
    private fun onNotify(handle: Int, v: ByteArray) {
        when (handle) {
            Sw2.H_INPUT -> Sw2.parseInput(v)?.let { listener.onState(it) }
            Sw2.H_RESPONSE -> {
                log("RX: ${v.hex()}")
                if (pairing && v.isNotEmpty() && v[0] == 0x15.toByte()) advance()
            }
        }
    }

    private fun log(m: String) = listener.onLog(m)
    private fun status(m: String) = listener.onStatus(m)

    companion object {
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
