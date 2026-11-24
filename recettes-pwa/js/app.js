import * as db from './db.js';
import { parseText } from './parser.js';
import { importFromUrl, extractUrl } from './importer.js';
import { recipesToJson, jsonToRecipes } from './backup.js';
import { readImages } from './ocr.js';

const root = document.getElementById('app');
const busyEl = document.getElementById('busy');
const busyText = document.getElementById('busy-text');
const toastEl = document.getElementById('toast');

const state = { recipes: [], query: '', draft: null };

// ---------------------------------------------------------------- utilitaires

const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) =>
  ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const norm = (s) => String(s ?? '').normalize('NFD').replace(/\p{M}+/gu, '').toLowerCase();
const lines = (s) => String(s ?? '').split('\n').map((l) => l.trim()).filter(Boolean);

function getRelay() { try { return localStorage.getItem('relay') || ''; } catch { return ''; } }
function setRelay(v) { try { localStorage.setItem('relay', v); } catch { /* stockage indisponible */ } }

let toastTimer;
function toast(message) {
  toastEl.textContent = message;
  toastEl.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { toastEl.hidden = true; }, 5000);
}

async function withBusy(text, fn) {
  busyText.textContent = text;
  busyEl.hidden = false;
  try {
    return await fn((t) => { busyText.textContent = t; });
  } catch (e) {
    toast(e.message || String(e));
  } finally {
    busyEl.hidden = true;
  }
}

function go(path) {
  const target = `#${path}`;
  if (location.hash === target) route();
  else location.hash = target;
}

async function refresh() { state.recipes = await db.all(); }

function download(filename, text, type = 'application/json') {
  const url = URL.createObjectURL(new Blob([text], { type }));
  const a = Object.assign(document.createElement('a'), { href: url, download: filename });
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10000);
}

const backupName = () => `recettes-${new Date().toISOString().slice(0, 10)}.json`;

// ---------------------------------------------------------------- routage

