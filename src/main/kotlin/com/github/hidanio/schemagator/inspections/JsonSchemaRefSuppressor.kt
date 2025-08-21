package com.github.hidanio.schemagator.inspections

import com.intellij.codeInspection.InspectionSuppressor
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

class JsonSchemaRefSuppressor : InspectionSuppressor {
    private val log = Logger.getInstance(JsonSchemaRefSuppressor::class.java)

    // in diffs builds I found diffs id's
    private val TARGET_IDS = setOf("JsonSchemaRefReference", "JsonSchemaRefReferenceInspection")

    override fun isSuppressedFor(element: PsiElement, toolId: String): Boolean {
        if (toolId !in TARGET_IDS) return false

        // always up to string lit
        val lit = when (element) {
            is JsonStringLiteral -> element
            else -> PsiTreeUtil.getParentOfType(element, JsonStringLiteral::class.java, false)
        } ?: return false

        val prop = lit.parent as? JsonProperty ?: return false
        // suppress only value for property "$ref"/"$schema"
        if (prop.nameElement == lit) return false
        if (prop.name != "\$ref" && prop.name != "\$schema") return false

        // If any resolved - ok
        val ok = lit.references.any { runCatching { it.resolve() }.getOrNull() != null }

        log.debug(
            "[Schemagator] suppress?=$ok toolId=$toolId " +
                    "file=${lit.containingFile.virtualFile.path} text='${lit.value}' refs=${lit.references.size}"
        )

        return ok
    }

    override fun getSuppressActions(element: PsiElement?, toolId: String): Array<SuppressQuickFix> =
        SuppressQuickFix.EMPTY_ARRAY
}