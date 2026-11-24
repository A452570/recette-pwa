# Mes recettes — application web (PWA) pour iPhone et Android

Même fonctions que l'application Android, mais sous forme de site web installable : aucune
App Store, aucun Mac, aucun compte développeur. Tout est stocké **sur l'appareil**
(base IndexedDB du navigateur), et l'application fonctionne hors connexion.

## Fonctions

- Liste avec recherche par titre ou ingrédient (sans tenir compte des accents)
- Ajout, modification, suppression
- **Coller du texte** : l'appli repère titre, ingrédients, étapes, durée, portions
- **Capture / photo** et **Prendre une photo** : lecture du texte (OCR) puis mise en forme
- **Importer un lien** : lit les données de recette de la page (nécessite un relais, voir plus bas)
- **Sauvegarde** : export en fichier JSON, partage, import (fusion sans doublons)
- Les fichiers de sauvegarde sont **identiques à ceux de l'appli Android** : on peut passer
  ses recettes d'un système à l'autre.

## 1. Publier l'application (GitHub Pages, gratuit)

1. Crée un dépôt GitHub (public, ou privé si ton offre le permet pour Pages).
2. Envoie-y **le contenu** de ce dossier (`index.html`, `sw.js`, `js/`, `icons/`… à la racine du dépôt).
3. Dans le dépôt : **Settings → Pages → Build and deployment → Deploy from a branch**,
   branche `main`, dossier `/ (root)`, puis **Save**.
4. Après une ou deux minutes, l'adresse apparaît en haut de la page :
   `https://TON_PSEUDO.github.io/NOM_DU_DEPOT/`

## 2. Installer sur l'iPhone

1. Ouvre l'adresse **dans Safari** (pas dans Chrome ni dans une autre application).
2. Touche le bouton **Partager** (carré avec une flèche) puis **Sur l'écran d'accueil**.
3. Valide : l'icône « Recettes » apparaît et l'application s'ouvre en plein écran.

Sur Android : Chrome → menu ⋮ → **Installer l'application**.

> **Important sur iPhone** : installe bien l'application sur l'écran d'accueil. Dans un simple
> onglet Safari, iOS peut effacer les données du site après quelques semaines sans visite.
> Pense aussi à faire une **sauvegarde** (Sauvegarde ▾ → Exporter) de temps en temps.

## 3. Activer l'import par lien (facultatif)

Un navigateur n'a pas le droit de lire le contenu d'un autre site. Il faut un petit relais
gratuit, qui télécharge la page à ta place :

1. Crée un compte gratuit sur cloudflare.com, puis **Workers & Pages → Create → Worker**.
2. Ouvre `worker/relay.js`, remplace `https://TON_PSEUDO.github.io` par l'adresse de **ton**
   application (sans `/` final, et sans le nom du dépôt : seulement `https://pseudo.github.io`),
   colle le code dans le Worker et clique sur **Deploy**.
3. Copie l'adresse du Worker (`https://….workers.dev`).
4. Dans l'application : **Sauvegarde ▾ → Réglages**, colle cette adresse, enregistre.

Seule ton application est autorisée à utiliser ce relais. Sans relais, l'import par lien est
indisponible ; « Coller du texte » et la lecture de photo marchent toujours.

## Limites à connaître

- **Lecture de photo** : la bibliothèque et le modèle de français (quelques Mo) se téléchargent
  la **première fois** (connexion internet nécessaire), puis la lecture se fait sur l'appareil.
  La qualité dépend de la netteté de l'image ; l'écriture manuscrite est mal reconnue.
- **Partage depuis Safari** : iOS ne permet pas d'envoyer un lien directement vers une PWA.
  Copie le lien (ou le texte) puis colle-le dans l'application. Sur Android, une PWA installée
  apparaît bien dans le menu Partager.
- **Mises à jour** : l'application se met à jour toute seule à l'ouverture quand il y a du réseau.
