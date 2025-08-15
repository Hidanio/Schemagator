package com.github.hidanio.schemagator.providers

import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReferenceSet
import com.intellij.util.ProcessingContext

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

        val valueRangeInQuotes = ElementManipulators.getValueTextRange(lit) // внутри кавычек
        val raw = lit.text.substring(valueRangeInQuotes.startOffset, valueRangeInQuotes.endOffset)

        // Cut it: file path and JSON Pointer (if exist)
        val hashIdx = raw.indexOf('#')
        val pathPart = if (hashIdx >= 0) raw.substring(0, hashIdx) else raw
        val pointerPart = if (hashIdx >= 0) raw.substring(hashIdx + 1) else null // without '#'

        val result = mutableListOf<PsiReference>()

        // ---- File part: ../foo/bar.json (soft) ----
        if (pathPart.isNotBlank()) {
            val pathOffsetInElement = valueRangeInQuotes.startOffset + raw.indexOf(pathPart)
            val fileRefs = object : FileReferenceSet(pathPart, lit, pathOffsetInElement, null, true) {
                override fun isSoft() = true
            }.allReferences
            result += fileRefs
        }

        // ---- JSON POINTER: #/defs/a/b --> segment refs ----
        if (!pointerPart.isNullOrBlank()) {
            val pointerStartInValue = raw.indexOf('#') + 1 // pos after '#'
            var curOffsetInValue = pointerStartInValue

            // Each segment: '/definitions' '/foo' '/bar'
            val segments = pointerPart.split('/')

            segments.forEachIndexed { i, seg ->
                // first elem can be "" (if string start with '/')
                val segText = if (i == 0) seg else "/$seg"
                val startInValue = curOffsetInValue
                val endInValue = curOffsetInValue + segText.length

                val rangeInElement = TextRange(
                    valueRangeInQuotes.startOffset + startInValue,
                    valueRangeInQuotes.startOffset + endInValue
                )

                result += JsonPointerSegmentReference(
                    lit = lit,
                    rangeInElement = rangeInElement,
                    fullRaw = raw,
                    pathPart = pathPart,
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
    private val fullRaw: String,
    private val pathPart: String,
    private val segments: List<String>,
    private val index: Int
) : PsiReferenceBase<JsonStringLiteral>(lit, rangeInElement, /*soft*/ true) {

    override fun resolve(): PsiElement? {
        val project = lit.project

        // base VFS-file
        val startVf = if (pathPart.isBlank()) {
            lit.containingFile.virtualFile
        } else {
            lit.containingFile.virtualFile.parent?.findFileByRelativePath(pathPart)
        } ?: return null

        val psiFile = PsiManager.getInstance(project).findFile(startVf) ?: return null

        // root JsonObject
        val root = psiFile.children.firstOrNull { it is com.intellij.json.psi.JsonObject }
                as? com.intellij.json.psi.JsonObject ?: return null

        // Check segments 0...index (0 can be empty)
        var cur: PsiElement = root
        for (i in 0..index) {
            val rawSeg = segments[i]
            // first elem "" for "#/..." - skip
            if (i == 0 && rawSeg.isEmpty()) continue

            val seg = decodeJsonPointerSegment(rawSeg)
            val obj = when (cur) {
                is com.intellij.json.psi.JsonObject -> cur
                is com.intellij.json.psi.JsonProperty -> cur.value as? com.intellij.json.psi.JsonObject
                else -> return null
            } ?: return null

            val prop = obj.propertyList.firstOrNull { it.name == seg } ?: return null
            cur = prop.value ?: prop
        }

        // Target - property name, если это property, or value
        val target = when (cur) {
            is com.intellij.json.psi.JsonProperty -> cur.nameElement ?: cur
            else -> cur
        }

        // ===== StackOverflow check =====
        // If the target is inside the same string literal, we consider it a self-reference and do not resolve it
        if (target.containingFile == lit.containingFile && target.textRange.intersects(lit.textRange)) {
            return null
        }

        return target
    }

    override fun getVariants(): Array<Any> = emptyArray()

    private fun decodeJsonPointerSegment(seg: String): String {
        // JSON Pointer decoding: ~1 => /, ~0 => ~
        return seg.replace("~1", "/").replace("~0", "~")
    }
}
