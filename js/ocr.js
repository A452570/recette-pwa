// Lecture du texte d'une photo ou d'une capture d'écran (OCR) avec Tesseract.js.
// La bibliothèque et le modèle de langue français sont téléchargés à la première utilisation
// (connexion internet nécessaire à ce moment-là), puis la lecture se fait sur l'appareil.

const SRC = 'https://cdn.jsdelivr.net/npm/tesseract.js@5.1.1/dist/tesseract.min.js';
let loading;

function loadLib() {
  if (window.Tesseract) return Promise.resolve();
  if (!loading) {
    loading = new Promise((resolve, reject) => {
      const s = document.createElement('script');
      s.src = SRC;
      s.onload = resolve;
      s.onerror = () => {
        loading = null;
        reject(new Error("Impossible de charger le module de lecture de texte. Vérifie ta connexion internet (nécessaire la première fois)."));
      };
      document.head.appendChild(s);
    });
  }
  return loading;
}

/** Réduit les très grandes photos (et applique leur rotation) pour accélérer la lecture. */
async function prepare(file) {
  try {
    const bmp = await createImageBitmap(file, { imageOrientation: 'from-image' });
    const scale = Math.min(1, 2200 / Math.max(bmp.width, bmp.height));
    const canvas = document.createElement('canvas');
    canvas.width = Math.round(bmp.width * scale);
    canvas.height = Math.round(bmp.height * scale);
    canvas.getContext('2d').drawImage(bmp, 0, 0, canvas.width, canvas.height);
    if (bmp.close) bmp.close();
    return await new Promise((resolve) => canvas.toBlob((b) => resolve(b || file), 'image/png'));
  } catch {
    return file;
  }
}

/** Lit les images dans l'ordre et retourne le texte réuni. onStatus reçoit un message de progression. */
export async function readImages(files, onStatus) {
  onStatus?.('Chargement du module de lecture…');
  await loadLib();

  let index = 0;
  const worker = await window.Tesseract.createWorker('fra', 1, {
    logger: (m) => {
      if (m.status === 'recognizing text') {
        onStatus?.(`Lecture de l'image ${index + 1}/${files.length} : ${Math.round(m.progress * 100)} %`);
      }
    },
  });
  try {
    const parts = [];
    for (index = 0; index < files.length; index++) {
      onStatus?.(`Lecture de l'image ${index + 1}/${files.length}…`);
      const input = await prepare(files[index]);
      const { data } = await worker.recognize(input);
      if (data.text && data.text.trim()) parts.push(data.text.trim());
    }
    return parts.join('\n');
  } finally {
    await worker.terminate();
  }
}
