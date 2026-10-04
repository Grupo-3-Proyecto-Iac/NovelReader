# NovelReader

Aplicación Android para leer novelas EPUB con biblioteca local, progreso,
marcadores, personalización de lectura y lectura en voz alta mediante un
servidor TTS remoto Supertonic M5.

El proyecto es independiente de NovelTranslator: NovelReader importa EPUB
como archivos y no necesita acceder al código ni a la base de datos de ese
proyecto.

## Funcionalidades

- Kotlin, Jetpack Compose y Readium Kotlin Toolkit 3.2.0.
- Importación de EPUB mediante el selector de archivos de Android (SAF).
- Copia privada del libro, metadatos OPF, autor, portada y detección de duplicados.
- Biblioteca con búsqueda, filtros, importación y ajustes.
- Lector con índice, progreso, marcadores y controles de lectura.
- Room para biblioteca, progreso y marcadores.
- DataStore para preferencias de lectura.
- TTS remoto exclusivamente con la voz M5 de Supertonic.
- Precarga del siguiente segmento de audio para reducir pausas durante la lectura.

## Estructura importante

    NovelReader/
    ├── app/                    # Aplicación Android
    ├── server/                 # API FastAPI + Supertonic M5
    ├── gradlew.bat             # Gradle Wrapper para Windows
    ├── local.properties.example
    └── README.md

## Requisitos

Para compilar la aplicación necesitas:

- Windows, macOS o Linux.
- Android Studio actualizado.
- JDK 17.
- Android SDK con API 35 o superior; el proyecto compila con compileSdk 36.
- Un emulador Android o un dispositivo físico con Android 8.0/API 26 o superior.
- Para usar TTS local: Python 3.11 o superior y suficiente espacio para la caché
  del modelo Supertonic.

Configuración Android actual:

    compileSdk: 36
    targetSdk: 35
    minSdk:     26
    Java/Kotlin: 17
    Gradle:     8.11.1

## 1. Obtener el código

Desde PowerShell:

    git clone https://github.com/Grupo-3-Proyecto-Iac/NovelReader.git
    cd NovelReader

Si ya tienes el repositorio local:

    cd "C:\Users\Usuario\Desktop\Programación\NovelReader"
    git pull origin main

## 2. Abrir y ejecutar la aplicación Android

1. Abre la carpeta NovelReader en Android Studio.
2. Espera a que termine la sincronización de Gradle.
3. Comprueba que Android Studio detecte JDK 17.
4. Selecciona un emulador o conecta un teléfono con depuración USB.
5. Pulsa Run para instalar la variante debug.
6. Desde la aplicación, importa un archivo EPUB con el botón de importar.

También puedes compilar desde PowerShell, situado en la raíz del proyecto:

    cd "C:\Users\Usuario\Desktop\Programación\NovelReader"
    .\gradlew.bat --no-daemon --max-workers=1 :app:assembleDebug

El APK se genera en:

    app\build\outputs\apk\debug\app-debug.apk

Si tienes adb configurado, puedes instalarlo así:

    adb install -r ".\app\build\outputs\apk\debug\app-debug.apk"

Para compilar la variante de distribución:

    .\gradlew.bat --no-daemon --max-workers=1 :app:assembleRelease

La variante release está preparada para firmarse, pero este repositorio no
incluye ninguna clave privada. La firma debe configurarse en Android Studio o
en un sistema CI seguro.

## 3. Configurar la URL del TTS

local.properties es un archivo local y no debe subirse al repositorio. Copia
el ejemplo:

    Copy-Item .\local.properties.example .\local.properties

Edita local.properties y conserva también la propiedad sdk.dir que haya
creado Android Studio. Un ejemplo completo es:

    sdk.dir=C:\\Users\\TU_USUARIO\\AppData\\Local\\Android\\Sdk
    remoteTtsUrl=ws://10.0.2.2:8765/ws/tts
    remoteTtsToken=

Usa una sola de estas URLs según dónde ejecutes el servidor:

| Entorno | remoteTtsUrl |
| --- | --- |
| Emulador Android Studio y API en el PC | ws://10.0.2.2:8765/ws/tts |
| Teléfono físico en la misma Wi-Fi que el PC | ws://IP_LAN_DEL_PC:8765/ws/tts |
| VPS con HTTPS/proxy configurado | wss://dominio-o-ip/ws/tts |

En el emulador, 10.0.2.2 representa al 127.0.0.1 del ordenador. En un
teléfono físico, 127.0.0.1 apunta al propio teléfono y no al PC.

