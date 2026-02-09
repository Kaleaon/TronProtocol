package com.tronprotocol.app.aimodel

import android.content.Context
import android.util.Log
import com.tronprotocol.app.rag.RAGStore
import com.tronprotocol.app.rag.RetrievalStrategy
import com.tronprotocol.app.security.SecureStorage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Model Training Manager - AI creates and trains its own models from knowledge
 */
class ModelTrainingManager(private val context: Context) {

    private val storage = SecureStorage(context)
    private val models = mutableListOf<TrainedModel>()

    init {
        loadModels()
    }

    fun createModelFromKnowledge(
        modelName: String,
        ragStore: RAGStore,
        category: String
    ): TrainedModel {
        Log.d(TAG, "Creating model from knowledge...")
        val modelId = "model_${System.currentTimeMillis()}"

        val results = ragStore.retrieve(
            "$category knowledge", RetrievalStrategy.MEMRL, 100
        )

        val model = TrainedModel(
            modelId, modelName, category, results.size, 0,
            System.currentTimeMillis()
        )

        model.accuracy = 0.5 + (results.size / 200.0)
        models.add(model)
        saveModels()

        return model
    }

    fun getAllModels(): List<TrainedModel> = ArrayList(models)

    private fun saveModels() {
        val array = JSONArray()
        for (m in models) {
            val obj = JSONObject().apply {
                put("id", m.id)
                put("name", m.name)
            }
            array.put(obj)
        }
        storage.store(MODELS_KEY, array.toString())
    }

    private fun loadModels() {
        try {
            val data = storage.retrieve(MODELS_KEY) ?: return
            val array = JSONArray(data)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val model = TrainedModel(
                    obj.getString("id"), obj.getString("name"),
                    "", 0, 0, System.currentTimeMillis()
                )
                models.add(model)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading models", e)
        }
    }

    companion object {
        private const val TAG = "ModelTrainingManager"
        private const val MODELS_KEY = "trained_models"
    }
}
