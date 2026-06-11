package com.ifs.oculosassistente

import android.content.res.AssetManager
import android.graphics.*
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Encapsula a detecção de rostos com YOLOv8 (via TFLite)
 * e toda a lógica de alertas portada do código Python original.
 *
 * ─────────────────────────────────────────────────────────────
 * Como usar:
 *   1. Coloque o arquivo "yolov8n-face.tflite" em app/src/main/assets/
 *   2. Instancie passando o AssetManager e um callback de alerta
 *   3. Chame processar(bitmap) a cada frame recebido
 * ─────────────────────────────────────────────────────────────
 *
 * @param assets     AssetManager para carregar o modelo TFLite
 * @param onAlerta   Callback chamado com a mensagem de voz quando um
 *                   alerta deve ser disparado
 */
class DetectorRostos(
    assets: AssetManager,
    private val onAlerta: (String) -> Unit
) {

    // ── Configurações do modelo ────────────────────────────────────────────
    private val TAMANHO_ENTRADA = 640        // YOLOv8 espera 640×640
    private val CONFIANCA_MINIMA = 0.5f     // descarta detecções abaixo disso
    private val NOME_MODELO = "yolov8n-face.tflite"

    // ── Calibração de distância (mesmos valores do Python) ────────────────
    // Distância estimada via fórmula: d = (focal * largura_real) / largura_px
    private val LARGURA_REAL_ROSTO = 0.15f  // metros (rosto adulto médio)
    private val FOCAL_LENGTH = (8f * 2.0f) / LARGURA_REAL_ROSTO  // f = (px_calib * dist_calib) / largura_real

    // ── Zonas horizontais (frações da largura do frame) ───────────────────
    private val LIMITE_ESQUERDO = 1f / 3f
    private val LIMITE_DIREITO  = 2f / 3f

    // ── Parâmetros dos alertas ─────────────────────────────────────────────
    private val FRAMES_HISTORICO = 6          // quantos frames guardar no histórico
    private val TIMER_TIPO_1 = 15_000L        // ms entre alertas de múltiplas pessoas
    private val TIMER_TIPO_2 = 5_000L         // ms entre alertas de aproximação
    private val DIST_TIPO_3 = 2.5f            // metros — aciona descrição de pessoa parada

    // ── Estado interno dos alertas (equivalente à classe SistemaAlertas) ──
    private val historico = ArrayDeque<List<Pessoa>>()   // últimos N frames
    private var ultimoTipo1 = 0L              // timestamp do último alerta tipo 1
    private var ultimoTipo2 = 0L              // timestamp do último alerta tipo 2
    private var tipo3Disparado = false        // evita repetição do tipo 3

    // ── TFLite ────────────────────────────────────────────────────────────
    private val interpreter: Interpreter

    // ── Buffers pré-alocados para performance (evita GC churn a cada frame) ──
    private val inputBuffer = ByteBuffer.allocateDirect(1 * TAMANHO_ENTRADA * TAMANHO_ENTRADA * 3 * 4).apply { 
        order(ByteOrder.nativeOrder()) 
    }
    private val outputBuffer = Array(1) { Array(5) { FloatArray(8400) } }

    init {
        // Carrega o modelo da pasta assets/ uma única vez
        interpreter = Interpreter(carregarModelo(assets, NOME_MODELO))
    }

    // ──────────────────────────────────────────────────────────────────────
    // Estrutura de dados
    // ──────────────────────────────────────────────────────────────────────

    /** Representa um rosto detectado em um frame. */
    data class Pessoa(
        val bbox: RectF,        // bounding box em coordenadas do frame original
        val confianca: Float,   // probabilidade [0, 1]
        val distancia: Float    // distância estimada em metros
    )

    // ──────────────────────────────────────────────────────────────────────
    // API pública
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Processa um frame:
     *  1. Redimensiona para 640×640 e normaliza para Float32
     *  2. Roda inferência YOLOv8
     *  3. Decodifica saída e estima distâncias
     *  4. Verifica e dispara alertas de voz
     *  5. Desenha anotações no bitmap
     *
     * @return Par (Bitmap anotado, número de pessoas detectadas)
     */
    fun processar(frame: Bitmap): Pair<Bitmap, Int> {
        val pessoas = detectar(frame)
        verificarAlertas(pessoas, frame.width)

        val anotado = desenharAnotacoes(frame.copy(Bitmap.Config.ARGB_8888, true), pessoas, frame.width)
        return Pair(anotado, pessoas.size)
    }

    /** Libera o intérprete TFLite ao encerrar o app. */
    fun fechar() {
        interpreter.close()
    }

    // ──────────────────────────────────────────────────────────────────────
    // Detecção (YOLOv8 TFLite)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Prepara o tensor de entrada, roda o modelo e decodifica as detecções.
     *
     * Formato de saída padrão do YOLOv8 exportado para TFLite:
     *   shape [1, 5, 8400]  → 5 valores (cx, cy, w, h, conf) × 8400 ancoragens
     */
    private fun detectar(frame: Bitmap): List<Pessoa> {
        // 1. Redimensiona para 640×640
        val redim = Bitmap.createScaledBitmap(frame, TAMANHO_ENTRADA, TAMANHO_ENTRADA, true)

        // 2. Converte para ByteBuffer Float32 RGB normalizado [0, 1]
        inputBuffer.rewind() // Volta o ponteiro para o início do buffer

        for (y in 0 until TAMANHO_ENTRADA) {
            for (x in 0 until TAMANHO_ENTRADA) {
                val pixel = redim.getPixel(x, y)
                inputBuffer.putFloat(Color.red(pixel)   / 255f)
                inputBuffer.putFloat(Color.green(pixel) / 255f)
                inputBuffer.putFloat(Color.blue(pixel)  / 255f)
            }
        }

        // 3. Tensor de saída [1, 5, 8400]
        interpreter.run(inputBuffer, outputBuffer)

        // 4. Decodifica detecções válidas
        val pessoas = mutableListOf<Pessoa>()
        val escalaX = frame.width.toFloat()  / TAMANHO_ENTRADA
        val escalaY = frame.height.toFloat() / TAMANHO_ENTRADA

        for (i in 0 until 8400) {
            val conf = outputBuffer[0][4][i]
            if (conf < CONFIANCA_MINIMA) continue

            // YOLOv8 retorna cx, cy, w, h (em coordenadas 640×640)
            val cx = outputBuffer[0][0][i] * escalaX
            val cy = outputBuffer[0][1][i] * escalaY
            val w  = outputBuffer[0][2][i] * escalaX
            val h  = outputBuffer[0][3][i] * escalaY

            val bbox = RectF(cx - w/2, cy - h/2, cx + w/2, cy + h/2)
            val distancia = calcularDistancia(w)

            pessoas.add(Pessoa(bbox, conf, distancia))
        }

        // NMS simples: remove bboxes sobrepostos (IoU > 0.5)
        return nms(pessoas)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Estimativa de distância
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Mesma fórmula do Python:
     *   distância = (focal * largura_real) / largura_pixels
     */
    private fun calcularDistancia(larguraPx: Float): Float {
        if (larguraPx <= 0f) return 99f   // evita divisão por zero
        return (FOCAL_LENGTH * LARGURA_REAL_ROSTO) / larguraPx
    }

    // ──────────────────────────────────────────────────────────────────────
    // Lógica de alertas (port 1:1 do Python SistemaAlertas)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Atualiza o histórico e verifica qual alerta disparar.
     * Prioridade: Tipo 3 > Tipo 2 > Tipo 1
     */
    private fun verificarAlertas(pessoas: List<Pessoa>, larguraFrame: Int) {
        historico.addLast(pessoas)
        if (historico.size > FRAMES_HISTORICO) historico.removeFirst()

        // Aguarda histórico completo antes de avaliar
        if (historico.size < FRAMES_HISTORICO) return

        val agora = System.currentTimeMillis()

        // ── Tipo 3 (prioridade máxima): pessoa parada perto ─────────────
        // Condição: exatamente 1 pessoa, distância ≤ 2,5 m,
        //           distância estável nos últimos N frames (variação < 0,3 m)
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
                        // Ponto de integração futura com Gemini Vision
                        return
                    }
                }
            }
        } else {
            tipo3Disparado = false   // reseta se não há exatamente 1 pessoa
        }

        // ── Tipo 2: pessoa se aproximando pela área central ──────────────
        // Condição: 1 pessoa no centro do frame, distância decrescendo
        //           nos últimos N frames consecutivos
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
                ultimoTipo2 = 0L   // reseta timer se saiu da área central
            }
        }

        // ── Tipo 1: múltiplas pessoas ao redor ───────────────────────────
        // Condição: > 1 pessoa em todos os frames do histórico
        if (pessoas.size > 1) {
            val todasMultiplas = historico.all { it.size > 1 }
            if (todasMultiplas && (agora - ultimoTipo1) > TIMER_TIPO_1) {
                ultimoTipo1 = agora
                onAlerta("${pessoas.size} pessoas detectadas ao redor.")
            }
        }
    }

    /**
     * Verifica se a bounding box toca ou está dentro da área central (zona 2).
     * Mesma lógica da função pessoa_na_area_2 do Python.
     */
    private fun pesssoaNaAreaCentral(bbox: RectF, larguraFrame: Int): Boolean {
        val limEsqPx = larguraFrame * LIMITE_ESQUERDO
        val limDirPx = larguraFrame * LIMITE_DIREITO
        return bbox.left < limDirPx && bbox.right > limEsqPx
    }

    // ──────────────────────────────────────────────────────────────────────
    // Anotações visuais
    // ──────────────────────────────────────────────────────────────────────

    /** Desenha bounding boxes, distâncias e linhas de zona no bitmap. */
    private fun desenharAnotacoes(bmp: Bitmap, pessoas: List<Pessoa>, larguraFrame: Int): Bitmap {
        val canvas = Canvas(bmp)

        val paintVerde = Paint().apply {
            color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 3f
        }
        val paintVermelho = Paint().apply {
            color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 3f
        }
        val paintTexto = Paint().apply {
            color = Color.WHITE; textSize = 28f
        }
        val paintZona = Paint().apply {
            color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 2f
        }

        // Linhas de zona (idênticas ao desenhar_zonas do Python)
        val xEsq = larguraFrame * LIMITE_ESQUERDO
        val xDir = larguraFrame * LIMITE_DIREITO
        canvas.drawLine(xEsq, 0f, xEsq, bmp.height.toFloat(), paintZona)
        canvas.drawLine(xDir, 0f, xDir, bmp.height.toFloat(), paintZona)

        // Bounding boxes
        for (p in pessoas) {
            val paint = if (p.distancia > 1.5f) paintVerde else paintVermelho
            canvas.drawRect(p.bbox, paint)
            canvas.drawText(
                "${"%.0f".format(p.confianca * 100)}% | ${"%.1f".format(p.distancia)}m",
                p.bbox.left, p.bbox.top - 10f, paintTexto
            )
        }

        // Contador total
        canvas.drawText("Pessoas: ${pessoas.size}", 20f, 50f, paintTexto.apply { textSize = 40f })

        return bmp
    }

    // ──────────────────────────────────────────────────────────────────────
    // Non-Maximum Suppression (NMS)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Remove detecções redundantes com sobreposição (IoU) > 50%.
     * Necessário porque YOLOv8 pode gerar múltiplas bboxes para o mesmo rosto.
     */
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

    /** Calcula a Intersection over Union entre dois retângulos. */
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

    // ──────────────────────────────────────────────────────────────────────
    // Carregamento do modelo TFLite
    // ──────────────────────────────────────────────────────────────────────

    /** Mapeia o arquivo .tflite da pasta assets/ diretamente em memória. */
    private fun carregarModelo(assets: AssetManager, nome: String): MappedByteBuffer {
        val fd = assets.openFd(nome)
        return FileInputStream(fd.fileDescriptor).channel.map(
            FileChannel.MapMode.READ_ONLY,
            fd.startOffset,
            fd.declaredLength
        )
    }
}