Después de cambiar local.properties, recompila e instala la app para que la
nueva URL quede incorporada en BuildConfig.

## 4. Ejecutar la API TTS en local (Windows)

La API está en server/ y usa FastAPI, Uvicorn y Supertonic. El servidor
expone /health por HTTP y /ws/tts por WebSocket.

Abre una segunda ventana de PowerShell y ejecuta:

    cd "C:\Users\Usuario\Desktop\Programación\NovelReader\server"
    python -m venv .venv
    .\.venv\Scripts\Activate.ps1
    python -m pip install --upgrade pip
    python -m pip install -r requirements.txt

Si PowerShell bloquea la activación del entorno, puedes permitirla solo para
el usuario actual:

    Set-ExecutionPolicy -Scope CurrentUser RemoteSigned

Configura el servidor para una carga moderada de CPU:

    $env:SUPERTONIC_MODEL = "supertonic-3"
    $env:SUPERTONIC_VOICE = "M5"
    $env:SUPERTONIC_STEPS = "8"
    $env:MAX_TTS_CONCURRENCY = "1"
    $env:OMP_NUM_THREADS = "4"
    $env:MKL_NUM_THREADS = "4"
    $env:ORT_INTRA_OP_NUM_THREADS = "4"
    $env:ORT_INTER_OP_NUM_THREADS = "1"

Inicia la API escuchando conexiones del emulador y de la red local:

    python -m uvicorn app:app --host 0.0.0.0 --port 8765

La primera ejecución puede tardar porque descarga y carga el modelo. Mientras
la ventana muestre Application startup complete, la API está lista.

En otra ventana, comprueba el estado:

    Invoke-WebRequest http://127.0.0.1:8765/health | Select-Object -Expand Content

La respuesta debe indicar que el modelo está cargado. La API no genera un MP3
ni guarda archivos: entrega audio PCM por WebSocket a la aplicación Android.

Para detenerla, vuelve a la ventana de Uvicorn y pulsa Ctrl+C.

### Reducir todavía más la carga

Puedes probar menos pasos de inferencia:

    $env:SUPERTONIC_STEPS = "6"
    python -m uvicorn app:app --host 0.0.0.0 --port 8765

Mantén MAX_TTS_CONCURRENCY=1. Aumentar la concurrencia puede generar más
audio simultáneamente, pero también eleva el consumo de CPU y memoria.

## Parámetros ajustables del procesamiento de audio

Hay tres grupos de parámetros: el modelo del servidor, la solicitud de cada
segmento y la división del texto en la aplicación Android.

### Parámetros del servidor

Se pueden definir como variables de entorno antes de iniciar Uvicorn o dentro
de server/.env cuando se usa Docker:

| Parámetro | Valor habitual | Para qué sirve | Efecto principal |
| --- | ---: | --- | --- |
| SUPERTONIC_MODEL | supertonic-3 | Selecciona el modelo Supertonic cargado. | Cambiarlo puede modificar compatibilidad, memoria y calidad. |
| SUPERTONIC_VOICE | M5 | Voz predeterminada si el cliente no envía otra. | En esta aplicación se usa M5 deliberadamente. |
| SUPERTONIC_STEPS | 8 | Número de pasos de inferencia por segmento. | Más pasos suelen dar mayor estabilidad/calidad, pero tardan más y consumen más CPU. |
| MAX_TTS_CONCURRENCY | 1 | Número máximo de segmentos procesados al mismo tiempo. | Subirlo puede aumentar el rendimiento total, pero eleva mucho la carga y la memoria. |
| MAX_PENDING_SEGMENTS | 3 | Cantidad máxima de solicitudes esperando en la cola de una conexión. | Una cola mayor permite más precarga; una menor limita memoria y evita acumular audio inútil. |
| OMP_NUM_THREADS | 4 | Hilos de OpenMP usados por operaciones numéricas. | Afecta la carga de CPU y la velocidad. |
| MKL_NUM_THREADS | 4 | Hilos usados por Intel MKL cuando está disponible. | Afecta la carga de CPU y la velocidad. |
| ORT_INTRA_OP_NUM_THREADS | 4 | Hilos dentro de una operación de ONNX Runtime. | Más hilos pueden acelerar una inferencia, pero aumentan el consumo. |
| ORT_INTER_OP_NUM_THREADS | 1 | Operaciones de ONNX Runtime ejecutadas en paralelo. | Mantenerlo en 1 evita paralelismo excesivo en un equipo limitado. |
| LOG_LEVEL | INFO | Nivel de detalle de los logs. | DEBUG muestra más información; no mejora el audio. |
| TTS_AUTH_TOKEN | vacío en local | Token que debe presentar el cliente para usar el WebSocket. | No cambia la calidad; protege la API cuando se expone fuera de la red local. |

