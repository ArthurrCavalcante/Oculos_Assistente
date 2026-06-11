# Óculos Assistente — App Android

App Android complementar ao projeto de óculos assistivos para deficientes visuais
desenvolvido no Instituto Iracema (IFCE). Substitui o script Python original,
rodando inteiramente no celular via Bluetooth.

---

## Estrutura do projeto

```
app/src/main/
├── java/com/ifs/oculosassistente/
│   ├── MainActivity.kt      → UI, permissões, loop de captura
│   ├── BluetoothHelper.kt   → Conexão BT e protocolo de foto com ESP32
│   └── DetectorRostos.kt    → YOLOv8 TFLite + lógica dos 3 alertas
├── res/layout/
│   └── activity_main.xml    → Layout da tela principal
└── AndroidManifest.xml      → Permissões Bluetooth
```

Apenas **3 arquivos Kotlin** — cada um com responsabilidade única e bem definida.

---

## Pré-requisitos

| Item | Detalhe |
|------|---------|
| Android Studio | Hedgehog (2023.1.1) ou mais recente |
| SDK mínimo | Android 7.0 (API 24) |
| Dispositivo | Bluetooth Classic obrigatório |
| Modelo | `yolov8n-face.tflite` em `app/src/main/assets/` |

---

## Como obter o modelo .tflite

O modelo YOLOv8n-face (de código aberto) precisa ser convertido para TFLite.
Rode uma vez no computador:

```bash
pip install ultralytics

# Baixa o modelo .pt (mesmo do script Python original)
# https://github.com/lindevs/yolov8-face
# → yolov8n-face-lindevs.pt

python - <<'EOF'
from ultralytics import YOLO
model = YOLO("yolov8n-face-lindevs.pt")
model.export(format="tflite", imgsz=640, int8=False)
# Gera: yolov8n-face-lindevs_float32.tflite
EOF

# Renomeie e copie para o projeto:
cp yolov8n-face-lindevs_float32.tflite \
   app/src/main/assets/yolov8n-face.tflite
```

---

## Pareamento Bluetooth

1. No ESP32, o nome do dispositivo BT deve ser exatamente **`Oculos-Inteligente-BT`**
   (igual ao código Python original).
2. Pare o dispositivo nas configurações de Bluetooth do Android antes de abrir o app.
3. O app busca esse nome na lista de dispositivos já pareados — não faz varredura ativa.

---

## Fluxo de funcionamento

```
ESP32 (câmera)
    │  "FOTO\n"          ← BluetoothHelper envia
    │  "SIZE:12345\n"    → BluetoothHelper lê tamanho
    │  <bytes JPEG>      → BluetoothHelper lê frame
    ▼
DetectorRostos.processar(bitmap)
    ├─ YOLOv8 TFLite → lista de rostos com confiança
    ├─ Estima distância por cada rosto (fórmula focal)
    ├─ Verifica alertas (Tipo 1 / 2 / 3)
    │      └─ onAlerta("mensagem") → MainActivity.falar()
    └─ Desenha anotações no bitmap
    ▼
MainActivity
    ├─ imgView.setImageBitmap(bitmapAnotado)
    ├─ tvPessoas.text = "Pessoas detectadas: N"
    └─ TextToSpeech.speak("mensagem de alerta")
```

---

## Tipos de alerta (port do Python)

| Tipo | Condição | Mensagem |
|------|----------|----------|
| **1** | > 1 pessoa por ≥ 6 frames seguidos | "N pessoas detectadas ao redor." |
| **2** | 1 pessoa no centro do frame, se aproximando por 6 frames | "Atenção, pessoa se aproximando." |
| **3** | 1 pessoa parada a ≤ 2,5 m por 6 frames (distância estável) | "Pessoa parada detectada. Solicitando descrição." |

Timers anti-spam: Tipo 1 = 15 s, Tipo 2 = 5 s, Tipo 3 = uma vez por ocorrência.

---

## Próximos passos (integração futura)

- **Tipo 3 + Gemini Vision:** o ponto de integração está marcado com o comentário
  `// Ponto de integração futura com Gemini Vision` em `DetectorRostos.kt`.
  Basta chamar a API multimodal do Gemini passando o bitmap do frame atual.

- **GPU Delegate:** para melhorar a performance do TFLite, ative o `GpuDelegate`
  no construtor do `Interpreter` em `DetectorRostos.kt`.
