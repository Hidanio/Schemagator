package com.github.hidanio.schemagator.providers

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.*
import com.intellij.util.ProcessingContext
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.util.TextRange


class JsonRefContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(r: PsiReferenceRegistrar) {

        val valueOfRefProp = PlatformPatterns.psiElement(JsonStringLiteral::class.java)
            .withParent(
                PlatformPatterns.psiElement(JsonProperty::class.java).withName("\$ref")
            )

        r.registerReferenceProvider(valueOfRefProp, JsonRefProvider())
    }
}


class JsonRefProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(
        element: PsiElement, ctx: ProcessingContext
    ): Array<PsiReference> {

        val lit = element as JsonStringLiteral
        val prop = lit.parent as JsonProperty

        // if this key (not value) — skip
        if (prop.nameElement == lit) return PsiReference.EMPTY_ARRAY

        val (path, pointer) = lit.value.split('#', limit = 2).let {
            it[0] to it.getOrNull(1)
        }
        return arrayOf(JsonFileReference(lit, path, pointer))
    }
}

private class JsonFileReference(
    element: JsonStringLiteral,
    private val relPath: String,
    private val jsonPointer: String?
) : PsiReferenceBase<JsonStringLiteral>(
    element,
    TextRange(1, element.textLength - 1),
    false
) {

    override fun resolve(): PsiElement? {
        val src = myElement.containingFile.virtualFile
        val vFile = src.parent?.findFileByRelativePath(relPath)
            ?: LocalFileSystem.getInstance().findFileByPath(relPath)
            ?: return null

        val psiFile = PsiManager.getInstance(myElement.project).findFile(vFile) ?: return null
        println("Resolving: $relPath # $jsonPointer -> $psiFile")
        if (jsonPointer.isNullOrBlank()) return psiFile

        return resolvePointer(psiFile, jsonPointer)
    }

    /** Navigation by  `/definitions/foo/bar` */
    private fun resolvePointer(file: PsiFile, pointer: String): PsiElement? {
        var cur: PsiElement = file.children
            .firstOrNull { it is com.intellij.json.psi.JsonObject } ?: return null

        for (part in pointer.trimStart('/').split('/')) {
            val prop = cur.children
                .filterIsInstance<JsonProperty>()
                .firstOrNull { it.name == part } ?: return null
            cur = prop.value ?: return null
        }
        return cur
    }
}