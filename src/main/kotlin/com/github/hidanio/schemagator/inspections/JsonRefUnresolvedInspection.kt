package com.github.hidanio.schemagator.inspections

import com.github.hidanio.schemagator.JsonPointerUtil
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElementVisitor

class JsonRefUnresolvedInspection : LocalInspectionTool() {

    override fun getDisplayName(): String = "Schemagator JSON \$ref resolver"
    override fun getShortName(): String = "SchemagatorJsonRefResolver"
    override fun isEnabledByDefault(): Boolean = true

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : com.intellij.json.psi.JsonElementVisitor() {
            override fun visitStringLiteral(lit: JsonStringLiteral) {
                val prop = lit.parent as? JsonProperty ?: return
                if (prop.nameElement == lit) return
                if (prop.name != "\$ref" && prop.name != "\$schema") return

                val valueRange = JsonPointerUtil.valueRangeInsideQuotes(lit)
                val raw = lit.text.substring(valueRange.startOffset, valueRange.endOffset)
                val parts = JsonPointerUtil.splitRef(raw) ?: return

                val vFile = JsonPointerUtil.resolveFile(lit, parts.path)
                if (vFile == null) {
                    // broken file path highlight
                    if (parts.path.isNotBlank()) {
                        val off = valueRange.startOffset + raw.indexOf(parts.path)
                        val range = TextRange(off, off + parts.path.length)
                        holder.registerProblem(
                            lit,
                            "File not found: ${parts.path}",
                            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                            range
                        )
                    } else {
                        holder.registerProblem(lit, "Empty path", ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
                    }
                    return
                }

                val psiFile = com.intellij.psi.PsiManager.getInstance(lit.project).findFile(vFile) ?: run {
                    holder.registerProblem(lit, "Cannot open file: '${vFile.path}'")
                    return
                }

                val pointer = parts.pointer
                if (pointer.isNullOrBlank()) return // if only file

                val segments = JsonPointerUtil.splitSegments(pointer)
                val res = JsonPointerUtil.resolveSegments(psiFile, segments)
                if (!res.ok) {
                    // highlight broken segment
                    val breakIndex = res.failIndex ?: (segments.size - 1)
                    // restore range for segment inside lit
                    val hashPos = raw.indexOf('#')
                    var cur = hashPos + 1
                    segments.forEachIndexed { i, seg ->
                        val segText = if (i == 0) seg else "/$seg"
                        val start = cur
                        val end = cur + segText.length
                        if (i == breakIndex) {
                            val range = TextRange(valueRange.startOffset + start, valueRange.startOffset + end)
                            val msg = "Unresolved JSON Pointer segment '${JsonPointerUtil.decodeSegment(seg)}'" +
                                    (res.failReason?.let { ": $it" } ?: "")
                            holder.registerProblem(
                                lit,
                                msg,
                                ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                                range
                            )
                            return
                        }
                        cur = end
                    }
                }
            }
        }
}