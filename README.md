# Óculos Assistente para Deficientes Visuais 🕶️📷

> **Projeto de visão computacional embarcada desenvolvido para auxiliar pessoas com deficiência visual na detecção de obstáculos dinâmicos (pessoas) utilizando Inteligência Artificial offline.**

---

## 🎯 Sobre o Projeto

As soluções tradicionais de acessibilidade (como bengalas e cães-guia) funcionam bem para obstáculos estáticos, mas apresentam limitações com obstáculos dinâmicos, principalmente pessoas em movimento. 

Este projeto propõe um sistema **wearable (vestível) de baixo custo**, composto por uma **ESP32-CAM** e um **Smartphone Android**, capaz de detectar pessoas, estimar a distância e emitir alertas de voz em tempo real.

---

## 📸 Demonstração

<img width="758" height="1177" alt="WhatsApp Image 2026-07-01 at 22 57 58" src="https://github.com/user-attachments/assets/3d0508cf-1b52-4879-9d32-d08484378fc4" />
<img width="899" height="1599" alt="WhatsApp Image 2026-06-15 at 21 11 16" src="https://github.com/user-attachments/assets/eeb8f59c-872f-455f-ba7a-f1d07e1c0686" />

---

## 🚀 Como Funciona a Arquitetura?

O sistema é dividido em quatro etapas principais, rodando de forma 100% offline:

1. **Captura (ESP32-CAM):** A câmera capta imagens via demanda e as comprime em formato JPEG.
2. **Transmissão (Bluetooth SPP):** As imagens são enviadas da placa para o celular usando o protocolo Bluetooth Classic (Serial Port Profile).
3. **Processamento (App Android + TFLite):** O celular atua como o cérebro do sistema. Ele processa a imagem usando uma rede neural **YOLOv8n-face**, identifica rostos e calcula a distância aproximada (erro constante de ~20cm).
4. **Alerta por Voz (Text-to-Speech):** O aplicativo sintetiza a voz e informa o usuário diretamente pelo fone de ouvido.

### Tipos de Alerta 🔊
O sistema possui 3 níveis lógicos de aviso:
- **Aviso de Multidão:** Informa a quantidade de pessoas próximas.
- **Aviso de Aproximação:** Alerta quando alguém está caminhando em direção ao usuário.
- **Aviso de Parada:** Identifica quando uma pessoa está parada próxima ao usuário por vários segundos.

---

## 🛠️ Tecnologias Utilizadas

- **Linguagem Principal:** Kotlin (App Android) / C++ (ESP32)
- **Inteligência Artificial:** TensorFlow Lite (YOLOv8n-face)
- **Hardware:** TTGO T-Journal (ESP32 + Câmera OV2640)
- **Comunicação:** Bluetooth Classic

---

## 🚀 Como Rodar o Projeto

1. Abra o projeto no **Android Studio**.
2. Certifique-se de que sua placa ESP32-CAM esteja ligada e pareada com o Bluetooth do celular.
3. Compile e instale o APK no smartphone.
4. Conceda as permissões de Bluetooth e Localização.
5. Clique em "Conectar" no aplicativo e o rastreamento começará automaticamente.

---
*Desenvolvido pela equipe de Microprocessadores e Microcontroladores (IFCE).*
