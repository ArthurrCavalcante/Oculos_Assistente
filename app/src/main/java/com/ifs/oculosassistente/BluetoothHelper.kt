package com.ifs.oculosassistente
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
class BluetoothHelper(private val adapter: BluetoothAdapter) {
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var socket: BluetoothSocket? = null
    private var entrada: InputStream? = null
    private var saida: OutputStream? = null
    val conectado: Boolean
        get() = socket?.isConnected == true
    fun conectar(nomeDispositivo: String): Boolean {
        val dispositivo = adapter.bondedDevices
            ?.firstOrNull { it.name == nomeDispositivo }
            ?: run {
                Log.e("BT", "Dispositivo '$nomeDispositivo' não encontrado nos pareados.")
                return false
            }
        return try {
            socket = dispositivo.createRfcommSocketToServiceRecord(SPP_UUID)
            socket!!.connect()
            entrada = socket!!.inputStream
            saida   = socket!!.outputStream
            Log.i("BT", "Conectado a ${dispositivo.name}")
            true
        } catch (e: IOException) {
            Log.e("BT", "Erro ao conectar: ${e.message}")
            socket = null
            false
        }
    }
    fun desconectar() {
        try {
            socket?.close()
        } catch (_: IOException) {}
        socket  = null
        entrada = null
        saida   = null
    }
    fun solicitarFoto(): ByteArray? {
        val input  = entrada ?: return null
        val output = saida   ?: return null
        return try {
            output.write("FOTO\n".toByteArray())
            output.flush()
            val linhaSize = lerLinha(input)
            if (!linhaSize.startsWith("SIZE:")) {
                Log.w("BT", "Resposta inesperada: $linhaSize")
                return null
            }
            val tamanho = linhaSize.removePrefix("SIZE:").trim().toInt()
            val buffer = ByteArray(tamanho)
            var lidos = 0
            while (lidos < tamanho) {
                val n = input.read(buffer, lidos, tamanho - lidos)
                if (n == -1) break
                lidos += n
            }
            lerLinha(input)
            if (lidos == tamanho) buffer else null
        } catch (e: Exception) {
            Log.e("BT", "Erro ao receber foto: ${e.message}")
            null
        }
    }
    private fun lerLinha(stream: InputStream): String {
        val sb = StringBuilder()
        var b: Int
        while (stream.read().also { b = it } != -1) {
            if (b == '\n'.code) break
            sb.append(b.toChar())
        }
        return sb.toString().trim()
    }
}