// Sauvegarde JSON, compatible avec l'application Android (même format).

const FORMAT_VERSION = 1;

export function recipesToJson(recipes) {
  return JSON.stringify({
    app: 'recettes',
    version: FORMAT_VERSION,
    exportedAt: Date.now(),
    recipes: recipes.map((r) => ({
      title: r.title,
      ingredients: r.ingredients,
      instructions: r.instructions,
      sourceUrl: r.sourceUrl || '',
      totalTime: r.totalTime || '',
      servings: r.servings || '',
      createdAt: r.createdAt,
    })),
  }, null, 2);
}

/** Retourne des recettes sans id. Lève une Error au message lisible si le fichier est invalide. */
export function jsonToRecipes(text) {
  let root;
  try {
    root = JSON.parse(String(text).replace(/^﻿/, '').trim());
  } catch {
    throw new Error("Ce fichier n'est pas une sauvegarde de recettes valide.");
  }
  if ((root.version ?? 1) > FORMAT_VERSION) {
    throw new Error("Cette sauvegarde vient d'une version plus récente de l'application.");
  }
  if (!Array.isArray(root.recipes)) {
    throw new Error('Ce fichier ne contient pas de recettes.');
  }
  return root.recipes
    .filter((o) => o && typeof o.title === 'string' && o.title.trim())
    .map((o) => ({
      title: o.title.trim(),
      ingredients: String(o.ingredients ?? ''),
      instructions: String(o.instructions ?? ''),
      sourceUrl: String(o.sourceUrl ?? ''),
      totalTime: String(o.totalTime ?? ''),
      servings: String(o.servings ?? ''),
      createdAt: Number(o.createdAt) || Date.now(),
    }));
}