PORT aparece en server/.env.example para Docker o plataformas de despliegue.
Cuando ejecutas Uvicorn localmente, el puerto real lo determina el argumento
--port, por ejemplo --port 8765.

Configuración equilibrada para tu PC:

    SUPERTONIC_STEPS=8
    MAX_TTS_CONCURRENCY=1
    MAX_PENDING_SEGMENTS=3
    OMP_NUM_THREADS=4
    MKL_NUM_THREADS=4
    ORT_INTRA_OP_NUM_THREADS=4
    ORT_INTER_OP_NUM_THREADS=1

Configuración de menor consumo:

    SUPERTONIC_STEPS=6
    MAX_TTS_CONCURRENCY=1
    MAX_PENDING_SEGMENTS=2
    OMP_NUM_THREADS=2
    MKL_NUM_THREADS=2
    ORT_INTRA_OP_NUM_THREADS=2
    ORT_INTER_OP_NUM_THREADS=1

No conviene subir todos los valores a la vez. Primero cambia un parámetro,
prueba un segmento y compara los logs de generation_ms, audio_ms y RTF.

### Parámetros enviados por cada segmento

La aplicación envía por WebSocket una solicitud con estos campos:

    {
      "type": "tts_request",
      "sessionId": "libro-capitulo",
      "segmentId": 12,
      "text": "Texto en español.",
      "voice": "M5",
      "speed": 1.0
    }

- text: texto que se convertirá en audio. Segmentos demasiado grandes tardan
  más en generarse y pueden aumentar la espera inicial.
- voice: voz de Supertonic. NovelReader envía M5 y la interfaz está diseñada
  para usar únicamente esa voz.
- speed: velocidad de lectura. La app ofrece 0.75, 1.0, 1.25, 1.4, 1.5 y 2.0.
  El cliente limita los valores enviados al intervalo 0.5–2.0. Una velocidad de 1.4 reduce la
  duración del audio, pero puede sonar menos natural que 1.0 o 1.25.
- segmentId y sessionId: identificadores de orden y reproducción; no cambian
  la calidad ni la velocidad de inferencia.

### División del texto en Android

La aplicación no procesa un capítulo entero de una sola vez. SpeechChunker
divide cada párrafo respetando frases y límites de párrafo para evitar cortes,
repeticiones y solicitudes demasiado pesadas. Actualmente usa estas constantes
en app/src/main/java/com/novelreader/reader/SpeechChunker.kt:

| Constante | Valor | Función |
| --- | ---: | --- |
| MIN_CHARS | 40 | Intenta unir unidades muy pequeñas con la anterior. |
| TARGET_CHARS | 180 | Tamaño objetivo al agrupar frases largas. |
| MAX_CHARS | 300 | Límite aproximado de cada unidad enviada al servidor. |

No se recomienda modificar estos valores mientras se busca resolver pausas o
palabras cortadas. Si se aumenta MAX_CHARS, habrá menos solicitudes, pero cada
solicitud tardará más y será más difícil recuperar un segmento fallido. Si se
reduce demasiado, aumentarán las pausas y el número de solicitudes.

La precarga del cliente está configurada en 2 segmentos en
RemoteTtsPlaybackManager.kt. Esto permite generar el siguiente audio mientras
se reproduce el actual. Reducirla a 1 disminuye memoria y carga pendiente;
aumentarla puede reducir pausas en una red lenta, pero también puede adelantar
mucho el procesamiento y dificultar la sincronización visual.

### Qué parámetro cambiar según el problema

| Problema | Primer ajuste recomendado |
| --- | --- |
| CPU demasiado alta | Mantener MAX_TTS_CONCURRENCY=1 y bajar los hilos a 2; después probar STEPS=6. |
| La lectura se queda sin audio | Mantener STEPS=8 y MAX_PENDING_SEGMENTS=3; revisar el RTF y la red. |
| Mucha memoria o demasiada cola | Bajar MAX_PENDING_SEGMENTS a 2 y no aumentar la concurrencia. |
| Voz demasiado lenta | Cambiar speed a 1.25 o 1.4 desde la app; no confundirlo con STEPS. |
| Voz inestable o palabras cortadas | Mantener STEPS=8, speed entre 1.0 y 1.25 y no reducir demasiado MAX_CHARS. |
| Muchas pausas entre unidades | No aumentar la concurrencia automáticamente; revisar RTF, red y precarga. |

