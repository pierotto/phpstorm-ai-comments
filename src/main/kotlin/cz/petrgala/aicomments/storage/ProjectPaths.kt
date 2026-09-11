package cz.petrgala.aicomments.storage

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

object ProjectPaths {
    const val DIRECTORY = ".claude"
    const val FILE_NAME = "comments.json"

    fun commentsFile(project: Project): Path =
        Path.of(requireNotNull(project.basePath) { "project has no base path" }, DIRECTORY, FILE_NAME)

    fun projectDir(project: Project): VirtualFile? = project.guessProjectDir()

    fun relativePath(project: Project, file: VirtualFile): String? {
        val root = projectDir(project) ?: return null
        return VfsUtilCore.getRelativePath(file, root, '/')
    }

    fun findFile(project: Project, relativePath: String): VirtualFile? =
        projectDir(project)?.findFileByRelativePath(relativePath)
}
