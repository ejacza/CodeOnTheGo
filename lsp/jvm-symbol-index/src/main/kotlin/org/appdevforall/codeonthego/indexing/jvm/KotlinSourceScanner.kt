package org.appdevforall.codeonthego.indexing.jvm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.appdevforall.codeonthego.indexing.jvm.KotlinSourceScanner.fqnToInternalName
import org.appdevforall.codeonthego.indexing.jvm.KotlinSourceScanner.scan
import org.appdevforall.codeonthego.lsp.kotlin.parser.KotlinParser
import org.appdevforall.codeonthego.lsp.kotlin.symbol.ClassKind
import org.appdevforall.codeonthego.lsp.kotlin.symbol.ClassSymbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.FunctionSymbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.PropertySymbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.Symbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.SymbolBuilder
import org.appdevforall.codeonthego.lsp.kotlin.symbol.TypeAliasSymbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.TypeReference
import org.appdevforall.codeonthego.lsp.kotlin.symbol.Visibility
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Parses a Kotlin source file and produces [JvmSymbol] entries for indexing,
 * working directly from the [SymbolBuilder] output.
 *
 * Type references coming from source (which are as-written, dot-separated)
 * are converted to internal names via [fqnToInternalName], which applies the
 * standard Java naming convention: lowercase segments are package components,
 * uppercase-starting segments are class components.
 *
 * Thread safety: each call to [scan] creates its own [KotlinParser] instance.
 */
object KotlinSourceScanner {

	private val log = LoggerFactory.getLogger(KotlinSourceScanner::class.java)

