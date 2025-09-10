package com.theveloper.pixelplay.utils

import android.app.Activity
import android.util.Base64
import android.util.Log
import com.microsoft.graph.authentication.IAuthenticationProvider
import com.microsoft.graph.core.ClientException
import com.microsoft.graph.requests.GraphServiceClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.concurrent.CompletableFuture

object GraphRepository {
    private val _songFolders = MutableStateFlow<List<SongFolderDroid>>(emptyList())
    val songFolders: StateFlow<List<SongFolderDroid>> = _songFolders

    suspend fun fetchStructuredSongs(activity: Activity, sharedLink: String): List<SongFolderDroid> {
        val token = GraphAuth.acquireToken(activity)

        val authProvider = SimpleAuthProvider(token)
        val client = GraphServiceClient.builder()
            .authenticationProvider(authProvider)
            .buildClient()

        val encoded = Base64.encodeToString(
            sharedLink.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        val shareId = "u!$encoded"

        return try {
            val rootItem = withContext(Dispatchers.IO) {
                client.shares(shareId)
                    .driveItem()
                    .buildRequest()
                    .get()
            }

            val rootId = rootItem?.id ?: return emptyList()
            val rootName = rootItem.name ?: "Root"

            val rootFolder = fetchSongsInFolder(client, rootId, rootName)

            val folderList = listOf(rootFolder)
            _songFolders.value = folderList
            folderList

        } catch (e: ClientException) {
            Log.e("GraphRepository", "ClientException: ${e.localizedMessage}", e)
            emptyList()
        } catch (e: Exception) {
            Log.e("GraphRepository", "Unexpected error", e)
            emptyList()
        }
    }

    private suspend fun fetchSongsInFolder(
        client: GraphServiceClient<*>,
        folderId: String,
        folderName: String
    ): SongFolderDroid {
        val songs = mutableListOf<SongDroid>()
        val subFolders = mutableListOf<SongFolderDroid>()

        var childrenPage = withContext(Dispatchers.IO) {
            client.me()
                .drive()
                .items(folderId)
                .children()
                .buildRequest()
                .get()
        }

        // Keep looping until no more pages
        while (childrenPage != null) {
            val items = childrenPage.currentPage ?: emptyList()

            for (child in items) {
                val isFolder = child.folder != null
                val file = child.file

                if (isFolder) {
                    val childId = child.id ?: continue
                    val subFolder = fetchSongsInFolder(client, childId, child.name ?: "Unnamed Folder")
                    subFolders.add(subFolder)
                    continue
                }

                val downloadUrlElement = child.additionalDataManager()["@microsoft.graph.downloadUrl"]
                val downloadUrl = if (downloadUrlElement != null && downloadUrlElement.isJsonPrimitive) {
                    downloadUrlElement.asString
                } else null

                val name = child.name?.lowercase() ?: ""
                val mime = file?.mimeType ?: ""

                val isAudio = mime.startsWith("audio") ||
                        (mime == "application/octet-stream" &&
                                (name.endsWith(".mp3") || name.endsWith(".flac") || name.endsWith(".wav")))

                if (file != null && downloadUrl != null && isAudio) {
                    songs.add(
                        SongDroid(
                            name = child.name ?: "Unknown",
                            size = child.size ?: 0L,
                            url = downloadUrl
                        )
                    )
                }
            }

            // Go to next page if available
            childrenPage = withContext(Dispatchers.IO) {
                childrenPage.nextPage?.buildRequest()?.get()
            }

        }

        return SongFolderDroid(
            folderName = folderName,
            songs = songs,
            subFolders = subFolders
        )
    }
}


class SimpleAuthProvider(private val token: String) : IAuthenticationProvider {
    override fun getAuthorizationTokenAsync(requestUrl: URL): CompletableFuture<String> {
        return CompletableFuture.completedFuture(token)
    }
}

@kotlinx.serialization.Serializable
data class SongDroid(
    val name: String,
    val size: Long,
    val url: String
)

@kotlinx.serialization.Serializable
data class SongFolderDroid(
    val folderName: String,
    val songs: List<SongDroid>,
    val subFolders: List<SongFolderDroid> = emptyList()
)