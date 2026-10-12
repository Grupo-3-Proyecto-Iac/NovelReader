/* NovelReader Web: EPUBs stay in this browser; book contents are never uploaded. */
(() => {
  'use strict';

  const DB_NAME = 'novelreader-web';
  const DB_VERSION = 1;
  const PREF_KEY = 'novelreader-web-preferences';
  const DEFAULTS = { theme: 'dark', font: 'Georgia', fontSize: 20, lineHeight: 1.75, paragraphSpace: 1.1, alignment: 'left', speed: 1.4, ttsUrl: '', ttsToken: '' };
  const state = {
    prefs: { ...DEFAULTS, ...readPrefs() }, books: [], screen: 'home', filter: 'all', query: '',
    db: null, current: null, epub: null, rendition: null, toc: [], location: null, locationsReady: false,
    panelTab: 'reading', panelOpen: false, readerMarks: [], searchJob: 0, toastTimer: null,
    audio: { playing: false, socket: null, segments: [], segmentIndex: 0, requested: 0, nextId: 1, incoming: null, ready: new Map(), active: false, pending: false, source: null, context: null, currentNode: null, generation: 0, selectionMode: false, startCfi: '', startText: '', startNode: null }
  };

  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
  const main = $('#main');
  const fileInput = $('#epub-file');
  const esc = value => String(value ?? '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]));

  function readPrefs() {
    try { return JSON.parse(localStorage.getItem(PREF_KEY) || '{}'); } catch { return {}; }
  }
  function savePrefs() {
    localStorage.setItem(PREF_KEY, JSON.stringify(state.prefs));
    document.documentElement.dataset.theme = state.prefs.theme;
    document.documentElement.style.setProperty('--reader-font', state.prefs.font);
    document.documentElement.style.setProperty('--reader-size', `${state.prefs.fontSize}px`);
    document.documentElement.style.setProperty('--reader-leading', state.prefs.lineHeight);
    document.documentElement.style.setProperty('--reader-spacing', `${state.prefs.paragraphSpace}em`);
    const meta = $('meta[name="theme-color"]');
    if (meta) meta.content = state.prefs.theme === 'light' ? '#fbfaf7' : state.prefs.theme === 'violet' ? '#1a1626' : '#14181a';
    const themeToggle = $('.theme-toggle');
    if (themeToggle) themeToggle.innerHTML = `<span aria-hidden="true">${state.prefs.theme === 'light' ? '☀' : state.prefs.theme === 'dark' ? '☾' : '✦'}</span> ${state.prefs.theme === 'light' ? 'Claro' : state.prefs.theme === 'dark' ? 'Oscuro' : 'Violeta'}`;
    applyReaderTheme();
  }

  function openDb() {
    return new Promise((resolve, reject) => {
      const request = indexedDB.open(DB_NAME, DB_VERSION);
      request.onupgradeneeded = () => {
        const db = request.result;
        if (!db.objectStoreNames.contains('books')) db.createObjectStore('books', { keyPath: 'id' });
      };
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
  }
  function storeRequest(mode, action) {
    return new Promise((resolve, reject) => {
      const tx = state.db.transaction('books', mode);
      const request = action(tx.objectStore('books'));
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
      tx.onabort = () => reject(tx.error);
    });
  }
  const allBooks = () => storeRequest('readonly', store => store.getAll());
  const putBook = book => storeRequest('readwrite', store => store.put(book));
  const getBook = id => storeRequest('readonly', store => store.get(id));

  function toast(message) {
    const node = $('#toast');
    node.textContent = message;
    node.classList.add('show');
    clearTimeout(state.toastTimer);
    state.toastTimer = setTimeout(() => node.classList.remove('show'), 2600);
  }
  function setBusy(message) {
    main.innerHTML = `<section class="page"><div class="empty-state"><div><div class="empty-icon">◌</div><h2>${esc(message)}</h2><p>El archivo se procesa localmente en tu navegador.</p></div></div></section>`;
  }
  function setReaderLoading(message) {
    const loading = $('#reader-loading');
    if (loading) { loading.textContent = message; loading.hidden = false; }
  }
  function withTimeout(promise, message, milliseconds = 30000) {
    let timer;
    return Promise.race([
      promise,
      new Promise((_, reject) => { timer = setTimeout(() => reject(new Error(message)), milliseconds); })
    ]).finally(() => clearTimeout(timer));
  }
  async function loadEpub(arrayBuffer) {
    const book = ePub();
    try {
      await withTimeout(book.open(arrayBuffer, 'binary'), 'Epub.js tardó demasiado en abrir el archivo. Recarga y vuelve a intentarlo.');
      await withTimeout(book.ready, 'Epub.js no pudo terminar de leer la estructura del EPUB.');
      return book;
    } catch (error) {
      try { book.destroy(); } catch { /* Best effort cleanup. */ }
      throw error;
    }
  }
  function percent(book) { return Math.max(0, Math.min(100, Math.round((book.progress?.percent || 0) * 100))); }
  function titlePlaceholder(title) { return `<div class="cover cover-placeholder">${esc(title || 'EPUB')}</div>`; }
  function coverMarkup(book, className = 'cover') {
    return book.cover ? `<img class="${className}" src="${esc(book.cover)}" alt="Portada de ${esc(book.title)}">` : titlePlaceholder(book.title);
  }

  function renderHome() {
    state.screen = 'home';
    closeReader(false);
    const books = state.books.filter(book => {
      const haystack = `${book.title} ${book.author}`.toLocaleLowerCase('es');
      const matchesQuery = haystack.includes(state.query.toLocaleLowerCase('es'));
      const matchesFilter = state.filter === 'all' || (state.filter === 'reading' && percent(book) > 0 && percent(book) < 100) || (state.filter === 'done' && percent(book) >= 100);
      return matchesQuery && matchesFilter;
    }).sort((a, b) => (b.lastOpened || b.importedAt) - (a.lastOpened || a.importedAt));
    const latest = [...state.books].filter(book => percent(book) > 0).sort((a, b) => (b.lastOpened || 0) - (a.lastOpened || 0))[0];
    const heading = `<div class="page-heading"><div><h1>Tu biblioteca</h1><p class="subhead">${state.books.length} ${state.books.length === 1 ? 'novela' : 'novelas'} · disponibles en este navegador</p></div></div>`;
    const search = `<label class="searchbox"><span>⌕</span><input id="library-search" type="search" placeholder="Buscar novela o autor…" value="${esc(state.query)}" autocomplete="off"></label><div class="filters">${[['all','Todas'],['reading','Leyendo'],['done','Terminadas']].map(([key,label]) => `<button class="chip ${state.filter === key ? 'active' : ''}" data-filter="${key}">${label}</button>`).join('')}</div>`;
    let hero = '';
    if (latest && !state.query && state.filter === 'all') {
      hero = `<section class="continue-card" data-open-book="${esc(latest.id)}">${coverMarkup(latest)}<div class="continue-info"><div class="accent-label">CONTINUAR LEYENDO</div><h2>${esc(latest.title)}</h2><p class="author">${esc(latest.author || 'Autor desconocido')} · ${percent(latest)} % leído</p><div class="progress-track"><span style="width:${percent(latest)}%"></span></div><p class="progress-caption">${percent(latest)} % leído</p><button class="primary-button" data-open-book="${esc(latest.id)}">Continuar →</button></div></section>`;
    }
    const grid = books.length ? `<section><div class="page-heading"><div><div class="eyebrow">Tus historias</div><h2>${state.query ? 'Resultados' : 'Todos tus libros'}</h2></div><span class="subhead">${books.length}</span></div><div class="book-grid">${books.map(book => `<article class="book-card" data-open-book="${esc(book.id)}">${coverMarkup(book)}<h3>${esc(book.title)}</h3><p>${esc(book.author || 'Autor desconocido')}</p><div class="card-footer"><div class="progress-track"><span style="width:${percent(book)}%"></span></div><small>${percent(book)}%</small></div></article>`).join('')}</div></section>` : state.books.length ? `<section class="empty-state"><div><div class="empty-icon">⌕</div><h2>No encontramos novelas</h2><p>Prueba otro título, autor o filtro.</p><button class="secondary-button" data-action="clear-search">Limpiar búsqueda y filtros</button></div></section>` : `<section class="empty-state"><div><div class="empty-icon">▤</div><h2>Tu próxima historia empieza aquí</h2><p>Importa un archivo EPUB para leer a tu ritmo. Tus libros y tu progreso quedan guardados localmente en este navegador.</p><button class="primary-button" data-action="import">＋ Importar EPUB</button></div></section>`;
    main.innerHTML = `<section class="page">${heading}${search}${hero}${grid}<button class="library-fab" data-action="import"><span aria-hidden="true">＋</span> Importar EPUB</button></section>`;
    syncNav();
  }

  function syncNav() {
    $$('.nav-link[data-action="home"], .mobile-nav button[data-action="home"]').forEach(button => button.classList.toggle('active', state.screen === 'home'));
    $$('.nav-link[data-action="settings"], .mobile-nav button[data-action="settings"]').forEach(button => button.classList.toggle('active', state.screen === 'settings'));
  }

  function renderSettings() {
    state.screen = 'settings';
    closeReader(false);
    const themeNames = { light: 'Claro', dark: 'Oscuro', violet: 'Violeta' };
    const fontNames = ['Georgia','Palatino','Garamond','Arial','Monospace'];
    main.innerHTML = `<section class="page"><div class="page-heading"><div><div class="eyebrow">Personaliza tu experiencia</div><h1>A tu manera</h1><p class="subhead">Los cambios se guardan automáticamente en este navegador.</p></div></div><div class="settings-grid">
      <section class="settings-card"><h2>Tema de la aplicación</h2><p>Se aplica a la biblioteca, los ajustes y el lector.</p><div class="theme-options">${Object.entries(themeNames).map(([key,label]) => `<button class="theme-choice ${state.prefs.theme === key ? 'active' : ''}" data-theme-choice="${key}">${label}</button>`).join('')}</div></section>
      <section class="settings-card"><h2>Tipografía</h2><p>Elige la fuente del texto del EPUB.</p><div class="font-options">${fontNames.map(font => `<button class="font-choice ${state.prefs.font === font ? 'active' : ''}" data-font-choice="${font}" style="font-family:${esc(font)}">Aa ${font}</button>`).join('')}</div><label class="field-label" for="setting-size">Tamaño de lectura <output class="range-value">${state.prefs.fontSize}px</output></label><div class="range-row"><input id="setting-size" data-pref="fontSize" type="range" min="13" max="34" step="1" value="${state.prefs.fontSize}"><span></span></div><label class="field-label" for="setting-leading">Interlineado <output class="range-value">${Number(state.prefs.lineHeight).toFixed(2)}</output></label><div class="range-row"><input id="setting-leading" data-pref="lineHeight" type="range" min="1.3" max="2.3" step="0.05" value="${state.prefs.lineHeight}"><span></span></div></section>
      <section class="settings-card wide"><h2>Servidor de voz M5</h2><p>Opcional. La aplicación web se conecta a tu API Supertonic M5; no utiliza otra voz.</p><label class="field-label" for="tts-url">URL WebSocket del servidor</label><div class="form-row"><input class="text-input" id="tts-url" value="${esc(state.prefs.ttsUrl)}" placeholder="ws://localhost:8765/ws/tts"><button class="secondary-button" data-action="save-tts">Guardar</button></div><p class="helper">Usa <code>ws://</code> en local o <code>wss://</code> con HTTPS. El navegador puede bloquear conexiones inseguras desde una página HTTPS.</p><label class="field-label" for="tts-token">Token (si tu servidor lo requiere)</label><input class="text-input" id="tts-token" type="password" value="${esc(state.prefs.ttsToken)}" placeholder="Opcional"><label class="field-label" for="setting-speed">Velocidad <output class="range-value">${Number(state.prefs.speed).toFixed(2)}×</output></label><div class="range-row"><input id="setting-speed" data-pref="speed" type="range" min="0.5" max="2" step="0.05" value="${state.prefs.speed}"><span></span></div><div class="form-row" style="margin-top:15px"><button class="secondary-button" data-action="test-tts">Probar conexión</button><span class="audio-status" id="tts-test-status"></span></div></section>
      <section class="settings-card wide"><h2>Almacenamiento local</h2><p>Los archivos EPUB no se suben a ningún servidor. Puedes quitar un libro desde su menú en la biblioteca.</p></section>
    </div></section>`;
    syncNav();
  }

  async function importEpub(file) {
    if (!file) return;
    if (!file.name.toLowerCase().endsWith('.epub')) {
      toast('NovelReader Web solo admite archivos .epub.');
      fileInput.value = '';
      return;
    }
    if (!window.JSZip || !window.ePub) { toast('No se cargaron las librerías EPUB. Comprueba Internet y recarga la página.'); return; }
    setBusy('Preparando el EPUB…');
    try {
      const bytes = await file.arrayBuffer();
      setBusy('Comprobando el archivo EPUB…');
      const signature = new Uint8Array(bytes.slice(0, 4));
      if (signature[0] !== 0x50 || signature[1] !== 0x4b) throw new Error('El archivo no parece ser un EPUB válido.');
      const archive = await window.JSZip.loadAsync(bytes);
      if (!archive.file('META-INF/container.xml') || !archive.file('mimetype')) {
        throw new Error('El ZIP no tiene la estructura de un EPUB: falta META-INF/container.xml o mimetype.');
      }
      const mime = await archive.file('mimetype').async('string');
      if (mime.trim() !== 'application/epub+zip') throw new Error('El ZIP no declara el tipo application/epub+zip; puede que no sea un EPUB válido.');
      const id = await digest(bytes);
      const existing = await getBook(id);
      if (existing) {
        toast('Este EPUB ya está en tu biblioteca.');
        await refreshBooks();
        await openBook(id);
        return;
      }
      setBusy('Abriendo el contenido del EPUB…');
      const parsed = await loadEpub(bytes);
      setBusy('Leyendo título y portada…');
      const metadata = await parsed.loaded.metadata;
      let cover = '';
      try {
        const coverUrl = await withTimeout(parsed.coverUrl(), 'La portada tardó demasiado; se importará el EPUB sin portada.', 7000);
        if (coverUrl) cover = await withTimeout(urlToDataUrl(coverUrl), 'No se pudo leer la portada; se importará el EPUB sin portada.', 7000);
      } catch { /* El libro puede no incluir portada. */ }
      const record = {
        id, title: metadata.title || file.name.replace(/\.epub$/i, ''), author: metadata.creator || '',
        cover, blob: new Blob([bytes], { type: 'application/epub+zip' }), size: file.size,
        importedAt: Date.now(), lastOpened: Date.now(), progress: { cfi: '', percent: 0 }, bookmarks: []
      };
      setBusy('Guardando en la biblioteca…');
      await withTimeout(putBook(record), 'El navegador tardó demasiado en guardar el EPUB. Revisa el espacio disponible.', 15000);
      parsed.destroy();
      await refreshBooks();
      toast('EPUB agregado a tu biblioteca.');
      await openBook(id);
    } catch (error) {
      console.error(error);
      renderHome();
      toast(error?.message || 'No se pudo abrir este EPUB.');
    } finally { fileInput.value = ''; }
  }

  async function digest(buffer) {
    if (window.crypto?.subtle) {
      const hash = await window.crypto.subtle.digest('SHA-256', buffer);
      return [...new Uint8Array(hash)].map(byte => byte.toString(16).padStart(2, '0')).join('');
    }
    return `${buffer.byteLength}-${Date.now()}-${Math.random().toString(36).slice(2)}`;
  }
  async function urlToDataUrl(url) {
    if (url.startsWith('data:')) return url;
    const response = await fetch(url);
    const blob = await response.blob();
    return new Promise(resolve => { const reader = new FileReader(); reader.onload = () => resolve(reader.result); reader.readAsDataURL(blob); });
  }
  async function refreshBooks() { state.books = await allBooks(); }

  async function openBook(id) {
    await stopAudio();
    if (state.epub) { try { state.epub.destroy(); } catch { /* Best effort cleanup. */ } }
    const book = await getBook(id);
    if (!book) { toast('No se encontró el EPUB.'); await refreshBooks(); renderHome(); return; }
    state.current = book;
    state.current.lastOpened = Date.now();
    state.readerMarks = book.bookmarks || [];
    state.audio.selectionMode = false;
    state.audio.startCfi = book.audioStart?.cfi || '';
    state.audio.startText = book.audioStart?.text || '';
    state.audio.startNode = null;
    state.screen = 'reader';
    main.innerHTML = `<section class="reader-page">
      <header class="reader-toolbar"><button class="reader-back" data-action="close-reader" aria-label="Volver">‹</button><button class="chapter-button" id="chapter-previous" data-action="chapter-previous" aria-label="Capítulo anterior" title="Capítulo anterior">◀</button><div class="reader-title-block"><strong class="reader-book-title">${esc(book.title)}</strong><small id="reader-chapter-title">Cargando capítulo…</small></div><button class="chapter-button" id="chapter-next" data-action="chapter-next" aria-label="Capítulo siguiente" title="Capítulo siguiente">▶</button><button data-action="reader-search" aria-label="Buscar en el libro">⌕</button><button class="bookmark-top" id="bookmark-current" data-action="toggle-bookmark" aria-label="Marcar posición">♧</button></header>
      <div class="reader-stage"><div id="epub-viewer" class="epub-viewer"></div><div id="reader-loading" class="reader-loading">Abriendo EPUB…</div><div class="reader-mini"><button class="play-round" data-action="audio-toggle" aria-label="Reproducir o pausar">▶</button><div class="mini-details"><strong id="mini-status">Lectura en voz alta · M5</strong><div class="mini-progress-row"><div class="progress-track"><span id="reader-progress" style="width:${percent(book)}%"></span></div><small id="reader-progress-label">${percent(book)}%</small></div></div><button class="icon-button" data-action="open-panel" aria-label="Ajustes de lectura">Aa</button></div></div>
      <div class="reader-bottom-space"></div><div id="reader-sheet" class="reader-bottom-sheet"><div class="sheet-handle"></div><div class="sheet-tabs">${[['reading','Aa','Lectura'],['audio','♧','Audio'],['toc','☷','Índice'],['marks','♧','Marcas']].map(([key,icon,label]) => `<button class="sheet-tab ${state.panelTab === key ? 'active' : ''}" data-panel-tab="${key}"><span>${icon}</span>${label}</button>`).join('')}</div><div id="sheet-panel" class="sheet-panel"></div></div>
      <div id="reader-search" class="reader-search" hidden><section class="search-dialog"><div class="search-dialog-head"><h2>Buscar en el EPUB</h2><form id="book-search-form" class="form-row"><input id="book-search-input" class="text-input" type="search" placeholder="Palabra o frase…" required><button class="primary-button">Buscar</button><button class="quiet-button" type="button" data-action="close-search">Cerrar</button></form></div><div id="search-results" class="search-results"><p class="helper">La búsqueda se realiza en el contenido local del EPUB.</p></div></section></div>
    </section>`;
    document.body.classList.add('reading-mode');
    syncNav();
    try {
      setReaderLoading('Abriendo el contenido…');
      state.epub = await loadEpub(await book.blob.arrayBuffer());
      state.rendition = state.epub.renderTo('epub-viewer', {
        width: '100%',
        height: '100%',
        spread: 'none',
        flow: 'scrolled-doc',
        manager: 'default',
        allowScriptedContent: false
      });
      state.rendition.themes.default(epubThemeRules());
      state.locationsReady = false;
      state.rendition.on('relocated', location => {
        state.location = location;
        const progression = progressForLocation(location);
        updateReaderProgress(progression, location?.start?.cfi || '');
        saveCurrentBook();
        syncBookmarkButton();
        syncChapterControls();
      });
      state.rendition.on('rendered', (_section, contents) => injectAudioStyle(contents));
      const navigation = await state.epub.loaded.navigation;
      state.toc = flattenToc(navigation?.toc || []);
      setReaderLoading('Preparando la página…');
      await withTimeout(state.rendition.display(book.progress?.cfi || undefined), 'El EPUB abrió, pero tardó demasiado en mostrar su primera página.', 30000);
      $('#reader-loading').hidden = true;
      renderSheetPanel();
      syncBookmarkButton();
      syncChapterControls();
      saveCurrentBook();
      prepareEpubLocations();
    } catch (error) {
      console.error(error);
      toast(error?.message || 'No se pudo abrir el EPUB.');
      if (state.epub) { try { state.epub.destroy(); } catch { /* Best effort cleanup. */ } state.epub = null; }
      closeReader(false);
      renderHome();
    }
  }

  function epubThemeRules() {
    return {
      'html': {
        'box-sizing': 'border-box !important',
        'width': '100% !important',
        'max-width': '100% !important',
        'overflow-x': 'hidden !important',
        'background': getComputedStyle(document.documentElement).getPropertyValue('--bg')
      },
      'body': {
        'box-sizing': 'border-box !important',
        'width': '100% !important',
        'max-width': '760px !important',
        'min-width': '0 !important',
        'margin': '0 auto !important',
        'padding': 'clamp(28px, 6vh, 64px) clamp(20px, 5vw, 52px) 150px !important',
        'overflow-x': 'hidden !important',
        'color': getComputedStyle(document.documentElement).getPropertyValue('--text'),
        'background': getComputedStyle(document.documentElement).getPropertyValue('--bg'),
        'font-family': state.prefs.font,
        'font-size': `${state.prefs.fontSize}px`,
        'line-height': String(state.prefs.lineHeight)
      },
      'p': {
        'max-width': '100% !important',
        'margin': `0 0 ${state.prefs.paragraphSpace}em !important`,
        'text-align': `${state.prefs.alignment} !important`,
        'overflow-wrap': 'break-word',
        'word-break': 'normal'
      },
      'h1': { 'max-width': '100% !important', 'text-align': 'center', 'font-family': state.prefs.font, 'overflow-wrap': 'break-word' },
      'h2, h3, h4': { 'max-width': '100% !important', 'overflow-wrap': 'break-word' },
      'img, svg, video': { 'max-width': '100% !important', 'height': 'auto !important' },
      '.nr-audio-current': { 'background-color': 'rgba(224,179,106,.28) !important', 'border-radius': '.2em', 'box-shadow': '0 0 0 .18em rgba(224,179,106,.18)' }
    };
  }
  function applyReaderTheme() {
    if (state.rendition) {
      try { state.rendition.themes.default(epubThemeRules()); } catch (error) { console.warn('No se pudieron actualizar los estilos del EPUB', error); }
    }
  }
  function injectAudioStyle(contents) {
    try {
      const doc = contents.document;
      if (!$('#nr-audio-style', doc)) {
        const style = doc.createElement('style');
        style.id = 'nr-audio-style';
        style.textContent = '.nr-audio-current{background:rgba(224,179,106,.28)!important;border-radius:.2em;box-shadow:0 0 0 .18em rgba(224,179,106,.18)}.nr-audio-start{outline:2px solid rgba(224,179,106,.9)!important;outline-offset:4px;border-radius:.2em}.nr-selecting-audio-start p,.nr-selecting-audio-start h1,.nr-selecting-audio-start h2,.nr-selecting-audio-start h3,.nr-selecting-audio-start h4,.nr-selecting-audio-start blockquote,.nr-selecting-audio-start li{cursor:pointer}.nr-selecting-audio-start p:hover,.nr-selecting-audio-start h1:hover,.nr-selecting-audio-start h2:hover,.nr-selecting-audio-start h3:hover,.nr-selecting-audio-start h4:hover,.nr-selecting-audio-start blockquote:hover,.nr-selecting-audio-start li:hover{background:rgba(224,179,106,.18)!important}';
        doc.head.appendChild(style);
      }
      doc.body?.classList.toggle('nr-selecting-audio-start', state.audio.selectionMode);
      for (const node of doc.querySelectorAll('p, h1, h2, h3, h4, blockquote, li')) {
        if (state.audio.startCfi && nodeCfi(node, contents) === state.audio.startCfi) {
          node.classList.add('nr-audio-start');
          state.audio.startNode = node;
        }
        if (node.dataset.nrAudioSelectable === 'true') continue;
        node.dataset.nrAudioSelectable = 'true';
        node.addEventListener('click', event => {
          if (!state.audio.selectionMode) return;
          event.preventDefault();
          event.stopPropagation();
          chooseAudioStart(node, contents);
        });
      }
    } catch { /* Some EPUB documents disallow style injection. */ }
  }
  function flattenToc(items, depth = 0) {
    return (items || []).flatMap(item => [{ label: item.label || item.title || 'Sección', href: item.href, depth }, ...flattenToc(item.subitems || item.children || [], depth + 1)]);
  }
  function normalizedHref(value = '') {
    try { return decodeURIComponent(String(value).split('#')[0].replace(/^\.\//, '')); }
    catch { return String(value).split('#')[0].replace(/^\.\//, ''); }
  }
  function spinePositionForHref(value = '') {
    const href = normalizedHref(value);
    const items = [...(state.epub?.spine?.spineItems || [])];
    if (!items.length && state.epub?.spine?.each) state.epub.spine.each(item => items.push(item));
    let index = items.findIndex(item => normalizedHref(item.href) === href);
    if (index < 0 && href) index = items.findIndex(item => href.endsWith(normalizedHref(item.href)) || normalizedHref(item.href).endsWith(href));
    return { items, index };
  }
  function currentSpinePosition() {
    return spinePositionForHref(state.location?.start?.href || '');
  }
  function clampProgress(value) {
    return Math.max(0, Math.min(1, Number.isFinite(Number(value)) ? Number(value) : 0));
  }
  function exactProgressFromCfi(cfi) {
    if (!state.locationsReady || !cfi || !state.epub?.locations) return null;
    try {
      const value = state.epub.locations.percentageFromCfi(cfi);
      return Number.isFinite(value) ? clampProgress(value) : null;
    } catch { return null; }
  }
  function progressForLocation(location = state.location) {
    const exact = exactProgressFromCfi(location?.start?.cfi);
    if (exact != null) return exact;
    const { items, index } = spinePositionForHref(location?.start?.href || '');
    if (index < 0 || !items.length) return state.current?.progress?.percent || 0;
    const displayed = location?.start?.displayed;
    const local = displayed?.total > 0 ? Math.max(0, (Number(displayed.page || 1) - 1) / displayed.total) : 0;
    return clampProgress((index + local) / items.length);
  }
  function updateReaderProgress(value, cfi = '') {
    if (!state.current) return;
    const progression = clampProgress(value);
    state.current.progress = { cfi: cfi || state.current.progress?.cfi || '', percent: progression };
    const percentage = Math.round(progression * 100);
    const bar = $('#reader-progress');
    const label = $('#reader-progress-label');
    if (bar) bar.style.width = `${percentage}%`;
    if (label) label.textContent = `${percentage}%`;
  }
  async function prepareEpubLocations() {
    const epub = state.epub;
    const bookId = state.current?.id;
    if (!epub || !bookId) return;
    try {
      if (state.current.locationMap) epub.locations.load(state.current.locationMap);
      else {
        await epub.locations.generate(1200);
        if (state.epub !== epub || state.current?.id !== bookId) return;
        state.current.locationMap = epub.locations.save();
        saveCurrentBook();
      }
      if (state.epub !== epub || state.current?.id !== bookId) return;
      state.locationsReady = true;
      updateReaderProgress(progressForLocation(), state.location?.start?.cfi || '');
      saveCurrentBook();
    } catch (error) {
      console.warn('No se pudo generar el mapa exacto de progreso; se usará el avance por capítulo.', error);
    }
  }
  function syncChapterControls() {
    const { items, index } = currentSpinePosition();
    const previous = $('#chapter-previous');
    const next = $('#chapter-next');
    if (previous) previous.disabled = index <= 0;
    if (next) next.disabled = index < 0 || index >= items.length - 1;
    const href = normalizedHref(state.location?.start?.href || items[index]?.href || '');
    const tocItem = state.toc.find(item => {
      const itemHref = normalizedHref(item.href);
      return itemHref === href || href.endsWith(itemHref) || itemHref.endsWith(href);
    });
    const label = $('#reader-chapter-title');
    if (label) label.textContent = tocItem?.label || (index >= 0 ? `Sección ${index + 1} de ${items.length}` : '');
  }
  async function navigateChapter(direction) {
    const { items, index } = currentSpinePosition();
    const target = index >= 0 ? items[index + direction] : null;
    if (!target) { toast(direction < 0 ? 'Este es el primer capítulo.' : 'Este es el último capítulo.'); return; }
    await stopAudio();
    state.audio.selectionMode = false;
    state.audio.startNode = null;
    await state.rendition.display(target.href);
    syncChapterControls();
  }
  function saveCurrentBook() {
    if (!state.current) return;
    state.current.lastOpened = Date.now();
    state.current.bookmarks = state.readerMarks;
    putBook(state.current).catch(error => console.error('No se pudo guardar el libro', error));
  }
  function closeReader(save = true) {
    if (state.screen !== 'reader' && !state.epub) return;
    if (save) saveCurrentBook();
    stopAudio();
    if (state.epub) { try { state.epub.destroy(); } catch { /* no-op */ } }
    state.audio.selectionMode = false;
    state.audio.startNode = null;
    state.epub = null; state.rendition = null; state.current = null; state.location = null; state.locationsReady = false;
    document.body.classList.remove('reading-mode');
  }
  function syncBookmarkButton() {
    const button = $('#bookmark-current');
    if (!button || !state.current) return;
    const cfi = state.location?.start?.cfi;
    const found = state.readerMarks.some(mark => mark.kind === 'reading' && mark.cfi === cfi);
    button.classList.toggle('saved', found);
    button.textContent = found ? '◆' : '♧';
  }

  function currentCfi() { return state.location?.start?.cfi || ''; }
  function addManualBookmark() {
    const cfi = currentCfi();
    if (!cfi || !state.current) return;
    const index = state.readerMarks.findIndex(mark => mark.kind === 'reading' && mark.cfi === cfi);
    if (index >= 0) {
      state.readerMarks.splice(index, 1);
      toast('Marca quitada.');
    } else {
      state.readerMarks.push({ id: window.crypto?.randomUUID?.() || String(Date.now()), cfi, label: `Lectura · ${Math.round((state.current.progress?.percent || 0) * 100)}%`, kind: 'reading', createdAt: Date.now() });
      toast('Párrafo marcado.');
    }
    saveCurrentBook(); syncBookmarkButton(); renderSheetPanel();
  }

  function renderSheetPanel() {
    const panel = $('#sheet-panel');
    if (!panel || !state.current) return;
    $$('.sheet-tab', $('#reader-sheet')).forEach(tab => tab.classList.toggle('active', tab.dataset.panelTab === state.panelTab));
    if (state.panelTab === 'reading') {
      panel.innerHTML = `<section class="reader-card"><h3>TEMA</h3><div class="theme-options">${[['light','Claro'],['dark','Oscuro'],['violet','Violeta']].map(([key,label]) => `<button class="theme-choice ${state.prefs.theme === key ? 'active' : ''}" data-theme-choice="${key}">${label}</button>`).join('')}</div><h3 style="margin-top:20px">TIPOGRAFÍA</h3><div class="font-options">${['Georgia','Palatino','Garamond','Arial','Monospace'].map(font => `<button class="font-choice ${state.prefs.font === font ? 'active' : ''}" data-font-choice="${font}" style="font-family:${esc(font)}">Aa<br><small>${font}</small></button>`).join('')}</div><div class="reader-control"><button data-reader-size="-1">A−</button><output>${state.prefs.fontSize}px</output><button data-reader-size="1">A+</button></div><label class="field-label">Interlineado <output class="range-value">${Number(state.prefs.lineHeight).toFixed(2)}</output></label><input data-pref="lineHeight" type="range" min="1.3" max="2.3" step="0.05" value="${state.prefs.lineHeight}"><label class="field-label">Espacio entre párrafos <output class="range-value">${Number(state.prefs.paragraphSpace).toFixed(1)}×</output></label><input data-pref="paragraphSpace" type="range" min="0" max="2.5" step="0.1" value="${state.prefs.paragraphSpace}"><div class="theme-options" style="margin-top:12px"><button class="theme-choice ${state.prefs.alignment === 'left' ? 'active' : ''}" data-alignment="left">Izquierda</button><button class="theme-choice ${state.prefs.alignment === 'justify' ? 'active' : ''}" data-alignment="justify">Justificado</button></div></section>`;
    } else if (state.panelTab === 'audio') {
      const chosenStart = state.audio.startText ? `Párrafo elegido: «${esc(state.audio.startText.slice(0, 120))}${state.audio.startText.length > 120 ? '…' : ''}»` : 'Si no eliges uno, la lectura comenzará desde el primer párrafo visible.';
      panel.innerHTML = `<section class="reader-card" style="text-align:center"><h3>Lectura en voz alta</h3><p>Voz M5 · Supertonic · Español</p><div id="audio-panel-status" class="audio-status" style="margin-top:7px">${audioStatusText()}</div></section><div class="audio-controls"><button data-action="audio-previous" aria-label="Párrafo anterior">|◀</button><button class="primary" data-action="audio-toggle" aria-label="Reproducir o pausar">${state.audio.playing ? 'Ⅱ' : '▶'}</button><button data-action="audio-next" aria-label="Párrafo siguiente">▶|</button></div><section class="reader-card"><h3>INICIO DE LA LECTURA</h3><p class="audio-start-description">${chosenStart}</p><button class="primary-button audio-start-button" data-action="select-audio-start">Elegir párrafo inicial</button></section><section class="reader-card"><h3>VELOCIDAD <span class="range-value">${Number(state.prefs.speed).toFixed(2)}×</span></h3><input data-pref="speed" type="range" min="0.5" max="2" step="0.05" value="${state.prefs.speed}"><div class="helper">El audio se procesa en el servidor M5 configurado en Ajustes.</div></section><section class="reader-card"><h3>CONTINUACIÓN AUTOMÁTICA</h3><p>Al terminar un capítulo, continuará desde el inicio del siguiente. En el último capítulo se detendrá.</p></section><section class="reader-card"><h3>Autoguardado</h3><p>La última frase de audio se guarda como «Autoguardado» en Marcas.</p></section>`;
    } else if (state.panelTab === 'toc') {
      panel.innerHTML = `<section class="reader-card"><h3>ÍNDICE DEL EPUB</h3>${state.toc.length ? `<div class="toc-list">${state.toc.map(item => `<button class="toc-item" data-toc-href="${esc(item.href)}" style="padding-left:${8 + item.depth * 15}px">${esc(item.label)}</button>`).join('')}</div>` : '<p>Este EPUB no incluye un índice navegable. Puedes seguir leyendo normalmente.</p>'}</section>`;
    } else {
      const marks = [...state.readerMarks].sort((a,b) => (b.createdAt || 0) - (a.createdAt || 0));
      panel.innerHTML = `<section class="reader-card"><h3>MARCAS GUARDADAS</h3>${marks.length ? `<div class="bookmark-list">${marks.map(mark => `<button class="bookmark-item" data-mark-id="${esc(mark.id)}">${mark.kind === 'auto-audio' ? '▶ ' : '♧ '}${esc(mark.label || 'Marcador')}<small>${mark.kind === 'auto-audio' ? 'Autoguardado de audio' : 'Marcador manual'}</small></button>`).join('')}</div>` : '<p>Aún no tienes marcas. Toca el icono de marcador para guardar una posición.</p>'}</section>`;
    }
  }
  function audioStatusText() {
    if (state.audio.playing) return state.audio.active ? 'Reproduciendo con M5' : 'Conectando o preparando audio…';
    return state.prefs.ttsUrl ? 'Pausado' : 'Configura el servidor M5 en Ajustes.';
  }

  function showPanel(tab = state.panelTab) {
    state.panelTab = tab;
    state.panelOpen = true;
    $('#reader-sheet')?.classList.add('open');
    renderSheetPanel();
  }
  function hidePanel() {
    state.panelOpen = false;
    $('#reader-sheet')?.classList.remove('open');
  }
  function openSearch() {
    const dialog = $('#reader-search');
    if (!dialog) return;
    dialog.hidden = false;
    $('#book-search-input')?.focus();
  }
  function closeSearch() { const dialog = $('#reader-search'); if (dialog) dialog.hidden = true; }

  async function searchBook(query) {
    const resultsNode = $('#search-results');
    if (!resultsNode || !state.epub) return;
    const job = ++state.searchJob;
    resultsNode.innerHTML = '<p class="helper">Buscando en el EPUB…</p>';
    const term = query.trim().toLocaleLowerCase('es');
    if (!term) { resultsNode.innerHTML = '<p class="helper">Escribe algo para buscar.</p>'; return; }
    const results = [];
    try {
      await state.epub.loaded.spine;
      const items = [];
      state.epub.spine.each(item => items.push(item));
      for (const item of items) {
        if (job !== state.searchJob) return;
        const doc = await state.epub.load(item.href);
        const blocks = [...doc.querySelectorAll('p, h1, h2, h3, h4, li, blockquote')];
        for (const element of blocks) {
          const text = (element.textContent || '').replace(/\s+/g, ' ').trim();
          const lower = text.toLocaleLowerCase('es');
          let from = 0;
          while (results.length < 60) {
            const index = lower.indexOf(term, from);
            if (index < 0) break;
            let cfi = item.href;
            try {
              const range = doc.createRange();
              const walker = doc.createTreeWalker(element, NodeFilter.SHOW_TEXT);
              let node; let offset = index; let found = false;
              while ((node = walker.nextNode())) {
                if (offset <= node.textContent.length) {
                  range.setStart(node, offset);
                  range.setEnd(node, Math.min(node.textContent.length, offset + term.length));
                  found = true; break;
                }
                offset -= node.textContent.length;
              }
              if (found && state.epub.locations?.cfiFromRange) cfi = state.epub.locations.cfiFromRange(range);
            } catch { /* Keep the spine href as a chapter fallback. */ }
            const start = Math.max(0, index - 55);
            const end = Math.min(text.length, index + term.length + 90);
            results.push({ href: item.href, cfi, title: item.idref || item.href, snippet: `${start ? '…' : ''}${text.slice(start, end)}${end < text.length ? '…' : ''}` });
            from = index + Math.max(1, term.length);
          }
          if (results.length >= 60) break;
        }
        try { state.epub.unload(item.href); } catch { /* Ignore cache cleanup errors. */ }
        if (results.length >= 60) break;
        await new Promise(resolve => setTimeout(resolve, 0));
      }
      if (job !== state.searchJob) return;
      resultsNode.innerHTML = results.length ? results.map((result, index) => `<button class="search-result" data-search-index="${index}"><strong>Coincidencia ${index + 1}</strong><span>${esc(result.snippet)}</span><small>${esc(result.href.split('/').pop())}</small></button>`).join('') : '<p class="helper">No hubo coincidencias en este EPUB.</p>';
      state.searchResults = results;
    } catch (error) {
      console.error(error);
      resultsNode.innerHTML = '<p class="helper">No se pudo completar la búsqueda en este EPUB.</p>';
    }
  }

  function nodeCfi(node, contents) {
    try {
      const range = node.ownerDocument.createRange();
      range.selectNodeContents(node);
      if (typeof contents?.cfiFromRange === 'function') return contents.cfiFromRange(range);
    } catch { /* Fall back to the current reader position. */ }
    return currentCfi();
  }

  function setAudioSelectionMode(enabled) {
    state.audio.selectionMode = enabled;
    for (const contents of state.rendition?.getContents?.() || []) {
      injectAudioStyle(contents);
      contents.document.body?.classList.toggle('nr-selecting-audio-start', enabled);
    }
  }

  function chooseAudioStart(node, contents) {
    const text = (node.innerText || node.textContent || '').replace(/\s+/g, ' ').trim();
    if (!text || !state.current) return;
    const cfi = nodeCfi(node, contents);
    for (const rendered of state.rendition?.getContents?.() || []) {
      $$('.nr-audio-start', rendered.document).forEach(item => item.classList.remove('nr-audio-start'));
    }
    node.classList.add('nr-audio-start');
    state.audio.startCfi = cfi;
    state.audio.startText = text;
    state.audio.startNode = node;
    state.current.audioStart = { cfi, text };
    saveCurrentBook();
    setAudioSelectionMode(false);
    const mini = $('#mini-status');
    if (mini) mini.textContent = `Inicio: ${text}`;
    toast('Párrafo inicial seleccionado. Pulsa reproducir.');
  }

  async function beginAudioStartSelection() {
    if (state.audio.playing) await stopAudio();
    hidePanel();
    setAudioSelectionMode(true);
    toast('Toca el párrafo desde el que quieres comenzar.');
  }

  function visibleAudioParagraphs() {
    if (!state.rendition || !state.epub) return [];
    const found = [];
    const seen = new Set();
    for (const content of state.rendition.getContents()) {
      const doc = content.document;
      injectAudioStyle(content);
      const nodes = [...doc.querySelectorAll('p, h1, h2, h3, h4, blockquote, li')];
      const chapter = spinePositionForHref(content.section?.href || state.location?.start?.href || '');
      for (const [nodeIndex, node] of nodes.entries()) {
        const text = (node.innerText || node.textContent || '').replace(/\s+/g, ' ').trim();
        if (!text || seen.has(node) || node.closest('li') && node.tagName.toLowerCase() === 'p' && node.closest('li').textContent.trim() === text) continue;
        seen.add(node);
        const cfi = nodeCfi(node, content);
        const fallbackProgress = chapter.index >= 0 && chapter.items.length
          ? clampProgress((chapter.index + (nodeIndex + 1) / Math.max(1, nodes.length)) / chapter.items.length)
          : progressForLocation();
        found.push({ text, cfi, node, fallbackProgress });
      }
    }
    if (!found.length) return found;
    const selected = found.findIndex(item => item.node === state.audio.startNode || (state.audio.startCfi && item.cfi === state.audio.startCfi));
    if (selected >= 0) return found.slice(selected);
    const visible = found.filter(item => {
      const rect = item.node.getBoundingClientRect();
      const frame = item.node.ownerDocument.defaultView?.frameElement;
      const frameRect = frame?.getBoundingClientRect?.() || { top: 0, left: 0 };
      const top = frameRect.top + rect.top;
      const bottom = frameRect.top + rect.bottom;
      const left = frameRect.left + rect.left;
      const right = frameRect.left + rect.right;
      return bottom > 64 && top < window.innerHeight && right > 0 && left < window.innerWidth;
    });
    const viewportTop = item => {
      const rect = item.node.getBoundingClientRect();
      const frameTop = item.node.ownerDocument.defaultView?.frameElement?.getBoundingClientRect?.().top || 0;
      return frameTop + rect.top;
    };
    const startNode = visible.sort((a,b) => Math.abs(viewportTop(a) - 72) - Math.abs(viewportTop(b) - 72))[0];
    if (!startNode) return found;
    const start = found.indexOf(startNode);
    return found.slice(start);
  }

  async function startAudio(startIndex = 0) {
    if (!state.prefs.ttsUrl) { showPanel('audio'); toast('Configura la URL del servidor M5 en Ajustes.'); return; }
    await stopAudio();
    const audio = state.audio;
    audio.generation++;
    const generation = audio.generation;
    audio.segments = visibleAudioParagraphs();
    if (!audio.segments.length) { toast('No encontré texto para leer en esta sección.'); return; }
    audio.segmentIndex = Math.max(0, Math.min(startIndex, audio.segments.length - 1));
    audio.requested = audio.segmentIndex;
    audio.nextId = 1;
    audio.ready.clear(); audio.incoming = null; audio.active = false; audio.pending = false;
    audio.playing = true;
    audio.context = audio.context || new (window.AudioContext || window.webkitAudioContext)();
    await audio.context.resume();
    updateAudioButtons();
    const url = state.prefs.ttsUrl.trim();
    let socket;
    try { socket = new WebSocket(url); } catch (error) { audio.playing = false; updateAudioButtons(); toast(`URL TTS inválida: ${error.message}`); return; }
    socket.binaryType = 'arraybuffer'; audio.socket = socket;
    socket.onopen = () => {
      if (generation !== audio.generation) return;
      if (state.prefs.ttsToken) {
        socket.send(JSON.stringify({ type: 'auth', token: state.prefs.ttsToken }));
      } else sendNextAudioRequest(generation);
      updateAudioStatus('Conectado · preparando audio M5');
    };
    socket.onmessage = event => handleTtsMessage(event, generation);
    socket.onerror = () => { if (generation === audio.generation) { updateAudioStatus('No se pudo conectar al servidor de voz'); toast('Error de conexión con el servidor M5.'); } };
    socket.onclose = event => {
      if (generation !== audio.generation) return;
      if (audio.playing) {
        audio.playing = false; updateAudioButtons();
        if (event.code !== 1000) updateAudioStatus(`Conexión cerrada (${event.code})`);
      }
    };
  }
  async function stopAudio() {
    const audio = state.audio;
    audio.generation++;
    audio.playing = false;
    if (audio.source) { try { audio.source.stop(); } catch { /* already stopped */ } audio.source = null; }
    audio.active = false;
    if (audio.socket) {
      try { if (audio.socket.readyState === WebSocket.OPEN) audio.socket.send(JSON.stringify({ type: 'cancel', sessionId: `web-${state.current?.id || 'reader'}` })); audio.socket.close(1000); } catch { /* no-op */ }
    }
    audio.socket = null; audio.incoming = null; audio.ready.clear();
    clearAudioHighlight();
    updateAudioButtons();
  }
  function sendNextAudioRequest(generation = state.audio.generation) {
    const audio = state.audio;
    if (generation !== audio.generation || !audio.playing || !audio.socket || audio.socket.readyState !== WebSocket.OPEN) return;
    if (audio.requested >= audio.segments.length) return;
    const index = audio.requested++;
    const id = audio.nextId++;
    audio.segments[index].segmentId = id;
    audio.segments[index].audioIndex = index;
    audio.segments[index].sessionId = `web-${state.current.id}`;
    audio.socket.send(JSON.stringify({ type: 'tts_request', sessionId: `web-${state.current.id}`, segmentId: id, text: audio.segments[index].text, voice: 'M5', speed: Number(state.prefs.speed) }));
  }
  async function handleTtsMessage(event, generation) {
    const audio = state.audio;
    if (generation !== audio.generation) return;
    if (typeof event.data !== 'string') {
      if (audio.incoming) audio.incoming.parts.push(event.data instanceof Blob ? await event.data.arrayBuffer() : event.data);
      return;
    }
    let message;
    try { message = JSON.parse(event.data); } catch { return; }
    if (message.type === 'auth_result') {
      if (!message.ok) { updateAudioStatus('Token rechazado por el servidor'); toast('El token TTS no es válido.'); await stopAudio(); }
      else sendNextAudioRequest(generation);
    } else if (message.type === 'audio_start') {
      audio.incoming = { id: Number(message.segmentId), sampleRate: Number(message.sampleRate) || 24000, parts: [] };
    } else if (message.type === 'audio_end') {
      const incoming = audio.incoming; audio.incoming = null;
      if (!incoming) return;
      const segment = audio.segments.find(item => item.segmentId === incoming.id);
      if (!segment) return;
      const bytesLength = incoming.parts.reduce((sum, part) => sum + part.byteLength, 0);
      const bytes = new Uint8Array(bytesLength);
      let offset = 0; incoming.parts.forEach(part => { bytes.set(new Uint8Array(part), offset); offset += part.byteLength; });
      const samples = Math.floor(bytes.length / 2);
      const buffer = audio.context.createBuffer(1, samples, incoming.sampleRate);
      const floats = buffer.getChannelData(0); const view = new DataView(bytes.buffer);
      for (let i = 0; i < samples; i++) floats[i] = view.getInt16(i * 2, true) / 32768;
      audio.ready.set(segment.audioIndex, buffer);
      sendNextAudioRequest(generation); // Generate the next paragraph while this one plays.
      playReadyAudio(generation);
    } else if (message.type === 'error') {
      updateAudioStatus(message.message || 'Error generando audio');
      toast(`Error TTS: ${message.message || 'no se pudo generar el párrafo'}`);
      if (audio.segmentIndex + 1 < audio.segments.length) { audio.segmentIndex++; sendNextAudioRequest(generation); }
    } else if (message.type === 'backpressure') {
      setTimeout(() => sendNextAudioRequest(generation), 800);
    }
  }
  function playReadyAudio(generation) {
    const audio = state.audio;
    if (generation !== audio.generation || !audio.playing || audio.active) return;
    const buffer = audio.ready.get(audio.segmentIndex);
    if (!buffer) { audio.pending = true; updateAudioStatus('Preparando siguiente párrafo…'); return; }
    audio.ready.delete(audio.segmentIndex);
    const segment = audio.segments[audio.segmentIndex];
    markAudioPosition(segment);
    const source = audio.context.createBufferSource();
    source.buffer = buffer; source.connect(audio.context.destination); audio.source = source; audio.active = true; audio.pending = false;
    updateAudioStatus('Leyendo con voz M5');
    source.onended = () => {
      if (generation !== audio.generation) return;
      audio.active = false; audio.source = null; audio.segmentIndex++;
      if (audio.segmentIndex >= audio.segments.length) {
        continueAudioWithNextChapter(generation); return;
      }
      playReadyAudio(generation);
    };
    source.start();
  }
  async function continueAudioWithNextChapter(generation) {
    const audio = state.audio;
    if (generation !== audio.generation || !audio.playing) return;
    const { items, index } = currentSpinePosition();
    const target = index >= 0 ? items[index + 1] : null;
    if (!target) {
      audio.playing = false;
      clearAudioHighlight();
      updateAudioButtons();
      updateAudioStatus('Lectura terminada · último capítulo.');
      toast('Terminaste el último capítulo disponible.');
      return;
    }
    updateAudioStatus('Cambiando al siguiente capítulo…');
    await stopAudio();
    const transitionGeneration = audio.generation;
    audio.startCfi = '';
    audio.startText = '';
    audio.startNode = null;
    if (state.current) {
      state.current.audioStart = null;
      saveCurrentBook();
    }
    try {
      await state.rendition.display(target.href);
      await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
      if (transitionGeneration !== audio.generation || !state.current) return;
      syncChapterControls();
      updateAudioStatus('Preparando el siguiente capítulo…');
      await startAudio(0);
    } catch (error) {
      console.error(error);
      audio.playing = false;
      updateAudioButtons();
      updateAudioStatus('No se pudo abrir el siguiente capítulo.');
      toast('No se pudo continuar con el siguiente capítulo.');
    }
  }
  function markAudioPosition(segment) {
    clearAudioHighlight();
    segment.node?.classList.add('nr-audio-current');
    segment.node?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    if (segment.cfi && state.current) {
      const progression = exactProgressFromCfi(segment.cfi) ?? segment.fallbackProgress ?? progressForLocation();
      updateReaderProgress(progression, segment.cfi);
      const auto = state.readerMarks.find(mark => mark.kind === 'auto-audio');
      const mark = { id: auto?.id || 'auto-audio', cfi: segment.cfi, label: 'Autoguardado', kind: 'auto-audio', createdAt: Date.now() };
      if (auto) Object.assign(auto, mark); else state.readerMarks.push(mark);
      saveCurrentBook();
    }
    if ($('#mini-status')) $('#mini-status').textContent = segment.text;
    renderSheetPanelIfOpen();
  }
  function clearAudioHighlight() {
    for (const content of state.rendition?.getContents?.() || []) {
      try { $$('.nr-audio-current', content.document).forEach(node => node.classList.remove('nr-audio-current')); } catch { /* ignore */ }
    }
  }
  function updateAudioButtons() {
    $$('.play-round,[data-action="audio-toggle"]').forEach(button => { button.textContent = state.audio.playing ? 'Ⅱ' : '▶'; });
    if ($('#audio-panel-status')) $('#audio-panel-status').textContent = audioStatusText();
  }
  function updateAudioStatus(message) {
    const panel = $('#audio-panel-status'); if (panel) panel.textContent = message;
    const mini = $('#mini-status'); if (mini && (!state.audio.active || !state.audio.playing)) mini.textContent = message;
  }
  function renderSheetPanelIfOpen() { if (state.panelOpen && state.panelTab === 'marks') renderSheetPanel(); }

  async function testTtsConnection() {
    const status = $('#tts-test-status');
    const url = $('#tts-url')?.value.trim();
    const token = $('#tts-token')?.value.trim();
    if (!url) { if (status) status.textContent = 'Ingresa primero la URL.'; return; }
    if (status) status.textContent = 'Conectando…';
    let socket;
    try { socket = new WebSocket(url); } catch (error) { if (status) status.textContent = `URL inválida: ${error.message}`; return; }
    const timeout = setTimeout(() => { try { socket.close(); } catch {} if (status) status.textContent = 'Tiempo de espera agotado.'; }, 7000);
    socket.onopen = () => {
      if (token) socket.send(JSON.stringify({ type: 'auth', token }));
      else {
        clearTimeout(timeout);
        if (status) status.textContent = 'Conexión WebSocket correcta.';
        setTimeout(() => socket.close(1000), 700);
      }
    };
    socket.onmessage = event => {
      try { const data = JSON.parse(event.data); if (data.type === 'auth_result') status.textContent = data.ok ? 'Conexión y token correctos.' : 'Token rechazado.'; } catch { /* no-op */ }
      clearTimeout(timeout); setTimeout(() => socket.close(1000), 700);
    };
    socket.onerror = () => { clearTimeout(timeout); if (status) status.textContent = 'No se pudo conectar. Revisa URL y servidor.'; };
    socket.onclose = () => clearTimeout(timeout);
  }

  document.addEventListener('click', async event => {
    const openTarget = event.target.closest('[data-open-book]');
    if (openTarget) { await openBook(openTarget.dataset.openBook); return; }
    const filter = event.target.closest('[data-filter]');
    if (filter) { state.filter = filter.dataset.filter; renderHome(); return; }
    const action = event.target.closest('[data-action]')?.dataset.action;
    if (action) {
      switch (action) {
        case 'home': await refreshBooks(); renderHome(); break;
        case 'settings': renderSettings(); break;
        case 'import': fileInput.click(); break;
        case 'cycle-theme': {
          const themes = ['light','dark','violet'];
          state.prefs.theme = themes[(themes.indexOf(state.prefs.theme) + 1) % themes.length];
          savePrefs(); if (state.screen === 'home') renderHome(); else if (state.screen === 'settings') renderSettings();
          break;
        }
        case 'menu': document.querySelector('.sidebar')?.classList.toggle('mobile-open'); toast('Usa Biblioteca o Ajustes para navegar.'); break;
        case 'clear-search': state.query = ''; state.filter = 'all'; renderHome(); break;
        case 'close-reader': await refreshBooks(); closeReader(); renderHome(); break;
        case 'open-panel': state.panelOpen ? hidePanel() : showPanel(); break;
        case 'reader-search': openSearch(); break;
        case 'close-search': closeSearch(); break;
        case 'toggle-bookmark': addManualBookmark(); break;
        case 'chapter-previous': await navigateChapter(-1); break;
        case 'chapter-next': await navigateChapter(1); break;
        case 'select-audio-start': await beginAudioStartSelection(); break;
        case 'audio-toggle': if (state.audio.playing) await stopAudio(); else await startAudio(0); break;
        case 'audio-previous': await startAudio(Math.max(0, state.audio.segmentIndex - 1)); break;
        case 'audio-next': await startAudio(Math.min(state.audio.segments.length - 1, state.audio.segmentIndex + 1)); break;
        case 'save-tts': {
          state.prefs.ttsUrl = $('#tts-url')?.value.trim() || '';
          state.prefs.ttsToken = $('#tts-token')?.value.trim() || '';
          savePrefs(); toast('Servidor M5 guardado.'); break;
        }
        case 'test-tts':
          state.prefs.ttsUrl = $('#tts-url')?.value.trim() || '';
          state.prefs.ttsToken = $('#tts-token')?.value.trim() || '';
          savePrefs(); await testTtsConnection(); break;
      }
      return;
    }
    const tab = event.target.closest('[data-panel-tab]');
    if (tab) { showPanel(tab.dataset.panelTab); return; }
    const theme = event.target.closest('[data-theme-choice]');
    if (theme) { state.prefs.theme = theme.dataset.themeChoice; savePrefs(); if (state.screen === 'settings') renderSettings(); else if (state.panelOpen) renderSheetPanel(); return; }
    const font = event.target.closest('[data-font-choice]');
    if (font) { state.prefs.font = font.dataset.fontChoice; savePrefs(); if (state.panelOpen) renderSheetPanel(); return; }
    const alignment = event.target.closest('[data-alignment]');
    if (alignment) { state.prefs.alignment = alignment.dataset.alignment; savePrefs(); renderSheetPanel(); return; }
    const size = event.target.closest('[data-reader-size]');
    if (size) { state.prefs.fontSize = Math.max(13, Math.min(34, Number(state.prefs.fontSize) + Number(size.dataset.readerSize))); savePrefs(); renderSheetPanel(); return; }
    const toc = event.target.closest('[data-toc-href]');
    if (toc) { hidePanel(); state.rendition?.display(toc.dataset.tocHref); return; }
    const mark = event.target.closest('[data-mark-id]');
    if (mark) {
      const item = state.readerMarks.find(candidate => candidate.id === mark.dataset.markId);
      if (item?.cfi) { hidePanel(); state.rendition?.display(item.cfi); }
      return;
    }
    const result = event.target.closest('[data-search-index]');
    if (result) {
      const item = state.searchResults?.[Number(result.dataset.searchIndex)];
      if (item) { closeSearch(); state.rendition?.display(item.cfi || item.href); }
    }
  });

  fileInput.addEventListener('change', () => importEpub(fileInput.files?.[0]));
  document.addEventListener('input', event => {
    const input = event.target;
    if (input.id === 'library-search') { state.query = input.value; renderHome(); const next = $('#library-search'); next?.focus(); next?.setSelectionRange(next.value.length, next.value.length); return; }
    const pref = input.dataset.pref;
    if (pref) {
      state.prefs[pref] = Number(input.value);
      savePrefs();
      const output = input.closest('.range-row')?.previousElementSibling?.querySelector('output') || input.parentElement?.previousElementSibling?.querySelector('output');
      if (output) output.textContent = pref === 'speed' ? `${Number(input.value).toFixed(2)}×` : pref === 'fontSize' ? `${input.value}px` : Number(input.value).toFixed(pref === 'paragraphSpace' ? 1 : 2);
      return;
    }
    if (input.id === 'book-search-input') state.searchQuery = input.value;
  });
  document.addEventListener('submit', event => {
    if (event.target.id === 'book-search-form') { event.preventDefault(); searchBook($('#book-search-input')?.value || ''); }
  });
  document.addEventListener('change', event => {
    if (event.target.dataset.pref) savePrefs();
  });
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape') {
      if (!$('#reader-search')?.hidden) closeSearch();
      else if (state.panelOpen) hidePanel();
    }
    if (state.screen === 'reader' && !state.panelOpen && !event.ctrlKey && !event.metaKey && !['INPUT','TEXTAREA'].includes(document.activeElement?.tagName)) {
      if (event.key === 'ArrowLeft') state.rendition?.prev();
      if (event.key === 'ArrowRight') state.rendition?.next();
    }
  });

  async function init() {
    document.documentElement.dataset.theme = state.prefs.theme;
    savePrefs();
    try {
      state.db = await openDb();
      await refreshBooks();
      renderHome();
    } catch (error) {
      console.error(error);
      main.innerHTML = '<section class="page"><div class="empty-state"><div><div class="empty-icon">!</div><h2>No se pudo iniciar la biblioteca</h2><p>Este navegador no habilitó el almacenamiento local. Abre NovelReader Web desde un servidor local o HTTPS.</p></div></div></section>';
    }
  }
  init();
})();