	/**
	 * Parses the Kotlin source file at [filePath] and emits a [JvmSymbol] for
	 * each public/internal declaration found (classes and their members,
	 * top-level functions, properties, and type aliases).
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
				// Internal prefix for this file's package: "com/example"
				val pkgInternal = symbolTable.packageName.replace('.', '/')

				for (symbol in symbolTable.topLevelSymbols) {
					for (jvmSymbol in toJvmSymbols(
						symbol,
						pkgInternal,
						containingClass = "",
						sourceId
					)) {
						emit(jvmSymbol)
					}
				}
			}
		}
	}.flowOn(Dispatchers.IO)

	private fun toJvmSymbols(
		symbol: Symbol,
		pkgInternal: String,
		containingClass: String,
		sourceId: String,
	): List<JvmSymbol> = when (symbol) {
		is ClassSymbol -> classSymbols(symbol, pkgInternal, containingClass, sourceId)
		is FunctionSymbol -> listOfNotNull(
			functionSymbol(
				symbol,
				pkgInternal,
				containingClass,
				sourceId
			)
		)

		is PropertySymbol -> listOfNotNull(
			propertySymbol(
				symbol,
				pkgInternal,
				containingClass,
				sourceId
			)
		)

		is TypeAliasSymbol -> listOfNotNull(
			typeAliasSymbol(
				symbol,
				pkgInternal,
				containingClass,
				sourceId
			)
		)

		else -> emptyList()
	}

	private fun classSymbols(
		symbol: ClassSymbol,
		pkgInternal: String,
		containingClass: String,
		sourceId: String,
	): List<JvmSymbol> {
		// Enum entries use a member-style key relative to their containing enum.
		if (symbol.kind == ClassKind.ENUM_ENTRY) {
			return listOf(enumEntrySymbol(symbol, pkgInternal, containingClass, sourceId))
		}

		val visibility = mapVisibility(symbol.visibility)
		if (visibility == JvmVisibility.PRIVATE) return emptyList()

		// Internal name: "com/example/Outer$Inner" for nested, "com/example/Outer" for top-level.
		val classInternalName = buildClassInternalName(symbol.name, pkgInternal, containingClass)
		val packageName = pkgInternal.replace('/', '.')

		val kind = mapClassKind(symbol)

		val classSymbol = JvmSymbol(
			key = classInternalName,
			sourceId = sourceId,
			name = classInternalName,
			shortName = symbol.name,
			packageName = packageName,
			kind = kind,
			language = JvmSourceLanguage.KOTLIN,
			visibility = visibility,
			data = JvmClassInfo(
				internalName = classInternalName,
				containingClassName = containingClass,
				supertypeNames = symbol.superTypes.map { fqnToInternalName(it.name) },
				typeParameters = symbol.typeParameters.map { it.name },
				isAbstract = symbol.modifiers.isAbstract,
				isFinal = symbol.modifiers.isFinal,
				isInner = symbol.modifiers.isInner,
				kotlin = KotlinClassInfo(
					isData = symbol.modifiers.isData,
					isValue = symbol.modifiers.isValue,
					isSealed = symbol.modifiers.isSealed,
				),
			),
		)

		val result = mutableListOf(classSymbol)

		// Primary constructor (not always in the member scope, depending on SymbolBuilder).
		symbol.primaryConstructor?.let { ctor ->
			if (!ctor.isPrimaryConstructor || ctor !in (symbol.memberScope?.allSymbols
					?: emptyList())
			) {
				functionSymbol(ctor, pkgInternal, classInternalName, sourceId)?.let { result += it }
			}
		}

		// All members: nested classes, secondary constructors, functions, properties.
		for (member in symbol.memberScope?.allSymbols ?: emptyList()) {
			result += toJvmSymbols(member, pkgInternal, classInternalName, sourceId)
		}

		return result
	}

	private fun enumEntrySymbol(
		symbol: ClassSymbol,
		pkgInternal: String,
		containingClass: String,
		sourceId: String,
	): JvmSymbol {
		val packageName = pkgInternal.replace('/', '.')
		return JvmSymbol(
			key = "$containingClass#${symbol.name}",
			sourceId = sourceId,
			name = "$containingClass#${symbol.name}",
			shortName = symbol.name,
			packageName = packageName,
			kind = JvmSymbolKind.ENUM_ENTRY,
			language = JvmSourceLanguage.KOTLIN,
			data = JvmEnumEntryInfo(containingClassName = containingClass),
		)
	}

	private fun functionSymbol(
		symbol: FunctionSymbol,
		pkgInternal: String,
		containingClass: String,
		sourceId: String,
	): JvmSymbol? {
		val visibility = mapVisibility(symbol.visibility)
		if (visibility == JvmVisibility.PRIVATE) return null

		val kind = when {
			symbol.isConstructor -> JvmSymbolKind.CONSTRUCTOR
			symbol.isExtension -> JvmSymbolKind.EXTENSION_FUNCTION
			else -> JvmSymbolKind.FUNCTION
		}

		val packageName = pkgInternal.replace('/', '.')
		val owner = containingClass.ifEmpty { pkgInternal }

		// For constructors, the short name is the class's simple name.
		val shortName = if (symbol.isConstructor) {
			containingClass.substringAfterLast('/').substringAfterLast('$')
		} else {
			symbol.name
		}
		val name = "$owner#$shortName"

		val parameters = symbol.parameters.map { param ->
			JvmParameterInfo(
				name = param.name,
				typeName = param.type?.let { fqnToInternalName(it.name) } ?: "",
				typeDisplayName = param.type?.render() ?: "",
				hasDefaultValue = param.hasDefaultValue,
				isVararg = param.isVararg,
				isCrossinline = param.isCrossinline,
				isNoinline = param.isNoinline,
			)
		}

		val returnTypeInternal =
			symbol.returnType?.let { fqnToInternalName(it.name) } ?: "kotlin/Unit"
		val returnTypeDisplay = symbol.returnType?.render() ?: "Unit"
		val receiverType = symbol.receiverType

		val key = "$name(${parameters.joinToString(",") { it.typeName }})"

		val signatureDisplay = buildString {
			receiverType?.let { append(displayName(it)).append('.') }
			if (symbol.typeParameters.isNotEmpty()) {
				append('<')
				append(symbol.typeParameters.joinToString { it.name })
				append('>')
			}
			append('(')
			append(parameters.joinToString(", ") { "${it.name}: ${it.typeDisplayName}" })
			append(')')
			if (!symbol.isConstructor) {
				append(": ")
				append(returnTypeDisplay)
			}
		}

		return JvmSymbol(
			key = key,
			sourceId = sourceId,
			name = name,
			shortName = shortName,
			packageName = packageName,
			kind = kind,
			language = JvmSourceLanguage.KOTLIN,
			visibility = visibility,
			data = JvmFunctionInfo(
				containingClassName = containingClass,
				returnTypeName = returnTypeInternal,
				returnTypeDisplayName = returnTypeDisplay,
				parameterCount = parameters.size,
				parameters = parameters,
				signatureDisplay = signatureDisplay,
				typeParameters = symbol.typeParameters.map { it.name },
				isAbstract = symbol.modifiers.isAbstract,
				isFinal = symbol.modifiers.isFinal,
				kotlin = KotlinFunctionInfo(
					receiverTypeName = receiverType?.let { fqnToInternalName(it.name) } ?: "",
					receiverTypeDisplayName = receiverType?.let { displayName(it) } ?: "",
					isSuspend = symbol.isSuspend,
					isInline = symbol.isInline,
					isInfix = symbol.isInfix,
					isOperator = symbol.isOperator,
					isTailrec = symbol.isTailrec,
					isExternal = symbol.modifiers.isExternal,
					isExpect = symbol.modifiers.isExpect,
					isActual = symbol.modifiers.isActual,
					isReturnTypeNullable = symbol.returnType?.isNullable ?: false,
				),
			),
		)
	}

	private fun propertySymbol(
		symbol: PropertySymbol,
		pkgInternal: String,
		containingClass: String,
		sourceId: String,
	): JvmSymbol? {
		val visibility = mapVisibility(symbol.visibility)
		if (visibility == JvmVisibility.PRIVATE) return null

		val kind =
			if (symbol.isExtension) JvmSymbolKind.EXTENSION_PROPERTY else JvmSymbolKind.PROPERTY
		val packageName = pkgInternal.replace('/', '.')
		val owner = containingClass.ifEmpty { pkgInternal }
		val name = "$owner#${symbol.name}"
		val receiverType = symbol.receiverType

		return JvmSymbol(
			key = name,
			sourceId = sourceId,
			name = name,
			shortName = symbol.name,
			packageName = packageName,
			kind = kind,
			language = JvmSourceLanguage.KOTLIN,
			visibility = visibility,
			data = JvmFieldInfo(
				containingClassName = containingClass,
				typeName = symbol.type?.let { fqnToInternalName(it.name) } ?: "",
				typeDisplayName = symbol.type?.let { displayName(it) } ?: "",
				isFinal = !symbol.isVar,
				kotlin = KotlinPropertyInfo(
					receiverTypeName = receiverType?.let { fqnToInternalName(it.name) } ?: "",
					receiverTypeDisplayName = receiverType?.let { displayName(it) } ?: "",
					isConst = symbol.isConst,
					isLateinit = symbol.isLateInit,
					hasGetter = symbol.hasCustomGetter,
					hasSetter = symbol.hasCustomSetter,
					isDelegated = symbol.isDelegated,
					isExpect = symbol.modifiers.isExpect,
					isActual = symbol.modifiers.isActual,
					isExternal = symbol.modifiers.isExternal,
					isTypeNullable = symbol.type?.isNullable ?: false,
				),
			),
		)
	}

	private fun typeAliasSymbol(
		symbol: TypeAliasSymbol,
		pkgInternal: String,
		containingClass: String,
		sourceId: String,
	): JvmSymbol? {
		val visibility = mapVisibility(symbol.visibility)
		if (visibility == JvmVisibility.PRIVATE) return null

		val packageName = pkgInternal.replace('/', '.')
		val internalName = buildClassInternalName(symbol.name, pkgInternal, containingClass)

		return JvmSymbol(
			key = internalName,
			sourceId = sourceId,
			name = internalName,
			shortName = symbol.name,
			packageName = packageName,
			kind = JvmSymbolKind.TYPE_ALIAS,
			language = JvmSourceLanguage.KOTLIN,
			visibility = visibility,
			data = JvmTypeAliasInfo(
				containingClassName = containingClass,
				expandedTypeName = symbol.underlyingType?.let { fqnToInternalName(it.name) } ?: "",
				expandedTypeDisplayName = symbol.underlyingType?.let { displayName(it) } ?: "",
				typeParameters = symbol.typeParameters.map { it.name },
			),
		)
	}

	/**
	 * Builds the JVM internal name for a class.
	 *
	 * - Top-level: `com/example/MyClass`
	 * - Nested:    `com/example/MyClass$Inner`
	 */
	private fun buildClassInternalName(
		simpleName: String,
		pkgInternal: String,
		containingClass: String,
	): String = when {
		containingClass.isNotEmpty() -> $$"$$containingClass$$$simpleName"
		pkgInternal.isNotEmpty() -> "$pkgInternal/$simpleName"
		else -> simpleName
	}

