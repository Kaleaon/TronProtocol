package com.tronprotocol.app.llm.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelStoreRepositoryStateTest {

    private val repository = ModelStoreRepository(api = FakeApi())

    @Test
    fun installStateTransitionIsAtomic() {
        val modelId = "repo/model"

        val changed = repository.transitionInstallState(
            modelId,
            ModelStoreRepository.InstallState.NONE,
            ModelStoreRepository.InstallState.DOWNLOADING
        )

        val notChanged = repository.transitionInstallState(
            modelId,
            ModelStoreRepository.InstallState.NONE,
            ModelStoreRepository.InstallState.INSTALLING
        )

        assertTrue(changed)
        assertFalse(notChanged)
        assertEquals(ModelStoreRepository.InstallState.DOWNLOADING, repository.getInstallState(modelId))
    }

    @Test
    fun markRollbackUpdatesState() {
        val modelId = "repo/model"
        repository.markRollback(modelId, "install_failed")

        assertEquals(ModelStoreRepository.InstallState.ROLLED_BACK, repository.getInstallState(modelId))
    }

    private class FakeApi : HuggingFaceApi {
        override suspend fun getRepoInfo(repo: String, authHeader: String?) =
            HuggingFaceRepoResponse(id = repo)

        override suspend fun getRepoFiles(repo: String, authHeader: String?) =
            emptyList<HuggingFaceFileResponse>()
    }
}
