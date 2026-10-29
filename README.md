# Traductor ES↔TR Offline para Android

Proyecto Android desde cero. El objetivo es desarrollar una interfaz de entrada centrada exclusivamente en la escucha continua y la traducción de voz a texto entre turco y español, sin teclado de letras.

## Principios del proyecto

- Funcionar sin Internet durante el reconocimiento y la traducción, una vez instalados los modelos requeridos.
- Capturar audio continuamente mediante `AudioRecord` sin cerrar el micrófono por pausas del hablante.
- Mantener y ampliar el texto reconocido, evitando sustituir el historial al recibir nuevos fragmentos.
- Mostrar únicamente los controles necesarios (micrófono y visor de texto).
- Mantener el código, recursos y configuraciones en un repositorio nuevo e independiente.

## Estado

**Fase 0: repositorio inicial.** Todavía no hay APK ni código funcional. El siguiente paso es decidir y crear la arquitectura Android, integrar modelos locales reales y automatizar la compilación.

Consulta [docs/ALCANCE.md](docs/ALCANCE.md) para los requisitos iniciales.
