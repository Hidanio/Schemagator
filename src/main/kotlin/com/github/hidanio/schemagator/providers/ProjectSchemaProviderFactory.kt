package com.github.hidanio.schemagator.providers

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType
import java.nio.charset.StandardCharsets

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
    private val SCHEMA_DECL_REGEX =
        Regex("\"\\\$schema\"\\s*:\\s*\"([^\"]+)\"")

    // --------- основные сведения о схеме ----------
    override fun getName() =
        "Project JSON Schema («${schemaFile.parent?.name ?: "root"}»)"

    override fun getSchemaFile(): VirtualFile = schemaFile

    override fun getSchemaType(): SchemaType = SchemaType.userSchema

    // --------- general: which files we should check ----------
    override fun isAvailable(file: VirtualFile): Boolean {
        // 1) file should be schema
        if (file == schemaFile) return true

        // 2) only .json
        if (file.extension != "json") return false

        // 3) checking first bytes "$schema": "…"
        val bytes = ByteArray(4096)
        val len = file.inputStream.use { it.read(bytes) }
        val text = String(bytes, 0, maxOf(len, 0), StandardCharsets.UTF_8)

        // raw RegExp:   "$schema"  :  "relative/path/schema.json"
        val regex = SCHEMA_DECL_REGEX
        val match = regex.find(text) ?: return false
        val declaredPath = match.groupValues[1]

        // 4) resolve path and checking with schema
        val resolved = file.parent?.findFileByRelativePath(declaredPath)
        return resolved == schemaFile
    }
}