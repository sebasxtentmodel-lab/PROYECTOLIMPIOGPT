# SmartFk — análisis de referencias multimedia y decisiones de ingeniería
Fecha: 2026-10-29
Origen: video de 91,56 s (1000068630.mp4) + captura (1000068637.png), aportados por el usuario.
Estado: especificación y revisión de arquitectura; NO certificación de funciones aún sin probar.

## 1. Qué muestran efectivamente los archivos
El video es una GRABACIÓN DE PANTALLA desplazándose por una respuesta escrita de investigación, NO una grabación de Hi Translate funcionando.
- Aproximadamente 0–20 s: comparación de traductores locales: LibreTranslate, Argos Translate y Marian NMT, además de alternativas de navegador.
- 20–45 s: listado de proyectos Android y de escritorio de código abierto, entre ellos RTranslator, Offline Translator, Translate You, Seamless, Firefox Translations y translateLocally. La presencia de un nombre no demuestra que su motor pueda reutilizarse en Android.
- 45–60 s: recomendaciones de Marian NMT/ONNX y Bergamot para traducción local; advierte que un modelo grande puede sobrecargar un teléfono.
- 60–85 s: separa reconocimiento de voz, dictado streaming, captura de micrófono, codificación Ogg/Opus y servicios en la nube. Menciona Whisper.cpp, Vosk y AudioRecord como opciones locales.
- 85–91,56 s y captura: párrafo titulado «Motores de Hi Translate / TexAI (Función de Reunión)». Habla de captura de audio de llamadas/reuniones, transcripción, traducción y continuidad. También afirma colaboración con Google Cloud, Azure y DeepSeek: NO está demostrado en los archivos ni en fuentes oficiales consultadas.

## 2. Qué se ha podido verificar fuera de los archivos
Hi Translate anuncia traducción de reuniones, interpretación oral y resumen de reuniones:
- https://play.google.com/store/apps/details?id=com.zaz.translate
- https://www.hitranslate.ai/
Las fuentes públicas consultadas NO identifican de forma comprobable las bibliotecas privadas de reconocimiento ni los proveedores concretos de sus inferencias, ni demuestran funcionamiento totalmente offline. NO afirmar que Hi Translate incorpora Azure, DeepSeek o Google Cloud internamente.

Restricción Android: AudioPlaybackCapture (API 29+) requiere consentimiento MediaProjection, RECORD_AUDIO, mismo perfil y permiso de captura de la aplicación fuente. No funciona universalmente para llamadas y aplicaciones que bloquean la captura. Una AccessibilityService NO es un permiso secreto para grabar audio.
Documentación: https://developer.android.com/media/platform/av-capture

## 3. Decisión para SmartFk
SmartFk sigue siendo un teclado Android NO convencional (InputMethodService): NO implementar una aplicación de reuniones ajena al teclado.
- Superficie: SOLO texto en la altura de teclado, sin teclas, iconos ni paneles.
- Inicio: abrir IME -> pedir autorización concedida previamente -> capturar audio mono PCM16 de 16 kHz.
- Continuidad: la sesión AudioRecord permanece viva aun con silencios; nunca reiniciar por pausa del interlocutor. Cierra solo al cambiar/cerrar teclado o revocar permiso.
- Separación de cargas: captura en hilo prioritario; ASR y traducción en hilos propios; política de cola acotada y diagnóstico si no alcanza tiempo real.
- Texto: hipótesis parciales sustituyen únicamente previsualización, resultados definitivos se consolidan de forma incremental, sin repetir palabras.
- Conversación: distinguir turnos por idioma/intervención; transcripción y traducción en bloques Rolling; no borrar anteriores al llegar texto nuevo. La clasificación de hablantes reales y VAD avanzado AÚN NO están implementados.
- Interacción: toque izquierdo -> lectura TTS del bloque elegido; segundo toque -> detener TTS. Micrófono físico sigue abierto; ASR se inhibe mientras habla el altavoz y se reactiva automáticamente al terminar o cancelar.
- Gestos: mantener centro 2 segundos para cambiar IME, deslizar lado derecho para revisar bloques.
- Privacidad: no audio persistido ni transmisión a servidores; no INTERNET en manifest de versión offline.

