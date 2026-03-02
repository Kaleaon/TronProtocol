package com.tronprotocol.app.llm.store

import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path

/**
 * Retrofit interface for the HuggingFace Hub API.
 *
 * Ported from ToolNeuron's HuggingFace API client. Provides model repo browsing,
 * file listing, and metadata retrieval for on-device model discovery.
 */
interface HuggingFaceApi {

    /**
     * Get repository metadata (description, tags, downloads, etc.).
     */
    @GET("api/models/{repo}")
    suspend fun getRepoInfo(
        @Path("repo", encoded = true) repo: String,
        @Header("Authorization") authHeader: String? = null
    ): HuggingFaceRepoResponse

    /**
     * List files in a repository's main branch.
     */
    @GET("api/models/{repo}/tree/main")
    suspend fun getRepoFiles(
        @Path("repo", encoded = true) repo: String,
        @Header("Authorization") authHeader: String? = null
    ): List<HuggingFaceFileResponse>
}

/** Response from the HuggingFace model info endpoint. */
data class HuggingFaceRepoResponse(
    val id: String,
    val modelId: String? = null,
    val author: String? = null,
    val sha: String? = null,
    val lastModified: String? = null,
    val tags: List<String> = emptyList(),
    val pipeline_tag: String? = null,
    val downloads: Int = 0,
    val likes: Int = 0,
    val library_name: String? = null,
    val siblings: List<HuggingFaceSibling> = emptyList()
)

/** A file entry returned in the siblings list or tree endpoint. */
data class HuggingFaceSibling(
    val rfilename: String,
    val size: Long? = null
)

/** Response from the tree endpoint. */
data class HuggingFaceFileResponse(
    val type: String,       // "file" or "directory"
    val oid: String? = null,
    val size: Long = 0,
    val path: String,
    val lfs: HuggingFaceLfsInfo? = null
)

/** LFS metadata for large files. */
data class HuggingFaceLfsInfo(
    val oid: String,
    val size: Long,
    val pointerSize: Long? = null
)