	/**
	 * Converts a dot-separated FQN (as written in Kotlin source) to a JVM internal name.
	 *
	 * Applies standard Java naming conventions: lowercase-starting segments are
	 * package components (joined with `/`), uppercase-starting segments are class
	 * components (joined with `$`).
	 *
	 * Examples:
	 * ```
	 * "String"              → "String"
	 * "kotlin.String"       → "kotlin/String"
	 * "java.util.Map"       → "java/util/Map"
	 * "java.util.Map.Entry" → "java/util/Map$Entry"
	 * ```
	 */
	internal fun fqnToInternalName(fqn: String): String {
		if (fqn.isEmpty() || !fqn.contains('.')) return fqn

		val parts = fqn.split('.')
		val pkg = mutableListOf<String>()
		val cls = mutableListOf<String>()

		for (part in parts) {
			if (cls.isEmpty() && part.isNotEmpty() && part[0].isLowerCase()) {
				pkg += part
			} else {
				cls += part
			}
		}

		return when {
			pkg.isEmpty() -> cls.joinToString("$")
			cls.isEmpty() -> pkg.joinToString("/")
			else -> "${pkg.joinToString("/")}/${cls.joinToString("$")}"
		}
	}

	/**
	 * Returns a short display name for a [TypeReference]: the simple class name
	 * with type arguments rendered, but without the package prefix.
	 *
	 * E.g. `java.util.Map.Entry` → `Entry`, `List<String>` → `List<String>`.
	 */
	private fun displayName(ref: TypeReference): String {
		val baseName = fqnToInternalName(ref.name)
			.substringAfterLast('/')
			.substringAfterLast('$')
		val args = ref.typeArguments
		return buildString {
			append(baseName)
			if (args.isNotEmpty()) {
				append('<')
				append(args.joinToString(", ") { displayName(it) })
				append('>')
			}
			if (ref.isNullable) append('?')
		}
	}

