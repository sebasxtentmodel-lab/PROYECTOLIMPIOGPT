# SmartFk — teclado intérprete Android 100 % local

**Tipo de aplicación:** método de entrada Android (`InputMethodService`) sin letras ni teclas físicas. No es un traductor independiente ni una copia de Gboard/WM Keyboard.

## Qué hace

- Al activar el teclado, mantiene una sesión de micrófono `AudioRecord` de PCM16 a 16 kHz; las pausas no deben cerrar el micrófono.
- Reconoce voz turca o española con Vosk; incorpora whisper.cpp multilingüe de apoyo.
- Traduce localmente mediante **Mozilla Bergamot/Marian NMT** con cuatro modelos integrados y verificados por SHA-256, sin conectar con APIs en la nube.
- En turco→español utiliza TR→EN→ES; en español→turco, ES→EN→TR.
- El visor de traducciones solo contiene texto: bloques Rolling por intervención, deslizar para ver anteriores.
- Toque izquierdo: reproducir TTS bajo demanda o interrumpir la lectura; la captura física continúa, pero el reconocedor evita procesar su propio altavoz.
- Mantener pulsado el centro durante dos segundos: cambiar de teclado.
- Zona derecha: desplazamiento por bloques. Ningún botón visual dentro del teclado.

## Motores realmente integrados

| Capa | Tecnología | Estado |
|---|---|---|
| Teclado Android | InputMethodService, interfaz propia | Registrado y probado en Android 13 |
| Micrófono | AudioRecord PCM16 | Código implementado; falta prueba prolongada en teléfono real |
| Voz rápida | Vosk + modelos TR y ES | Empaquetados; falta evaluación con voz real |
| Voz de apoyo | whisper.cpp de WM Keyboard + Tiny multilingüe | Empaquetado; no garantía de latencia mínima |
| Traducción | Mozilla Bergamot/Marian + 4 modelos | **Comprobada con texto real en ambas direcciones en emulador Android 13** |
| Lectura | TextToSpeech Android | Requiere voces offline instaladas; se debe probar en dispositivo |

La aplicación no declara permiso de Internet. **La prueba de traducción de texto es distinta de una prueba de voz en directo.** No declarar completamente funcional el teclado hasta probar ASR→NMT→visor→TTS en el dispositivo.

## Cómo se diseñó a partir de referencias

Se han estudiado WM Keyboard (repositorio de código abierto), Firefox Translations, Gboard como referencia de interacción y la función de reuniones de Hi Translate/TexAI como ejemplo conceptual de continuidad. Se reutiliza únicamente tecnología con licencia compatible o APIs de Android. No se ha extraído código cerrado, motores de nube ni supuestas APIs privadas de Hi Translate.

**Análisis del video y la imagen aportados el 29/10/2026:**
[docs/ANALISIS_HI_TRANSLATE_Y_MOTORES.md](docs/ANALISIS_HI_TRANSLATE_Y_MOTORES.md)

La captura afirma que Hi Translate utiliza Google Cloud, Azure y DeepSeek, pero no existe verificación técnica suficiente de esos proveedores. La arquitectura de SmartFk seguirá siendo offline, sin grabación de llamadas ni uso de servicios cloud. AudioPlaybackCapture necesita permiso explícito de MediaProjection y está restringida a audio que permita su captura: no confundirlo con Accessibility.

## Consideraciones de memoria y precisión

En el Infinix Smart 8 (Android 13, memoria física cercana a 4 GB), no ejecutar varios modelos grandes en paralelo sin medirlo. Bergamot mantiene solo la ruta NMT activa (dos modelos) y Whisper debe funcionar como apoyo, no bloquear el hilo de audio. El modelo multilingüe Tiny sacrifica precisión frente a modelos grandes; no prometer precisión perfecta ni «infinito» sin pruebas.

## Compilación

GitHub Actions `SmartFk Android APK`: descarga y verifica los modelos Vosk, los binarios de WM Keyboard, los modelos Mozilla Bergamot y Whisper; compila la APK y prueba su registro como teclado. La prueba del traductor ejecuta frases en ambas direcciones en un emulador Android 13.

**Bibliotecas y derechos:** ver [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Siguientes pruebas obligatorias

1. Probar voz turca natural→español y voz española→turco sin acceso a Internet.
2. Verificar que el micrófono no se reinicia por silencios; segmentar por turnos sin cortar el flujo.
3. Medir latencia parcial/final, deduplicación Vosk/Whisper y consumo RAM/CPU.
4. Comprobar TTS, cancelación y reanudación automática.
5. Realizar una prueba real de 30–60 minutos con cambios de aplicación y pantalla encendida.

No se agregará un modo de grabación de otras aplicaciones ni un servicio persistente con pantalla apagada sin autorización y requisitos específicos.