STEPS controla el trabajo de generación; speed controla la velocidad de habla.
Son parámetros diferentes: subir speed no necesariamente reduce el tiempo que
el servidor tarda en generar el audio.

## 5. Probar con el emulador

1. Inicia la API local en el PC en el puerto 8765.
2. En local.properties, usa:
   remoteTtsUrl=ws://10.0.2.2:8765/ws/tts
3. Recompila e instala la app.
4. Abre un EPUB y entra en la sección de audio.
5. Pulsa reproducir y comprueba que la voz sea M5.

## 6. Probar con un teléfono físico

El teléfono y el PC deben estar en la misma red Wi-Fi. En PowerShell, obtén
la IPv4 del PC con:

    ipconfig

Busca la dirección IPv4 del adaptador Wi-Fi, por ejemplo 192.168.0.40, y usa:

    remoteTtsUrl=ws://192.168.0.40:8765/ws/tts

La API debe iniciarse con --host 0.0.0.0. Puedes comprobar desde el PC que el
puerto está escuchando con:

    Test-NetConnection 127.0.0.1 -Port 8765

Si el teléfono no conecta, permite el puerto TCP 8765 en el Firewall de Windows
para redes privadas y verifica que ambos dispositivos estén en la misma red.
No uses localhost ni 127.0.0.1 en la configuración del teléfono.

## 7. Ejecutar la API con Docker (opcional)

Si prefieres no instalar las dependencias de Python directamente en Windows,
necesitas Docker Desktop y puedes ejecutar:

    cd "C:\Users\Usuario\Desktop\Programación\NovelReader\server"
    docker build -t novelreader-tts .
    docker volume create novelreader-model-cache
    docker run --rm --name novelreader-tts -p 8765:10000 -v novelreader-model-cache:/root/.cache novelreader-tts

El volumen conserva la caché del modelo entre ejecuciones. Si usas un archivo
.env, puedes añadir --env-file .env al comando docker run. El archivo .env es
local y no debe publicarse.

## 8. Flujo de prueba recomendado

    1. Iniciar server/app.py
    2. Comprobar GET /health
    3. Configurar remoteTtsUrl
    4. Recompilar e instalar la app
    5. Importar un EPUB
    6. Abrir el lector y probar Audio > Reproducir

Durante la generación, el servidor registra métricas como tiempo de generación,
duración del audio, RTF y tiempo hasta el primer audio. Un RTF menor que 1
significa que genera audio más rápido de lo que se reproduce.

## Solución de problemas

### Voz M5 no disponible

Revisa que remoteTtsUrl no esté vacío y recompila la aplicación. La URL debe
terminar en /ws/tts.

### conexión TTS cerrada

Comprueba que Uvicorn siga ejecutándose, que /health responda y que la URL
corresponda al entorno. Usa 10.0.2.2 en el emulador y la IPv4 del PC en un
teléfono físico.

### El teléfono no puede conectarse

Revisa Wi-Fi, Firewall de Windows, --host 0.0.0.0 y el puerto 8765. Prueba
primero desde el propio PC y después desde el teléfono.

### El modelo tarda en la primera petición

Es normal: Supertonic se descarga/carga una vez y queda en caché. No cierres el
servidor durante esa primera carga.

### El procesador llega al 100 %

Mantén una sola solicitud simultánea, usa SUPERTONIC_STEPS=6 y reduce los
hilos OMP_NUM_THREADS, MKL_NUM_THREADS y ORT_INTRA_OP_NUM_THREADS si es
necesario. El modelo se ejecuta mediante CPUExecutionProvider en esta versión.

## Seguridad y archivos locales

No subas al repositorio:

- local.properties;
- server/.env;
- claves privadas de firma;
- cachés de modelos;
- APKs y artefactos de compilación.

Para un servidor accesible desde Internet, usa HTTPS/WSS, configura un token
de autenticación y no expongas directamente un puerto sin protección.

## Estado de publicación

La variante debug está destinada a pruebas. Antes de publicar en Play Store
o distribuir un APK de producción, configura firma, HTTPS/WSS, autenticación y
las políticas de privacidad correspondientes.
