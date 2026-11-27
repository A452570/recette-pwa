// Import d'une recette depuis une page web, via les données structurées schema.org/Recipe (JSON-LD).
//
// Un navigateur n'a pas le droit de lire une autre page web (restriction CORS). Pour que l'import
// par lien fonctionne, il faut un petit "relais" (voir worker/relay.js et le README) dont l'adresse
// se renseigne dans Réglages. Sans relais, on essaie quand même en direct (rarement possible).

import { parsePage } from './parser.js';

const URL_RE = /https?:\/\/[^\s]+/i;

export const extractUrl = (text) => {
  const m = URL_RE.exec(text);
  return m ? m[0].replace(/[.,)]+$/, '') : null;
};

export async function importFromUrl(rawUrl, relay) {
  let url = rawUrl.trim();
  if (!/^https?:\/\//i.test(url)) url = `https://${url}`;

  let html;
  try {
    const target = relay ? `${relay}${relay.includes('?') ? '&' : '?'}url=${encodeURIComponent(url)}` : url;
    const resp = await fetch(target);
    if (!resp.ok) throw new Error(`Le site a répondu avec l'erreur HTTP ${resp.status}.`);
    html = await resp.text();
  } catch (e) {
    if (e instanceof TypeError) {
      throw new Error(relay
        ? "Impossible de joindre le relais ou le site. Vérifie l'adresse du relais dans Réglages et ta connexion."
        : "Le navigateur ne peut pas lire ce site directement. Configure un relais dans Réglages, ou copie le texte de la recette et utilise « Coller du texte ».");
    }
    throw e;
  }

  const doc = new DOMParser().parseFromString(html, 'text/html');
  for (const script of doc.querySelectorAll('script[type="application/ld+json"]')) {
    let json;
    try { json = JSON.parse(script.textContent.trim()); } catch { continue; }
    const node = findRecipe(json);
    if (node) return toRecipe(node, url);
  }
  // Pas de données structurées : on analyse le texte visible de la page.
  const title = pageTitle(doc);
  const recipe = parsePage(pageLines(doc), title);
  if (recipe) return { ...recipe, sourceUrl: url };
  throw new Error("Aucune recette trouvée sur cette page. Copie le texte de la recette et utilise « Coller du texte ».");
}

const NOISE = 'script,style,noscript,nav,footer,aside,form,iframe,svg,button,select,' +
  '[class*="comment"],[id*="comment"],[class*="sidebar"],[id*="sidebar"],[class*="share"]';
const BLOCK = new Set(['P', 'DIV', 'LI', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'BR', 'TR', 'UL', 'OL',
  'SECTION', 'ARTICLE', 'TABLE', 'BLOCKQUOTE', 'FIGURE', 'FIGCAPTION']);

function pageTitle(doc) {
  const raw = doc.querySelector('h1')?.textContent
    || doc.querySelector('meta[property="og:title"]')?.getAttribute('content') || '';
  return raw.replace(/\s+[|–—-]\s+[^|–—-]*$/, '').replace(/\s+/g, ' ').trim();
}

/** Texte visible de la page, une ligne par bloc (les éléments de menu, commentaires… sont écartés). */
function pageLines(doc) {
  for (const n of doc.querySelectorAll(NOISE)) {
    if (n.matches('body,html') || n.querySelector('h1,article,main')) continue;
    n.remove();
  }
  const out = [];
  const walk = (node) => {
    if (node.nodeType === 3) { out.push(node.nodeValue); return; }
    if (node.nodeType !== 1) return;
    const block = BLOCK.has(node.tagName);
    if (block) out.push('\n');
    if (node.tagName === 'LI') out.push('- ');
    node.childNodes.forEach(walk);
    if (block) out.push('\n');
  };
  walk(doc.body);
  return out.join('').replace(/\u00a0/g, ' ').replace(/\n- *\n+/g, '\n- ').split('\n')
    .map((l) => l.trim()).filter(Boolean);
}

function findRecipe(node) {
  if (Array.isArray(node)) {
    for (const n of node) { const r = findRecipe(n); if (r) return r; }
  } else if (node && typeof node === 'object') {
    if (isRecipeType(node['@type'])) return node;
    return findRecipe(node['@graph']);
  }
  return null;
}

const isRecipeType = (t) =>
  Array.isArray(t) ? t.some((x) => String(x).toLowerCase() === 'recipe')
    : typeof t === 'string' && t.toLowerCase() === 'recipe';

function clean(s) {
  if (s == null) return '';
  const text = new DOMParser().parseFromString(String(s), 'text/html').documentElement.textContent;
  return text.replace(/ /g, ' ').trim();
}

function toRecipe(json, sourceUrl) {
  const raw = json.recipeIngredient ?? json.ingredients;
  let ingredients = [];
  if (Array.isArray(raw)) ingredients = raw.map(clean);
  else if (typeof raw === 'string') ingredients = raw.split('\n').map(clean);

  const steps = [];
  collectSteps(json.recipeInstructions, steps);

  return {
    title: clean(json.name) || 'Recette sans titre',
    ingredients: ingredients.filter(Boolean).join('\n'),
    instructions: steps.join('\n'),
    sourceUrl,
    totalTime: formatDuration(json.totalTime || json.cookTime || ''),
    servings: formatServings(json.recipeYield),
  };
}

/** "6" devient "6 portions" ; un texte comme "6 parts" est conservé tel quel. */
function formatServings(value) {
  const list = Array.isArray(value) ? value : [value];
  const texts = list.map(clean).filter(Boolean);
  const text = texts.find((t) => /\D/.test(t)) || texts[0] || '';
  return /^\d+$/.test(text) ? `${text} portions` : text;
}

function collectSteps(node, out) {
  if (typeof node === 'string') {
    node.split('\n').map(clean).filter(Boolean).forEach((s) => out.push(s));
  } else if (Array.isArray(node)) {
    node.forEach((n) => collectSteps(n, out));
  } else if (node && typeof node === 'object') {
    if (node.itemListElement) collectSteps(node.itemListElement, out);
    else {
      const t = clean(node.text || node.name);
      if (t) out.push(t);
    }
  }
}

/** Convertit une durée ISO 8601 (PT1H30M) en texte lisible. */
function formatDuration(iso) {
  if (!iso) return '';
  const m = /^P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?/i.exec(String(iso).trim());
  if (!m) return String(iso);
  const parts = [];
  if (m[1]) parts.push(`${m[1]} j`);
  if (m[2]) parts.push(`${m[2]} h`);
  if (m[3]) parts.push(`${m[3]} min`);
  return parts.join(' ') || String(iso);
}
