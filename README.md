# NovelReader

Aplicación Android offline para leer novelas EPUB.

## Estado actual

- Kotlin y Jetpack Compose.
- Importación SAF con copia privada del EPUB.
- Metadatos OPF, autor, portada y detección de duplicados.
- Room para biblioteca, progreso y marcadores navegables.
- DataStore para preferencias de lectura.
- Readium Kotlin Toolkit 3.2.0 para abrir y renderizar EPUB.
- Gradle Wrapper 8.11.1 incluido.
- Biblioteca con búsqueda, filtros, importación y ajustes.
- Lector con índice, progreso, marcadores y controles de lectura.

## Configuración

- `compileSdk`: 36
- `targetSdk`: 35
- `minSdk`: 26
- Java: 17
- Versiones: `debug` para pruebas y `release` preparada para firmarse.

Abre esta carpeta en Android Studio y sincroniza Gradle. El proyecto trabaja
de forma independiente de `NovelTranslator`; solo importa sus EPUB como archivos.

## Verificación

Con JDK 17 y el SDK Android configurado:

```text
gradlew.bat --no-daemon --max-workers=1 :app:assembleDebug
gradlew.bat --no-daemon --max-workers=1 :app:assembleRelease
```

La prueba directa del parser EPUB pasa correctamente. En este entorno, el
runner JVM de Gradle puede informar `ClassNotFoundException` aunque la clase
de prueba sí se compile; no afecta al APK generado.

La variante `release` no incluye una clave privada. Para publicar, configura
la firma del propietario en Android Studio o mediante variables seguras de CI.

## TTS remoto M5

El servidor de `server/` expone Supertonic por WebSocket y el APK reproduce el
PCM directamente en memoria usando exclusivamente la voz M5. Para activarlo, copia
`local.properties.example` a la configuración local de Android y define:

```properties
remoteTtsUrl=ws://IP_DEL_SERVIDOR:8765/ws/tts
remoteTtsToken=
```

Usa `wss://` y un token en producción. Si `remoteTtsUrl` queda vacío, el
reproductor M5 permanecerá deshabilitado; la aplicación no sustituirá la voz
por otro motor. El servidor no guarda los audios; solo conserva el modelo
cargado en RAM.
