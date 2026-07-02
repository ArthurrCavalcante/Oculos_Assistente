package com.ifs.oculosassistente
import android.content.res.AssetManager
import android.graphics.*
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
class DetectorRostos(
    assets: AssetManager,
    private val onAlerta: (String) -> Unit
) {
    private val TAMANHO_ENTRADA = 640
    private val CONFIANCA_MINIMA = 0.3f
    private val NOME_MODELO = "yolov8n-face.tflite"
    private val LARGURA_REAL_ROSTO = 0.15f
    private val FOCAL_LENGTH = 280f
    private val LIMITE_ESQUERDO = 1f / 3f
    private val LIMITE_DIREITO  = 2f / 3f
    private val FRAMES_HISTORICO = 4
    private val TIMER_TIPO_1 = 15_000L
    private val TIMER_TIPO_2 = 5_000L
    private val DIST_TIPO_3 = 2.5f
    private val historico = ArrayDeque<List<Pessoa>>()
    private var ultimoTipo1 = 0L
    private var ultimoTipo2 = 0L
    private var tipo3Disparado = false
    private val interpreter: Interpreter
    private val inputBuffer = ByteBuffer.allocateDirect(1 * TAMANHO_ENTRADA * TAMANHO_ENTRADA * 3 * 4).apply {
        order(ByteOrder.nativeOrder())
    }
    private val outputBuffer = Array(1) { Array(5) { FloatArray(8400) } }
    init {
        interpreter = Interpreter(carregarModelo(assets, NOME_MODELO))
    }
    data class Pessoa(
        val bbox: RectF,
        val confianca: Float,
        val distancia: Float
    )
    fun processar(frame: Bitmap): Pair<Bitmap, String> {
        val redim = Bitmap.createScaledBitmap(frame, TAMANHO_ENTRADA, TAMANHO_ENTRADA, true)
        val (pessoas, debugText) = detectar(redim)
        verificarAlertas(pessoas, TAMANHO_ENTRADA)
        val anotado = desenharAnotacoes(redim.copy(Bitmap.Config.ARGB_8888, true), pessoas, TAMANHO_ENTRADA)
        return Pair(anotado, debugText)
    }
    fun fechar() {
        interpreter.close()
    }
    private fun detectar(frame: Bitmap): Pair<List<Pessoa>, String> {
        inputBuffer.rewind()
        for (y in 0 until TAMANHO_ENTRADA) {
            for (x in 0 until TAMANHO_ENTRADA) {
                val pixel = frame.getPixel(x, y)
                inputBuffer.putFloat(Color.red(pixel)   / 255f)
                inputBuffer.putFloat(Color.green(pixel) / 255f)
                inputBuffer.putFloat(Color.blue(pixel)  / 255f)
            }
        }
        inputBuffer.rewind()
        interpreter.run(inputBuffer, outputBuffer)
        val pessoas = mutableListOf<Pessoa>()
        val escalaX = TAMANHO_ENTRADA.toFloat()
        val escalaY = TAMANHO_ENTRADA.toFloat()
        var maxScore = 0f
        var count10 = 0
        var count30 = 0
        var count50 = 0
        for (i in 0 until 8400) {
            val conf = outputBuffer[0][4][i]
            if (conf > maxScore) maxScore = conf
            if (conf > 0.1f) count10++
            if (conf > 0.3f) count30++
            if (conf > 0.5f) count50++
            if (conf < CONFIANCA_MINIMA) continue
            val cx = outputBuffer[0][0][i] * escalaX
            val cy = outputBuffer[0][1][i] * escalaY
            val w  = outputBuffer[0][2][i] * escalaX
            val h  = outputBuffer[0][3][i] * escalaY
            val bbox = RectF(cx - w/2, cy - h/2, cx + w/2, cy + h/2)
            val distancia = calcularDistancia(w)
            pessoas.add(Pessoa(bbox, conf, distancia))
        }
        val debugLines = StringBuilder()
        debugLines.append("Max:${"%.4f".format(maxScore)}|>0.1:$count10|>0.3:$count30|>0.5:$count50\n")
        val pessoasNMS = nms(pessoas)
        debugLines.append("NMS: ${pessoasNMS.size}\n")
        if (pessoasNMS.isNotEmpty()) {
            val p = pessoasNMS[0]
            debugLines.append("Conf:${"%.2f".format(p.confianca)} R:[${p.bbox.left.toInt()},${p.bbox.top.toInt()},${p.bbox.right.toInt()},${p.bbox.bottom.toInt()}]")
        }
        return Pair(pessoasNMS, debugLines.toString())
    }
    private fun calcularDistancia(larguraPx: Float): Float {
        if (larguraPx <= 0f) return 99f
        return (FOCAL_LENGTH * LARGURA_REAL_ROSTO) / larguraPx
    }
    private fun verificarAlertas(pessoas: List<Pessoa>, larguraFrame: Int) {
        historico.addLast(pessoas)
        if (historico.size > FRAMES_HISTORICO) historico.removeFirst()
        if (historico.size < FRAMES_HISTORICO) return
        val agora = System.currentTimeMillis()
        if (pessoas.size == 1) {
            val p = pessoas[0]
            if (p.distancia <= DIST_TIPO_3) {
                val distancias = historico.mapNotNull { h ->
                    if (h.size == 1) h[0].distancia else null
                }
                if (distancias.size == FRAMES_HISTORICO) {
                    val variacao = distancias.max() - distancias.min()
                    if (variacao < 0.3f && !tipo3Disparado) {
                        tipo3Disparado = true
                        onAlerta("Pessoa parada detectada. Solicitando descrição.")
                        return
                    }
                }
            }
        } else {
            tipo3Disparado = false
        }
        if (pessoas.size == 1) {
            val p = pessoas[0]
            if (pesssoaNaAreaCentral(p.bbox, larguraFrame)) {
                val distancias = historico.mapNotNull { h ->
                    if (h.size == 1) h[0].distancia else null
                }
                if (distancias.size == FRAMES_HISTORICO) {
                    val seAproximando = distancias.zipWithNext().all { (a, b) -> a > b }
                    if (seAproximando && (agora - ultimoTipo2) > TIMER_TIPO_2) {
                        ultimoTipo2 = agora
                        onAlerta("Atenção, pessoa se aproximando.")
                        return
                    }
                }
            } else {
                ultimoTipo2 = 0L
            }
        }
        if (pessoas.size > 1) {
            val todasMultiplas = historico.all { it.size > 1 }
            if (todasMultiplas && (agora - ultimoTipo1) > TIMER_TIPO_1) {
                ultimoTipo1 = agora
                onAlerta("${pessoas.size} pessoas detectadas ao redor.")
            }
        }
    }
    private fun pesssoaNaAreaCentral(bbox: RectF, larguraFrame: Int): Boolean {
        val limEsqPx = larguraFrame * LIMITE_ESQUERDO
        val limDirPx = larguraFrame * LIMITE_DIREITO
        return bbox.left < limDirPx && bbox.right > limEsqPx
    }
    private fun desenharAnotacoes(bmp: Bitmap, pessoas: List<Pessoa>, larguraFrame: Int): Bitmap {
        val canvas = Canvas(bmp)
        val paintVerde = Paint().apply {
            color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 8f
        }
        val paintVermelho = Paint().apply {
            color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 8f
        }
        val paintTexto = Paint().apply {
            color = Color.WHITE; textSize = 48f; isFakeBoldText = true
        }
        val paintZona = Paint().apply {
            color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 4f
        }
        val xEsq = larguraFrame * LIMITE_ESQUERDO
        val xDir = larguraFrame * LIMITE_DIREITO
        canvas.drawLine(xEsq, 0f, xEsq, bmp.height.toFloat(), paintZona)
        canvas.drawLine(xDir, 0f, xDir, bmp.height.toFloat(), paintZona)
        for (p in pessoas) {
            android.util.Log.d("DetectorRostos", "Desenhando bbox: RectF(${p.bbox.left}, ${p.bbox.top}, ${p.bbox.right}, ${p.bbox.bottom}) no bitmap ${bmp.width}x${bmp.height}")
            val paint = if (p.distancia > 1.5f) paintVerde else paintVermelho
            canvas.drawRect(p.bbox, paint)
            canvas.drawText(
                "${"%.0f".format(p.confianca * 100)}% | ${"%.1f".format(p.distancia)}m",
                p.bbox.left, p.bbox.top - 10f, paintTexto
            )
        }
        canvas.drawText("Pessoas: ${pessoas.size}", 20f, 50f, paintTexto.apply { textSize = 40f })
        return bmp
    }
    private fun nms(deteccoes: List<Pessoa>, limiarIou: Float = 0.5f): List<Pessoa> {
        val ordenadas = deteccoes.sortedByDescending { it.confianca }.toMutableList()
        val mantidas = mutableListOf<Pessoa>()
        while (ordenadas.isNotEmpty()) {
            val melhor = ordenadas.removeFirst()
            mantidas.add(melhor)
            ordenadas.removeAll { calcularIou(melhor.bbox, it.bbox) > limiarIou }
        }
        return mantidas
    }
    private fun calcularIou(a: RectF, b: RectF): Float {
        val interLeft   = maxOf(a.left,   b.left)
        val interTop    = maxOf(a.top,    b.top)
        val interRight  = minOf(a.right,  b.right)
        val interBottom = minOf(a.bottom, b.bottom)
        val interArea = maxOf(0f, interRight - interLeft) * maxOf(0f, interBottom - interTop)
        if (interArea == 0f) return 0f
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        return interArea / (areaA + areaB - interArea)
    }
    private fun carregarModelo(assets: AssetManager, nome: String): MappedByteBuffer {
        val fd = assets.openFd(nome)
        return FileInputStream(fd.fileDescriptor).channel.map(
            FileChannel.MapMode.READ_ONLY,
            fd.startOffset,
            fd.declaredLength
        )
    }
}