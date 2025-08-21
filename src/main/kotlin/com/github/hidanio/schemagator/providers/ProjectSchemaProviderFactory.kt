package com.github.hidanio.schemagator.providers

import com.github.hidanio.schemagator.JsonPointerUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType

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

    override fun getName(): String =
        "Project JSON Schema («${schemaFile.parent?.name ?: "root"}»)"

    override fun getSchemaFile(): VirtualFile = schemaFile

    override fun getSchemaType(): SchemaType = SchemaType.userSchema

    override fun isAvailable(file: VirtualFile): Boolean {
        return JsonPointerUtil.fileDeclaresProjectSchema(file)
    }
}
