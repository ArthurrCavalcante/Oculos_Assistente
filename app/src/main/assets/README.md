# Modelo YOLOv8n-face TFLite

O app espera o arquivo `yolov8n-face.tflite` nesta pasta.

## Como gerar

```bash
pip install ultralytics

# Baixe o modelo .pt de:
# https://github.com/lindevs/yolov8-face
# → yolov8n-face-lindevs.pt

python -c "
from ultralytics import YOLO
model = YOLO('yolov8n-face-lindevs.pt')
model.export(format='tflite', imgsz=640, int8=False)
"

# Renomeie e copie:
cp yolov8n-face-lindevs_float32.tflite app/src/main/assets/yolov8n-face.tflite
```

> ⚠️ O arquivo .tflite tem ~12 MB e NÃO deve ser commitado no Git sem Git LFS.
