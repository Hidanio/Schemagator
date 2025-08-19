package com.github.hidanio.schemagator.providers

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType
import java.nio.charset.StandardCharsets
import kotlin.math.max

class ProjectSchemaProviderFactory : JsonSchemaProviderFactory {
    override fun getProviders(project: Project): List<JsonSchemaFileProvider> {
        val schemas = FilenameIndex.getVirtualFilesByName(
            project, "schema.json", GlobalSearchScope.projectScope(project)
        )
        return schemas.map { ProjectSchemaProvider(it) }
    }
}

private class ProjectSchemaProvider(
    private val schemaFile: VirtualFile
) : JsonSchemaFileProvider {

    private val schemaDeclRegex = Regex("\"\\\$schema\"\\s*:\\s*\"([^\"]+)\"")

    override fun getName(): String =
        "Project JSON Schema («${schemaFile.parent?.name ?: "root"}»)"

    override fun getSchemaFile(): VirtualFile = schemaFile

    override fun getSchemaType(): SchemaType = SchemaType.userSchema

    override fun isAvailable(file: VirtualFile): Boolean {
        // 1) schema should be always available
        if (file == schemaFile) return true

        // 2) only .json
        if (!"json".equals(file.extension, ignoreCase = true)) return false

        // 3) first  ~4КB and find "$schema":"…"
        val prefixBytes = ByteArray(4096)
        val len = try {
            file.inputStream.use { it.read(prefixBytes) }
        } catch (_: Throwable) {
            -1
        }
        if (len <= 0) return false
        val text = String(prefixBytes, 0, max(len, 0), StandardCharsets.UTF_8)

        val match = schemaDeclRegex.find(text) ?: return false
        val declaredPath = match.groupValues[1] // right from "$schema": "..."

        // 4) resolve relative path from file location
        val resolved = file.parent?.findFileByRelativePath(declaredPath)
        return resolved == schemaFile
    }
}
