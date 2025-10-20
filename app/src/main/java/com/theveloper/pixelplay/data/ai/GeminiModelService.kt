package com.theveloper.pixelplay.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

data class GeminiModel(
    val name: String,
    val displayName: String
)

@Singleton
class GeminiModelService @Inject constructor() {

    /**
     * Fetches available Gemini models using the provided API key.
     * Returns a list of model names that are available for the user.
     */
    suspend fun fetchAvailableModels(apiKey: String): Result<List<GeminiModel>> {
        return withContext(Dispatchers.IO) {
            try {
                if (apiKey.isBlank()) {
                    return@withContext Result.failure(Exception("API Key is required"))
                }

                // Use a lightweight model to test the API key and fetch available models
                // We'll make a request to list models using the Gemini API
                val response = makeModelsListRequest(apiKey)

                Result.success(response)
            } catch (e: Exception) {
                Timber.e(e, "Error fetching Gemini models")
                Result.failure(e)
            }
        }
    }

    private suspend fun makeModelsListRequest(apiKey: String): List<GeminiModel> {
        return withContext(Dispatchers.IO) {
            try {
                // Make HTTP request to Google's Gemini API to list models
                val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection

                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    parseModelsResponse(response)
                } else {
                    val errorMessage = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    Timber.e("Failed to fetch models: $responseCode - $errorMessage")
                    // Return default models if API call fails
                    getDefaultModels()
                }
            } catch (e: Exception) {
                Timber.e(e, "Exception fetching models, returning defaults")
                getDefaultModels()
            }
        }
    }

    private fun parseModelsResponse(jsonResponse: String): List<GeminiModel> {
        try {
            // Parse the JSON response to extract model names
            // Expected format: {"models": [{"name": "models/gemini-...", ...}, ...]}
            val models = mutableListOf<GeminiModel>()

            // Simple JSON parsing - extract model names
            val modelPattern = """"name":\s*"(models/[^"]+)"""".toRegex()
            val matches = modelPattern.findAll(jsonResponse)

            for (match in matches) {
                val fullName = match.groupValues[1]
                val modelName = fullName.removePrefix("models/")

                // Filter for generative models (gemini, gemini-pro, gemini-flash, etc.)
                if (modelName.startsWith("gemini", ignoreCase = true) &&
                    !modelName.contains("embedding", ignoreCase = true)) {
                    models.add(GeminiModel(
                        name = modelName,
                        displayName = formatDisplayName(modelName)
                    ))
                }
            }

            return if (models.isNotEmpty()) {
                models.sortedBy { it.displayName }
            } else {
                getDefaultModels()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error parsing models response")
            return getDefaultModels()
        }
    }

    private fun formatDisplayName(modelName: String): String {
        // Convert "gemini-2.5-flash" to "Gemini 2.5 Flash"
        return modelName
            .split("-")
            .joinToString(" ") { word ->
                word.replaceFirstChar { it.uppercase() }
            }
    }

    private fun getDefaultModels(): List<GeminiModel> {
        // Fallback models if API call fails
        return listOf(
            GeminiModel("gemini-2.5-flash", "Gemini 2.5 Flash"),
        )
    }
}
