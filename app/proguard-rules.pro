# =============================================================================
# proguard-rules.pro — Regras do ProGuard/R8 para OculosAssistente
# =============================================================================
# Adicione aqui regras específicas do projeto para o ProGuard/R8.
#
# Por padrão, as regras do Android (proguard-android-optimize.txt)
# já são aplicadas via build.gradle. Este arquivo serve para regras
# adicionais específicas deste aplicativo.
#
# Documentação completa:
# https://developer.android.com/build/shrink-code
# =============================================================================

# --- TensorFlow Lite ---
# Preserva as classes nativas do TensorFlow Lite para evitar
# erros em tempo de execução quando o R8/ProGuard está habilitado.
-keep class org.tensorflow.lite.** { *; }
-keepclassmembers class org.tensorflow.lite.** { *; }

# --- Regras gerais de depuração (descomente se necessário) ---
# Mantém informações de número de linha para stack traces mais legíveis.
# -keepattributes SourceFile,LineNumberTable
# -renamesourcefileattribute SourceFile
