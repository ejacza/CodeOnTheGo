package com.itsaky.androidide.lsp.kotlin.compiler.services

import com.itsaky.androidide.lsp.kotlin.compiler.index.KtSymbolIndex
import com.itsaky.androidide.lsp.kotlin.compiler.modules.KtModule
import com.itsaky.androidide.lsp.kotlin.compiler.modules.NotUnderContentRootModule
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProviderBase
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaNotUnderContentRootModule
import org.jetbrains.kotlin.cli.jvm.index.JavaRoot
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import java.nio.file.Paths

internal class ProjectStructureProvider : KtLspService, KotlinProjectStructureProviderBase() {

	private lateinit var modules: List<KtModule>
	private lateinit var project: Project

	private val notUnderContentRootModuleWithoutPsiFile by lazy {
		NotUnderContentRootModule(
			id = "unnamed-outside-content-root",
			moduleDescription = "unnamed-outside-content-root",
			project = project,
		)
	}

	override fun setupWith(
		project: MockProject,
		index: KtSymbolIndex,
		modules: List<KtModule>,
		libraryRoots: List<JavaRoot>
	) {
		this.modules = modules
		this.project = project
	}

	override fun getModule(
		element: PsiElement,
		useSiteModule: KaModule?
	): KaModule {
		val virtualFile = element.containingFile.virtualFile
		val visited = mutableSetOf<KaModule>()

		modules.forEach { module ->
			val foundModule = searchVirtualFileInModule(virtualFile, useSiteModule ?: module, visited)
			if (foundModule != null) return foundModule
		}

		return NotUnderContentRootModule(
			id = "unnamed-outside-content-root",
			moduleDescription = "unnamed-outside-content-root module with a PSI file.",
			project = project,
			file = element.containingFile,
		)
	}

	/**
	 * Find the [KaModule] that owns the given [sourceId].
	 *
	 * - For library JARs, [sourceId] is the JAR path — matched against [KtModule.contentRoots] exactly.
	 * - For source files, [sourceId] is the `.kt` file path — matched by checking whether the path
	 *   falls under any source root in [KtModule.contentRoots].
	 *
	 * The search is recursive: if the top-level modules do not match, their transitive dependencies
	 * are checked as well.
	 *
	 * @return The declaring [KaModule], or `null` if none is found.
	 */
	@OptIn(KaExperimentalApi::class)
	fun findModuleForSourceId(sourceId: String): KaModule? {
		val path = Paths.get(sourceId)
		val visited = mutableSetOf<String>()

		fun search(module: KaModule): KaModule? {
			if (!visited.add(module.moduleDescription)) return null
			if (module is KtModule) {
				val roots = module.contentRoots
				if (roots.contains(path) || roots.any { path.startsWith(it) }) return module
			}
			return module.directRegularDependencies.firstNotNullOfOrNull { search(it) }
		}

		return modules.firstNotNullOfOrNull { search(it) }
	}

	override fun getImplementingModules(module: KaModule): List<KaModule> {
		// TODO: needs to be implemented when we want to support KMP
		return emptyList()
	}

	@OptIn(KaPlatformInterface::class)
	override fun getNotUnderContentRootModule(project: Project): KaNotUnderContentRootModule {
		return notUnderContentRootModuleWithoutPsiFile
	}

	private fun searchVirtualFileInModule(vf: VirtualFile, module: KaModule, visited: MutableSet<KaModule>): KaModule? {
		if (visited.contains(module)) return null
		if (module.contentScope.contains(vf)) return module

		visited.add(module)
		module.directRegularDependencies
			.forEach { dependency ->
				val submodule = searchVirtualFileInModule(vf, dependency, visited)
				if (submodule != null) return submodule
			}

		return null
	}
}