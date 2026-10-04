# Servidor TTS remoto de NovelReader

Este servidor procesa texto con Supertonic y devuelve PCM16 mono por WebSocket. El audio solo vive en RAM: no se escriben WAV, MP3, OGG ni archivos temporales.

## Ejecución local

Desde esta carpeta:

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
$env:SUPERTONIC_STEPS = "8"
uvicorn app:app --host 0.0.0.0 --port 8765
```

Comprobación:

```powershell
Invoke-WebRequest http://127.0.0.1:8765/health
```

La URL del cliente Android será `ws://IP_DEL_SERVIDOR:8765/ws/tts`. Para producción usa `wss://` detrás de un proxy TLS y configura `TTS_AUTH_TOKEN`.

## Docker / Render

```powershell
docker build -t novelreader-tts .
docker run --rm -p 10000:10000 --env-file .env novelreader-tts
```

El contenedor arranca `uvicorn app:app`. El primer arranque puede tardar porque descarga y carga el modelo. El modelo permanece cargado en memoria mientras el servidor está activo; no se descarga por cada frase.

## Protocolo WebSocket

El cliente envía primero, si hay token:

```json
{"type":"auth","token":"secreto"}
```

Después envía solicitudes:

```json
{"type":"tts_request","sessionId":"book-1-ch-1","segmentId":"001-002-003","text":"Texto en español.","voice":"M5","speed":1.0}
```

El servidor responde con `audio_start`, varios mensajes binarios PCM16 little-endian y `audio_end`. También acepta `cancel` y `cancel_segment`. La cola está limitada para ejercer backpressure y evitar que el teléfono o el VPS acumulen audio indefinidamente.

## Métricas

Cada segmento registra `generation_ms`, `audio_duration_ms`, `rtf` y `ttfa_ms` cuando corresponde. Las métricas se escriben únicamente en el log del proceso; el audio no se persiste.

## Límites actuales del MVP

- Una sesión WebSocket por reproducción.
- Concurrencia de inferencia limitada por `MAX_TTS_CONCURRENCY` (por defecto 1).
- La aplicación Android usa exclusivamente la voz remota Supertonic M5; si `remoteTtsUrl` no está configurado, la reproducción TTS queda deshabilitada.
- La reproducción remota actual está pensada para la actividad abierta; un servicio multimedia persistente se puede añadir después sin cambiar el protocolo.