	private fun mapClassKind(symbol: ClassSymbol): JvmSymbolKind = when (symbol.kind) {
		ClassKind.CLASS -> when {
			symbol.modifiers.isSealed -> JvmSymbolKind.SEALED_CLASS
			else -> JvmSymbolKind.CLASS
		}

		ClassKind.INTERFACE -> when {
			symbol.modifiers.isSealed -> JvmSymbolKind.SEALED_INTERFACE
			else -> JvmSymbolKind.INTERFACE
		}

		ClassKind.OBJECT -> JvmSymbolKind.OBJECT
		ClassKind.COMPANION_OBJECT -> JvmSymbolKind.COMPANION_OBJECT
		ClassKind.ENUM_CLASS -> JvmSymbolKind.ENUM
		ClassKind.ENUM_ENTRY -> JvmSymbolKind.ENUM_ENTRY
		ClassKind.ANNOTATION_CLASS -> JvmSymbolKind.ANNOTATION_CLASS
		ClassKind.DATA_CLASS -> JvmSymbolKind.DATA_CLASS
		ClassKind.VALUE_CLASS -> JvmSymbolKind.VALUE_CLASS
	}

	private fun mapVisibility(visibility: Visibility): JvmVisibility = when (visibility) {
		Visibility.PUBLIC -> JvmVisibility.PUBLIC
		Visibility.PROTECTED -> JvmVisibility.PROTECTED
		Visibility.INTERNAL -> JvmVisibility.INTERNAL
		Visibility.PRIVATE -> JvmVisibility.PRIVATE
	}
}
