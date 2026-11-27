// Met en forme un texte brut de recette : titre, ingrédients, étapes, durée, portions.
// Même logique que l'application Android (RecipeTextParser.kt).

const INGREDIENT_TITLES = new Set([
  'ingredients', 'ingredient', 'liste des ingredients', 'il vous faut',
  'vous aurez besoin de', 'what you need', 'les ingredients', 'ingredients necessaires',
  'il te faut', 'tu auras besoin de', 'tu as besoin de', 'vous avez besoin de',
]);
const STEP_TITLES = new Set([
  'preparation', 'la preparation', 'preparation de la recette', 'recette', 'realisation',
  'etapes', 'etapes de preparation', 'etapes de la recette', 'instructions', 'methode',
  'procedure', 'deroulement', 'directions', 'method', 'steps', 'how to make it',
  'prepa', 'preparations', 'etape', 'les etapes', 'etapes a suivre', 'la recette',
  'marche a suivre', 'mode operatoire',
]);

const BULLET = /^([•·▪◦✓☐]\s*|[-–*]\s+)/;
const NUMBERED_STEP = /^(\d+\s*[.)]\s+|(?:étape|etape|step)\s*\d+)/i;
const QUANTITY_START = /^(\d+([.,/]\d+)?|[½¼¾⅓⅔⅛])(\s*[-–]\s*\d+)?\s*([A-Za-zÀ-ÿŒœ]|$)/;

const TIME_LABEL = /^(temps total|temps de preparation|temps de cuisson|duree totale|prep time|cook time|total time|temps|duree|total|preparation|cuisson|prep|cook)\s*[:\-–]?\s*(.+)$/;
const TIME_VALUE = /\d+\s*(h|heures?|min|mn|minutes?)/;
const SERVINGS_FIND = /(\d+)\s*(personnes?|pers\b|portions?|parts?|convives?|couverts)/;
const SERVINGS_LABEL = /(?:portions?|serves|servings|nombre de personnes)\s*:?\s*(\d+)/;
const SERVINGS_ONLY = /^(pour\s+)?\d+\s*(personnes?|pers|portions?|parts?|convives?|couverts)\W*$|^(portions?|nombre de personnes|serves|servings)\s*:?\s*\d+\W*$/;

const norm = (s) => s.normalize('NFD').replace(/\p{M}+/gu, '').toLowerCase();

function headerOf(line) {
  if (line.length > 60) return null;
  let core = norm(line).replace(/\(.*?\)/g, ' ');
  core = core.replace(/\bpour\s+\d+.*$/, ' ');
  core = core.replace(/^[^\p{L}]+|[^\p{L}]+$/gu, '').replace(/\s+/g, ' ');
  if (INGREDIENT_TITLES.has(core)) return 'ingredients';
  if (STEP_TITLES.has(core)) return 'steps';
  // Variantes : "Ingrédients nécessaires pour la pâte", "Ingrédient (4 pers)"…
  if (core.startsWith('ingredient') && core.split(' ').length <= 4 && !/\d/.test(line)) return 'ingredients';
  return null;
}

// ---- Nettoyage des légendes de réseaux sociaux (emojis, hashtags, promo, accroche) ----

