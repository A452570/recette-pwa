// Relais pour l'import par lien (Cloudflare Worker, gratuit).
// Il télécharge la page demandée et la renvoie à l'application avec les en-têtes CORS.
//
// 1. Remplace ALLOWED_ORIGIN par l'adresse de TON application (sans "/" final),
//    par exemple https://monpseudo.github.io
//    Seule cette adresse pourra utiliser le relais.
// 2. Colle ce fichier dans un nouveau Worker Cloudflare, puis déploie.
// 3. Dans l'application : Sauvegarde ▾ → Réglages → adresse du relais = l'URL du Worker.

const ALLOWED_ORIGIN = 'https://TON_PSEUDO.github.io';

export default {
  async fetch(request) {
    const cors = {
      'Access-Control-Allow-Origin': ALLOWED_ORIGIN,
      'Access-Control-Allow-Methods': 'GET, OPTIONS',
      'Access-Control-Allow-Headers': '*',
      Vary: 'Origin',
    };

    if (request.headers.get('Origin') !== ALLOWED_ORIGIN) {
      return new Response('Origine non autorisée', { status: 403 });
    }
    if (request.method === 'OPTIONS') return new Response(null, { headers: cors });

    const target = new URL(request.url).searchParams.get('url');
    let url;
    try {
      url = new URL(target);
    } catch {
      return new Response('URL invalide', { status: 400, headers: cors });
    }
    if (url.protocol !== 'http:' && url.protocol !== 'https:') {
      return new Response('URL invalide', { status: 400, headers: cors });
    }

    const resp = await fetch(url.toString(), {
      redirect: 'follow',
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36',
        Accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8',
        'Accept-Language': 'fr-FR,fr;q=0.9,en;q=0.7',
        Referer: 'https://www.google.com/',
        'Upgrade-Insecure-Requests': '1',
        'Sec-Fetch-Dest': 'document',
        'Sec-Fetch-Mode': 'navigate',
        'Sec-Fetch-Site': 'cross-site',
      },
    });
    const body = (await resp.text()).slice(0, 4_000_000);
    return new Response(body, {
      status: resp.status,
      headers: { ...cors, 'Content-Type': 'text/html; charset=utf-8' },
    });
  },
};
