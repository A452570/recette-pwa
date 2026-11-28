package com.example.recettes

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Lit le texte d'une photo ou d'une capture d'écran (OCR ML Kit, exécuté sur le téléphone). */
object RecipeScanner {

    suspend fun readText(context: Context, uri: Uri): String {
        // Le décodage de l'image (et sa rotation EXIF) se fait hors du thread principal.
        val image = withContext(Dispatchers.IO) { InputImage.fromFilePath(context, uri) }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    recognizer.close()
                    cont.resume(result.text)
                }
                .addOnFailureListener { e ->
                    recognizer.close()
                    cont.resumeWithException(e)
                }
        }
    }
}
