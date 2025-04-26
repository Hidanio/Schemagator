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
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        val refString = PlatformPatterns.psiElement(JsonStringLiteral::class.java)
            .withParent(
                PlatformPatterns.psiElement(JsonProperty::class.java)
                    .withName("\$ref")
            )
        registrar.registerReferenceProvider(refString, JsonRefProvider())
    }
}


class JsonRefProvider : PsiReferenceProvider() {

    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext
    ): Array<PsiReference> {
        println("REF: " + (element as JsonStringLiteral).value)

        val literal = element as? JsonStringLiteral ?: return PsiReference.EMPTY_ARRAY
        val (pathPart, pointer) = literal.value.split("#", limit = 2).let {
            it[0] to it.getOrNull(1)
        }

        return arrayOf(JsonFileReference(literal, pathPart, pointer))
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
        if (jsonPointer.isNullOrBlank()) return psiFile

        return resolvePointer(psiFile, jsonPointer)
    }

    /** Navigation by  `/definitions/foo/bar` */
    private fun resolvePointer(file: PsiFile, pointer: String): PsiElement? {
        var cur: PsiElement = file
        for (part in pointer.trimStart('/').split('/')) {
            val prop = cur.children.filterIsInstance<JsonProperty>()
                .firstOrNull { it.name == part } ?: return null
            cur = prop.value ?: return null
        }
        return cur
    }
}