package cz.petrgala.aicomments.storage

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

object ProjectPaths {
    const val DIRECTORY = ".claude"
    const val FILE_NAME = "comments.json"

    fun commentsFile(project: Project): Path =
        Path.of(requireNotNull(project.basePath) { "project has no base path" }, DIRECTORY, FILE_NAME)

    fun projectDir(project: Project): VirtualFile? =
        project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) } ?: project.guessProjectDir()

    fun relativePath(project: Project, file: VirtualFile): String? =
        roots(project).firstNotNullOfOrNull { VfsUtilCore.getRelativePath(file, it, '/') }

    fun findFile(project: Project, relativePath: String): VirtualFile? =
        roots(project).firstNotNullOfOrNull { it.findFileByRelativePath(relativePath) }

    // guessProjectDir is a fallback because the light test fixture puts files in temp:///src, the module content root but not the base path.
    private fun roots(project: Project): List<VirtualFile> =
        listOfNotNull(projectDir(project), project.guessProjectDir()).distinct()
}