function route() {
  const [name, arg] = location.hash.replace(/^#\/?/, '').split('/');
  window.scrollTo(0, 0);
  if (name === 'recipe') return renderDetail(Number(arg));
  if (name === 'edit') return renderEdit(arg === 'new' ? null : Number(arg));
  return renderList();
}

// ---------------------------------------------------------------- vues

function renderList() {
  document.title = 'Mes recettes';
  root.innerHTML = `
    <header class="bar">
      <h1>Mes recettes</h1>
      <button class="ghost" data-action="menu">Sauvegarde ▾</button>
    </header>
    <div class="search">
      <input id="q" type="search" placeholder="Rechercher (titre ou ingrédient)"
             value="${esc(state.query)}" autocomplete="off">
    </div>
    <main id="cards"></main>
    <footer class="actions">
      <button class="primary" data-action="open-link">Importer un lien</button>
      <button class="primary" data-action="open-text">Coller du texte</button>
      <button class="secondary" data-action="pick-photo">Capture / photo</button>
      <button class="secondary" data-action="take-photo">Prendre une photo</button>
      <button class="secondary wide" data-action="manual">Saisie manuelle</button>
    </footer>`;
  renderCards();
  document.getElementById('q').addEventListener('input', (e) => {
    state.query = e.target.value;
    renderCards();
  });
}

function renderCards() {
  const q = norm(state.query.trim());
  const list = state.recipes.filter((r) =>
    !q || norm(r.title).includes(q) || norm(r.ingredients).includes(q));
  const el = document.getElementById('cards');
  if (!list.length) {
    el.innerHTML = `<p class="empty">${state.query.trim()
      ? 'Aucun résultat.'
      : "Aucune recette pour l'instant.\nImporte-en une depuis un lien, un texte ou une photo, ou saisis-la à la main."}</p>`;
    return;
  }
  el.innerHTML = list.map((r) => {
    const meta = [r.totalTime, r.servings].filter(Boolean).join(' · ');
    return `<button class="card" data-action="open" data-id="${r.id}">
      <h3>${esc(r.title)}</h3>${meta ? `<div class="meta">${esc(meta)}</div>` : ''}
    </button>`;
  }).join('');
}

function renderDetail(id) {
  const r = state.recipes.find((x) => x.id === id);
  if (!r) return go('/');
  document.title = r.title;
  const meta = [r.totalTime, r.servings].filter(Boolean).join(' · ');
  const src = /^https?:\/\//i.test(r.sourceUrl || '')
    ? `<a href="${esc(r.sourceUrl)}" target="_blank" rel="noopener noreferrer">${esc(r.sourceUrl)}</a>`
    : esc(r.sourceUrl);
  root.innerHTML = `
    <header class="bar">
      <button class="ghost" data-action="back">← Retour</button>
      <h1>${esc(r.title)}</h1>
    </header>
    <div class="detail">
      ${meta ? `<p class="meta">${esc(meta)}</p>` : ''}
      <h2>Ingrédients</h2>
      <ul>${lines(r.ingredients).map((l) => `<li>${esc(l)}</li>`).join('')}</ul>
      <h2>Préparation</h2>
      <ol>${lines(r.instructions).map((l) => `<li>${esc(l)}</li>`).join('')}</ol>
      ${r.sourceUrl ? `<p class="source">Source : ${src}</p>` : ''}
    </div>
    <div class="footer-actions">
      <button class="danger" data-action="delete" data-id="${r.id}">Supprimer</button>
      <button class="primary" data-action="edit" data-id="${r.id}">Modifier</button>
    </div>`;
}

function renderEdit(id) {
  const existing = id != null ? state.recipes.find((x) => x.id === id) : null;
  if (id != null && !existing) return go('/');
  const r = existing || state.draft || {};
  document.title = existing ? 'Modifier la recette' : 'Nouvelle recette';
  root.innerHTML = `
    <header class="bar">
      <button class="ghost" data-action="cancel-edit" data-id="${id ?? ''}">← Annuler</button>
      <h1>${existing ? 'Modifier la recette' : 'Nouvelle recette'}</h1>
    </header>
    <form class="edit" id="form-edit">
      <label for="f-title">Titre</label>
      <input id="f-title" name="title" required value="${esc(r.title)}">
      <div class="row2">
        <div><label for="f-time">Durée</label>
          <input id="f-time" name="totalTime" value="${esc(r.totalTime)}"></div>
        <div><label for="f-serv">Portions</label>
          <input id="f-serv" name="servings" value="${esc(r.servings)}"></div>
      </div>
      <label for="f-ing">Ingrédients (un par ligne)</label>
      <textarea id="f-ing" name="ingredients" rows="8">${esc(r.ingredients)}</textarea>
      <label for="f-ins">Étapes (une par ligne)</label>
      <textarea id="f-ins" name="instructions" rows="10">${esc(r.instructions)}</textarea>
      <p></p>
      <button class="primary" type="submit" style="width:100%">Enregistrer</button>
    </form>`;

  document.getElementById('form-edit').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = new FormData(e.target);
    const recipe = {
      title: f.get('title').trim(),
      ingredients: f.get('ingredients').trim(),
      instructions: f.get('instructions').trim(),
      totalTime: f.get('totalTime').trim(),
      servings: f.get('servings').trim(),
      sourceUrl: r.sourceUrl || '',
      createdAt: existing ? existing.createdAt : Date.now(),
    };
    if (!recipe.title) return;
    let savedId = id;
    if (existing) {
      await db.put({ ...recipe, id });
    } else {
      savedId = await db.add(recipe);
    }
    state.draft = null;
    await refresh();
    go(`/recipe/${savedId}`);
  });
}

// ---------------------------------------------------------------- imports

function openDraft(recipe) {
  state.draft = recipe;
  go('/edit/new');
}

async function importLink(rawUrl) {
  await withBusy('Récupération de la recette…', async () => {
    const recipe = await importFromUrl(rawUrl, getRelay().trim());
    const already = state.recipes.some((r) => r.sourceUrl && r.sourceUrl === recipe.sourceUrl);
    if (already) toast('Cette recette est déjà enregistrée (tu peux quand même la sauvegarder).');
    openDraft(recipe);
  });
}

function importText(text) {
  const recipe = parseText(text);
  if (!recipe) return toast('Le texte est vide : rien à mettre en forme.');
  openDraft(recipe);
}

async function importImages(files) {
  if (!files.length) return;
  await withBusy('Lecture de l\'image…', async (setText) => {
    const text = await readImages(files, setText);
    const recipe = text.trim() ? parseText(text) : null;
    if (!recipe) throw new Error('Aucun texte détecté. Essaie avec une image plus nette et bien cadrée.');
    openDraft(recipe);
  });
}

// ---------------------------------------------------------------- sauvegarde

async function exportBackup() {
  if (!state.recipes.length) return toast('Aucune recette à exporter.');
  download(backupName(), recipesToJson(state.recipes));
  toast(`Sauvegarde créée : ${state.recipes.length} recette(s) exportée(s).`);
}

async function shareBackup() {
  if (!state.recipes.length) return toast('Aucune recette à partager.');
  const file = new File([recipesToJson(state.recipes)], backupName(), { type: 'application/json' });
  if (navigator.canShare && navigator.canShare({ files: [file] })) {
    try {
      await navigator.share({ files: [file], title: 'Mes recettes' });
    } catch (e) {
      if (e.name !== 'AbortError') toast('Partage impossible : ' + e.message);
    }
  } else {
    download(file.name, await file.text());
    toast("Le partage direct n'est pas disponible ici : le fichier a été téléchargé.");
  }
}

