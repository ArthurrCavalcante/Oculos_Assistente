package com.ifs.oculosassistente

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Activity principal do aplicativo.
 *
 * Responsabilidades:
 *  - Solicitar permissões de Bluetooth em tempo de execução
 *  - Conectar ao ESP32 via Bluetooth Classic (SPP)
 *  - Receber frames JPEG e exibi-los na tela
 *  - Passar cada frame para o DetectorRostos
 *  - Reproduzir alertas de voz via Android TTS
 *
 * Fluxo resumido:
 *  [Usuário toca "Conectar"] → BluetoothHelper busca "Oculos-Inteligente-BT"
 *      → loop: solicita foto → decodifica JPEG → detecta rostos → atualiza UI
 */
class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    // ── Componentes de UI ──────────────────────────────────────────────────
    private lateinit var imgView: ImageView       // exibe o frame atual
    private lateinit var tvStatus: TextView       // linha de status (conectado / erro)
    private lateinit var tvPessoas: TextView      // contagem de rostos detectados
    private lateinit var btnConectar: Button      // botão de conectar/desconectar

    // ── Módulos de negócio ─────────────────────────────────────────────────
    private lateinit var bluetoothHelper: BluetoothHelper   // comunicação BT com ESP32
    private lateinit var detector: DetectorRostos           // YOLOv8 + lógica de alertas
    private lateinit var tts: TextToSpeech                  // síntese de voz Android nativa

    // Flag: TTS pronto para uso
    private var ttsReady = false

    // Código de requisição de permissões (valor arbitrário ≥ 0)
    private val REQUEST_PERMISSIONS = 1

    // ── Permissões necessárias (variam conforme API level) ─────────────────
    private val permissoesNecessarias: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: permissões granulares de Bluetooth
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            // Android ≤ 11: permissão de localização usada para descoberta BT
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    // ──────────────────────────────────────────────────────────────────────
    // Ciclo de vida
    // ──────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Vincula widgets ao layout
        imgView    = findViewById(R.id.imgView)
        tvStatus   = findViewById(R.id.tvStatus)
        tvPessoas  = findViewById(R.id.tvPessoas)
        btnConectar = findViewById(R.id.btnConectar)

        // Inicializa o TTS com idioma português
        tts = TextToSpeech(this, this)

        // Inicializa o detector (carrega modelo TFLite de assets/)
        detector = DetectorRostos(assets) { mensagem ->
            // Callback chamado sempre que um alerta deve ser falado
            falar(mensagem)
        }

        // Obtém o adaptador Bluetooth do sistema
        val btManager = getSystemService(BluetoothManager::class.java)
        bluetoothHelper = BluetoothHelper(btManager.adapter)

        btnConectar.setOnClickListener { onBotaoConectarClicado() }

        // Pede as permissões já na abertura do app
        pedirPermissoes()
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothHelper.desconectar()   // fecha socket BT
        detector.fechar()               // libera intérprete TFLite
        tts.stop()
        tts.shutdown()
    }

    // ──────────────────────────────────────────────────────────────────────
    // TextToSpeech.OnInitListener
    // ──────────────────────────────────────────────────────────────────────

    /** Chamado quando o motor TTS termina de inicializar. */
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("pt", "BR")
            ttsReady = true
        } else {
            Log.e("TTS", "Falha ao inicializar TextToSpeech")
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Permissões
    // ──────────────────────────────────────────────────────────────────────

    private fun pedirPermissoes() {
        val faltando = permissoesNecessarias.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (faltando.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, faltando.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            val negadas = grantResults.filter { it != PackageManager.PERMISSION_GRANTED }
            if (negadas.isNotEmpty()) {
                tvStatus.text = "Permissões negadas — Bluetooth não funcionará."
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Controle de conexão
    // ──────────────────────────────────────────────────────────────────────

    private fun onBotaoConectarClicado() {
        if (bluetoothHelper.conectado) {
            // Desconecta se já estava conectado
            bluetoothHelper.desconectar()
            btnConectar.text = "Conectar"
            tvStatus.text = "Desconectado"
        } else {
            // Tenta conectar ao dispositivo chamado "Oculos-Inteligente-BT"
            tvStatus.text = "Conectando..."
            Thread {
                val ok = bluetoothHelper.conectar("Oculos-Inteligente-BT")
                runOnUiThread {
                    if (ok) {
                        tvStatus.text = "Conectado!"
                        btnConectar.text = "Desconectar"
                        iniciarLoopCaptura()   // começa a receber frames
                    } else {
                        tvStatus.text = "Falha — dispositivo não encontrado"
                    }
                }
            }.start()
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Loop de captura (thread separada para não travar a UI)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Roda em background enquanto estiver conectado.
     * A cada iteração:
     *   1. Solicita JPEG à ESP32
     *   2. Decodifica para Bitmap
     *   3. Envia ao detector (que dispara alertas internamente)
     *   4. Atualiza UI na thread principal
     */
    private fun iniciarLoopCaptura() {
        Thread {
            while (bluetoothHelper.conectado) {
                val jpegBytes = bluetoothHelper.solicitarFoto()

                if (jpegBytes != null) {
                    val bitmap: Bitmap? = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)

                    if (bitmap != null) {
                        // Detecção — retorna bitmap anotado e contagem de pessoas
                        val (bitmapAnotado, qtdPessoas) = detector.processar(bitmap)

                        runOnUiThread {
                            imgView.setImageBitmap(bitmapAnotado)
                            tvPessoas.text = "Pessoas detectadas: $qtdPessoas"
                        }
                    }
                }

                // Aguarda 1 segundo antes do próximo frame (igual ao Python original)
                Thread.sleep(1000)
            }

            runOnUiThread {
                tvStatus.text = "Conexão encerrada"
                btnConectar.text = "Conectar"
            }
        }.start()
    }

    // ──────────────────────────────────────────────────────────────────────
    // Áudio
    // ──────────────────────────────────────────────────────────────────────

    /** Fala um texto via TTS. Pode ser chamado de qualquer thread. */
    private fun falar(texto: String) {
        if (ttsReady) {
            // QUEUE_FLUSH interrompe fala anterior antes de começar a nova
            tts.speak(texto, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }
}
