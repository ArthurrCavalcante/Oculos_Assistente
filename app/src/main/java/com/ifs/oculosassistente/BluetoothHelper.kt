package com.ifs.oculosassistente

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Gerencia a comunicação Bluetooth Classic (perfil SPP) com o ESP32.
 *
 * Protocolo idêntico ao código Python original:
 *  → Envia "FOTO\n"
 *  ← Recebe "SIZE:<número>\n"
 *  ← Lê exatamente <número> bytes JPEG
 *  ← Lê linha de confirmação final (descartada)
 *
 * UUID do SPP (Serial Port Profile) — valor padrão Bluetooth, igual em todos os dispositivos.
 */
class BluetoothHelper(private val adapter: BluetoothAdapter) {

    // UUID padrão do perfil SPP — não alterar
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var socket: BluetoothSocket? = null
    private var entrada: InputStream? = null
    private var saida: OutputStream? = null

    /** Verdadeiro enquanto o socket estiver aberto e streams disponíveis. */
    val conectado: Boolean
        get() = socket?.isConnected == true

    // ──────────────────────────────────────────────────────────────────────
    // Conexão
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Procura nos dispositivos Bluetooth já pareados aquele cujo nome
     * corresponde a [nomeDispositivo] e abre um socket SPP.
     *
     * DEVE ser chamado em uma thread de background (não na UI thread).
     *
     * @return true se conectou com sucesso, false caso contrário.
     */
    fun conectar(nomeDispositivo: String): Boolean {
        // Busca o dispositivo pelo nome na lista de pareados
        val dispositivo = adapter.bondedDevices
            ?.firstOrNull { it.name == nomeDispositivo }
            ?: run {
                Log.e("BT", "Dispositivo '$nomeDispositivo' não encontrado nos pareados.")
                return false
            }

        return try {
            // Cria socket SPP e conecta (operação bloqueante)
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

    /** Fecha o socket e limpa os recursos. */
    fun desconectar() {
        try {
            socket?.close()
        } catch (_: IOException) {}
        socket  = null
        entrada = null
        saida   = null
    }

    // ──────────────────────────────────────────────────────────────────────
    // Protocolo de foto
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Executa uma requisição completa de foto:
     *  1. Envia "FOTO\n"
     *  2. Lê "SIZE:<n>\n" e extrai o tamanho
     *  3. Lê exatamente <n> bytes do JPEG
     *  4. Descarta a linha de confirmação final
     *
     * @return ByteArray com os bytes JPEG, ou null em caso de erro.
     */
    fun solicitarFoto(): ByteArray? {
        val input  = entrada ?: return null
        val output = saida   ?: return null

        return try {
            // 1. Envia comando
            output.write("FOTO\n".toByteArray())
            output.flush()

            // 2. Lê linha "SIZE:<n>"
            val linhaSize = lerLinha(input)
            if (!linhaSize.startsWith("SIZE:")) {
                Log.w("BT", "Resposta inesperada: $linhaSize")
                return null
            }
            val tamanho = linhaSize.removePrefix("SIZE:").trim().toInt()

            // 3. Lê os bytes do JPEG em blocos de 4 KB
            val buffer = ByteArray(tamanho)
            var lidos = 0
            while (lidos < tamanho) {
                val n = input.read(buffer, lidos, tamanho - lidos)
                if (n == -1) break   // stream encerrada inesperadamente
                lidos += n
            }

            // 4. Descarta linha de confirmação final
            lerLinha(input)

            if (lidos == tamanho) buffer else null

        } catch (e: Exception) {
            Log.e("BT", "Erro ao receber foto: ${e.message}")
            null
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Utilitário interno
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Lê bytes de [stream] até encontrar '\n' e retorna a linha como String.
     * Equivale ao readline() do Python serial.
     */
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