async function importBackup(file) {
  await withBusy('Import de la sauvegarde…', async () => {
    const incoming = jsonToRecipes(await file.text());
    let added = 0;
    let skipped = 0;
    const known = new Set(state.recipes.map((r) => `${r.title}\n${r.ingredients}`));
    for (const r of incoming) {
      const key = `${r.title}\n${r.ingredients}`;
      if (known.has(key)) { skipped++; continue; }
      await db.add(r);
      known.add(key);
      added++;
    }
    await refresh();
    route();
    toast(`Import terminé : ${added} recette(s) ajoutée(s), ${skipped} déjà présente(s).`);
  });
}

// ---------------------------------------------------------------- événements

const $ = (id) => document.getElementById(id);

root.addEventListener('click', async (e) => {
  const el = e.target.closest('[data-action]');
  if (!el) return;
  const id = Number(el.dataset.id);
  switch (el.dataset.action) {
    case 'open': return go(`/recipe/${id}`);
    case 'back': return go('/');
    case 'edit': return go(`/edit/${id}`);
    case 'cancel-edit': state.draft = null; return go(el.dataset.id ? `/recipe/${id}` : '/');
    case 'manual': state.draft = null; return go('/edit/new');
    case 'delete': {
      const r = state.recipes.find((x) => x.id === id);
      if (r && confirm(`Supprimer « ${r.title} » ?`)) {
        await db.remove(id);
        await refresh();
        go('/');
      }
      return;
    }
    case 'open-link': $('link-url').value = ''; return $('dlg-link').showModal();
    case 'open-text': $('text-body').value = ''; return $('dlg-text').showModal();
    case 'pick-photo': return $('in-gallery').click();
    case 'take-photo': return $('in-camera').click();
    case 'menu': return $('dlg-menu').showModal();
  }
});

// Menu Sauvegarde (fenêtre séparée de #app)
$('dlg-menu').addEventListener('click', (e) => {
  const el = e.target.closest('[data-action]');
  if (!el) return;
  $('dlg-menu').close();
  switch (el.dataset.action) {
    case 'export': return exportBackup();
    case 'share': return shareBackup();
    case 'import-backup': return $('in-backup').click();
    case 'settings':
      $('relay-url').value = getRelay();
      return $('dlg-settings').showModal();
  }
});

document.querySelectorAll('dialog').forEach((dlg) => {
  dlg.addEventListener('click', (e) => {
    if (e.target.closest('[data-close]') || e.target === dlg) dlg.close();
  });
});

$('form-link').addEventListener('submit', () => {
  const value = $('link-url').value.trim();
  $('dlg-link').close();
  if (value) importLink(extractUrl(value) || value);
});
$('form-text').addEventListener('submit', () => {
  const value = $('text-body').value;
  $('dlg-text').close();
  if (value.trim()) importText(value);
});
$('form-settings').addEventListener('submit', () => {
  setRelay($('relay-url').value.trim().replace(/\/+$/, ''));
  $('dlg-settings').close();
  toast('Réglages enregistrés.');
});

for (const id of ['in-gallery', 'in-camera']) {
  $(id).addEventListener('change', (e) => {
    const files = [...e.target.files];
    e.target.value = '';
    importImages(files);
  });
}
$('in-backup').addEventListener('change', (e) => {
  const file = e.target.files[0];
  e.target.value = '';
  if (file) importBackup(file);
});

window.addEventListener('hashchange', route);

// ---------------------------------------------------------------- démarrage

async function init() {
  try {
    await refresh();
  } catch {
    root.innerHTML = '<p class="empty">Le stockage du navigateur est indisponible (navigation privée ?). L\'application ne peut pas enregistrer de recettes.</p>';
    return;
  }
  if (navigator.storage && navigator.storage.persist) navigator.storage.persist().catch(() => {});

  // Lien ou texte reçu via le partage Android (PWA installée) : /?title=…&text=…&url=…
  const params = new URLSearchParams(location.search);
  const shared = [params.get('url'), params.get('text'), params.get('title')].filter(Boolean).join('\n');
  if (shared) {
    history.replaceState(null, '', location.pathname);
    const url = extractUrl(shared);
    if (url && shared.length - url.length < 200) { route(); return importLink(url); }
    route();
    return importText(shared);
  }
  route();
}

if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('sw.js').catch(() => { /* hors HTTPS ou non supporté */ });
  });
}

init();