const BULLET_EMOJI = /^[ \t]*(?:(?:✅|✔|☑|▪|🔸|🔹|🔶|🔷|👉|➡|▶|●|○)\uFE0F?[ \t]*)+/u;
const EMOJI = /[\u{1F000}-\u{1FAFF}\u{2300}-\u{23FF}\u{2600}-\u{27BF}\u{2B00}-\u{2BFF}\uFE0F\u200D\u20E3]/gu;
const HASHTAG = /(^|\s)#\p{L}[\p{L}\p{N}_]*/gu;
const MENTION = /(^|\s)@[\p{L}\p{N}_.]+/gu;
const PROMO = /abonne[zr]?(-|\s)?(toi|vous)|suis[- ]moi|suivez|follow|lien en bio|link in bio|commente[zr]?\b|\blike[zr]?\b|likez|n'oublie[zs]? pas|partage[zr]? (cette|ce|la|le|avec)|sauvegarde[zr]? (cette|ce|la|le)|enregistre[zr]? (cette|ce|la|le)|dis[- ]moi en commentaire|identifie|\btag\b|cliquez? sur|\bdm\b|bon appetit|bonne degustation|bonne cuisine/;
const TEASER_START = /^(voici|voila|decouvr|aujourd|ce soir|qui veut|tu |vous |je |j'|on |bonjour|salut|hello|coucou|regarde|venez|viens|le secret|mon secret|astuce|essaye|testez|teste )/;

function cleanLine(raw) {
  let line = raw.replace(/(\d)\uFE0F?\u20E3/gu, '$1. ');
  if (BULLET_EMOJI.test(line)) line = line.replace(BULLET_EMOJI, '- ');
  return line
    .replace(EMOJI, (m) => (m === '\u2713' || m === '\u2610' ? m : ' '))
    .replace(HASHTAG, ' ').replace(MENTION, ' ')
    .replace(/[ \t]+/g, ' ').trim();
}

const isPromo = (line) => line.length <= 140 && PROMO.test(norm(line));
const isTeaser = (line) => {
  const n = norm(line);
  return TEASER_START.test(n) || ((/[!?]$/.test(line)) && !BULLET.test(line) && !NUMBERED_STEP.test(line));
};

/** Choisit le titre parmi les premières lignes ; retourne [titre, lignes restantes]. */
function pickTitle(head) {
  // Les phrases d'accroche du début ("Voici ma recette !") sont écartées.
  let pool = head.filter((l, i) => !(i < 3 && isTeaser(l)));
  if (!pool.length) pool = head;
  let best = -1;
  let bestScore = Infinity;
  pool.slice(0, 4).forEach((l, i) => {
    if (!looksLikeTitle(l)) return;
    const words = l.split(/\s+/).length;
    const score = i * 0.5 + (words > 8 ? 2 : 0) + (l.includes(':') ? 1 : 0) + words / 20;
    if (score < bestScore) { bestScore = score; best = i; }
  });
  if (best === -1) return ['', pool];
  // Ce qui précède le titre est de l'accroche : on l'écarte.
  return [cleanTitle(pool[best]), pool.slice(best + 1)];
}

function servingsIn(line) {
  const n = norm(line);
  let m = SERVINGS_FIND.exec(n);
  if (m) return m[0].trim();
  m = SERVINGS_LABEL.exec(n);
  if (m) return `${m[1]} portions`;
  return null;
}

function consumeMeta(line, meta) {
  if (line.length > 60) return false;
  const n = norm(line);

  if (SERVINGS_ONLY.test(n)) {
    const s = servingsIn(line);
    if (s && !meta.servings) meta.servings = s;
    return true;
  }

  const m = TIME_LABEL.exec(n);
  if (!m || !TIME_VALUE.test(m[2])) return false;
  const start = Math.min(Math.max(n.length - m[2].length, 0), line.length);
  const value = line.slice(start).trim();
  if (!value || value.length > 30) return false;

  const label = m[1];
  if (label.includes('cuisson') || label.includes('cook')) {
    if (!meta.cook) meta.cook = value;
  } else if (label.includes('prepa') || label.startsWith('prep')) {
    if (!meta.prep) meta.prep = value;
  } else if (!meta.total) {
    meta.total = value;
  }
  return true;
}

const looksLikeTitle = (line) =>
  line.length <= 100 && !line.endsWith('.') && !BULLET.test(line) &&
  !QUANTITY_START.test(line) && !NUMBERED_STEP.test(line);

function isIngredientLike(line) {
  if (NUMBERED_STEP.test(line)) return false;
  if (line.length > 80) return false;
  if (BULLET.test(line)) return true;
  if (QUANTITY_START.test(line)) return true;
  const words = line.split(/\s+/).length;
  return line.length <= 45 && !line.endsWith('.') && words <= 7;
}

const cleanTitle = (line) => {
  const t = line.replace(/^(titre|nom|recette)\s*:\s*/i, '')
    .replace(/^(la |ma |ta |notre )?recette (de |du |des |d')\s*/i, '')
    .replace(/^[#*_ ]+|[#*_ ]+$/g, '').trim();
  return t.charAt(0).toUpperCase() + t.slice(1);
};

const cleanIngredient = (line) => line.replace(BULLET, '').trim();

const cleanStep = (line) =>
  line.replace(BULLET, '')
    .replace(/^(étape|etape|step)\s*\d+\s*[:.)\-–]?\s*/i, '')
    .replace(/^\d+\s*[.)]\s+/, '')
    .trim();

function timeOf(meta) {
  if (meta.total) return meta.total;
  return [meta.prep && `Préparation ${meta.prep}`, meta.cook && `Cuisson ${meta.cook}`]
    .filter(Boolean).join(' · ');
}

/** Retourne un brouillon de recette, ou null si le texte est vide. */
export function parseText(text) {
  const lines = String(text).replace(/\r/g, '').split('\n')
    .map(cleanLine).filter((l) => l && !isPromo(l));
  if (!lines.length) return null;

  const meta = { total: '', prep: '', cook: '', servings: '' };
  const head = [];
  let ingredients = [];
  let steps = [];
  let section = 'none';
  let hasHeaders = false;

  for (const line of lines) {
    const header = headerOf(line);
    if (header) {
      hasHeaders = true;
      section = header;
      const s = servingsIn(line);
      if (s && !meta.servings) meta.servings = s;
      continue;
    }
    if (consumeMeta(line, meta)) continue;
    if (section === 'none') head.push(line);
    else if (section === 'ingredients') ingredients.push(line);
    else steps.push(line);
  }

  const [title, rest] = pickTitle(head);

  let notes = [];
  if (!hasHeaders) {
    const firstStep = rest.findIndex((l) => !isIngredientLike(l));
    if (firstStep === -1) {
      ingredients = ingredients.concat(rest);
    } else {
      ingredients = ingredients.concat(rest.slice(0, firstStep));
      steps = steps.concat(rest.slice(firstStep));
    }
  } else if (!ingredients.length && steps.length) {
    const firstStep = rest.findIndex((l) => !isIngredientLike(l));
    const cut = firstStep === -1 ? rest.length : firstStep;
    ingredients = rest.slice(0, cut);
    notes = rest.slice(cut);
  } else if (!steps.length && ingredients.length) {
    const cut = ingredients.findIndex((l) => !isIngredientLike(l));
    if (cut !== -1) {
      steps = ingredients.slice(cut);
      ingredients = ingredients.slice(0, cut);
    }
    notes = rest;
  } else {
    notes = rest;
  }

  const finalIngredients = ingredients.map(cleanIngredient).filter(Boolean);
  let finalSteps = steps.concat(notes).map(cleanStep).filter(Boolean);
  // Un seul gros paragraphe : on le découpe phrase par phrase.
  if (finalSteps.length === 1 && finalSteps[0].length > 150) {
    finalSteps = finalSteps[0]
      .replace(/([.!?])\s+(?=[A-ZÀ-ÖØ-Þ])/g, '$1\n')
      .split('\n').map((s) => s.trim()).filter(Boolean);
  }

  if (!title && !finalIngredients.length && !finalSteps.length) return null;

  return {
    title,
    ingredients: finalIngredients.join('\n'),
    instructions: finalSteps.join('\n'),
    totalTime: timeOf(meta),
    servings: meta.servings,
    sourceUrl: '',
  };
}
