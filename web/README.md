# NovelReader Web

Versión web ligera de NovelReader. Acepta únicamente archivos **`.epub`**; no permite importar PDF. El EPUB, la portada, los marcadores y el progreso permanecen en el almacenamiento local del navegador mediante IndexedDB y localStorage. El libro no se envía a la API TTS.

## Funciones

- Biblioteca local con portada, autor, búsqueda y filtros por estado de lectura.
- Importación y lectura de EPUB con botones de capítulo anterior/siguiente, índice y restauración de posición.
- Búsqueda de frases dentro del texto del EPUB.
- Temas claro, oscuro y violeta; tipografía, tamaño e interlineado ajustables.
- Marcadores manuales y una marca «Autoguardado» actualizada mientras se reproduce audio.
- Reproducción M5 opcional desde una API NovelReader TTS, con selección del párrafo inicial, continuación automática entre capítulos, control de velocidad y precarga de un párrafo.
- Diseño adaptable a móvil y escritorio.

## Ejecutar en Windows

1. Instala Python 3 si aún no lo tienes.
2. Abre PowerShell en la carpeta del proyecto y ejecuta:

   ```powershell
   cd "C:\Users\Usuario\Desktop\Programación\NovelReader\web"
   python -m http.server 4173
   ```

3. Abre `http://localhost:4173` en Chrome, Edge o Firefox.
4. Pulsa **Importar EPUB** y elige un archivo `.epub`.

No abras `index.html` con doble clic: servirlo desde localhost permite que el navegador use IndexedDB y CFI de forma consistente.

## Activar audio M5

El audio requiere tener en marcha la API FastAPI de NovelReader. En **Ajustes → Servidor de voz M5**, configura:

- Si ejecutas la web y la API en la misma PC: `ws://localhost:8765/ws/tts`.
- En una red local: `ws://IP_DEL_SERVIDOR:8765/ws/tts`.
- En un servidor con TLS: `wss://tu-dominio/ws/tts`.
- El token, solo si el servidor tiene configurado `TTS_AUTH_TOKEN`.

Pulsa **Probar conexión** y después **Guardar**. El navegador solo usa la voz `M5` y envía el texto a ese servidor para sintetizar el audio. En una página servida por HTTPS, el navegador normalmente bloqueará una conexión de audio `ws://`; en ese caso usa `wss://`.

## Dependencias del navegador

La vista EPUB usa JSZip y Epub.js desde jsDelivr; se necesita conexión a Internet para cargar estas librerías. JSZip abre y comprueba el contenedor ZIP del EPUB antes de importarlo; el libro sigue procesándose y guardándose localmente. Si aparece un error de librerías, revisa la conexión y recarga la página. Si el aviso indica que falta `META-INF/container.xml` o `mimetype`, el archivo probablemente no es un EPUB válido aunque termine en `.epub`.

## Alcance de esta primera versión

Es una aplicación web local, no una cuenta sincronizada ni un servicio de alojamiento. La biblioteca solo existe en el navegador y dispositivo donde se importaron los libros. Borrar los datos del sitio desde el navegador puede borrar la biblioteca y los marcadores. Las notificaciones de pantalla bloqueada y la reproducción en segundo plano de Android no se trasladan automáticamente a la web.
