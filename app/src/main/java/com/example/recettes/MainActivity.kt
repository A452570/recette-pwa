package com.example.recettes

import android.content.Intent
import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.recettes.data.Recipe

class MainActivity : ComponentActivity() {

    private val viewModel: RecipeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleShareIntent(intent)

        setContent {
            MaterialTheme {
                RecipeApp(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    /** Si l'utilisateur partage un lien depuis son navigateur, on lance l'import directement. */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val url = RecipeImporter.extractUrl(text)
            // Un simple lien (éventuellement avec un titre) : import web. Sinon : texte de recette.
            if (url != null && text.length - url.length < 200) viewModel.importFromUrl(url)
            else if (text.isNotBlank()) viewModel.importFromText(text)
        }
    }
}

@Composable
fun RecipeApp(vm: RecipeViewModel) {
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    BackHandler(enabled = vm.screen != Screen.List) {
        val current = vm.screen
        vm.goTo(
            if (current is Screen.Edit && current.id != null) Screen.Detail(current.id) else Screen.List
        )
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (val s = vm.screen) {
                is Screen.List -> ListScreen(vm)
                is Screen.Detail -> DetailScreen(vm, s.id)
                is Screen.Edit -> EditScreen(vm, s)
            }
        }
    }
}

// ---------------------------------------------------------------- Liste

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(vm: RecipeViewModel) {
    val recipes by vm.recipes.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val filter by vm.categoryFilter.collectAsStateWithLifecycle()
    val sort by vm.sortMode.collectAsStateWithLifecycle()
    var sortMenuOpen by remember { mutableStateOf(false) }
    var showCategories by rememberSaveable { mutableStateOf(false) }
    var showImportDialog by rememberSaveable { mutableStateOf(false) }
    var showTextDialog by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) vm.exportTo(uri) }

    // "*/*" : certaines applis (messageries, Drive) fournissent le .json avec un type générique.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.importBackup(uri) }

    // Sélecteur de photos/captures d'écran (aucune permission requise), jusqu'à 5 images à la suite.
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(5)
    ) { uris -> vm.importFromImages(uris) }

    var cameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { saved -> if (saved) cameraUri?.let { vm.importFromImages(listOf(it)) } }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Mes recettes") },
            actions = {
                Box {
                    TextButton(onClick = { menuOpen = true }) { Text("Menu ▾") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Gérer les catégories") },
                            onClick = {
                                menuOpen = false
                                showCategories = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Exporter vers un fichier") },
                            onClick = {
                                menuOpen = false
                                exportLauncher.launch(vm.backupFileName())
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Partager mes recettes") },
                            onClick = {
                                menuOpen = false
                                scope.launch {
                                    val uri = vm.prepareShareFile()
                                    if (uri == null) {
                                        vm.showMessage("Aucune recette à partager.")
                                    } else {
                                        val send = Intent(Intent.ACTION_SEND).apply {
                                            type = "application/json"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(send, "Partager mes recettes"))
                                    }
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Importer une sauvegarde") },
                            onClick = {
                                menuOpen = false
                                importLauncher.launch(arrayOf("*/*"))
                            }
                        )
                    }
                }
            }
        )

        OutlinedTextField(
            value = query,
            onValueChange = vm::setQuery,
            label = { Text("Rechercher (titre ou ingrédient)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        )

        if (categories.isNotEmpty() || filter != null) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = filter == null,
                        onClick = { vm.setCategoryFilter(null) },
                        label = { Text("Toutes") }
                    )
                }
                items(categories) { c ->
                    val selected = filter.equals(c, true)
                    FilterChip(
                        selected = selected,
                        onClick = { vm.setCategoryFilter(if (selected) null else c) },
                        label = { Text(c) }
                    )
                }
                item {
                    FilterChip(
                        selected = filter == "",
                        onClick = { vm.setCategoryFilter(if (filter == "") null else "") },
                        label = { Text("Sans catégorie") }
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            Box {
                TextButton(onClick = { sortMenuOpen = true }) { Text("Tri : ${sort.label} ▾") }
                DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                    SortMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.label) },
                            onClick = { vm.setSortMode(mode); sortMenuOpen = false }
                        )
                    }
                }
            }
        }

        if (vm.importing) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(Modifier.height(24.dp))
                Text("Lecture de la recette en cours…")
            }
        }

        if (recipes.isEmpty() && !vm.importing) {
            Text(
                if (query.isBlank() && filter != null) "Aucune recette dans cette catégorie."
                else if (query.isBlank())
                    "Aucune recette pour l'instant.\nImporte-en une depuis un lien, ou partage un lien vers cette appli depuis ton navigateur."
                else "Aucun résultat.",
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(recipes, key = { it.id }) { recipe ->
                Card(
                    Modifier.fillMaxWidth().clickable { vm.goTo(Screen.Detail(recipe.id)) }
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(recipe.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        val meta = listOf(recipe.category, recipe.totalTime, recipe.servings).filter { it.isNotBlank() }
                        if (meta.isNotEmpty()) {
                            Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ExtendedFloatingActionButton(
                    onClick = { showImportDialog = true },
                    modifier = Modifier.weight(1f)
                ) { Text("Importer un lien") }
                ExtendedFloatingActionButton(
                    onClick = { showTextDialog = true },
                    modifier = Modifier.weight(1f)
                ) { Text("Coller du texte") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Capture / photo") }
                OutlinedButton(
                    onClick = {
                        val uri = vm.newCameraUri()
                        cameraUri = uri
                        camera.launch(uri)
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Prendre une photo") }
            }
            OutlinedButton(
                onClick = { vm.goTo(Screen.Edit(null)) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Saisie manuelle") }
        }
    }

    if (showCategories) CategoriesDialog(vm) { showCategories = false }

    if (showTextDialog) {
        var text by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showTextDialog = false },
            title = { Text("Coller une recette") },
            text = {
                Column {
                    Text(
                        "Colle le texte de la recette : l'appli repère le titre, les ingrédients et les étapes. " +
                            "Tu pourras tout corriger avant d'enregistrer.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("Texte de la recette") },
                        modifier = Modifier.fillMaxWidth().height(260.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = text.isNotBlank(),
                    onClick = {
                        showTextDialog = false
                        vm.importFromText(text)
                    }
                ) { Text("Mettre en forme") }
            },
            dismissButton = { TextButton(onClick = { showTextDialog = false }) { Text("Annuler") } }
        )
    }

    if (showImportDialog) {
        var url by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Importer une recette") },
            text = {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Adresse de la page ou du reel Facebook") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = url.isNotBlank(),
                    onClick = {
                        showImportDialog = false
                        vm.importFromUrl(RecipeImporter.extractUrl(url) ?: url)
                    }
                ) { Text("Importer") }
            },
            dismissButton = { TextButton(onClick = { showImportDialog = false }) { Text("Annuler") } }
        )
    }
}

// ---------------------------------------------------------------- Détail

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(vm: RecipeViewModel, id: Long) {
    var recipe by remember(id) { mutableStateOf<Recipe?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(id) { recipe = vm.load(id) }

    val r = recipe
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(r?.title ?: "") },
            navigationIcon = { TextButton(onClick = { vm.goTo(Screen.List) }) { Text("← Retour") } }
        )
        if (r == null) return@Column

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            val meta = listOf(r.category, r.totalTime, r.servings).filter { it.isNotBlank() }
            if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)

            Spacer(Modifier.height(16.dp))
            Text("Ingrédients", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            r.ingredients.lines().filter { it.isNotBlank() }.forEach {
                Text("• $it", modifier = Modifier.padding(vertical = 2.dp))
            }

            Spacer(Modifier.height(16.dp))
            Text("Préparation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            r.instructions.lines().filter { it.isNotBlank() }.forEachIndexed { i, step ->
                Text("${i + 1}. $step", modifier = Modifier.padding(vertical = 4.dp))
            }

            if (r.sourceUrl.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                val isLink = r.sourceUrl.startsWith("http://", true) || r.sourceUrl.startsWith("https://", true)
                Text(
                    "Source : ${r.sourceUrl}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isLink) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Unspecified,
                    modifier = if (isLink) Modifier.clickable {
                        runCatching { uriHandler.openUri(r.sourceUrl) }
                    } else Modifier
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.weight(1f)) { Text("Supprimer") }
            Button(onClick = { vm.goTo(Screen.Edit(r.id)) }, modifier = Modifier.weight(1f)) { Text("Modifier") }
        }
    }

    if (confirmDelete && r != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Supprimer cette recette ?") },
            text = { Text(r.title) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete(r) }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } }
        )
    }
}

