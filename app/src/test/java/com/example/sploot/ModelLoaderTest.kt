package com.example.sploot

import com.example.sploot.features.FeatureVectorBuilder
import com.example.sploot.ml.EngineRegistry
import com.example.sploot.ml.ModelLoader
import com.example.sploot.ml.ModelMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelLoaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun assetsModelsDir(): File {
        // Gradle unit tests run with CWD = app module; walk up as fallback.
        val candidates = listOf(
            File("src/main/assets/models"),
            File("app/src/main/assets/models"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("assets/models not found (CWD=${File(".").absolutePath})")
    }

    private fun writeModel(dir: File, engineId: String, json: String) {
        // Copy the placeholder .onnx bytes as the "model"; only metadata varies here.
        val src = File(
            assetsModelsDir(),
            "sploot-${if (engineId == EngineRegistry.IBI) "ibi-cnn" else if (engineId == EngineRegistry.SEQ) "seq" else "gbdt"}.onnx",
        )
        File(dir, "$engineId.onnx").writeBytes(src.readBytes())
        File(dir, "$engineId.json").writeText(json)
    }

    @Test
    fun `bundled models load and validate`() {
        val loader = ModelLoader.forDirectory(assetsModelsDir())
        val models = loader.loadAll()
        assertEquals(setOf(EngineRegistry.IBI, EngineRegistry.SEQ, EngineRegistry.GBDT), models.keys)
        models.values.forEach { assertTrue(it.bytes.size > 100) }
    }

    @Test
    fun `ibi model metadata carries wake bias and skips feature contract`() {
        val loader = ModelLoader.forDirectory(assetsModelsDir())
        val meta = loader.load(EngineRegistry.IBI).metadata
        assertEquals(EngineRegistry.IBI, meta.engineId)
        assertEquals(1.25f, meta.wakeBias)
        meta.validateFeatureContract() // raw-IBI model: no 127-feature contract, must not throw
    }

    @Test
    fun `rejects wrong feature version`() {
        val meta = ModelMetadata.parse(
            """{"engineId":"sploot-seq","featureVersion":2,"logits":false}"""
        )
        try {
            meta.validateFeatureContract()
            throw AssertionError("expected rejection of featureVersion 2")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("FEATURE_VERSION"))
        }
    }

    @Test
    fun `rejects mismatched explicit feature list`() {
        val meta = ModelMetadata.parse(
            """{"engineId":"sploot-seq","featureNames":["a","b"]}"""
        )
        try {
            meta.validateFeatureContract()
            throw AssertionError("expected rejection of mismatched feature list")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `accepts matching explicit feature list`() {
        val names = FeatureVectorBuilder.FEATURE_NAMES.joinToString("\",\"", "\"", "\"")
        val meta = ModelMetadata.parse("""{"engineId":"sploot-seq","featureNames":[$names],"logits":true}""")
        meta.validateFeatureContract() // must not throw
        assertTrue(meta.logits)
    }

    @Test
    fun `rejects engineId mismatch on load`() {
        val dir = tmp.newFolder()
        writeModel(dir, EngineRegistry.SEQ, """{"engineId":"sploot-gbdt","featureVersion":1}""")
        try {
            ModelLoader.forDirectory(dir).load(EngineRegistry.SEQ)
            throw AssertionError("expected rejection on engineId mismatch")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("sploot-gbdt"))
        }
    }

    @Test
    fun `replacement model installs into model dir and takes precedence`() {
        val dir = tmp.newFolder()
        // First load from bundled assets (fallback path).
        val assetLoader = ModelLoader(
            { name -> File(assetsModelsDir(), name.removePrefix("models/")).takeIf { it.isFile }?.inputStream() },
            dir,
        )
        assertEquals(EngineRegistry.SEQ, assetLoader.load(EngineRegistry.SEQ).metadata.engineId)

        // Install a "replacement" (same placeholder bytes + metadata) and verify precedence.
        val assets = assetsModelsDir()
        val installed = assetLoader.installReplacement(
            EngineRegistry.GBDT,
            File(assets, "sploot-gbdt.onnx").inputStream(),
            File(assets, "sploot-gbdt.json").inputStream(),
        )
        assertEquals(EngineRegistry.GBDT, installed.metadata.engineId)
        assertTrue(File(dir, "sploot-gbdt.onnx").isFile)
        assertTrue(File(dir, "sploot-gbdt.json").isFile)

        // modelDir now wins over assets for gbdt.
        val reloaded = assetLoader.load(EngineRegistry.GBDT)
        assertEquals(EngineRegistry.GBDT, reloaded.metadata.engineId)
    }

    @Test
    fun `installReplacement rejects wrong feature version without installing`() {
        val dir = tmp.newFolder()
        val loader = ModelLoader({ null }, dir)
        val assets = assetsModelsDir()
        try {
            loader.installReplacement(
                EngineRegistry.SEQ,
                File(assets, "sploot-seq.onnx").inputStream(),
                """{"engineId":"sploot-seq","featureVersion":99}""".byteInputStream(),
            )
            throw AssertionError("expected rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("99"))
        }
        assertTrue(!File(dir, "sploot-seq.onnx").exists()) // nothing written
    }
}
