package com.example.sploot.ml

import com.example.sploot.features.FeatureVectorBuilder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream

/** Validated metadata JSON accompanying every .onnx model. */
@Serializable
data class ModelMetadata(
    /** Engine identifier, e.g. "sploot-seq" or "sploot-gbdt" (must match [EngineRegistry] id). */
    val engineId: String,
    /**
     * Feature contract: either an integer feature-list version (compared against
     * [FeatureVectorBuilder.FEATURE_VERSION]) or an explicit `featureNames` list
     * (compared element-wise against FEATURE_NAMES).
     */
    val featureVersion: Int? = null,
    val featureNames: List<String>? = null,
    /** True when the model emits raw logits (softmax applied on-device). */
    val logits: Boolean = false,
    /** Optional tensor-name overrides (defaults: first session input/output). */
    val inputName: String? = null,
    val outputName: String? = null,
) {
    init {
        require(featureVersion != null || featureNames != null) {
            "metadata must carry either featureVersion or featureNames"
        }
        require(engineId.isNotBlank()) { "engineId must not be blank" }
    }

    /** Throws [IllegalArgumentException] when the model's contract != FEATURE_NAMES. */
    fun validateFeatureContract() {
        featureNames?.let { names ->
            require(names == FeatureVectorBuilder.FEATURE_NAMES) {
                "model feature list (${names.size} features) does not match app contract " +
                    "(${FeatureVectorBuilder.FEATURE_NAMES.size} features, v${FeatureVectorBuilder.FEATURE_VERSION})"
            }
        }
        featureVersion?.let { v ->
            require(v == FeatureVectorBuilder.FEATURE_VERSION) {
                "model featureVersion $v != app FEATURE_VERSION ${FeatureVectorBuilder.FEATURE_VERSION} — rejecting model"
            }
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): ModelMetadata = json.decodeFromString(serializer(), text)
    }
}

/** Model bytes + parsed metadata, ready for engine construction. */
class LoadedModel(val bytes: ByteArray, val metadata: ModelMetadata)

/**
 * Loads ONNX models for the staging engines:
 *  1. user-supplied replacements from `filesDir/models/<engineId>.onnx` + `.json`, if present;
 *  2. otherwise the models bundled in `assets/models/`.
 *
 * Replacement models are verified by opening an OrtSession on their bytes and
 * validating the metadata JSON (engineId + feature contract) — a model whose
 * feature contract != FEATURE_NAMES version is **rejected** before installation.
 */
class ModelLoader(
    private val assets: (String) -> InputStream?,   // Android: AssetManager::open
    private val modelDir: File?,                    // Android: File(context.filesDir, "models")
) {
    /** Load the active model for [engineId] (replacement dir first, then assets). */
    fun load(engineId: String): LoadedModel {
        modelDir?.let { dir ->
            val local = File(dir, "$engineId.onnx")
            val localMeta = File(dir, "$engineId.json")
            if (local.isFile && localMeta.isFile) {
                return LoadedModel(local.readBytes(), parseAndValidate(localMeta.readText(), engineId))
            }
        }
        val bytes = assets("models/$engineId.onnx")
            ?.use { it.readBytes() }
            ?: throw IllegalArgumentException("no model found for engine '$engineId' (assets or ${modelDir?.path})")
        val metaText = assets("models/$engineId.json")
            ?.use { it.readBytes().decodeToString() }
            ?: throw IllegalArgumentException("missing metadata models/$engineId.json in assets")
        return LoadedModel(bytes, parseAndValidate(metaText, engineId))
    }

    /** Load both engines' models in one go (registry ids from [EngineRegistry.ALL]). */
    fun loadAll(): Map<String, LoadedModel> = EngineRegistry.ALL.associateWith { load(it) }

    /**
     * Install a user-supplied replacement: validates bytes (OrtSession opens) and
     * metadata (engineId + feature contract) before copying into [modelDir].
     * Returns the loaded model; throws on any mismatch.
     */
    fun installReplacement(engineId: String, modelStream: InputStream, metadataStream: InputStream): LoadedModel {
        val dir = modelDir ?: error("no modelDir configured — cannot install replacements")
        val bytes = modelStream.use { it.readBytes() }
        val meta = parseAndValidate(metadataStream.use { it.readBytes().decodeToString() }, engineId)
        SessionOpener.DEFAULT.open(bytes).use { session -> // verify model opens
            require(session.inputNames.isNotEmpty() && session.outputNames.isNotEmpty()) {
                "model has no inputs/outputs"
            }
        }
        dir.mkdirs()
        File(dir, "$engineId.onnx").writeBytes(bytes)
        File(dir, "$engineId.json").writeText(Json { prettyPrint = true }.encodeToString(ModelMetadata.serializer(), meta))
        return LoadedModel(bytes, meta)
    }

    private fun parseAndValidate(metaText: String, engineId: String): ModelMetadata {
        val meta = ModelMetadata.parse(metaText)
        require(meta.engineId == engineId) {
            "metadata engineId '${meta.engineId}' does not match requested '$engineId'"
        }
        meta.validateFeatureContract()
        return meta
    }

    companion object {
        /**
         * Directory-based loader (unit tests, snoop-import flows): expects
         * `<dir>/<engineId>.onnx` + `<dir>/<engineId>.json`. Bypasses asset lookup.
         */
        fun forDirectory(dir: File): ModelLoader = ModelLoader(
            assets = { null },
            modelDir = dir,
        )
    }
}
