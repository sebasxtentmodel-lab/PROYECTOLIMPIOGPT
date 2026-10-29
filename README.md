# SmartFk — teclado intérprete Android

Proyecto **nuevo** e independiente. No se ha copiado código de las APK antiguas.

## Diseño funcional solicitado

- Teclado de Android sin teclas, iconos, botones ni controles propios visibles.
- Pantalla de texto en formato Rolling por intervenciones; el último bloque se muestra al final.
- Zona izquierda invisible: tocar reproduce el bloque visible y tocar de nuevo detiene la voz inmediatamente.
- Zona central invisible: mantener dos segundos para regresar al teclado anterior.
- Zona derecha invisible: desplazar verticalmente para consultar las traducciones anteriores y recientes.
- Captura AudioRecord continua mientras el teclado está visible, sin cierre por silencios.
- Durante la reproducción TTS: micrófono abierto, pero procesamiento ASR suspendido; se reanuda al terminar o detener TTS.
- Traducción bidireccional turco ↔ español, progresiva y local una vez instalados los modelos.

## Alcance real de esta primera versión ALFA

El reconocimiento local utiliza Vosk y los modelos turco/español se incorporan automáticamente **al compilar la APK**. La traducción se realiza en el teléfono con ML Kit **después de una descarga inicial**. Esto significa que la alfa **NO cumple aún el requisito final de funcionar completamente offline desde la primera instalación**. Las voces TTS requieren voces locales compatibles disponibles en Android, que no se incluyen en esta alfa.

No se ha probado la precisión de la detección automática, la duración de sesiones de horas, la reproducción y el ciclo de vida en un Infinix Smart 8 real. Esta es una entrega de ingeniería inicial, no una versión de producción.

## Seguridad y privacidad

- No se envían audios ni conversaciones a un servicio de traducción en la nube.
- ML Kit puede descargar modelos al iniciar la preparación; esta versión declara INTERNET por ello.
- No se guarda audio bruto.
- Los textos se mantienen en memoria hasta terminar el servicio. No existe todavía recuperación de sesiones anteriores.
- El indicador del micrófono que muestra Android no puede ni debe ocultarse.

## Compilación automática

En GitHub > Actions > «SmartFk Android APK», descargar el artefacto `SmartFk-debug-apk` cuando la compilación haya terminado correctamente. Dentro se encuentra `app-debug.apk`.

La acción instala Java y Gradle, descarga modelos Vosk oficiales y compila el APK con el Android SDK de GitHub Actions.

## Próximos requisitos pendientes

1. Incorporar un motor **neuronal independiente con modelos de traducción incluidos desde la instalación**, reemplazando la dependencia de descarga inicial de ML Kit.
2. Integrar voces TTS locales dentro del paquete, no depender de paquetes instalados externamente.
3. Mejorar la clasificación automática turco/español mediante un detector ligero y pruebas de reconocimiento.
4. Probar interrupciones del audio, latencia y consumo de RAM durante sesiones largas.
5. Pruebas de integración en un dispositivo Android 13 real.

El código es nuevo; las bibliotecas de terceros y sus modelos mantienen sus respectivas licencias.
