# Propuesta visual de NovelReader

## Revisión 2

- Acceso prioritario «Continuar leyendo», tarjetas diferenciadas y acciones más visibles.
- Botón claro/oscuro en la cabecera. El tema se comparte entre biblioteca, ajustes, lector y modal; al cambiarlo se restablece el color de texto automático para mantener legibilidad.
- Ocho opciones tipográficas con muestras: Serif, Sans Serif, Monospace, Clásica, Humanista, Literaria, Geométrica y Accesible. El prototipo usa fuentes locales y alternativas: su apariencia depende de las fuentes instaladas. En Android se deben empaquetar fuentes con licencia adecuada para asegurar variantes consistentes offline.
- Tamaño de lectura entre 12 y 20, incluyendo corrección de valores guardados mayores de 20. Controles A−/A+ y deslizador sincronizados. El límite se refiere al tamaño configurado, no a desactivar el escalado de accesibilidad de Android.
- Ajustes en vivo con previsualización y opciones secundarias plegables; no se cierra el modal. Orientación y lectura por columnas son demostraciones de navegador, no la paginación de Readium.
- Selector de logo local para probar la imagen original en la cabecera sin enviarla a servidores. La imagen seleccionada dura la sesión: falta incorporar el archivo original como recurso definitivo.

Abre `novelreader-prototipo.html` en un navegador. Es un prototipo visual autónomo, sin dependencias de red; no sustituye la aplicación Android ni procesa EPUB. Los libros y su contenido son ejemplos. El selector exterior permite revisar estados de carga, vacío, búsqueda sin resultados y error.

## Comportamiento y traducción a Android

| Prototipo | Implementación nativa propuesta |
| --- | --- |
| Biblioteca con búsqueda, chips y tarjetas | Scaffold, SearchBar/OutlinedTextField, FilterChip, LazyColumn |
| Navegación inferior | NavigationBar; consumir los insets una sola vez |
| Tema editorial claro, noche y violeta | ColorScheme de Material 3, compartido por biblioteca, lector, diálogos y controles |
| Ajustes agrupados con previsualización | Componentes Compose reutilizados entre pantalla completa y modal |
| Tocar centro del lector | Evento de toque de Readium; excluir enlaces, selección, arrastre y controles |
| Panel de lectura de 44 % de altura | ModalBottomSheet de altura limitada al área segura, contenido desplazable |
| Cambios de lectura en vivo | submitPreferences de Readium con estado único; no recrear la actividad |
| Guardado de preferencias | DataStore; actualizar la vista inmediatamente y agrupar escrituras durante arrastre |
| Progreso y marcadores | Locator de Readium y Room; actualizar el progreso existente |

## Sistema visual

- Fondo claro #F2EEE5, superficies #FFFCF6, texto #252B27, secundario #62695F.
- Noche: fondo #171C1A, superficie #222A26, texto #F2F3ED.
- Verde musgo #365C4B; usar verde claro sobre superficies oscuras. Dorado #A17A2E para progreso y detalles.
- Títulos serif; controles sans-serif. Escala de espaciado 4, 8, 12, 16, 24 y 32 dp.
- Superficies redondeadas de 16–24 dp. Objetivos táctiles de al menos 48 dp, etiquetas accesibles y foco visible.
- No indicar selección solo por color: incluir borde, texto o marca.

## Estados y navegación

Biblioteca → novela → lector → toque central → panel → ajustes / índice / marcadores. Volver cierra primero el modal y después retorna a biblioteca.

Carga muestra esqueletos; biblioteca vacía invita a importar; sin resultados permite limpiar filtros; error ofrece reintentar. Borrar requiere confirmación y especifica que el EPUB original no se elimina. El prototipo simula estas acciones.

## Validación nativa pendiente

La barra del teléfono se representa explícitamente en el prototipo. En Android se deben usar WindowInsets de barras del sistema y recortes de pantalla, nunca una altura fija. Verificar navegación por gestos y tres botones, orientación horizontal, teclado, fuente ampliada y TalkBack. Validar apertura de EPUB, restauración de posición y cambios de estilos en un dispositivo; una compilación exitosa no valida estos comportamientos.
