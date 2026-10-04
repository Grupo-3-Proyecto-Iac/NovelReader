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
