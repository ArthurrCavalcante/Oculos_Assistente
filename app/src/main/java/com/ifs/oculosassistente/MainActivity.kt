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
class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var imgView: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var tvPessoas: TextView
    private lateinit var btnConectar: Button
    private lateinit var bluetoothHelper: BluetoothHelper
    private lateinit var detector: DetectorRostos
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private val REQUEST_PERMISSIONS = 1
    private val permissoesNecessarias: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        imgView    = findViewById(R.id.imgView)
        tvStatus   = findViewById(R.id.tvStatus)
        tvPessoas  = findViewById(R.id.tvPessoas)
        btnConectar = findViewById(R.id.btnConectar)
        tts = TextToSpeech(this, this)
        detector = DetectorRostos(assets) { mensagem ->
            falar(mensagem)
        }
        val btManager = getSystemService(BluetoothManager::class.java)
        bluetoothHelper = BluetoothHelper(btManager.adapter)
        btnConectar.setOnClickListener { onBotaoConectarClicado() }
        pedirPermissoes()
    }
    override fun onDestroy() {
        super.onDestroy()
        bluetoothHelper.desconectar()
        detector.fechar()
        tts.stop()
        tts.shutdown()
    }
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("pt", "BR")
            ttsReady = true
        } else {
            Log.e("TTS", "Falha ao inicializar TextToSpeech")
        }
    }
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
    private fun onBotaoConectarClicado() {
        if (bluetoothHelper.conectado) {
            bluetoothHelper.desconectar()
            btnConectar.text = "Conectar"
            tvStatus.text = "Desconectado"
        } else {
            tvStatus.text = "Conectando..."
            Thread {
                val ok = bluetoothHelper.conectar("Oculos-Inteligente-BT")
                runOnUiThread {
                    if (ok) {
                        tvStatus.text = "Conectado!"
                        btnConectar.text = "Desconectar"
                        iniciarLoopCaptura()
                    } else {
                        tvStatus.text = "Falha — dispositivo não encontrado"
                    }
                }
            }.start()
        }
    }
    private fun iniciarLoopCaptura() {
        Thread {
            while (bluetoothHelper.conectado) {
                val jpegBytes = bluetoothHelper.solicitarFoto()
                if (jpegBytes != null) {
                    val bitmap: Bitmap? = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                    if (bitmap != null) {
                        val (bitmapAnotado, debugText) = detector.processar(bitmap)
                        runOnUiThread {
                            imgView.setImageBitmap(bitmapAnotado)
                            tvPessoas.text = debugText
                        }
                    }
                }
                Thread.sleep(200)
            }
            runOnUiThread {
                tvStatus.text = "Conexão encerrada"
                btnConectar.text = "Conectar"
            }
        }.start()
    }
    private fun falar(texto: String) {
        if (ttsReady) {
            tts.speak(texto, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }
}