package com.tronprotocol.app.llm.store

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Persistent storage for configured HuggingFace model repositories.
 *
 * Ported from ToolNeuron's ModelRepoDataStore. Stores the user's list of
 * configured HuggingFace repositories for model browsing, with defaults
 * for common GGUF and SD model sources.
 */
class ModelRepoDataStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    /**
     * Get the list of configured repos. Returns defaults if none are configured.
     */
    fun getRepos(): List<ModelStoreRepository.RepoConfig> {
        val json = prefs.getString(KEY_REPOS, null) ?: return ModelStoreRepository.DEFAULT_REPOS
        return try {
            val type = object : TypeToken<List<ModelStoreRepository.RepoConfig>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse repos, returning defaults: ${e.message}")
            ModelStoreRepository.DEFAULT_REPOS
        }
    }

    /**
     * Save the list of configured repos.
     */
    fun saveRepos(repos: List<ModelStoreRepository.RepoConfig>) {
        prefs.edit().putString(KEY_REPOS, gson.toJson(repos)).apply()
    }

    /**
     * Add a new repo to the configuration.
     */
    fun addRepo(repo: ModelStoreRepository.RepoConfig) {
        val current = getRepos().toMutableList()
        if (current.none { it.repoId == repo.repoId }) {
            current.add(repo)
            saveRepos(current)
        }
    }

    /**
     * Remove a repo from the configuration.
     */
    fun removeRepo(repoId: String) {
        val current = getRepos().toMutableList()
        current.removeAll { it.repoId == repoId }
        saveRepos(current)
    }

    /**
     * Reset to default repos.
     */
    fun resetToDefaults() {
        prefs.edit().remove(KEY_REPOS).apply()
    }

    /**
     * Get repos filtered by category.
     */
    fun getReposByCategory(category: ModelStoreRepository.ModelCategory): List<ModelStoreRepository.RepoConfig> {
        return getRepos().filter { it.category == category }
    }

    companion object {
        private const val TAG = "ModelRepoDataStore"
        private const val PREFS_NAME = "tronprotocol_model_repos"
        private const val KEY_REPOS = "configured_repos"
    }
}
