package com.example.sw2pad

import java.util.UUID

/**
 * Protocolo BLE de los mandos de Switch 2 (Joy-Con 2, Pro Controller 2, GameCube NSO).
 * Fuente: documentación por ingeniería inversa de bit-axis-labs y BlueRetro (Jacques Gagnon).
 */
object Sw2 {
    const val MANUFACTURER_ID = 0x0553

    val SERVICES: List<UUID> = listOf(
        UUID.fromString("00c5af5d-1964-4e30-8f51-1956f96bd280"),
        UUID.fromString("ab7de9be-89fe-49ad-828f-118f09df7fd0"),
    )

    // La documentación identifica las características por "handle", no por UUID.
    // En Android usamos BluetoothGattCharacteristic.instanceId (normalmente coincide con el handle).
    const val H_INPUT = 0x000A     // reportes de entrada (notify, 63 bytes)
    const val H_COMMAND = 0x0014   // comandos: init, pairing, features
    const val H_RESPONSE = 0x001A  // respuestas / ACK (notify)

    // Dirección "anfitrión" falsa: el mando no la valida contra la MAC real.
    val HOST_ADDR = byteArrayOf(
        0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(),
        0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte()
    )

    // ====== RELLENAR A MANO ======
    // La documentación dice que estos 16 bytes están en el firmware de BlueRetro
    // (github.com/darthcloud/BlueRetro) pero no los reproduce. Copia aquí:
    //  - la clave pública fija (paso 2)
    //  - el "challenge" de 16 bytes (paso 3)
    val PUBLIC_KEY = ByteArray(16)
    val CHALLENGE = ByteArray(16)

    val keysMissing: Boolean
        get() = PUBLIC_KEY.all { it == 0.toByte() } || CHALLENGE.all { it == 0.toByte() }

    /** Cabecera de 8 bytes: [cmd][0x91][0x01][sub][0x00][len][0x00][0x00][datos...] */
    fun command(cmd: Int, sub: Int, data: ByteArray): ByteArray {
        val out = ByteArray(8 + data.size)
        out[0] = cmd.toByte()
        out[1] = 0x91.toByte()
        out[2] = 0x01
        out[3] = sub.toByte()
        out[4] = 0x00
        out[5] = data.size.toByte()
        data.copyInto(out, 8)
        return out
    }

    /** Los 4 pasos del emparejamiento de aplicación (comando 0x15). */
    fun pairingSteps(): List<ByteArray> {
        val addr2 = HOST_ADDR.copyOf().also { it[0] = (it[0] - 1).toByte() }
        return listOf(
            command(0x15, 0x01, byteArrayOf(0x00, 0x02) + HOST_ADDR + addr2), // direcciones
            command(0x15, 0x04, byteArrayOf(0x00) + PUBLIC_KEY),              // clave pública
            command(0x15, 0x02, byteArrayOf(0x00) + CHALLENGE),               // confirmar LTK
            command(0x15, 0x03, byteArrayOf(0x00)),                           // finalizar
        )
    }

    /**
     * Activar funciones de entrada (cmd 0x0C). Pro Controller 2 = 0x2F.
     * AVISO: el valor 0x2F y que va a H_COMMAND están documentados, pero el formato exacto
     * (subcomandos 0x02/0x04 + máscara de 4 bytes LE) es una SUPOSICIÓN mía sin verificar.
     * Si no llegan reportes de entrada, revisa esto primero.
     */
    fun featureCommands(mask: Int = 0x2F): List<ByteArray> {
        val m = byteArrayOf(mask.toByte(), 0, 0, 0)
        return listOf(
            command(0x0C, 0x02, m),
            command(0x0C, 0x04, m),
        )
    }

    // ---------- Reporte de entrada (63 bytes) ----------

    private val BUTTONS = listOf(
        31 to "ZL", 30 to "L", 29 to "SL(L)", 28 to "SR(L)",
        27 to "Izq", 26 to "Der", 25 to "Arriba", 24 to "Abajo",
        22 to "C", 21 to "Captura", 20 to "Home", 19 to "L3", 18 to "R3",
        17 to "+", 16 to "-",
        15 to "ZR", 14 to "R", 13 to "SL(R)", 12 to "SR(R)",
        11 to "A", 10 to "B", 9 to "X", 8 to "Y",
    )

    data class Stick(val rawX: Int, val rawY: Int) {
        // Sin calibración: neutro 2048, recorrido ~1610 en el Pro Controller 2.
        val x: Float get() = ((rawX - 2048) / 1610f).coerceIn(-1f, 1f)
        val y: Float get() = ((rawY - 2048) / 1610f).coerceIn(-1f, 1f)
    }

    data class State(
        val buttons: Long,
        val left: Stick,
        val right: Stick,
        val batteryMv: Int?,
        val charging: Boolean?,
    ) {
        val pressed: List<String> get() = BUTTONS.filter { (buttons shr it.first) and 1L == 1L }.map { it.second }
    }

    fun parseInput(d: ByteArray): State? {
        if (d.size < 0x10) return null
        fun u(i: Int) = d[i].toInt() and 0xFF
        val buttons = u(3).toLong() or (u(4).toLong() shl 8) or (u(5).toLong() shl 16) or (u(6).toLong() shl 24)
        fun stick(o: Int) = Stick(
            rawX = u(o) or ((u(o + 1) and 0x0F) shl 8),
            rawY = (u(o + 1) shr 4) or (u(o + 2) shl 4),
        )
        val batt = if (d.size > 0x21) u(0x1F) or (u(0x20) shl 8) else null
        val chg = if (d.size > 0x21) u(0x21) > 0 else null
        return State(buttons, stick(0x0A), stick(0x0D), batt, chg)
    }
}

fun ByteArray.hex(): String = joinToString(" ") { "%02X".format(it) }
