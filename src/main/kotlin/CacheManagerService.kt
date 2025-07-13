import dev.openrune.cache.gameval.GameValElement
import dev.openrune.cache.gameval.GameValHandler
import dev.openrune.definition.GameValGroupTypes
import dev.openrune.cache.tools.DownloadListener
import dev.openrune.cache.tools.OpenRS2
import mu.KotlinLogging
import me.tongfei.progressbar.ProgressBar
import me.tongfei.progressbar.ProgressBarBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

private val logger = KotlinLogging.logger {}

class CacheManagerService(
    private val rev: Int,
    private val cacheDir: File = File("./cache/$rev/")
) {
    lateinit var gameCache: dev.openrune.filesystem.Cache
        private set

    lateinit var objectGameVals: List<GameValElement>
        private set
    lateinit var itemGameVals: List<GameValElement>
        private set
    lateinit var npcGameVals: List<GameValElement>
        private set
    lateinit var spriteGameVals: List<GameValElement>
        private set

    suspend fun prepareCache() {
        if (!cacheDir.exists()) {
            downloadCache()
        }
        loadCache()
        loadGameValues()
    }

    private suspend fun downloadCache() {
        OpenRS2.downloadCacheByRevision(rev,cacheDir, listener =  object : DownloadListener {
            var progressBar: ProgressBar? = null

            override fun onProgress(progress: Int, max: Long, current: Long) {
                if (progressBar == null) {
                    progressBar = ProgressBarBuilder()
                        .setTaskName("Downloading Cache")
                        .setInitialMax(max)
                        .build()
                } else {
                    progressBar?.stepTo(current)
                }
            }

            override fun onError(exception: Exception) {
                error("Error downloading: $exception")
            }

            override fun onFinished() {
                progressBar?.close()
                val zipLoc = File(cacheDir, "disk.zip")
                if (unzip(zipLoc, cacheDir)) {
                    logger.info { "Cache downloaded and unzipped successfully." }
                    zipLoc.delete()
                } else {
                    error("Error unzipping cache.")
                }
                OpenRS2.downloadKeysByRevision(rev, cacheDir)
            }
        })
    }

    private fun unzip(zipFile: File, destDir: File): Boolean = try {
        if (!destDir.exists()) destDir.mkdirs()
        ZipInputStream(FileInputStream(zipFile)).use { zipInputStream ->
            var entry: ZipEntry?
            while (zipInputStream.nextEntry.also { entry = it } != null) {
                val outputFile = File(destDir, entry!!.name.substringAfterLast('/'))
                if (!entry!!.isDirectory) {
                    outputFile.parentFile?.mkdirs()
                    FileOutputStream(outputFile).use { output -> zipInputStream.copyTo(output) }
                }
                zipInputStream.closeEntry()
            }
        }
        logger.info { "Unzipped cache successfully." }
        true
    } catch (e: IOException) {
        logger.error { "Error unzipping cache: ${e.message}" }
        false
    }

    private fun loadCache() {
        gameCache = dev.openrune.filesystem.Cache.load(cacheDir.toPath(), true)
    }

    private fun loadGameValues() {
        objectGameVals = GameValHandler.readGameVal(GameValGroupTypes.LOCTYPES, gameCache)
        itemGameVals = GameValHandler.readGameVal(GameValGroupTypes.OBJTYPES, gameCache)
        npcGameVals = GameValHandler.readGameVal(GameValGroupTypes.NPCTYPES, gameCache)
        spriteGameVals = GameValHandler.readGameVal(GameValGroupTypes.SPRITETYPES, gameCache)
    }
}