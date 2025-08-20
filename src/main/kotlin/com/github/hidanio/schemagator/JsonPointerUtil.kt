package com.github.hidanio.schemagator

import com.intellij.json.psi.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import java.nio.charset.StandardCharsets
import kotlin.math.max

object JsonPointerUtil {

    // ---- Data holders ----
    data class RefParts(val path: String, val pointer: String?)
    data class ResolveResult(
        val ok: Boolean,
        val target: PsiElement? = null,
        val failIndex: Int? = null,            // index of segment where resolving failed
        val failReason: String? = null
    )

    // ---- $ref helpers ----

    /** Split raw "$ref" into (path, pointerWithoutHash) */
    fun splitRef(raw: String): RefParts {
        val idx = raw.indexOf('#')
        return if (idx >= 0) RefParts(raw.substring(0, idx), raw.substring(idx + 1))
        else RefParts(raw, null)
    }

    /** Allow first "" element for "#/…". */
    fun splitSegments(pointer: String): List<String> = pointer.split('/')

    /** JSON Pointer decoding: ~1 => /, ~0 => ~ */
    fun decodeSegment(seg: String): String = seg.replace("~1", "/").replace("~0", "~")

    /** Resolve a VFS file by relative path (from `base` file), fallback to absolute LocalFileSystem */
    fun resolveFile(base: PsiElement, relPath: String?): VirtualFile? {
        if (relPath.isNullOrBlank()) return base.containingFile.virtualFile
        base.containingFile.virtualFile.parent?.findFileByRelativePath(relPath)?.let { return it }
        return LocalFileSystem.getInstance().findFileByPath(relPath)
    }

    /** Resolve JSON Pointer segments inside a JSON file */
    fun resolveSegments(psiFile: PsiFile, segments: List<String>): ResolveResult {
        val root = psiFile.children.firstOrNull { it is JsonObject } as? JsonObject
            ?: return ResolveResult(false, null, 0, "root object not found")

        var cur: PsiElement = root
        segments.forEachIndexed { i, rawSeg ->
            if (i == 0 && rawSeg.isEmpty()) return@forEachIndexed // "#/…": first empty — skip

            val seg = decodeSegment(rawSeg)
            val obj = when (cur) {
                is JsonObject -> cur
                is JsonProperty -> cur.value as? JsonObject
                else -> return ResolveResult(false, cur, i, "not an object at segment")
            } ?: return ResolveResult(false, cur, i, "null object at segment")

            val prop = obj.propertyList.firstOrNull { it.name == seg }
                ?: return ResolveResult(false, obj, i, "property '$seg' not found")

            cur = prop.value ?: prop
        }

        // Prefer property name element if target is a property
        val target = when (cur) {
            is JsonProperty -> cur.nameElement ?: cur
            else -> cur
        }

        // ===== Self-reference guard (protect from StackOverflow) =====
        val lit = psiFile.viewProvider.findElementAt(target.textRange.startOffset)?.parent as? JsonStringLiteral
        if (lit != null && target.textRange.intersects(lit.textRange)) {
            return ResolveResult(false, target, segments.lastIndex, "self reference")
        }

        return ResolveResult(true, target, null, null)
    }

    // ---- small helpers used by provider/inspection ----

    /** Range INSIDE quotes of a JsonStringLiteral. */
    fun valueRangeInsideQuotes(lit: JsonStringLiteral): TextRange =
        ElementManipulators.getValueTextRange(lit)

    private val SCHEMA_DECL_REGEX = Regex("\"\\\$schema\"\\s*:\\s*\"([^\"]+)\"")

    /** Our file mapping predicate: "file declares ../schema.json near itself" */
    fun fileDeclaresProjectSchema(file: VirtualFile, project: Project): Boolean {
        if (!file.extension.equals("json", true)) return false
        val buf = ByteArray(4096)
        val len = try {
            file.inputStream.use { it.read(buf) }
        } catch (_: Throwable) {
            -1
        }
        if (len <= 0) return false
        val text = String(buf, 0, max(len, 0), StandardCharsets.UTF_8)
        val m = SCHEMA_DECL_REGEX.find(text) ?: return false
        val schemaPath = m.groupValues[1]
        val resolved = file.parent?.findFileByRelativePath(schemaPath) ?: return false
        return resolved.name.equals("schema.json", ignoreCase = true)
    }

    /** PSI loader for VirtualFile. */
    fun psiFile(project: Project, vFile: VirtualFile?): PsiFile? =
        vFile?.let { PsiManager.getInstance(project).findFile(it) }
}
