package org.appdevforall.codeonthego.indexing.jvm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.appdevforall.codeonthego.lsp.kotlin.index.FileIndex
import org.appdevforall.codeonthego.lsp.kotlin.index.IndexedSymbol
import org.appdevforall.codeonthego.lsp.kotlin.index.IndexedSymbolKind
import org.appdevforall.codeonthego.lsp.kotlin.parser.KotlinParser
import org.appdevforall.codeonthego.lsp.kotlin.symbol.SymbolBuilder
import org.appdevforall.codeonthego.lsp.kotlin.symbol.Visibility
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Parses a Kotlin source file and produces [JvmSymbol] entries for indexing.
 *
 * Uses tree-sitter (via [KotlinParser]) for fast, error-tolerant parsing and
 * [SymbolBuilder] to extract declarations. The resulting symbols are represented
 * using the shared [JvmSymbol] model so they can be stored in any
 * [org.appdevforall.codeonthego.indexing.api.Index] that accepts [JvmSymbol].
 *
 * Thread safety: each call to [scan] creates its own [KotlinParser] instance,
 * so concurrent calls are safe.
 */
object KotlinSourceScanner {

    private val log = LoggerFactory.getLogger(KotlinSourceScanner::class.java)

    /**
     * Parses the Kotlin source file at [filePath] and emits a [JvmSymbol] for
     * each indexed declaration (classifiers, functions, properties, type aliases
     * — both top-level and members).
     *
     * @param filePath Absolute path to the `.kt` file on disk.
     * @param sourceId The [JvmSymbol.sourceId] to stamp on every emitted symbol.
     *                 Typically the same as [filePath] so that [removeBySource]
     *                 can remove all symbols from a specific file atomically.
     */
    fun scan(filePath: String, sourceId: String): Flow<JvmSymbol> = flow {
        val file = File(filePath)
        if (!file.exists() || !file.isFile) return@flow

        val content = try {
            file.readText()
        } catch (e: Exception) {
            log.warn("Failed to read source file: {}", filePath, e)
            return@flow
        }

        KotlinParser().use { parser ->
            val result = parser.parse(content, filePath)
            result.tree.use { syntaxTree ->
                val symbolTable = SymbolBuilder.build(syntaxTree, filePath)
                val fileIndex = FileIndex.fromSymbolTable(symbolTable)

                // findByPrefix("", 0) returns all symbols because every name starts with "".
                val allSymbols = fileIndex.findByPrefix("", 0)
                for (symbol in allSymbols) {
                    toJvmSymbol(symbol, sourceId)?.let { emit(it) }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun toJvmSymbol(symbol: IndexedSymbol, sourceId: String): JvmSymbol? {
        val kind = mapKind(symbol) ?: return null
        val visibility = mapVisibility(symbol.visibility)

        val data: JvmSymbolInfo = when {
            symbol.kind.isClass -> JvmClassInfo(
                internalName = symbol.fqName,
                containingClassName = symbol.containingClass ?: "",
                supertypeNames = symbol.superTypes,
                typeParameters = symbol.typeParameters,
                kotlin = KotlinClassInfo(
                    isData = symbol.kind == IndexedSymbolKind.DATA_CLASS,
                    isValue = symbol.kind == IndexedSymbolKind.VALUE_CLASS,
                ),
            )

            symbol.kind == IndexedSymbolKind.FUNCTION
                    || symbol.kind == IndexedSymbolKind.CONSTRUCTOR -> {
                val params = symbol.parameters.map { param ->
                    JvmParameterInfo(
                        name = param.name,
                        typeName = param.type,
                        typeDisplayName = param.type,
                        hasDefaultValue = param.hasDefault,
                        isVararg = param.isVararg,
                    )
                }
                JvmFunctionInfo(
                    containingClassName = symbol.containingClass ?: "",
                    returnTypeName = symbol.returnType ?: "Unit",
                    returnTypeDisplayName = symbol.returnType ?: "Unit",
                    parameterCount = params.size,
                    parameters = params,
                    signatureDisplay = buildSignatureDisplay(symbol),
                    typeParameters = symbol.typeParameters,
                    kotlin = symbol.receiverType?.let { receiverType ->
                        KotlinFunctionInfo(
                            receiverTypeName = receiverType,
                            receiverTypeDisplayName = receiverType,
                        )
                    },
                )
            }

            symbol.kind == IndexedSymbolKind.PROPERTY -> JvmFieldInfo(
                containingClassName = symbol.containingClass ?: "",
                typeName = symbol.returnType ?: "Any",
                typeDisplayName = symbol.returnType ?: "Any",
                kotlin = symbol.receiverType?.let { receiverType ->
                    KotlinPropertyInfo(
                        receiverTypeName = receiverType,
                        receiverTypeDisplayName = receiverType,
                    )
                },
            )

            symbol.kind == IndexedSymbolKind.TYPE_ALIAS -> JvmTypeAliasInfo(
                expandedTypeName = symbol.returnType ?: "",
                expandedTypeDisplayName = symbol.returnType ?: "",
                typeParameters = symbol.typeParameters,
            )

            else -> return null
        }

        val key = when {
            kind == JvmSymbolKind.FUNCTION
                    || kind == JvmSymbolKind.EXTENSION_FUNCTION
                    || kind == JvmSymbolKind.CONSTRUCTOR -> {
                val paramTypes = symbol.parameters.joinToString(",") { it.type }
                "${symbol.fqName}($paramTypes)"
            }
            else -> symbol.fqName
        }

        return JvmSymbol(
            key = key,
            sourceId = sourceId,
            name = symbol.fqName,
            shortName = symbol.name,
            packageName = symbol.packageName,
            kind = kind,
            language = JvmSourceLanguage.KOTLIN,
            visibility = visibility,
            isDeprecated = symbol.deprecated,
            data = data,
        )
    }

    private fun mapKind(symbol: IndexedSymbol): JvmSymbolKind? = when (symbol.kind) {
        IndexedSymbolKind.CLASS -> JvmSymbolKind.CLASS
        IndexedSymbolKind.INTERFACE -> JvmSymbolKind.INTERFACE
        IndexedSymbolKind.OBJECT -> JvmSymbolKind.OBJECT
        IndexedSymbolKind.ENUM_CLASS -> JvmSymbolKind.ENUM
        IndexedSymbolKind.ANNOTATION_CLASS -> JvmSymbolKind.ANNOTATION_CLASS
        IndexedSymbolKind.DATA_CLASS -> JvmSymbolKind.DATA_CLASS
        IndexedSymbolKind.VALUE_CLASS -> JvmSymbolKind.VALUE_CLASS
        IndexedSymbolKind.FUNCTION -> {
            if (symbol.receiverType != null) JvmSymbolKind.EXTENSION_FUNCTION
            else JvmSymbolKind.FUNCTION
        }
        IndexedSymbolKind.CONSTRUCTOR -> JvmSymbolKind.CONSTRUCTOR
        IndexedSymbolKind.PROPERTY -> {
            if (symbol.receiverType != null) JvmSymbolKind.EXTENSION_PROPERTY
            else JvmSymbolKind.PROPERTY
        }
        IndexedSymbolKind.TYPE_ALIAS -> JvmSymbolKind.TYPE_ALIAS
    }

    private fun mapVisibility(visibility: Visibility): JvmVisibility = when (visibility) {
        Visibility.PUBLIC -> JvmVisibility.PUBLIC
        Visibility.PROTECTED -> JvmVisibility.PROTECTED
        Visibility.INTERNAL -> JvmVisibility.INTERNAL
        Visibility.PRIVATE -> JvmVisibility.PRIVATE
    }

    private fun buildSignatureDisplay(symbol: IndexedSymbol): String = buildString {
        symbol.receiverType?.let { append(it).append('.') }
        if (symbol.typeParameters.isNotEmpty()) {
            append('<')
            append(symbol.typeParameters.joinToString())
            append('>')
        }
        append('(')
        append(symbol.parameters.joinToString { "${it.name}: ${it.type}" })
        append(')')
        symbol.returnType?.let { append(": ").append(it) }
    }
}
