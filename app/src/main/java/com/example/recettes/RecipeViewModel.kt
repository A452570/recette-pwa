package com.example.recettes

import android.app.Application
import android.content.Context
import org.json.JSONArray
import kotlinx.coroutines.flow.combine
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.recettes.data.BackupManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.recettes.data.AppDatabase
import com.example.recettes.data.Recipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SortMode(val label: String) {
    RECENT("Plus récentes"), TITLE("Titre A→Z"), CATEGORY("Catégorie")
}

sealed interface Screen {
    data object List : Screen
    data class Detail(val id: Long) : Screen
    /** id == null → nouvelle recette ; [draft] pré-remplit le formulaire (import). */
    data class Edit(val id: Long?, val draft: Recipe? = null) : Screen
}

class RecipeViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDatabase.get(app).recipeDao()

    var screen: Screen by mutableStateOf(Screen.List)
        private set

    val query = MutableStateFlow("")

    // ---- Catégories : liste gérée par l'utilisateur (préférences) + celles déjà utilisées ----

    private val prefs = app.getSharedPreferences("recettes_prefs", Context.MODE_PRIVATE)
    private val storedCategories = MutableStateFlow(loadCategories())

    private fun loadCategories(): List<String> {
        val raw = prefs.getString("categories", null)
            ?: return listOf("Entrée", "Plat", "Dessert", "Apéritif", "Boisson")
        return try {
            JSONArray(raw).let { a -> (0 until a.length()).map { a.getString(it) } }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun setStoredCategories(list: List<String>) {
        val clean = list.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        storedCategories.value = clean
        prefs.edit().putString("categories", JSONArray(clean).toString()).apply()
    }

    val categories: StateFlow<List<String>> =
        combine(storedCategories, dao.usedCategories()) { stored, used ->
            (stored + used).distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), storedCategories.value)

    /** null = toutes les recettes ; "" = recettes sans catégorie ; sinon le nom de la catégorie. */
    val categoryFilter = MutableStateFlow<String?>(null)
    val sortMode = MutableStateFlow(SortMode.RECENT)

    fun setCategoryFilter(value: String?) { categoryFilter.value = value }
    fun setSortMode(mode: SortMode) { sortMode.value = mode }

    fun addCategory(name: String) = addCategories(listOf(name))

    private fun addCategories(names: List<String>) {
        setStoredCategories(storedCategories.value + names)
    }

    fun renameCategory(old: String, newName: String) {
        val target = newName.trim()
        if (target.isEmpty() || target == old) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { dao.renameCategory(old, target) }
            setStoredCategories(storedCategories.value.filterNot { it.equals(old, true) } + target)
            if (categoryFilter.value.equals(old, true)) categoryFilter.value = target
        }
    }

    fun deleteCategory(name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { dao.renameCategory(name, "") }
            setStoredCategories(storedCategories.value.filterNot { it.equals(name, true) })
            if (categoryFilter.value.equals(name, true)) categoryFilter.value = null
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val recipes: StateFlow<List<Recipe>> = combine(
        query.flatMapLatest { dao.search(it.trim()) }, categoryFilter, sortMode
    ) { list, filter, mode ->
        val filtered = if (filter == null) list else list.filter { it.category.equals(filter, true) }
        when (mode) {
            SortMode.RECENT -> filtered
            SortMode.TITLE -> filtered.sortedBy { it.title.lowercase() }
            SortMode.CATEGORY -> filtered.sortedWith(
                compareBy({ it.category.isEmpty() }, { it.category.lowercase() }, { it.title.lowercase() })
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var importing by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    fun goTo(target: Screen) { screen = target }
    fun consumeMessage() { message = null }

    fun setQuery(q: String) { query.value = q }

    suspend fun load(id: Long): Recipe? = dao.get(id)

    /** Importe une recette depuis une URL et ouvre le formulaire pré-rempli pour validation. */
    fun importFromUrl(url: String) {
        if (importing) return
        importing = true
        viewModelScope.launch {
            try {
                val recipe = withContext(Dispatchers.IO) {
                    if (FacebookImporter.isFacebookUrl(url)) FacebookImporter.importFromUrl(url)
                    else RecipeImporter.importFromUrl(url)
                }
                val already = withContext(Dispatchers.IO) { dao.countBySource(recipe.sourceUrl) > 0 }
                if (already) message = "Cette recette est déjà enregistrée (tu peux quand même la sauvegarder)."
                screen = Screen.Edit(id = null, draft = recipe)
            } catch (e: RecipeImporter.ImportException) {
                message = e.message
            } catch (e: java.net.UnknownHostException) {
                message = "Adresse introuvable ou pas de connexion internet. Vérifie le lien et ta connexion."
            } catch (e: java.net.ConnectException) {
                message = "Connexion impossible au site. Vérifie ta connexion, ou désactive VPN, DNS privé ou bloqueur de publicités."
            } catch (e: java.net.SocketTimeoutException) {
                message = "Le site met trop de temps à répondre. Réessaie."
            } catch (e: Exception) {
                message = "Impossible de récupérer la recette : ${e.localizedMessage ?: "erreur réseau"}"
            } finally {
                importing = false
            }
        }
    }

    fun showMessage(text: String) { message = text }

    /** Fichier temporaire (partagé via FileProvider) où l'appareil photo enregistrera la prise de vue. */
    fun newCameraUri(): Uri {
        val ctx = getApplication<Application>()
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "photo-${System.currentTimeMillis()}.jpg").apply { createNewFile() }
        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    }

    /** Lit le texte des images (OCR), le met en forme et ouvre le formulaire pré-rempli. */
    fun importFromImages(uris: List<Uri>) {
        if (uris.isEmpty() || importing) return
        importing = true
        viewModelScope.launch {
            try {
                val ctx = getApplication<Application>()
                val text = uris.map { RecipeScanner.readText(ctx, it) }
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                val recipe = if (text.isBlank()) null else RecipeTextParser.parse(text)
                if (recipe == null) {
                    message = "Aucun texte détecté. Essaie avec une image plus nette et bien cadrée."
                } else {
                    screen = Screen.Edit(id = null, draft = recipe)
                }
            } catch (e: Exception) {
                message = "Lecture de l'image impossible : ${e.localizedMessage ?: "erreur inconnue"}"
            } finally {
                importing = false
            }
        }
    }

    /** Met en forme un texte collé et ouvre le formulaire pré-rempli pour validation. */
    fun importFromText(text: String) {
        val recipe = RecipeTextParser.parsePasted(text)
        if (recipe == null) {
            message = "Le texte est vide : rien à mettre en forme."
            return
        }
        screen = Screen.Edit(id = null, draft = recipe)
    }

    // ---- Sauvegarde / partage de la base ----

    fun backupFileName(): String =
        "recettes-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".json"

    /** Écrit toutes les recettes dans le fichier choisi par l'utilisateur. */
    fun exportTo(uri: Uri) {
        viewModelScope.launch {
            message = try {
                val count = withContext(Dispatchers.IO) {
                    val list = dao.all()
                    val out = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                        ?: throw IllegalStateException("Fichier inaccessible")
                    out.use { it.write(BackupManager.toJson(list, storedCategories.value).toByteArray(Charsets.UTF_8)) }
                    list.size
                }
                "Sauvegarde créée : $count recette(s) exportée(s)."
            } catch (e: Exception) {
                "Échec de l'export : ${e.localizedMessage ?: "erreur inconnue"}"
            }
        }
    }

    /** Fusionne une sauvegarde avec la base actuelle : les recettes déjà présentes sont ignorées. */
    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            message = try {
                val (added, skipped) = withContext(Dispatchers.IO) {
                    val text = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.use { it.readBytes().toString(Charsets.UTF_8) }
                        ?: throw IllegalStateException("Fichier inaccessible")
                    var added = 0
                    var skipped = 0
                    val data = BackupManager.fromJson(text)
                    addCategories(data.categories + data.recipes.map { it.category })
                    for (r in data.recipes) {
                        if (dao.countSame(r.title, r.ingredients) > 0) {
                            skipped++
                        } else {
                            dao.insert(r)
                            added++
                        }
                    }
                    added to skipped
                }
                "Import terminé : $added recette(s) ajoutée(s), $skipped déjà présente(s)."
            } catch (e: BackupManager.BackupException) {
                e.message
            } catch (e: Exception) {
                "Échec de l'import : ${e.localizedMessage ?: "erreur inconnue"}"
            }
        }
    }

    /** Crée un fichier de sauvegarde temporaire et retourne son Uri pour un partage, ou null si la base est vide. */
    suspend fun prepareShareFile(): Uri? = withContext(Dispatchers.IO) {
        val list = dao.all()
        if (list.isEmpty()) return@withContext null
        val ctx = getApplication<Application>()
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, backupFileName())
        file.writeText(BackupManager.toJson(list, storedCategories.value), Charsets.UTF_8)
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    }

    fun save(recipe: Recipe) {
        viewModelScope.launch {
            if (recipe.id == 0L) {
                val newId = dao.insert(recipe)
                screen = Screen.Detail(newId)
            } else {
                dao.update(recipe)
                screen = Screen.Detail(recipe.id)
            }
        }
    }

    fun delete(recipe: Recipe) {
        viewModelScope.launch {
            dao.delete(recipe)
            screen = Screen.List
        }
    }
}
