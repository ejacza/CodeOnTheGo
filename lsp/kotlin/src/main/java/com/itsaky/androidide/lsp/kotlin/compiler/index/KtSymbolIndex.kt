package com.itsaky.androidide.lsp.kotlin.compiler.index

import com.github.benmanes.caffeine.cache.Caffeine
import com.itsaky.androidide.lsp.kotlin.compiler.CompilationKind
import com.itsaky.androidide.lsp.kotlin.compiler.modules.KtModule
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.utils.toVirtualFileOrNull
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolIndex
import org.appdevforall.codeonthego.indexing.jvm.KtFileMetadataIndex
import org.appdevforall.codeonthego.indexing.service.IndexKey
import org.checkerframework.checker.index.qual.NonNegative
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.fir.resolve.toArrayOfFactoryName
import org.jetbrains.kotlin.psi.KtFile
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

val KT_SOURCE_FILE_INDEX_KEY = IndexKey<JvmSymbolIndex>("kt-source-file-index")
val KT_SOURCE_FILE_META_INDEX_KEY = IndexKey<KtFileMetadataIndex>("kt-source-file-meta-index")

/**
 * An index of symbols from Kotlin source files and JARs.
 *
 * NOTE: This index does not own the provided [fileIndex], [sourceIndex] and [libraryIndex].
 * Callers are responsible for closing the provided indexes.
 */
internal class KtSymbolIndex(
	val kind: CompilationKind,
	val project: Project,
	modules: List<KtModule>,
	val fileIndex: KtFileMetadataIndex,
	val sourceIndex: JvmSymbolIndex,
	val libraryIndex: JvmSymbolIndex,
	cacheSize: @NonNegative Long = DEFAULT_CACHE_SIZE,
	private val scope: CoroutineScope = CoroutineScope(
		Dispatchers.Default + SupervisorJob() + CoroutineName(
			"KtSymbolIndex"
		)
	)
) {
	companion object {
		private val logger = LoggerFactory.getLogger(KtSymbolIndex::class.java)
		const val DEFAULT_CACHE_SIZE = 100L
	}

	private val workerQueue = WorkerQueue<IndexCommand>()
	private val indexWorker = IndexWorker(
		project = project,
		queue = workerQueue,
		fileIndex = fileIndex,
		sourceIndex = sourceIndex,
		scope = scope,
	)

	private val scanningWorker = ScanningWorker(
		kind = kind,
		sourceIndex = sourceIndex,
		indexWorker = indexWorker,
		modules = modules,
	)

	private var scanningJob: Job? = null
	private var indexingJob: Job? = null

	private val ktFileCache = Caffeine
		.newBuilder()
		.maximumSize(cacheSize)
		.build<Path, KtFile>()

	private val openedFiles = ConcurrentHashMap<Path, KtFile>()

	val openedKtFiles: Sequence<Map.Entry<Path, KtFile>>
		get() = openedFiles.asSequence()

	fun syncIndexInBackground() {
		indexingJob?.cancel()
		startIndexing()

		scanningJob?.cancel()
		startScanning()
	}

	private fun startIndexing() {
		indexingJob = scope.launch {
			indexWorker.start()
			indexingJob = null
		}
	}

	private fun startScanning() {
		scanningJob = scope.launch {
			scanningWorker.scan()
			scanningJob = null
		}
	}

	fun refreshSources() {
		indexingJob ?: startIndexing()

		scanningJob?.cancel()
		startScanning()
	}

	private fun getVirtualFileOrWarn(path: Path): VirtualFile? {
		return path.toVirtualFileOrNull() ?: run {
			logger.warn("cannot submit {} for indexing. unable to find virtual file", path)
			null
		}
	}

	suspend fun submitForIndexing(path: Path) {
		val vf = getVirtualFileOrWarn(path) ?: return
		indexWorker.apply {
			submitCommand(IndexCommand.ScanSourceFile(vf))
			submitCommand(IndexCommand.IndexSourceFile(vf))
		}
	}

	suspend fun removeFromIndex(path: Path) {
		val vf = getVirtualFileOrWarn(path) ?: return
		indexWorker.submitCommand(IndexCommand.RemoveFromIndex(vf))
	}

	suspend fun onFileMoved(from: Path, to: Path) {
		val fromVf = getVirtualFileOrWarn(from) ?: return
		val toVf = getVirtualFileOrWarn(to) ?: return

		indexWorker.apply {
			submitCommand(IndexCommand.RemoveFromIndex(fromVf))
			submitCommand(IndexCommand.ScanSourceFile(toVf))
			submitCommand(IndexCommand.IndexSourceFile(toVf))
		}
	}

	fun queueOnFileChangedAsync(ktFile: KtFile) {
		scope.launch {
			queueOnFileChanged(ktFile)
		}
	}

	suspend fun queueOnFileChanged(ktFile: KtFile) {
		indexWorker.submitCommand(IndexCommand.IndexModifiedFile(ktFile))
	}

	fun openKtFile(path: Path, ktFile: KtFile) {
		openedFiles[path] = ktFile
	}

	fun closeKtFile(path: Path) {
		openedFiles.remove(path)
	}

	fun getOpenedKtFile(path: Path) = openedFiles[path]

	fun getKtFile(vf: VirtualFile): KtFile {
		val path = vf.toNioPath()

		openedFiles[path]?.also { return it }
		ktFileCache.getIfPresent(path)?.also { return it }

		val ktFile = loadKtFile(vf)

		ktFileCache.put(path, ktFile)
		return ktFile
	}

	private fun loadKtFile(vf: VirtualFile): KtFile = project.read {
		PsiManager.getInstance(project)
			.findFile(vf) as KtFile
	}

	suspend fun close() {
		indexWorker.submitCommand(IndexCommand.Stop)

		scanningJob?.join()
		indexingJob?.join()
	}
}

internal fun KtSymbolIndex.packageExistsInSource(packageFqn: String) =
	fileIndex.packageExists(packageFqn)

internal fun KtSymbolIndex.filesForPackage(packageFqn: String) =
	fileIndex.getFilesForPackage(packageFqn)

internal fun KtSymbolIndex.subpackageNames(packageFqn: String) =
	fileIndex.getSubpackageNames(packageFqn)

internal fun KtSymbolIndex.findSymbolBySimpleName(name: String, limit: Int) =
	(sourceIndex.findBySimpleName(name, 0) + libraryIndex.findBySimpleName(name, 0))
		.take(limit)
