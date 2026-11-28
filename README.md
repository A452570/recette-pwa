# Mes recettes – application Android

Application Kotlin / Jetpack Compose avec base de données locale Room (SQLite).

## Fonctions

- **Importer une recette depuis un lien** : l'appli télécharge la page et lit les données
  structurées `schema.org/Recipe` (titre, ingrédients, étapes, durée, portions).
  Fonctionne avec la plupart des sites de cuisine.
- **Coller du texte** : colle n'importe quel texte de recette ; l'appli repère le titre, les
  ingrédients, les étapes, la durée et les portions (par les rubriques « Ingrédients » /
  « Préparation » ou, à défaut, par la forme des lignes). Un écran de validation s'ouvre
  pour corriger. Un texte partagé depuis une autre appli est traité de la même façon.
- **Scanner une photo ou une capture d'écran** : « Capture / photo » (jusqu'à 5 images à la
  suite, si la recette tient sur plusieurs captures) ou « Prendre une photo ». Le texte est lu
  sur le téléphone (OCR Google ML Kit, hors ligne, alphabet latin), puis mis en forme comme
  pour « Coller du texte », avec écran de validation.
- **Partage depuis le navigateur** : dans Chrome, « Partager » → « Mes recettes » lance l'import.
- **Saisie manuelle**, **modification** et **suppression**.
- **Recherche** par titre ou ingrédient, consultable hors ligne.
- **Sauvegarde et partage de la base** (menu « Menu » de l'écran principal) :
  - *Exporter vers un fichier* : crée un fichier `recettes-AAAA-MM-JJ.json` à l'endroit choisi
    (stockage du téléphone, Google Drive…).
  - *Partager mes recettes* : envoie ce même fichier vers une autre appli (messagerie, e-mail…).
  - *Importer une sauvegarde* : fusionne un fichier `.json` avec la base actuelle. Les recettes
    déjà présentes (même titre et mêmes ingrédients) sont ignorées, rien n'est écrasé.
- Un écran de validation s'ouvre après l'import : tu peux corriger avant d'enregistrer.

## Obtenir l'APK

### Option A – Android Studio
1. Installer Android Studio (Ladybug ou plus récent), puis *File → Open* sur ce dossier.
2. Laisser Gradle synchroniser (il télécharge les dépendances).
3. *Build → Build APK(s)*, ou en ligne de commande (Gradle 8.9+, JDK 17, Android SDK 34) : `gradle assembleDebug`
4. L'APK se trouve dans `app/build/outputs/apk/debug/app-debug.apk`.

### Option B – sans rien installer (GitHub Actions)
1. Créer un dépôt GitHub et y pousser ce dossier.
2. Onglet **Actions** → workflow « Build APK » (il se lance à chaque push).
3. Télécharger l'artefact `recettes-debug-apk`.

## Installer sur le téléphone
Copier `app-debug.apk` sur le téléphone, l'ouvrir et autoriser « Installer des applications inconnues »
pour l'application utilisée (Fichiers, Chrome…).

## Limites connues
- Les sites sans données JSON-LD (ou protégés par un anti-bot) ne peuvent pas être importés :
  il reste la saisie manuelle.
- L'APK produit est un APK de *debug* (signé avec la clé de debug), suffisant pour un usage personnel.

## Reels / vidéos Facebook

Colle (ou partage vers l'appli) le lien d'un reel ou d'une vidéo Facebook : l'appli lit la
**légende** de la publication et la met en forme comme un texte collé (titre, ingrédients,
étapes), à relire avant d'enregistrer. Cela ne fonctionne que si la publication est publique
et que Facebook fournit son aperçu ; sinon, copie la légende dans Facebook et utilise
« Coller du texte ». Les recettes dites uniquement à l'oral dans la vidéo ne sont pas lues.

## Catégories

Chaque recette peut avoir une **catégorie** (Entrée, Plat, Dessert… choisie dans le formulaire).
Menu ▾ → **Gérer les catégories** : ajouter, renommer (les recettes suivent) ou supprimer
(les recettes sont conservées, sans catégorie). Sur l'écran principal, touche une catégorie
pour filtrer, et utilise **Tri** pour classer par date, par titre ou par catégorie. Les catégories
sont incluses dans les sauvegardes `.json`.
