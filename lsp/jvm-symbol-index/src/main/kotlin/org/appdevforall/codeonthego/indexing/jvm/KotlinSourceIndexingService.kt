package org.appdevforall.codeonthego.indexing.jvm

import android.content.Context
import com.itsaky.androidide.eventbus.events.editor.DocumentSaveEvent
import com.itsaky.androidide.eventbus.events.file.FileCreationEvent
import com.itsaky.androidide.eventbus.events.file.FileDeletionEvent
import com.itsaky.androidide.eventbus.events.file.FileRenameEvent
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.projects.api.ModuleProject
import com.itsaky.androidide.tasks.cancelIfActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.appdevforall.codeonthego.indexing.service.IndexKey
import org.appdevforall.codeonthego.indexing.service.IndexRegistry
import org.appdevforall.codeonthego.indexing.service.IndexingService
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Well-known registry key for the Kotlin source symbol index.
 */
val KOTLIN_SOURCE_SYMBOL_INDEX = IndexKey<KotlinSourceSymbolIndex>("kotlin-source-symbols")

/**
 * [IndexingService] that scans all Kotlin source files in the open project and
 * maintains an in-memory [KotlinSourceSymbolIndex].
 */
class KotlinSourceIndexingService(
	private val context: Context,
) : IndexingService {

	companion object {
		const val ID = "kotlin-source-indexing-service"
		private val log = LoggerFactory.getLogger(KotlinSourceIndexingService::class.java)
	}

	override val id = ID
	override val providedKeys = listOf(KOTLIN_SOURCE_SYMBOL_INDEX)

	private var index: KotlinSourceSymbolIndex? = null
	private val refreshMutex = Mutex()
	private val coroutineScope = CoroutineScope(Dispatchers.Default)

	override suspend fun initialize(registry: IndexRegistry) {
		val sourceIndex = KotlinSourceSymbolIndex.create(context)
		this.index = sourceIndex
		registry.register(KOTLIN_SOURCE_SYMBOL_INDEX, sourceIndex)

		if (!EventBus.getDefault().isRegistered(this)) {
			EventBus.getDefault().register(this)
		}

		log.info("Kotlin source symbol index initialized")
	}

	override fun close() {
		EventBus.getDefault().unregister(this)
		coroutineScope.cancelIfActive("Kotlin source indexing service closed")
		index?.close()
		index = null
	}

	/**
	 * Scans all `.kt` source files across all project modules and indexes any
	 * file not yet present in the in-memory index.
	 */
	fun refresh() {
		coroutineScope.launch {
			refreshMutex.withLock { indexAllSourceFiles() }
		}
	}

	private suspend fun indexAllSourceFiles() {
		val index = this.index ?: run {
			log.warn("Kotlin source index not initialized; skipping refresh")
			return
		}

		val workspace = ProjectManagerImpl.getInstance().workspace ?: run {
			log.warn("Workspace model not available; skipping Kotlin source scan")
			return
		}

		val sourceFiles = workspace.subProjects
			.asSequence()
			.filterIsInstance<ModuleProject>()
			.flatMap { module -> module.getSourceDirectories().asSequence() }
			.filter { it.exists() && it.isDirectory }
			.flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" } }
			.map { it.absolutePath }
			.toList()

		log.info("Found {} Kotlin source files to index", sourceFiles.size)

		var submitted = 0
		for (filePath in sourceFiles) {
			if (!index.isFileCached(filePath)) {
				submitted++
				index.indexFile(filePath)
			}
		}

		if (submitted > 0) {
			log.info("{} Kotlin source files submitted for background indexing", submitted)
		} else {
			log.info("All Kotlin source files already cached, nothing to index")
		}
	}

	@Subscribe(threadMode = ThreadMode.ASYNC)
	@Suppress("UNUSED")
	fun onFileCreated(event: FileCreationEvent) {
		if (!event.file.isKotlinSource) return
		val filePath = event.file.absolutePath
		log.debug("File created, indexing: {}", filePath)
		index?.indexFile(filePath)
	}

	@Subscribe(threadMode = ThreadMode.ASYNC)
	@Suppress("UNUSED")
	fun onFileSaved(event: DocumentSaveEvent) {
		val filePath = event.savedFile.toAbsolutePath().toString()
		if (!filePath.endsWith(".kt")) return
		log.debug("File saved, re-indexing: {}", filePath)
		index?.reindexFile(filePath)
	}

	@Subscribe(threadMode = ThreadMode.ASYNC)
	@Suppress("UNUSED")
	fun onFileDeleted(event: FileDeletionEvent) {
		if (!event.file.isKotlinSource) return
		val filePath = event.file.absolutePath
		log.debug("File deleted, removing from index: {}", filePath)
		index?.removeFile(filePath)
	}

	@Subscribe(threadMode = ThreadMode.ASYNC)
	@Suppress("UNUSED")
	fun onFileRenamed(event: FileRenameEvent) {
		val oldPath = event.file.absolutePath
		val newPath = event.newFile.absolutePath

		if (event.file.isKotlinSource) {
			log.debug("File renamed, removing old path from index: {}", oldPath)
			index?.removeFile(oldPath)
		}

		if (event.newFile.isKotlinSource) {
			log.debug("File renamed, indexing new path: {}", newPath)
			index?.indexFile(newPath)
		}
	}

	private val File.isKotlinSource: Boolean
		get() = isFile && extension == "kt"
}
