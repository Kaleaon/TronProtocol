package com.tronprotocol.app.aimodel

/**
 * Represents a trained AI model created from knowledge
 */
class TrainedModel(
    val id: String,
    val name: String,
    val category: String,
    val conceptCount: Int,
    val knowledgeSize: Int,
    val createdTimestamp: Long
) {
    var accuracy: Double = 0.0
    var trainingIterations: Int = 0
    var lastTrainedTimestamp: Long = createdTimestamp

    // Model data
    var knowledgeBase: MutableList<String> = mutableListOf()
    var embeddings: MutableList<FloatArray> = mutableListOf()
    var parameters: MutableMap<String, Any> = mutableMapOf()

    override fun toString(): String {
        return "TrainedModel{" +
                "id='$id'" +
                ", name='$name'" +
                ", category='$category'" +
                ", accuracy=${String.format("%.2f%%", accuracy * 100)}" +
                ", concepts=$conceptCount" +
                ", iterations=$trainingIterations" +
                "}"
    }
}
