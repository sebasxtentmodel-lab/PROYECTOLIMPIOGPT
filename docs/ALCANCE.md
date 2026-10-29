# Alcance inicial

## Función principal

Escuchar voz en turco y mostrar una traducción escrita al español; también permitir el sentido inverso español → turco.

## Requisitos funcionales

1. Captura constante del micrófono, incluso con pausas o silencios.
2. Reconocimiento de voz local y traducción local (sin llamadas obligatorias a servicios en la nube).
3. Texto acumulativo que preserve lo transcrito anteriormente.
4. Interfaz minimalista: micrófono y visor, sin teclado convencional.
5. Compatibilidad objetivo inicial: Android 13.

## Requisitos de ingeniería

- Gestionar correctamente los permisos de micrófono.
- Aislar la captura de audio del hilo principal de interfaz.
- Verificar disponibilidad y licencias de modelos ASR y traducción antes de distribuirlos.
- Probar consumo de RAM, CPU, batería y funcionamiento prolongado en dispositivo real.
- Mantener pruebas y compilaciones reproducibles.

## Fuera del alcance en esta fase

El proyecto todavía no contiene motores de voz, modelos de traducción ni instalador APK. No se debe declarar ninguna funcionalidad completa antes de implementarla y probarla.