// ---------------------------------------------------------------- Édition / ajout

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(vm: RecipeViewModel, screen: Screen.Edit) {
    var loaded by remember(screen) { mutableStateOf(screen.draft) }
    var ready by remember(screen) { mutableStateOf(screen.id == null) }

    LaunchedEffect(screen) {
        if (screen.id != null) {
            loaded = vm.load(screen.id)
            ready = true
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (screen.id == null) "Nouvelle recette" else "Modifier la recette") },
            navigationIcon = {
                TextButton(onClick = {
                    vm.goTo(if (screen.id != null) Screen.Detail(screen.id) else Screen.List)
                }) { Text("← Annuler") }
            }
        )
        if (!ready) return@Column

        val base = loaded
        var title by remember(base) { mutableStateOf(base?.title.orEmpty()) }
        var ingredients by remember(base) { mutableStateOf(base?.ingredients.orEmpty()) }
        var instructions by remember(base) { mutableStateOf(base?.instructions.orEmpty()) }
        var time by remember(base) { mutableStateOf(base?.totalTime.orEmpty()) }
        var servings by remember(base) { mutableStateOf(base?.servings.orEmpty()) }
        var source by remember(base) { mutableStateOf(base?.sourceUrl.orEmpty()) }
        var category by remember(base) { mutableStateOf(base?.category.orEmpty()) }
        var catMenu by remember { mutableStateOf(false) }
        val categories by vm.categories.collectAsStateWithLifecycle()

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("Titre") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Box {
                OutlinedButton(onClick = { catMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Catégorie : ${category.ifBlank { "aucune" }} ▾")
                }
                DropdownMenu(expanded = catMenu, onDismissRequest = { catMenu = false }) {
                    DropdownMenuItem(text = { Text("Aucune") }, onClick = { category = ""; catMenu = false })
                    categories.forEach { c ->
                        DropdownMenuItem(text = { Text(c) }, onClick = { category = c; catMenu = false })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(time, { time = it }, label = { Text("Durée") }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(servings, { servings = it }, label = { Text("Portions") }, modifier = Modifier.weight(1f), singleLine = true)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                ingredients, { ingredients = it },
                label = { Text("Ingrédients (un par ligne)") },
                modifier = Modifier.fillMaxWidth(), minLines = 5
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                instructions, { instructions = it },
                label = { Text("Étapes (une par ligne)") },
                modifier = Modifier.fillMaxWidth(), minLines = 6
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                source, { source = it },
                label = { Text("Source (lien ou référence)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
        }

        Button(
            enabled = title.isNotBlank(),
            onClick = {
                vm.save(
                    (base ?: Recipe(title = "", ingredients = "", instructions = "")).copy(
                        title = title.trim(),
                        ingredients = ingredients.trim(),
                        instructions = instructions.trim(),
                        totalTime = time.trim(),
                        servings = servings.trim(),
                        sourceUrl = source.trim(),
                        category = category,
                        // Pour un brouillon importé, id reste à 0 → insertion ; sinon mise à jour.
                        id = if (screen.id == null) 0 else screen.id
                    )
                )
            },
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) { Text("Enregistrer") }
    }
}


// ---------------------------------------------------------------- Gestion des catégories

@Composable
fun CategoriesDialog(vm: RecipeViewModel, onDismiss: () -> Unit) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    var newName by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Catégories") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        newName, { newName = it },
                        label = { Text("Nouvelle catégorie") },
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        enabled = newName.isNotBlank(),
                        onClick = { vm.addCategory(newName); newName = "" }
                    ) { Text("Ajouter") }
                }
                Spacer(Modifier.height(8.dp))
                if (categories.isEmpty()) Text("Aucune catégorie.")
                categories.forEach { c ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(c, Modifier.weight(1f))
                        TextButton(onClick = { renaming = c; renameText = c }) { Text("Renommer") }
                        TextButton(onClick = { deleting = c }) { Text("Suppr.") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } }
    )

    renaming?.let { old ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Renommer la catégorie") },
            text = {
                OutlinedTextField(renameText, { renameText = it }, singleLine = true, label = { Text("Nom") })
            },
            confirmButton = {
                TextButton(
                    enabled = renameText.isNotBlank(),
                    onClick = { vm.renameCategory(old, renameText); renaming = null }
                ) { Text("Enregistrer") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Annuler") } }
        )
    }

    deleting?.let { name ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Supprimer « $name » ?") },
            text = { Text("Les recettes de cette catégorie ne sont pas supprimées : elles n'auront plus de catégorie.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteCategory(name); deleting = null }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Annuler") } }
        )
    }
}