## 4. Qué motores hay REALMENTE en SmartFk 0.7 y qué falta
IMPLEMENTADOS EN EL CÓDIGO:
- AudioRecord: captura cruda PCM16.
- Vosk (Kaldi): reconocimiento local turco/español, resultados parciales/definitivos.
- whisper.cpp (JNI adaptado de WM Keyboard) + modelo multilingüe Tiny Q5: alternativa local por ventanas de 5 s cuando Vosk no reconoce. No es dictado streaming sin latencia; se debe medir.
- Mozilla Bergamot/Marian (JNI procedente de WM Keyboard) + cuatro modelos verificados: rutas TR→EN→ES y ES→EN→TR.
- Android TTS: reproduce traducción solo bajo demanda. Depende de voces offline instaladas en el sistema.
- InputMethodService: visor personalizado de texto y controles táctiles invisibles.

VERIFICADO:
- Compilación Android y empaquetado de modelos reales.
- Prueba de texto TR→ES y ES→TR sobre emulador Android 13 con inferencia real.
- Activación IME en emulador.

NO VERIFICADO:
- Captura/reconocimiento/traducción de una conversación hablada real sobre Infinix X6525.
- Precisión con ruido, aceleración Whisper real, detección de idioma y diarización por hablante.
- Sesiones continuas de horas, memoria/térmica y continuidad en aplicaciones de terceros.
- Cancelación de TTS bajo todas las condiciones.
- Captura de audio interno de otras aplicaciones: NO implementada ni requerida para la conversación cara a cara.

## 5. Qué NO se debe añadir por aparecer en el video
- Google Cloud Speech, Microsoft Azure, Deepgram, DeepSeek, OpenAI o similares: no cumplen el modo 100 % offline y exigen servidores, credenciales o contratos. No se integran sin un cambio explícito de alcance.
- No extraer código cerrado de Hi Translate, Gboard o SwiftKey ni afirmar licencias inexistentes. Usar sus prestaciones como referencias de experiencia.
- Ogg/Opus es un códec de almacenamiento/transmisión. NO mejora por sí mismo el reconocimiento; SmartFk usa PCM en memoria y no necesita guardar la voz.
- ForegroundService solo si el usuario autorizase explícitamente capturar con el teclado oculto/pantalla apagada; no está en el alcance actual y exige notificación y tratamiento de permisos.
- No atribuirle diarización real a Whisper Tiny: identificación de hablantes requeriría un módulo adicional y pruebas.

## 6. Criterios de aceptación antes de llamar a la APK «funcional»
1. Con datos y Wi-Fi desconectados, abrir SmartFk y ver texto turco traducido al español a partir de VOZ real, con pruebas equivalentes ES→TR.
2. Grabar 15 min de conversación (con permiso), incluyendo silencios de 10, 30 y 60 s; verificar que AudioRecord continúa y agrega texto nuevo.
3. Comprobar hipótesis parciales y finales sin repetición, correctas por intervención; las traducciones anteriores permanecen consultables.
4. Leer una traducción vía TTS y comprobar suspensión del ASR durante la voz; al terminar o interrumpir, recuperar automáticamente el reconocimiento.
5. Alternar entre WhatsApp, otro campo y SmartFk sin pantalla negra ni capturar en segundo plano tras cierre.
6. Medir percentil 50/95 de latencia ASR+NMT y RAM/picos en Infinix Smart 8; no prometer «tiempo real» sin resultados.
7. Ejecutar pruebas de voz ruidosa, turco y español natural, varias duraciones y cambio de interlocutor.
8. Documentar los fallos separadamente: permiso, señal PCM, hipótesis ASR, traducción NMT, reproducción TTS.

## 7. Prioridades concretas de integración
P0: probar con voz real en Android 13 y corregir ASR a NMT de extremo a extremo.
P1: audio productor/consumidor desacoplados y medición de buffers perdidos, sin reinicio por silencio.
P2: segmentación VAD/turnos sin cortar hardware y mejor detección idioma turco/español.
P3: deduplicación Vosk/Whisper y manejo de lectura TTS.
P4: pruebas de duración, rendimiento/memoria, evaluación de calidad de traducción y ergonomía Rolling.

**No se aprueba una APK por el simple hecho de compilar ni por mostrar el mensaje «Escuchando».**
