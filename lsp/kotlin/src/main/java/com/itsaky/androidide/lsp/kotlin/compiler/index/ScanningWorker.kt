package com.itsaky.androidide.lsp.kotlin.compiler.index

import com.itsaky.androidide.lsp.kotlin.compiler.modules.KtModule
import com.itsaky.androidide.lsp.kotlin.compiler.modules.asFlatSequence
import com.itsaky.androidide.lsp.kotlin.compiler.modules.isSourceModule
import java.util.concurrent.atomic.AtomicBoolean

internal class ScanningWorker(
	private val indexWorker: IndexWorker,
	private val modules: List<KtModule>,
) {

	private val isRunning = AtomicBoolean(false)

	suspend fun start() {
		isRunning.set(true)
		try {
			scan()
		} finally {
			isRunning.set(false)
		}
	}

	private suspend fun scan() {
		val allModules = modules.asFlatSequence()
		val sourceFiles = allModules
			.filter { it.isSourceModule }
			.flatMap { it.computeFiles(extended = true) }
			.takeWhile { isRunning.get() }
			.toList()

		for (sourceFile in sourceFiles) {
			if (!isRunning.get()) return
			indexWorker.submitCommand(IndexCommand.ScanSourceFile(sourceFile))
		}

		indexWorker.submitCommand(IndexCommand.SourceScanningComplete)

		sourceFiles.asSequence()
			.takeWhile { isRunning.get() }
			.forEach { sourceFile ->
				indexWorker.submitCommand(IndexCommand.IndexSourceFile(sourceFile))
			}

		allModules
			.filterNot { it.isSourceModule }
			.flatMap { it.computeFiles(extended = false) }
			.takeWhile { isRunning.get() }
			.forEach { indexWorker.submitCommand(IndexCommand.IndexLibraryFile(it)) }

		indexWorker.submitCommand(IndexCommand.IndexingComplete)
	}

	fun stop() {
		isRunning.set(false)
	}
}