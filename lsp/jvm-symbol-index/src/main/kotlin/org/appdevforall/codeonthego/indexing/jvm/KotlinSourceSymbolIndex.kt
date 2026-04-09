package org.appdevforall.codeonthego.indexing.jvm

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import org.appdevforall.codeonthego.indexing.SQLiteIndex
import org.appdevforall.codeonthego.indexing.api.indexQuery
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolDescriptor.KEY_CONTAINING_CLASS
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolDescriptor.KEY_NAME
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolDescriptor.KEY_PACKAGE
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolDescriptor.KEY_RECEIVER_TYPE
import org.appdevforall.codeonthego.indexing.util.BackgroundIndexer
import java.io.Closeable

/**
 * An index of symbols extracted from Kotlin source files in the project.
 *
 * Unlike [JvmLibrarySymbolIndex], which accumulates a persistent on-disk cache
 * of library JARs across IDE sessions, this index is deliberately **in-memory**:
 * it is rebuilt from scratch on each project open and discarded when the project
 * closes. This is correct because source files are cheap to re-parse (tree-sitter
 * is fast) and the index must always reflect the current on-disk state.
 */
class KotlinSourceSymbolIndex private constructor(
	val sourceIndex: SQLiteIndex<JvmSymbol>,
	val sourceIndexer: BackgroundIndexer<JvmSymbol>,
) : Closeable {

	companion object {

		const val INDEX_NAME_SOURCE = "kotlin-source-index"

		/**
		 * Creates a [KotlinSourceSymbolIndex] backed by an in-memory SQLite database.
		 *
		 * The [context] is required by the AndroidX SQLite helpers even for in-memory
		 * databases; it is not used for any file I/O in this case.
		 */
		fun create(context: Context): KotlinSourceSymbolIndex {
			// dbName = null → AndroidX SQLiteOpenHelper creates an in-memory database.
			val index = SQLiteIndex(
				descriptor = JvmSymbolDescriptor,
				context = context,
				dbName = null,
				name = INDEX_NAME_SOURCE,
			)
			val indexer = BackgroundIndexer(index)
			return KotlinSourceSymbolIndex(
				sourceIndex = index,
				sourceIndexer = indexer,
			)
		}
	}

	/**
	 * Indexes the symbols in [filePath], skipping the file if it was already
	 * indexed in this session.
	 *
	 * Use [reindexFile] to force re-parsing (e.g. after a save event).
	 */
	fun indexFile(
		filePath: String,
		provider: (sourceId: String) -> Flow<JvmSymbol> = { sourceId ->
			KotlinSourceScanner.scan(filePath, sourceId)
		},
	) = sourceIndexer.indexSource(filePath, skipIfExists = true, provider)

	/**
	 * Re-indexes [filePath] unconditionally, removing any previously indexed
	 * symbols for that file first.
	 *
	 * Call this after the file is saved to disk.
	 */
	fun reindexFile(
		filePath: String,
		provider: (sourceId: String) -> Flow<JvmSymbol> = { sourceId ->
			KotlinSourceScanner.scan(filePath, sourceId)
		},
	) = sourceIndexer.indexSource(filePath, skipIfExists = false, provider)

	/**
	 * Removes all symbols that originate from [filePath] from the index.
	 *
	 * Implemented by scheduling an indexing job with an empty provider so that
	 * the [BackgroundIndexer] properly cancels any in-flight job for the same
	 * source before clearing the entries.
	 */
	fun removeFile(filePath: String) {
		sourceIndexer.indexSource(
			sourceId = filePath,
			skipIfExists = false,
		) { kotlinx.coroutines.flow.emptyFlow() }
	}

	/**
	 * Returns `true` if [filePath] has already been indexed in this session.
	 */
	suspend fun isFileCached(filePath: String): Boolean =
		sourceIndex.containsSource(filePath)

	/** Prefix-based completion across all source symbols. */
	fun findByPrefix(prefix: String, limit: Int = 200): Flow<JvmSymbol> =
		sourceIndex.query(indexQuery { prefix(KEY_NAME, prefix); this.limit = limit })

	/** Prefix-based completion filtered to specific [kinds]. */
	fun findByPrefix(
		prefix: String,
		kinds: Set<JvmSymbolKind>,
		limit: Int = 200,
	): Flow<JvmSymbol> =
		sourceIndex.query(indexQuery { prefix(KEY_NAME, prefix); this.limit = 0 })
			.filter { it.kind in kinds }
			.take(limit)

	/** Find extension functions / properties declared for [receiverTypeFqName]. */
	fun findExtensionsFor(
		receiverTypeFqName: String,
		namePrefix: String = "",
		limit: Int = 200,
	): Flow<JvmSymbol> = sourceIndex.query(indexQuery {
		eq(KEY_RECEIVER_TYPE, receiverTypeFqName)
		if (namePrefix.isNotEmpty()) prefix(KEY_NAME, namePrefix)
		this.limit = limit
	})

	/** Top-level callable symbols (functions, properties) in a package. */
	fun findTopLevelCallablesInPackage(
		packageName: String,
		namePrefix: String = "",
		limit: Int = 200,
	): Flow<JvmSymbol> = sourceIndex.query(indexQuery {
		eq(KEY_PACKAGE, packageName)
		if (namePrefix.isNotEmpty()) prefix(KEY_NAME, namePrefix)
		this.limit = 0
	}).filter { it.kind.isCallable && it.isTopLevel }.take(limit)

	/** Top-level classifier symbols (classes, interfaces, objects…) in a package. */
	fun findClassifiersInPackage(
		packageName: String,
		namePrefix: String = "",
		limit: Int = 200,
	): Flow<JvmSymbol> = sourceIndex.query(indexQuery {
		eq(KEY_PACKAGE, packageName)
		if (namePrefix.isNotEmpty()) prefix(KEY_NAME, namePrefix)
		this.limit = 0
	}).filter { it.kind.isClassifier }.take(limit)

	/** Members of a specific class (functions, properties). */
	fun findMembersOf(
		classFqName: String,
		namePrefix: String = "",
		limit: Int = 200,
	): Flow<JvmSymbol> = sourceIndex.query(indexQuery {
		eq(KEY_CONTAINING_CLASS, classFqName)
		if (namePrefix.isNotEmpty()) prefix(KEY_NAME, namePrefix)
		this.limit = limit
	})

	/** Point lookup by fully-qualified name. */
	suspend fun findByFqName(fqName: String): JvmSymbol? = sourceIndex.get(fqName)

	/** All distinct package names present in the index. */
	fun allPackages(): Flow<String> = sourceIndex.distinctValues(KEY_PACKAGE)

	/** Suspends until all in-flight indexing jobs complete. */
	suspend fun awaitIndexing() = sourceIndexer.awaitAll()

	override fun close() {
		sourceIndexer.close()
		sourceIndex.close()
	}
}
