// Base de données locale (IndexedDB) : tout reste sur l'appareil.

const DB_NAME = 'recettes';
const STORE = 'recipes';
let dbPromise;

function open() {
  if (!dbPromise) {
    dbPromise = new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, 1);
      req.onupgradeneeded = () => {
        req.result.createObjectStore(STORE, { keyPath: 'id', autoIncrement: true });
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  }
  return dbPromise;
}

async function run(mode, fn) {
  const db = await open();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE, mode);
    const req = fn(tx.objectStore(STORE));
    tx.oncomplete = () => resolve(req.result);
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error);
  });
}

export const all = async () =>
  (await run('readonly', (s) => s.getAll())).sort((a, b) => b.createdAt - a.createdAt);

/** Ajoute une recette (sans id) et retourne son nouvel identifiant. */
export const add = (recipe) => run('readwrite', (s) => s.add(recipe));

export const put = (recipe) => run('readwrite', (s) => s.put(recipe));

export const remove = (id) => run('readwrite', (s) => s.delete(id));
