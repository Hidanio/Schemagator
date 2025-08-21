package com.github.hidanio.schemagator.providers

import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReferenceSet
import com.intellij.util.ProcessingContext
import com.github.hidanio.schemagator.JsonPointerUtil

class JsonRefContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        val valueOfRefProp = PlatformPatterns.psiElement(JsonStringLiteral::class.java)
            .withParent(PlatformPatterns.psiElement(JsonProperty::class.java).withName("\$ref"))

        registrar.registerReferenceProvider(valueOfRefProp, JsonRefProvider())
    }
}

private class JsonRefProvider : PsiReferenceProvider() {
    override fun acceptsTarget(target: PsiElement): Boolean = true

    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val lit = element as JsonStringLiteral
        val prop = lit.parent as? JsonProperty ?: return PsiReference.EMPTY_ARRAY
        // if this key, and not the value - return
        if (prop.nameElement == lit) return PsiReference.EMPTY_ARRAY

        val valueRange = JsonPointerUtil.valueRangeInsideQuotes(lit) // внутри кавычек
        val raw = lit.text.substring(valueRange.startOffset, valueRange.endOffset)

        val parts = JsonPointerUtil.splitRef(raw)
        val result = mutableListOf<PsiReference>()

        // ---- File part: ../foo/bar.json (soft) ----
        if (parts.path.isNotBlank()) {
            val pathOffsetInElement = valueRange.startOffset + raw.indexOf(parts.path)
            val fileRefs = object : FileReferenceSet(parts.path, lit, pathOffsetInElement, null, true) {
                override fun isSoft() = true
            }.allReferences
            result += fileRefs
        }

        // ---- JSON POINTER: #/defs/a/b --> segment refs ----
        parts.pointer?.let { pointer ->
            val pointerStartInValue = raw.indexOf('#') + 1 // pos after '#'
            var curOffsetInValue = pointerStartInValue

            val segments = JsonPointerUtil.splitSegments(pointer)
            segments.forEachIndexed { i, seg ->
                // first elem can be "" (if string start with '/')
                val segText = if (i == 0) seg else "/$seg"
                val startInValue = curOffsetInValue
                val endInValue = curOffsetInValue + segText.length

                val rangeInElement = TextRange(
                    valueRange.startOffset + startInValue,
                    valueRange.startOffset + endInValue
                )

                result += JsonPointerSegmentReference(
                    lit = lit,
                    rangeInElement = rangeInElement,
                    filePath = parts.path,
                    segments = segments,
                    index = i
                )

                curOffsetInValue = endInValue
            }
        }

        return result.toTypedArray()
    }
}

/**
 * A reference to a specific JSON Pointer segment (e.g. "/definitions" or "/foo").
 * Does a lazy resolve on access, with protection against self-references (StackOverflow).
 */
private class JsonPointerSegmentReference(
    private val lit: JsonStringLiteral,
    rangeInElement: TextRange,
    private val filePath: String,
    private val segments: List<String>,
    private val index: Int
) : PsiReferenceBase<JsonStringLiteral>(lit, rangeInElement, /* soft */ true) {

    override fun resolve(): PsiElement? {
        val vFile = JsonPointerUtil.resolveFile(lit, filePath) ?: return null
        val psiFile = PsiManager.getInstance(lit.project).findFile(vFile) ?: return null
        val sub = segments.subList(0, index + 1)
        val rr = JsonPointerUtil.resolveSegments(psiFile, sub)
        return rr.target.takeIf { rr.ok }
    }

    override fun getVariants(): Array<Any> = emptyArray()
}
