package com.theveloper.pixelplay.utils

import android.app.Activity
import android.content.ContentUris
import android.util.Base64
import android.util.Log
import androidx.core.net.toUri
import com.microsoft.graph.authentication.IAuthenticationProvider
import com.microsoft.graph.core.ClientException
import com.microsoft.graph.requests.GraphServiceClient
import com.theveloper.pixelplay.data.database.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.concurrent.CompletableFuture

object GraphRepository {
    private val _songFolders = MutableStateFlow<List<SongFolderDroid>>(emptyList())
    val songFolders: StateFlow<List<SongFolderDroid>> = _songFolders

    /**
     * Fetch folder hierarchy + map into SongEntity list for DB
     */
    suspend fun fetchStructuredSongs(activity: Activity, sharedLink: String): List<SongEntity> {
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

            // Build folder hierarchy
            val rootFolder = fetchSongsInFolder(client, rootId, rootName)

            // Optional: Keep folder hierarchy for UI
            _songFolders.value = listOf(rootFolder)

            // Flatten hierarchy to entities and return
            val songEntities = mapSongsFromFolder(rootFolder)
            Log.d("GraphRepository", "Fetched ${songEntities.size} songs mapped into entities")
            songEntities

        } catch (e: ClientException) {
            Log.e("GraphRepository", "ClientException: ${e.localizedMessage}", e)
            emptyList()
        } catch (e: Exception) {
            Log.e("GraphRepository", "Unexpected error", e)
            emptyList()
        }
    }


    /**
     * Recursively fetch songs + subfolders from OneDrive
     */
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

/**
 * Auth provider for Graph
 */
class SimpleAuthProvider(private val token: String) : IAuthenticationProvider {
    override fun getAuthorizationTokenAsync(requestUrl: URL): CompletableFuture<String> {
        return CompletableFuture.completedFuture(token)
    }
}

/**
 * Map folder hierarchy -> flat list of SongEntity for Room
 */
fun mapSongsFromFolder(
    folder: SongFolderDroid,
    artistId: Long = 1L,
    artistName: String = "Unknown Artist",
    albumIdSeed: Long = 1000L
): List<SongEntity> {
    val entities = mutableListOf<SongEntity>()

    val albumId = albumIdSeed + folder.folderName.hashCode().toLong()

    val albumArtUriString = ContentUris.withAppendedId(
        "content://media/external/audio/albumart".toUri(), albumId
    ).toString()

    folder.songs.forEachIndexed { index, song ->
        entities += SongEntity(
            id = song.url.hashCode().toLong(),
            title = song.name.replace(Regex("^\\d+|\\s*\\.flac\$|[-.]"), "")
                .trim(),
            artistName = artistName,
            artistId = artistId,
            albumName = folder.folderName,
            albumId = albumId,
            contentUriString = song.url,
            albumArtUriString = albumArtUriString,
            duration = 0L,
            genre = null,
            filePath = song.url,
//            parentDirectoryPath = folder.folderName,
            parentDirectoryPath = "ONEDRIVE:${folder.folderName}",
            trackNumber = index + 1
        )
    }

    folder.subFolders.forEach { sub ->
        entities += mapSongsFromFolder(sub, artistId, artistName, albumIdSeed)
    }

    return entities
}

/**
 * Data classes for OneDrive music hierarchy
 */
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
