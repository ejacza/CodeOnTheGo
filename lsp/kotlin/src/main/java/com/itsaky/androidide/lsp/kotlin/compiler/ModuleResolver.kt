package com.itsaky.androidide.lsp.kotlin.compiler

import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibraryModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.nio.file.Paths

internal class ModuleResolver(
	private val jarMap: Map<Path, KaLibraryModule>,
	private val sourceRootMap: Map<Path, KaSourceModule> = emptyMap(),
) {
	companion object {
		private val logger = LoggerFactory.getLogger(ModuleResolver::class.java)
	}

	/**
	 * Find the module that declares the given source ID.
	 *
	 * - For library JARs, the source ID is the JAR path — looked up directly.
	 * - For source files, the source ID is the `.kt` file path — resolved by
	 *   finding the source root directory that is an ancestor of that path.
	 */
	fun findDeclaringModule(sourceId: String): KaModule? {
		val path = Paths.get(sourceId)
		jarMap[path]?.let { return it }

		// Walk source roots to find which module owns this file.
		for ((root, module) in sourceRootMap) {
			if (path.startsWith(root)) return module
		}

		return null
	}
}