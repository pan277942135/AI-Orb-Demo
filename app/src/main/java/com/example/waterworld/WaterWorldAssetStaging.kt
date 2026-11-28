package com.example.waterworld

import android.content.Context
import android.content.res.AssetManager
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

/**
 * Process-wide, thread-safe staging helper for immutable Water World V2.1 frozen assets.
 *
 * Shared between Tab 3 (`WaterWorldPreviewActivity`) and Tab 2 (`Tab2WaterWorldHost`) so both
 * reuse the exact same frozen 65+ Water World textures, meshes, draw bindings, optical config,
 * and Work 3/3.2 motion profile without duplicating any assets or colliding during cache staging.
 */
internal object WaterWorldAssetStaging {

    const val DEFAULT_ASSET_ROOT_PATH = "water_world_v2_1"
    private const val STAGE_CACHE_DIR_NAME = "water_world_v2_1_tab2"
    private const val STAGE_STAMP_VERSION = "v2.1_work3_2_tab2_shared"

    private val stageLock = Any()

    data class StagedScene(
        val rootDir: File,
        val scene: WaterWorldSceneBindings.LoadedScene,
        val profile: WaterWorldMotionProfile,
        val stagingElapsedMs: Double,
    )

    fun hasRequiredAssets(assets: AssetManager, assetRootPath: String = DEFAULT_ASSET_ROOT_PATH): Boolean {
        val entries = assets.list(assetRootPath)
        if (entries == null || entries.isEmpty()) return false
        return try {
            assets.open("$assetRootPath/03_SCENE_GRAPH/draw_bindings.json").close()
            assets.open("$assetRootPath/02_SUPPLEMENTED_ASSETS/materials/optical_config.json").close()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Atomically extracts immutable Water World V2.1 assets into `cacheDir/water_world_v2_1` (once
     * per app install/version) and loads [WaterWorldSceneBindings.LoadedScene] and
     * [WaterWorldMotionProfile]. Safe to invoke from background I/O threads in both Tab 2 and Tab 3.
     */
    fun stageAndLoad(
        context: Context,
        assetRootPath: String = DEFAULT_ASSET_ROOT_PATH,
        forceReextract: Boolean = false,
        isCancelled: () -> Boolean = { false },
    ): StagedScene = synchronized(stageLock) {
        val startNs = System.nanoTime()
        val assets = context.assets
        if (!hasRequiredAssets(assets, assetRootPath)) {
            throw FileNotFoundException(
                "Missing required Water World APK assets under '$assetRootPath'",
            )
        }
        val pkgInfo = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val expectedStamp = "$STAGE_STAMP_VERSION|${assetRootPath}|${pkgInfo?.lastUpdateTime ?: 0L}"
        val root = File(context.cacheDir, STAGE_CACHE_DIR_NAME)
        val stampFile = File(root, ".stage_stamp")
        val bindingsFile = File(root, "03_SCENE_GRAPH/draw_bindings.json")
        val opticalFile = File(root, "02_SUPPLEMENTED_ASSETS/materials/optical_config.json")
        val motionFile = File(root, "04_MOTION/work3_motion_profile.json")

        val cacheValid = !forceReextract &&
            stampFile.isFile &&
            stampFile.readText().trim() == expectedStamp &&
            bindingsFile.isFile &&
            opticalFile.isFile &&
            motionFile.isFile

        if (!cacheValid) {
            val incoming = File(context.cacheDir, "${STAGE_CACHE_DIR_NAME}_staging_${System.nanoTime()}")
            try {
                if (incoming.exists()) incoming.deleteRecursively()
                check(incoming.mkdirs()) { "Failed to create staging directory: ${incoming.path}" }
                extractAssetsRecursive(assets, assetRootPath, incoming, isCancelled)
                if (isCancelled()) {
                    incoming.deleteRecursively()
                    throw InterruptedException("Water World asset staging cancelled")
                }
                File(incoming, ".stage_stamp").writeText(expectedStamp)
                if (root.exists()) check(root.deleteRecursively()) { "Failed to replace cache: ${root.path}" }
                check(incoming.renameTo(root)) { "Failed to stage Water World assets into ${root.path}" }
            } catch (failure: Throwable) {
                incoming.deleteRecursively()
                throw failure
            }
        }

        val scene = WaterWorldSceneBindings.loadScene(root)
        val profile = WaterWorldMotionProfile.loadFromAssetRoot(root)
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
        StagedScene(
            rootDir = root,
            scene = scene,
            profile = profile,
            stagingElapsedMs = elapsedMs,
        )
    }

    private fun extractAssetsRecursive(
        assets: AssetManager,
        assetPath: String,
        destination: File,
        isCancelled: () -> Boolean,
    ) {
        if (isCancelled()) return
        val children = assets.list(assetPath) ?: throw FileNotFoundException("Missing APK assets: $assetPath")
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                FileOutputStream(destination).use { input.copyTo(it) }
            }
            return
        }
        check(destination.isDirectory || destination.mkdirs())
        for (child in children) {
            if (isCancelled()) return
            extractAssetsRecursive(assets, "$assetPath/$child", File(destination, child), isCancelled)
        }
    }
}
