# AI Comments Plugin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A PhpStorm plugin that stores per-line "AI comments" in `.claude/comments.json`, shows their status in the gutter, lets the developer resolve / follow up on Claude's responses, and a Claude Code skill that processes the file.

**Architecture:** A pure, unit-testable storage layer (`CommentsFileCodec` + `CommentsFileRepository`, reload→change→atomic write under a lock) wrapped by a project service `CommentStore` that publishes `CommentsChanged` on the message bus. Editor UI (`CommentMarkerManager` with `RangeHighlighter` + `GutterIconRenderer`), the tool window and the file watcher are stateless consumers of that event. Dialogs return outcomes; `CommentWorkflow` maps outcomes to store calls.

**Tech Stack:** Kotlin 2.2.21 (api/language 1.9, JVM target 17), Gradle 9.0.0, IntelliJ Platform Gradle Plugin 2.18.1, PhpStorm 2024.1.7 as the build target, Gson (bundled), JUnit 5 + JUnit Vintage for `BasePlatformTestCase` tests, JDK 21 (Temurin).

**Spec:** `docs/superpowers/specs/2026-09-11-ai-comments-plugin-design.md`

## Global Constraints

- `sinceBuild = 241`, no `untilBuild`; built against PhpStorm `2024.1.7`, verified against `2024.1.7` and `2026.2.2`.
- Plugin id `cz.petrgala.aicomments`, name "AI Comments", root package `cz.petrgala.aicomments`.
- Kotlin stdlib is **not** bundled (`kotlin.stdlib.default.dependency=false`); `apiVersion`/`languageVersion` = 1.9 because 2024.1 bundles stdlib 1.9.22.
- JSON format is exactly spec §3.1 / design §5. Timestamps: ISO 8601 UTC, seconds precision (`2026-09-11T10:00:00Z`). Ids: `c_<epochSeconds>_<index>`.
- Storage file: `<project.basePath>/.claude/comments.json`; paths in the file are project-relative with `/` separators.
- Every write is reload → change → atomic write (tmp + `ATOMIC_MOVE`) under a lock. Never write from the in-memory snapshot.
- All user-visible strings are English. Repository content is English. No comments in code unless they explain a non-obvious *why*.
- Status priority for a line's icon: `open > processed > resolved`.
- Defaults: debounce 500 ms, max comment length 500, warning at 80 % of the limit.
- Two clarifications relative to the design doc, applied throughout this plan:
  - `processed` is written as `processedAt != null` (not `status != OPEN`) so a comment the user resolves without Claude ever seeing it is not reported as processed.
  - No resource bundle: strings are inline (the bundle listed in the design layout is dropped as unnecessary for a single-language plugin).
- Commit after every task with the attribution lines from the session (`Co-Authored-By` + `Claude-Session`).

## File map

| File | Responsibility |
|---|---|
| `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/wrapper/*` | Build |
| `src/main/resources/META-INF/plugin.xml` | Plugin descriptor, all registrations |
| `src/main/resources/icons/*.svg` | Gutter + tool window icons |
| `model/CommentStatus.kt`, `model/Comment.kt`, `model/CommentsFile.kt` | Immutable data model |
| `model/CommentsFileOps.kt` | Pure transformations (add, set status, set lines, next id, counts) |
| `storage/CommentsFileCodec.kt` | JSON ↔ model, lenient per-record validation |
| `storage/CommentsFileRepository.kt` | File I/O, lock, atomic write, backup of broken file |
| `storage/CommentStore.kt` | Project service: snapshot, read-only state, message bus, notifications |
| `storage/ProjectPaths.kt` | Project-relative path helpers |
| `settings/AiCommentsSettings.kt`, `settings/AiCommentsConfigurable.kt` | Persistent settings + UI |
| `editor/AiCommentsIcons.kt`, `editor/CommentGutterIconRenderer.kt`, `editor/CommentMarkerManager.kt` | Gutter markers |
| `editor/LineSyncOnSave.kt` | Writes marker lines back on document save |
| `actions/AddCommentAction.kt`, `actions/ReloadCommentsAction.kt` | Actions |
| `ui/AddCommentDialog.kt`, `ui/ViewCommentDialog.kt`, `ui/CommentWorkflow.kt` | Dialogs and their outcomes |
| `watcher/CommentsFileWatcher.kt` | Debounced reload on external change |
| `toolwindow/AiCommentsToolWindowFactory.kt`, `toolwindow/AiCommentsPanel.kt` | Tool window |
| `startup/AiCommentsStartup.kt` | Instantiates services on project open |
| `claude/skills/process-ai-comments/SKILL.md` | Claude Code skill |
| `README.md` | User + developer documentation |

All Kotlin paths below are relative to `src/main/kotlin/cz/petrgala/aicomments/` and tests to `src/test/kotlin/cz/petrgala/aicomments/`.

---

### Task 1: Toolchain and Gradle scaffold

**Files:**
- Create: `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `.gitignore`
- Create: `src/main/resources/META-INF/plugin.xml`
- Create: `gradle/wrapper/gradle-wrapper.properties` (+ wrapper jar/scripts via `gradle wrapper`)

**Interfaces:**
- Produces: a building, verifiable empty plugin; `./gradlew test` runs JUnit 5 and JUnit 4 (vintage) tests.

- [ ] **Step 1: Install JDK 21**

Run: `brew install --cask temurin@21`
Then: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && java -version`
Expected: `openjdk version "21.0.x"`

Add to `~/.zshrc` if not present: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`.

- [ ] **Step 2: Write `settings.gradle.kts`**

```kotlin
rootProject.name = "ai-comments"
```

- [ ] **Step 3: Write `gradle.properties`**

```properties
kotlin.stdlib.default.dependency=false
kotlin.code.style=official
org.gradle.jvmargs=-Xmx2g
org.gradle.caching=true
```

- [ ] **Step 4: Write `build.gradle.kts`**

```kotlin
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.21"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "cz.petrgala"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        phpstorm("2024.1.7")
        testFramework(TestFrameworkType.Platform)
        pluginVerifier()
    }
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        apiVersion = KotlinVersion.KOTLIN_1_9
        languageVersion = KotlinVersion.KOTLIN_1_9
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "cz.petrgala.aicomments"
        name = "AI Comments"
        version = project.version.toString()
        description = "Per-line AI comments stored in .claude/comments.json and processed by Claude Code."
        ideaVersion {
            sinceBuild = "241"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            ide(IntelliJPlatformType.PhpStorm, "2024.1.7")
            ide(IntelliJPlatformType.PhpStorm, "2026.2.2")
        }
    }
}

tasks {
    test {
        useJUnitPlatform()
    }
}
```

- [ ] **Step 5: Write `.gitignore`**

```
.gradle/
build/
.idea/
.kotlin/
.intellijPlatform/
*.iml
```

- [ ] **Step 6: Write the minimal `plugin.xml`**

`src/main/resources/META-INF/plugin.xml`:

```xml
<idea-plugin>
    <id>cz.petrgala.aicomments</id>
    <name>AI Comments</name>
    <vendor>Petr Gala</vendor>
    <depends>com.intellij.modules.platform</depends>

    <extensions defaultExtensionNs="com.intellij">
    </extensions>
</idea-plugin>
```

- [ ] **Step 7: Generate the Gradle wrapper**

Gradle is not installed. Bootstrap it with a temporary wrapper:

```bash
cd "/Users/petrgala/Projects/PhpStorm/AI comments"
mkdir -p gradle/wrapper
cat > gradle/wrapper/gradle-wrapper.properties <<'PROPS'
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-9.0.0-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
PROPS
curl -sL -o gradle/wrapper/gradle-wrapper.jar https://raw.githubusercontent.com/gradle/gradle/v9.0.0/gradle/wrapper/gradle-wrapper.jar
curl -sL -o gradlew https://raw.githubusercontent.com/gradle/gradle/v9.0.0/gradlew
curl -sL -o gradlew.bat https://raw.githubusercontent.com/gradle/gradle/v9.0.0/gradlew.bat
chmod +x gradlew
./gradlew wrapper --gradle-version 9.0.0
```

Expected: `BUILD SUCCESSFUL`, `./gradlew --version` prints `Gradle 9.0.0`.

- [ ] **Step 8: Verify the plugin configuration and an empty build**

Run: `./gradlew verifyPluginConfiguration build`
Expected: `BUILD SUCCESSFUL`; first run downloads PhpStorm 2024.1.7 (several minutes). Any Kotlin warning about deprecated language version 1.9 is expected.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "Scaffold Gradle build for the AI Comments plugin"
```

---

### Task 2: Data model and JSON codec

**Files:**
- Create: `model/CommentStatus.kt`, `model/Comment.kt`, `model/CommentsFile.kt`, `model/CommentsFileOps.kt`
- Create: `storage/CommentsFileCodec.kt`
- Create: `src/test/resources/comments.sample.json`
- Test: `storage/CommentsFileCodecTest.kt`, `model/CommentsFileOpsTest.kt`

**Interfaces:**
- Produces:
  - `enum class CommentStatus { OPEN, PROCESSED, RESOLVED }` with `val json: String` and `companion fun fromJson(String): CommentStatus?`
  - `data class Comment(id, line, author, text, status, created, processedAt, claudeResponse)`; `val processed get() = processedAt != null`
  - `data class FileMetadata(projectName, created, lastModified)`
  - `data class CommentsFile(version, metadata, comments: Map<String, List<Comment>>)` + `companion fun empty(projectName, now): CommentsFile`
  - ops: `CommentsFile.withComment(path, comment)`, `.withStatus(id, status)`, `.withLines(path, idToLine)`, `.find(id): Located?`, `.nextId(epochSeconds)`, `.count(status)`, `.all(): List<Located>` where `data class Located(val path: String, val comment: Comment)`
  - `class CommentsFileCodec(onSkippedRecord: (String) -> Unit = {})` with `fun decode(json: String): CommentsFile` (throws `MalformedCommentsFileException`, `UnsupportedCommentsVersionException(version)`) and `fun encode(file: CommentsFile): String`

- [ ] **Step 1: Write the sample fixture**

`src/test/resources/comments.sample.json` — the exact JSON from spec §3.1 (copy it verbatim from `docs/requirements/CLAUDE_AI_COMMENTS_PLUGIN_SPEC.md`, lines 104–161).

- [ ] **Step 2: Write the failing codec tests**

`src/test/kotlin/cz/petrgala/aicomments/storage/CommentsFileCodecTest.kt`:

```kotlin
package cz.petrgala.aicomments.storage

import cz.petrgala.aicomments.model.CommentStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommentsFileCodecTest {

    private val sample = javaClass.getResource("/comments.sample.json")!!.readText()

    @Test
    fun `decodes the sample file`() {
        val file = CommentsFileCodec().decode(sample)

        assertEquals(1, file.version)
        assertEquals("my-project", file.metadata.projectName)
        assertEquals(setOf("src/Service/UserService.php", "config/services.neon"), file.comments.keys)
        val first = file.comments.getValue("src/Service/UserService.php")[0]
        assertEquals("c_1726061700_0", first.id)
        assertEquals(45, first.line)
        assertEquals(CommentStatus.OPEN, first.status)
        assertFalse(first.processed)
        assertNull(first.claudeResponse)
        val processed = file.comments.getValue("src/Service/UserService.php")[2]
        assertEquals(CommentStatus.PROCESSED, processed.status)
        assertTrue(processed.processed)
        assertEquals("2026-09-11T10:25:00Z", processed.processedAt)
    }

    @Test
    fun `round trip keeps every field`() {
        val codec = CommentsFileCodec()
        val decoded = codec.decode(sample)

        val again = codec.decode(codec.encode(decoded))

        assertEquals(decoded, again)
    }

    @Test
    fun `encoded output is pretty printed with lowercase status and derived processed flag`() {
        val encoded = CommentsFileCodec().encode(CommentsFileCodec().decode(sample))

        assertTrue(encoded.contains("\"status\": \"processed\""))
        assertTrue(encoded.contains("\"processed\": true"))
        assertTrue(encoded.contains("\"claudeResponse\": null"))
        assertTrue(encoded.startsWith("{\n  \"version\": 1,"))
    }

    @Test
    fun `skips an invalid record and reports it`() {
        val skipped = mutableListOf<String>()
        val json = """
            {"version":1,"metadata":{"projectName":"p","created":"2026-09-11T10:00:00Z","lastModified":"2026-09-11T10:00:00Z"},
             "comments":{"a.php":[
                {"id":"c_1_0","line":1,"author":"human","text":"ok","status":"open","created":"2026-09-11T10:00:00Z","processed":false,"processedAt":null,"claudeResponse":null},
                {"id":"c_1_1","line":2,"author":"human","text":"bad status","status":"weird","created":"2026-09-11T10:00:00Z"},
                {"id":"c_1_2","author":"human","text":"missing line","status":"open","created":"2026-09-11T10:00:00Z"}
             ]}}
        """.trimIndent()

        val file = CommentsFileCodec(onSkippedRecord = { skipped += it }).decode(json)

        assertEquals(listOf("c_1_0"), file.comments.getValue("a.php").map { it.id })
        assertEquals(2, skipped.size)
    }

    @Test
    fun `missing optional fields get defaults`() {
        val json = """
            {"version":1,"comments":{"a.php":[
                {"id":"c_1_0","line":1,"text":"ok","status":"open","created":"2026-09-11T10:00:00Z"}
            ]}}
        """.trimIndent()

        val file = CommentsFileCodec().decode(json)

        assertEquals("human", file.comments.getValue("a.php")[0].author)
        assertEquals("", file.metadata.projectName)
    }

    @Test
    fun `malformed json throws`() {
        assertThrows(MalformedCommentsFileException::class.java) { CommentsFileCodec().decode("{ not json") }
        assertThrows(MalformedCommentsFileException::class.java) { CommentsFileCodec().decode("[]") }
    }

    @Test
    fun `unsupported version throws`() {
        val e = assertThrows(UnsupportedCommentsVersionException::class.java) {
            CommentsFileCodec().decode("""{"version":2,"comments":{}}""")
        }
        assertEquals(2, e.version)
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew test --tests 'cz.petrgala.aicomments.storage.CommentsFileCodecTest'`
Expected: compilation FAILS (`Unresolved reference: CommentsFileCodec`).

- [ ] **Step 4: Write the model**

`model/CommentStatus.kt`:

```kotlin
package cz.petrgala.aicomments.model

enum class CommentStatus(val json: String) {
    OPEN("open"),
    PROCESSED("processed"),
    RESOLVED("resolved");

    companion object {
        fun fromJson(value: String): CommentStatus? = entries.firstOrNull { it.json == value }
    }
}
```

`model/Comment.kt`:

```kotlin
package cz.petrgala.aicomments.model

data class Comment(
    val id: String,
    val line: Int,
    val author: String,
    val text: String,
    val status: CommentStatus,
    val created: String,
    val processedAt: String?,
    val claudeResponse: String?,
) {
    val processed: Boolean get() = processedAt != null

    companion object {
        const val HUMAN = "human"
    }
}
```

`model/CommentsFile.kt`:

```kotlin
package cz.petrgala.aicomments.model

data class FileMetadata(
    val projectName: String,
    val created: String,
    val lastModified: String,
)

data class CommentsFile(
    val version: Int,
    val metadata: FileMetadata,
    val comments: Map<String, List<Comment>>,
) {
    companion object {
        const val CURRENT_VERSION = 1

        fun empty(projectName: String, now: String): CommentsFile =
            CommentsFile(CURRENT_VERSION, FileMetadata(projectName, now, now), emptyMap())
    }
}
```

`model/CommentsFileOps.kt`:

```kotlin
package cz.petrgala.aicomments.model

data class Located(val path: String, val comment: Comment)

fun CommentsFile.all(): List<Located> =
    comments.flatMap { (path, list) -> list.map { Located(path, it) } }

fun CommentsFile.find(id: String): Located? =
    all().firstOrNull { it.comment.id == id }

fun CommentsFile.count(status: CommentStatus): Int =
    all().count { it.comment.status == status }

fun CommentsFile.withComment(path: String, comment: Comment): CommentsFile =
    copy(comments = comments + (path to (comments[path].orEmpty() + comment)))

fun CommentsFile.withStatus(id: String, status: CommentStatus): CommentsFile =
    copy(comments = comments.mapValues { (_, list) ->
        list.map { if (it.id == id) it.copy(status = status) else it }
    })

fun CommentsFile.withLines(path: String, idToLine: Map<String, Int>): CommentsFile {
    val list = comments[path] ?: return this
    val updated = list.map { idToLine[it.id]?.let { line -> it.copy(line = line) } ?: it }
    return copy(comments = comments + (path to updated))
}

fun CommentsFile.nextId(epochSeconds: Long): String {
    val prefix = "c_${epochSeconds}_"
    val taken = all().count { it.comment.id.startsWith(prefix) }
    return "$prefix$taken"
}
```

- [ ] **Step 5: Write the codec**

`storage/CommentsFileCodec.kt`:

```kotlin
package cz.petrgala.aicomments.storage

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import cz.petrgala.aicomments.model.Comment
import cz.petrgala.aicomments.model.CommentStatus
import cz.petrgala.aicomments.model.CommentsFile
import cz.petrgala.aicomments.model.FileMetadata

class MalformedCommentsFileException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class UnsupportedCommentsVersionException(val version: Int) :
    RuntimeException("comments.json version $version is not supported")

class CommentsFileCodec(private val onSkippedRecord: (String) -> Unit = {}) {

    private val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()

    fun decode(json: String): CommentsFile {
        val root = try {
            JsonParser.parseString(json)
        } catch (e: JsonParseException) {
            throw MalformedCommentsFileException(e.message ?: "invalid JSON", e)
        }
        if (!root.isJsonObject) throw MalformedCommentsFileException("root must be an object")
        val obj = root.asJsonObject

        val version = obj.optInt("version") ?: throw MalformedCommentsFileException("missing version")
        if (version > CommentsFile.CURRENT_VERSION) throw UnsupportedCommentsVersionException(version)

        val metadata = obj.getAsJsonObjectOrNull("metadata").let {
            FileMetadata(
                projectName = it?.optString("projectName") ?: "",
                created = it?.optString("created") ?: "",
                lastModified = it?.optString("lastModified") ?: "",
            )
        }

        val commentsObj = obj.getAsJsonObjectOrNull("comments")
            ?: throw MalformedCommentsFileException("missing comments object")
        val comments = commentsObj.entrySet().associate { (path, value) ->
            val list = if (value.isJsonArray) value.asJsonArray else {
                onSkippedRecord("comments for '$path' are not an array")
                emptyList<JsonElement>()
            }
            path to list.mapNotNull { decodeComment(path, it) }
        }

        return CommentsFile(version, metadata, comments)
    }

    fun encode(file: CommentsFile): String {
        val root = JsonObject()
        root.addProperty("version", file.version)
        root.add("metadata", JsonObject().apply {
            addProperty("projectName", file.metadata.projectName)
            addProperty("created", file.metadata.created)
            addProperty("lastModified", file.metadata.lastModified)
        })
        root.add("comments", JsonObject().apply {
            file.comments.forEach { (path, list) ->
                add(path, gson.toJsonTree(list.map(::encodeComment)))
            }
        })
        return gson.toJson(root) + "\n"
    }

    private fun decodeComment(path: String, element: JsonElement): Comment? {
        if (!element.isJsonObject) return skip(path, "record is not an object")
        val o = element.asJsonObject
        val id = o.optString("id") ?: return skip(path, "missing id")
        val line = o.optInt("line") ?: return skip(path, "$id: missing line")
        if (line < 1) return skip(path, "$id: line must be >= 1")
        val text = o.optString("text") ?: return skip(path, "$id: missing text")
        val statusJson = o.optString("status") ?: return skip(path, "$id: missing status")
        val status = CommentStatus.fromJson(statusJson) ?: return skip(path, "$id: unknown status '$statusJson'")
        val created = o.optString("created") ?: return skip(path, "$id: missing created")
        return Comment(
            id = id,
            line = line,
            author = o.optString("author") ?: Comment.HUMAN,
            text = text,
            status = status,
            created = created,
            processedAt = o.optString("processedAt"),
            claudeResponse = o.optString("claudeResponse"),
        )
    }

    private fun encodeComment(c: Comment): JsonObject = JsonObject().apply {
        addProperty("id", c.id)
        addProperty("line", c.line)
        addProperty("author", c.author)
        addProperty("text", c.text)
        addProperty("status", c.status.json)
        addProperty("created", c.created)
        addProperty("processed", c.processed)
        add("processedAt", c.processedAt?.let { gson.toJsonTree(it) } ?: JsonNull.INSTANCE)
        add("claudeResponse", c.claudeResponse?.let { gson.toJsonTree(it) } ?: JsonNull.INSTANCE)
    }

    private fun skip(path: String, reason: String): Comment? {
        onSkippedRecord("$path: $reason")
        return null
    }

    private fun JsonObject.optString(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.optInt(name: String): Int? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

    private fun JsonObject.getAsJsonObjectOrNull(name: String): JsonObject? =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject
}
```

- [ ] **Step 6: Run the codec tests**

Run: `./gradlew test --tests 'cz.petrgala.aicomments.storage.CommentsFileCodecTest'`
Expected: all 7 PASS.

- [ ] **Step 7: Write the ops tests**

`src/test/kotlin/cz/petrgala/aicomments/model/CommentsFileOpsTest.kt`:

```kotlin
package cz.petrgala.aicomments.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CommentsFileOpsTest {

    private val now = "2026-09-11T10:00:00Z"
    private fun comment(id: String, line: Int, status: CommentStatus = CommentStatus.OPEN) =
        Comment(id, line, Comment.HUMAN, "text $id", status, now, null, null)

    @Test
    fun `withComment appends to the file's list`() {
        val file = CommentsFile.empty("p", now)
            .withComment("a.php", comment("c_1_0", 1))
            .withComment("a.php", comment("c_1_1", 2))
            .withComment("b.php", comment("c_1_2", 3))

        assertEquals(listOf("c_1_0", "c_1_1"), file.comments.getValue("a.php").map { it.id })
        assertEquals(listOf("c_1_2"), file.comments.getValue("b.php").map { it.id })
    }

    @Test
    fun `withStatus changes only the matching comment`() {
        val file = CommentsFile.empty("p", now)
            .withComment("a.php", comment("c_1_0", 1))
            .withComment("a.php", comment("c_1_1", 2))

        val updated = file.withStatus("c_1_1", CommentStatus.RESOLVED)

        assertEquals(CommentStatus.OPEN, updated.find("c_1_0")!!.comment.status)
        assertEquals(CommentStatus.RESOLVED, updated.find("c_1_1")!!.comment.status)
    }

    @Test
    fun `withLines updates only listed ids of the given file`() {
        val file = CommentsFile.empty("p", now)
            .withComment("a.php", comment("c_1_0", 1))
            .withComment("a.php", comment("c_1_1", 2))

        val updated = file.withLines("a.php", mapOf("c_1_1" to 7))

        assertEquals(1, updated.find("c_1_0")!!.comment.line)
        assertEquals(7, updated.find("c_1_1")!!.comment.line)
        assertEquals(file, file.withLines("missing.php", mapOf("c_1_0" to 9)))
    }

    @Test
    fun `nextId counts ids created in the same second`() {
        val file = CommentsFile.empty("p", now)
            .withComment("a.php", comment("c_100_0", 1))
            .withComment("b.php", comment("c_100_1", 1))

        assertEquals("c_100_2", file.nextId(100))
        assertEquals("c_101_0", file.nextId(101))
    }

    @Test
    fun `find and count`() {
        val file = CommentsFile.empty("p", now)
            .withComment("a.php", comment("c_1_0", 1))
            .withComment("a.php", comment("c_1_1", 2, CommentStatus.PROCESSED))

        assertEquals("a.php", file.find("c_1_1")!!.path)
        assertNull(file.find("nope"))
        assertEquals(1, file.count(CommentStatus.OPEN))
        assertEquals(1, file.count(CommentStatus.PROCESSED))
        assertEquals(0, file.count(CommentStatus.RESOLVED))
    }
}
```

- [ ] **Step 8: Run all tests**

Run: `./gradlew test`
Expected: PASS (12 tests).

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "Add comment data model and JSON codec"
```

---

### Task 3: File repository (I/O, lock, atomic write)

**Files:**
- Create: `storage/CommentsFileRepository.kt`
- Test: `storage/CommentsFileRepositoryTest.kt`

**Interfaces:**
- Consumes: `CommentsFileCodec`, model ops from Task 2.
- Produces:
  - `sealed interface LoadResult { Loaded(file), Missing, Malformed(reason), UnsupportedVersion(version) }`
  - `class CommentsFileRepository(path: Path, projectName: String, clock: Clock = Clock.systemUTC(), onSkippedRecord: (String) -> Unit = {})`
    - `fun load(): LoadResult`
    - `fun mutate(change: (CommentsFile) -> CommentsFile): CommentsFile` — throws `CommentsFileUnavailableException` when the file is malformed or unsupported
    - `fun backupBroken(): Path?` — renames the file to `comments.json.broken-<yyyyMMdd-HHmmss>`, returns the new path or null if there was no file
    - `fun now(): String`, `fun epochSeconds(): Long`

- [ ] **Step 1: Write the failing tests**

`src/test/kotlin/cz/petrgala/aicomments/storage/CommentsFileRepositoryTest.kt`:

```kotlin
package cz.petrgala.aicomments.storage

import cz.petrgala.aicomments.model.Comment
import cz.petrgala.aicomments.model.CommentStatus
import cz.petrgala.aicomments.model.find
import cz.petrgala.aicomments.model.withComment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class CommentsFileRepositoryTest {

    @TempDir
    lateinit var dir: Path

    private val clock = Clock.fixed(Instant.parse("2026-09-11T10:00:00Z"), ZoneOffset.UTC)
    private val path get() = dir.resolve(".claude").resolve("comments.json")
    private fun repo() = CommentsFileRepository(path, "my-project", clock)
    private fun comment(id: String, line: Int) =
        Comment(id, line, Comment.HUMAN, "t", CommentStatus.OPEN, "2026-09-11T10:00:00Z", null, null)

    @Test
    fun `load reports a missing file`() {
        assertEquals(LoadResult.Missing, repo().load())
    }

    @Test
    fun `mutate creates directory and file with metadata`() {
        val result = repo().mutate { it.withComment("a.php", comment("c_1_0", 1)) }

        assertTrue(Files.exists(path))
        assertEquals("my-project", result.metadata.projectName)
        assertEquals("2026-09-11T10:00:00Z", result.metadata.created)
        assertEquals("2026-09-11T10:00:00Z", result.metadata.lastModified)
        val loaded = repo().load() as LoadResult.Loaded
        assertEquals(result, loaded.file)
        assertFalse(Files.exists(dir.resolve(".claude").resolve("comments.json.tmp")))
    }

    @Test
    fun `mutate reloads from disk before applying the change`() {
        val repo = repo()
        repo.mutate { it.withComment("a.php", comment("c_1_0", 1)) }
        val external = Files.readString(path).replace("\"status\": \"open\"", "\"status\": \"processed\"")
        Files.writeString(path, external)

        val result = repo.mutate { it.withComment("a.php", comment("c_1_1", 2)) }

        assertEquals(CommentStatus.PROCESSED, result.find("c_1_0")!!.comment.status)
        assertNotNull(result.find("c_1_1"))
    }

    @Test
    fun `malformed file is reported and blocks writes`() {
        Files.createDirectories(path.parent)
        Files.writeString(path, "{ oops")

        val load = repo().load()
        assertTrue(load is LoadResult.Malformed)
        assertThrows(CommentsFileUnavailableException::class.java) { repo().mutate { it } }
        assertEquals("{ oops", Files.readString(path))
    }

    @Test
    fun `unsupported version is reported and blocks writes`() {
        Files.createDirectories(path.parent)
        Files.writeString(path, """{"version": 2, "comments": {}}""")

        assertEquals(LoadResult.UnsupportedVersion(2), repo().load())
        assertThrows(CommentsFileUnavailableException::class.java) { repo().mutate { it } }
    }

    @Test
    fun `backupBroken renames the file with a timestamp`() {
        Files.createDirectories(path.parent)
        Files.writeString(path, "{ oops")

        val backup = repo().backupBroken()

        assertEquals("comments.json.broken-20260911-100000", backup!!.fileName.toString())
        assertFalse(Files.exists(path))
        assertEquals("{ oops", Files.readString(backup))
        assertEquals(null, repo().backupBroken())
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'cz.petrgala.aicomments.storage.CommentsFileRepositoryTest'`
Expected: compilation FAILS (`Unresolved reference: CommentsFileRepository`).

- [ ] **Step 3: Write the repository**

`storage/CommentsFileRepository.kt`:

```kotlin
package cz.petrgala.aicomments.storage

import cz.petrgala.aicomments.model.CommentsFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

sealed interface LoadResult {
    data class Loaded(val file: CommentsFile) : LoadResult
    object Missing : LoadResult
    data class Malformed(val reason: String) : LoadResult
    data class UnsupportedVersion(val version: Int) : LoadResult
}

class CommentsFileUnavailableException(message: String) : RuntimeException(message)

class CommentsFileRepository(
    val path: Path,
    private val projectName: String,
    private val clock: Clock = Clock.systemUTC(),
    onSkippedRecord: (String) -> Unit = {},
) {
    private val lock = ReentrantLock()
    private val codec = CommentsFileCodec(onSkippedRecord)

    fun load(): LoadResult = lock.withLock { read() }

    fun mutate(change: (CommentsFile) -> CommentsFile): CommentsFile = lock.withLock {
        val current = when (val result = read()) {
            is LoadResult.Loaded -> result.file
            LoadResult.Missing -> CommentsFile.empty(projectName, now())
            is LoadResult.Malformed -> throw CommentsFileUnavailableException("comments.json is malformed: ${result.reason}")
            is LoadResult.UnsupportedVersion -> throw CommentsFileUnavailableException("comments.json version ${result.version} is not supported")
        }
        val updated = change(current).let { it.copy(metadata = it.metadata.copy(lastModified = now())) }
        write(updated)
        updated
    }

    fun backupBroken(): Path? = lock.withLock {
        if (!Files.exists(path)) return null
        val stamp = BACKUP_STAMP.format(clock.instant().atOffset(ZoneOffset.UTC))
        val target = path.resolveSibling("${path.fileName}.broken-$stamp")
        Files.move(path, target, StandardCopyOption.REPLACE_EXISTING)
        target
    }

    fun now(): String = clock.instant().truncatedTo(ChronoUnit.SECONDS).toString()

    fun epochSeconds(): Long = clock.instant().epochSecond

    private fun read(): LoadResult {
        if (!Files.exists(path)) return LoadResult.Missing
        return try {
            LoadResult.Loaded(codec.decode(Files.readString(path)))
        } catch (e: MalformedCommentsFileException) {
            LoadResult.Malformed(e.message ?: "invalid JSON")
        } catch (e: UnsupportedCommentsVersionException) {
            LoadResult.UnsupportedVersion(e.version)
        }
    }

    private fun write(file: CommentsFile) {
        Files.createDirectories(path.parent)
        val tmp = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(tmp, codec.encode(file))
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private companion object {
        val BACKUP_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests 'cz.petrgala.aicomments.storage.CommentsFileRepositoryTest'`
Expected: 6 PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Add file repository with locked reload-modify-write"
```

---

### Task 4: CommentStore project service, paths, notifications, startup

**Files:**
- Create: `storage/ProjectPaths.kt`, `storage/CommentStore.kt`, `storage/CommentsChangedListener.kt`, `startup/AiCommentsStartup.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Test: `storage/CommentStoreTest.kt` (platform test, JUnit 4 via vintage)

**Interfaces:**
- Consumes: `CommentsFileRepository`, `LoadResult`, model ops.
- Produces:
  - `object ProjectPaths { fun commentsFile(project): Path; fun projectDir(project): VirtualFile?; fun relativePath(project, file: VirtualFile): String?; fun findFile(project, relativePath): VirtualFile? }`
  - `interface CommentsChangedListener { fun commentsChanged(snapshot: CommentsFile) }` with `companion val TOPIC: Topic<CommentsChangedListener>`
  - `@Service(PROJECT) class CommentStore(project)`:
    - `fun snapshot(): CommentsFile`, `fun commentsFor(relativePath): List<Comment>`, `val isReadOnly: Boolean`
    - `fun add(relativePath, line, text): Comment?`
    - `fun resolve(id: String)`
    - `fun followUp(originalId, text): Comment?`
    - `fun updateLines(relativePath, idToLine: Map<String, Int>)`
    - `fun reload()`, `fun reset()`
    - all mutations return `null` / do nothing and show a notification when the store is read-only or the write fails
  - `class AiCommentsStartup : ProjectActivity`

- [ ] **Step 1: Write `ProjectPaths`**

`storage/ProjectPaths.kt`:

```kotlin
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
```

`guessProjectDir()` is used (not `basePath`) so that the light test fixture, whose files live in a temp VFS, resolves the same relative paths as production, where both are the project directory.

- [ ] **Step 2: Write the listener topic**

`storage/CommentsChangedListener.kt`:

```kotlin
package cz.petrgala.aicomments.storage

import com.intellij.util.messages.Topic
import cz.petrgala.aicomments.model.CommentsFile

interface CommentsChangedListener {
    fun commentsChanged(snapshot: CommentsFile)

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<CommentsChangedListener> =
            Topic.create("AI comments changed", CommentsChangedListener::class.java)
    }
}
```

- [ ] **Step 3: Write the failing platform test**

`src/test/kotlin/cz/petrgala/aicomments/storage/CommentStoreTest.kt`:

```kotlin
package cz.petrgala.aicomments.storage

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.petrgala.aicomments.model.CommentStatus
import cz.petrgala.aicomments.model.CommentsFile
import cz.petrgala.aicomments.model.find
import java.nio.file.Files

class CommentStoreTest : BasePlatformTestCase() {

    private lateinit var store: CommentStore

    override fun setUp() {
        super.setUp()
        Files.deleteIfExists(ProjectPaths.commentsFile(project))
        store = project.service<CommentStore>()
        store.reload()
    }

    override fun tearDown() {
        try {
            Files.deleteIfExists(ProjectPaths.commentsFile(project))
        } finally {
            super.tearDown()
        }
    }

    fun testAddCreatesFileAndPublishes() {
        val received = mutableListOf<CommentsFile>()
        project.messageBus.connect(testRootDisposable).subscribe(CommentsChangedListener.TOPIC, object : CommentsChangedListener {
            override fun commentsChanged(snapshot: CommentsFile) { received += snapshot }
        })

        val added = store.add("src/Foo.php", 3, "use FQN")!!

        assertTrue(Files.exists(ProjectPaths.commentsFile(project)))
        assertEquals(CommentStatus.OPEN, added.status)
        assertEquals(listOf(added), store.commentsFor("src/Foo.php"))
        assertEquals(1, received.size)
    }

    fun testResolveAndFollowUp() {
        val first = store.add("src/Foo.php", 3, "first")!!
        store.resolve(first.id)
        assertEquals(CommentStatus.RESOLVED, store.snapshot().find(first.id)!!.comment.status)

        val second = store.add("src/Foo.php", 3, "second")!!
        val followUp = store.followUp(second.id, "not good enough")!!

        assertEquals(CommentStatus.RESOLVED, store.snapshot().find(second.id)!!.comment.status)
        assertEquals(CommentStatus.OPEN, followUp.status)
        assertEquals(3, followUp.line)
        assertEquals(3, store.commentsFor("src/Foo.php").size)
    }

    fun testUpdateLines() {
        val c = store.add("src/Foo.php", 3, "text")!!

        store.updateLines("src/Foo.php", mapOf(c.id to 5))

        assertEquals(5, store.commentsFor("src/Foo.php")[0].line)
    }

    fun testMalformedFileMakesStoreReadOnlyAndResetRecovers() {
        val path = ProjectPaths.commentsFile(project)
        Files.createDirectories(path.parent)
        Files.writeString(path, "{ oops")

        store.reload()

        assertTrue(store.isReadOnly)
        assertNull(store.add("src/Foo.php", 1, "text"))

        store.reset()

        assertFalse(store.isReadOnly)
        assertNotNull(store.add("src/Foo.php", 1, "text"))
        val backups = Files.list(path.parent).use { s -> s.filter { it.fileName.toString().startsWith("comments.json.broken-") }.toList() }
        assertEquals(1, backups.size)
        backups.forEach { Files.delete(it) }
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `./gradlew test --tests 'cz.petrgala.aicomments.storage.CommentStoreTest'`
Expected: compilation FAILS (`Unresolved reference: CommentStore`).

- [ ] **Step 5: Write `CommentStore`**

`storage/CommentStore.kt`:

```kotlin
package cz.petrgala.aicomments.storage

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import cz.petrgala.aicomments.model.Comment
import cz.petrgala.aicomments.model.CommentStatus
import cz.petrgala.aicomments.model.CommentsFile
import cz.petrgala.aicomments.model.find
import cz.petrgala.aicomments.model.nextId
import cz.petrgala.aicomments.model.withComment
import cz.petrgala.aicomments.model.withLines
import cz.petrgala.aicomments.model.withStatus
import java.io.IOException

@Service(Service.Level.PROJECT)
class CommentStore(private val project: Project) {

    private val log = thisLogger()
    private val repository = CommentsFileRepository(
        path = ProjectPaths.commentsFile(project),
        projectName = project.name,
        onSkippedRecord = { log.warn("comments.json: skipped record ($it)") },
    )

    @Volatile
    private var snapshot: CommentsFile = CommentsFile.empty(project.name, repository.now())

    @Volatile
    var isReadOnly: Boolean = false
        private set

    fun snapshot(): CommentsFile = snapshot

    fun commentsFor(relativePath: String): List<Comment> = snapshot.comments[relativePath].orEmpty()

    fun add(relativePath: String, line: Int, text: String): Comment? = mutate { file ->
        val comment = newComment(file, line, text)
        file.withComment(relativePath, comment) to comment
    }

    fun resolve(id: String) {
        mutate { file -> file.withStatus(id, CommentStatus.RESOLVED) to Unit }
    }

    fun followUp(originalId: String, text: String): Comment? = mutate { file ->
        val original = file.find(originalId) ?: return@mutate file to null
        val comment = newComment(file, original.comment.line, text)
        file.withStatus(originalId, CommentStatus.RESOLVED).withComment(original.path, comment) to comment
    }

    fun updateLines(relativePath: String, idToLine: Map<String, Int>) {
        if (idToLine.isEmpty()) return
        mutate { file -> file.withLines(relativePath, idToLine) to Unit }
    }

    fun reload() {
        when (val result = repository.load()) {
            is LoadResult.Loaded -> update(result.file, readOnly = false)
            LoadResult.Missing -> update(CommentsFile.empty(project.name, repository.now()), readOnly = false)
            is LoadResult.Malformed -> {
                update(CommentsFile.empty(project.name, repository.now()), readOnly = true)
                notifyBroken("comments.json is malformed: ${result.reason}", offerReset = true)
            }
            is LoadResult.UnsupportedVersion -> {
                update(CommentsFile.empty(project.name, repository.now()), readOnly = true)
                notifyBroken("comments.json version ${result.version} is not supported by this plugin", offerReset = false)
            }
        }
    }

    fun reset() {
        try {
            val backup = repository.backupBroken()
            refreshVfs()
            reload()
            if (backup != null) notify("Previous file kept as ${backup.fileName}", NotificationType.INFORMATION)
        } catch (e: IOException) {
            log.warn("comments.json reset failed", e)
            notify("Could not reset comments.json: ${e.message}", NotificationType.ERROR)
        }
    }

    private fun newComment(file: CommentsFile, line: Int, text: String) = Comment(
        id = file.nextId(repository.epochSeconds()),
        line = line,
        author = Comment.HUMAN,
        text = text,
        status = CommentStatus.OPEN,
        created = repository.now(),
        processedAt = null,
        claudeResponse = null,
    )

    private fun <T> mutate(change: (CommentsFile) -> Pair<CommentsFile, T>): T? {
        if (isReadOnly) {
            notify("comments.json cannot be written until it is fixed or reset", NotificationType.WARNING)
            return null
        }
        var produced: T? = null
        return try {
            val written = repository.mutate { file ->
                val (updated, result) = change(file)
                produced = result
                updated
            }
            update(written, readOnly = false)
            refreshVfs()
            produced
        } catch (e: CommentsFileUnavailableException) {
            reload()
            null
        } catch (e: IOException) {
            log.warn("comments.json write failed", e)
            notify("Could not write comments.json: ${e.message}", NotificationType.ERROR)
            null
        }
    }

    private fun update(file: CommentsFile, readOnly: Boolean) {
        snapshot = file
        isReadOnly = readOnly
        val publish = Runnable {
            if (!project.isDisposed) project.messageBus.syncPublisher(CommentsChangedListener.TOPIC).commentsChanged(file)
        }
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) publish.run() else app.invokeLater(publish, project.disposed)
    }

    private fun refreshVfs() {
        LocalFileSystem.getInstance().refreshIoFiles(listOf(repository.path.parent.toFile()), true, true, null)
    }

    private fun notifyBroken(message: String, offerReset: Boolean) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification("AI Comments", message, NotificationType.ERROR)
        if (offerReset) {
            notification.addAction(NotificationAction.createSimpleExpiring("Reset file") { reset() })
        }
        notification.addAction(NotificationAction.createSimpleExpiring("Open file") {
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(repository.path)?.let {
                OpenFileDescriptor(project, it).navigate(true)
            }
        })
        notification.notify(project)
    }

    private fun notify(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification("AI Comments", message, type)
            .notify(project)
    }

    companion object {
        const val NOTIFICATION_GROUP = "AI Comments"
    }
}
```

- [ ] **Step 6: Write the startup activity**

`startup/AiCommentsStartup.kt`:

```kotlin
package cz.petrgala.aicomments.startup

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import cz.petrgala.aicomments.storage.CommentStore

class AiCommentsStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<CommentStore>().reload()
    }
}
```

- [ ] **Step 7: Register in `plugin.xml`**

Replace the `<extensions>` block:

```xml
    <extensions defaultExtensionNs="com.intellij">
        <notificationGroup id="AI Comments" displayType="BALLOON"/>
        <postStartupActivity implementation="cz.petrgala.aicomments.startup.AiCommentsStartup"/>
    </extensions>
```

- [ ] **Step 8: Run all tests**

Run: `./gradlew test`
Expected: PASS, including the 4 `CommentStoreTest` methods. If `CommentStoreTest` fails with a headless/notification error, the notification code path is the cause only in `testMalformed…`; `Notification.notify` works headless in the light fixture, so investigate rather than skip.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "Add CommentStore project service with change notifications"
```

---

### Task 5: Settings

**Files:**
- Create: `settings/AiCommentsSettings.kt`, `settings/AiCommentsSettingsListener.kt`, `settings/AiCommentsConfigurable.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Test: `settings/AiCommentsSettingsTest.kt`

**Interfaces:**
- Produces:
  - `@Service(PROJECT) class AiCommentsSettings : SimplePersistentStateComponent<AiCommentsSettings.State>` with `State` properties `showGutterIcons: Boolean`, `autoRefresh: Boolean`, `debounceMs: Int`, `maxCommentLength: Int`, `showToolWindow: Boolean`, `hideResolved: Boolean`; `companion fun getInstance(project)`
  - `interface AiCommentsSettingsListener { fun settingsChanged() }` + `TOPIC`
  - `fun AiCommentsSettings.publishChanged(project)` — helper used by the configurable and by the tool window toggle
  - `const val TOOL_WINDOW_ID = "AI Comments"` in `AiCommentsSettings.Companion`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/cz/petrgala/aicomments/settings/AiCommentsSettingsTest.kt`:

```kotlin
package cz.petrgala.aicomments.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AiCommentsSettingsTest : BasePlatformTestCase() {

    fun testDefaults() {
        val state = AiCommentsSettings.getInstance(project).state

        assertTrue(state.showGutterIcons)
        assertTrue(state.autoRefresh)
        assertEquals(500, state.debounceMs)
        assertEquals(500, state.maxCommentLength)
        assertTrue(state.showToolWindow)
        assertFalse(state.hideResolved)
    }

    fun testPublishChangedNotifiesListeners() {
        var calls = 0
        project.messageBus.connect(testRootDisposable).subscribe(AiCommentsSettingsListener.TOPIC, object : AiCommentsSettingsListener {
            override fun settingsChanged() { calls++ }
        })

        AiCommentsSettings.getInstance(project).publishChanged(project)

        assertEquals(1, calls)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'cz.petrgala.aicomments.settings.AiCommentsSettingsTest'`
Expected: compilation FAILS.

- [ ] **Step 3: Write the settings service and listener**

`settings/AiCommentsSettings.kt`:

```kotlin
package cz.petrgala.aicomments.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(name = "AiCommentsSettings", storages = [Storage("aiComments.xml")])
class AiCommentsSettings : SimplePersistentStateComponent<AiCommentsSettings.State>(State()) {

    class State : BaseState() {
        var showGutterIcons by property(true)
        var autoRefresh by property(true)
        var debounceMs by property(500)
        var maxCommentLength by property(500)
        var showToolWindow by property(true)
        var hideResolved by property(false)
    }

    fun publishChanged(project: Project) {
        project.messageBus.syncPublisher(AiCommentsSettingsListener.TOPIC).settingsChanged()
    }

    companion object {
        const val TOOL_WINDOW_ID = "AI Comments"

        fun getInstance(project: Project): AiCommentsSettings = project.service()
    }
}
```

`settings/AiCommentsSettingsListener.kt`:

```kotlin
package cz.petrgala.aicomments.settings

import com.intellij.util.messages.Topic

interface AiCommentsSettingsListener {
    fun settingsChanged()

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<AiCommentsSettingsListener> =
            Topic.create("AI comments settings changed", AiCommentsSettingsListener::class.java)
    }
}
```

- [ ] **Step 4: Write the configurable**

`settings/AiCommentsConfigurable.kt`:

```kotlin
package cz.petrgala.aicomments.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel

class AiCommentsConfigurable(private val project: Project) : BoundConfigurable("AI Comments") {

    private val state = AiCommentsSettings.getInstance(project).state

    override fun createPanel(): DialogPanel = panel {
        row { checkBox("Show gutter icons").bindSelected(state::showGutterIcons) }
        row { checkBox("Refresh automatically when .claude/comments.json changes").bindSelected(state::autoRefresh) }
        row("Refresh debounce (ms):") { intTextField(0..5000).bindIntText(state::debounceMs) }
        row("Maximum comment length:") { intTextField(1..5000).bindIntText(state::maxCommentLength) }
        row { checkBox("Show the AI Comments tool window").bindSelected(state::showToolWindow) }
        row { checkBox("Hide resolved comments in the tool window").bindSelected(state::hideResolved) }
    }

    override fun apply() {
        super.apply()
        ToolWindowManager.getInstance(project)
            .getToolWindow(AiCommentsSettings.TOOL_WINDOW_ID)
            ?.isAvailable = state.showToolWindow
        AiCommentsSettings.getInstance(project).publishChanged(project)
    }
}
```

- [ ] **Step 5: Register in `plugin.xml`**

Inside `<extensions defaultExtensionNs="com.intellij">` add:

```xml
        <projectConfigurable parentId="tools"
                             id="cz.petrgala.aicomments.settings"
                             displayName="AI Comments"
                             nonDefaultProject="true"
                             instance="cz.petrgala.aicomments.settings.AiCommentsConfigurable"/>
```

- [ ] **Step 6: Run the tests**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Add per-project settings and configurable"
```

---

### Task 6: Dialogs and the comment workflow

**Files:**
- Create: `ui/AddCommentDialog.kt`, `ui/ViewCommentDialog.kt`, `ui/CommentWorkflow.kt`

**Interfaces:**
- Consumes: `CommentStore`, `AiCommentsSettings`.
- Produces:
  - `class AddCommentDialog(project, line: Int, maxLength: Int, followUp: Boolean) : DialogWrapper` with `fun showAndGetText(): String?`
  - `class ViewCommentDialog(project, comment: Comment) : DialogWrapper` with `enum class Outcome { CLOSE, RESOLVE, FOLLOW_UP }` and `val outcome: Outcome` after `show()`
  - `object CommentWorkflow { fun addComment(project, relativePath, line); fun openComment(project, comment) }`

No automated tests: dialogs are modal Swing; they are covered by the smoke checklist in Task 11. The logic worth testing (store transitions) is already tested in Task 4.

- [ ] **Step 1: Write `AddCommentDialog`**

`ui/AddCommentDialog.kt`:

```kotlin
package cz.petrgala.aicomments.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

class AddCommentDialog(
    project: Project,
    private val line: Int,
    private val maxLength: Int,
    followUp: Boolean = false,
) : DialogWrapper(project) {

    private val textArea = JBTextArea(6, 60).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val counter = JBLabel("0 / $maxLength").apply { foreground = UIUtil.getContextHelpForeground() }

    val text: String get() = textArea.text.trim()

    init {
        title = if (followUp) "Follow-up AI Comment – Line $line" else "Add AI Comment – Line $line"
        setOKButtonText(if (followUp) "Add Follow-up" else "Add Comment")
        textArea.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                counter.text = "${text.length} / $maxLength"
            }
        })
        init()
    }

    fun showAndGetText(): String? = if (showAndGet()) text else null

    override fun createCenterPanel(): JComponent = panel {
        row { cell(JBScrollPane(textArea)).align(AlignX.FILL) }
        row { cell(counter).align(AlignX.RIGHT) }
        row {
            comment("Be concise. Claude will see this line, 5 lines before and after, and the file path.")
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = textArea

    override fun doValidate(): ValidationInfo? {
        val length = text.length
        return when {
            length == 0 -> ValidationInfo("Comment text is required", textArea)
            length > maxLength -> ValidationInfo("Comment is longer than $maxLength characters ($length)", textArea)
            length >= maxLength * WARN_RATIO -> ValidationInfo("Long comment ($length of $maxLength characters)", textArea).asWarning().withOKEnabled()
            else -> null
        }
    }

    private companion object {
        const val WARN_RATIO = 0.8
    }
}
```

- [ ] **Step 2: Write `ViewCommentDialog`**

`ui/ViewCommentDialog.kt`:

```kotlin
package cz.petrgala.aicomments.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import cz.petrgala.aicomments.model.Comment
import cz.petrgala.aicomments.model.CommentStatus
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.JComponent

class ViewCommentDialog(project: Project, private val comment: Comment) : DialogWrapper(project) {

    enum class Outcome { CLOSE, RESOLVE, FOLLOW_UP }

    var outcome: Outcome = Outcome.CLOSE
        private set

    init {
        title = "AI Comment – Line ${comment.line}"
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        group("Your comment") {
            row { cell(readOnlyArea(comment.text, rows = 4)).align(AlignX.FILL) }
        }
        val response = comment.claudeResponse
        if (response != null) {
            group("Claude's response") {
                row { cell(readOnlyArea(response, rows = 10)).align(AlignX.FILL) }
            }
        }
    }

    override fun createActions(): Array<Action> = when (comment.status) {
        CommentStatus.OPEN -> arrayOf(outcomeAction("Mark as Resolved", Outcome.RESOLVE), closeAction())
        CommentStatus.PROCESSED -> arrayOf(outcomeAction("OK", Outcome.RESOLVE), outcomeAction("Follow-up", Outcome.FOLLOW_UP), closeAction())
        CommentStatus.RESOLVED -> arrayOf(closeAction())
    }

    private fun outcomeAction(name: String, result: Outcome): Action = object : DialogWrapperAction(name) {
        override fun doAction(e: ActionEvent) {
            outcome = result
            close(OK_EXIT_CODE)
        }
    }

    private fun closeAction(): Action = cancelAction.also { it.putValue(Action.NAME, "Close") }

    private fun readOnlyArea(text: String, rows: Int): JComponent =
        JBScrollPane(JBTextArea(text, rows, 60).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
        })
}
```

- [ ] **Step 3: Write `CommentWorkflow`**

`ui/CommentWorkflow.kt`:

```kotlin
package cz.petrgala.aicomments.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import cz.petrgala.aicomments.model.Comment
import cz.petrgala.aicomments.model.CommentStatus
import cz.petrgala.aicomments.settings.AiCommentsSettings
import cz.petrgala.aicomments.storage.CommentStore

object CommentWorkflow {

    fun addComment(project: Project, relativePath: String, line: Int) {
        val maxLength = AiCommentsSettings.getInstance(project).state.maxCommentLength
        val text = AddCommentDialog(project, line, maxLength).showAndGetText() ?: return
        project.service<CommentStore>().add(relativePath, line, text)
    }

    fun openComment(project: Project, comment: Comment) {
        if (comment.status == CommentStatus.RESOLVED) return
        val dialog = ViewCommentDialog(project, comment)
        dialog.show()
        val store = project.service<CommentStore>()
        when (dialog.outcome) {
            ViewCommentDialog.Outcome.CLOSE -> Unit
            ViewCommentDialog.Outcome.RESOLVE -> store.resolve(comment.id)
            ViewCommentDialog.Outcome.FOLLOW_UP -> {
                val maxLength = AiCommentsSettings.getInstance(project).state.maxCommentLength
                val text = AddCommentDialog(project, comment.line, maxLength, followUp = true).showAndGetText() ?: return
                store.followUp(comment.id, text)
            }
        }
    }
}
```

- [ ] **Step 4: Compile**

Run: `./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`. If `AlignX` is unresolved, the import is `com.intellij.ui.dsl.builder.AlignX` (available since 2022.3; 2024.1 has it).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Add comment dialogs and workflow"
```

---

### Task 7: Gutter markers and the Add AI Comment action

**Files:**
- Create: `src/main/resources/icons/comment-open.svg`, `comment-open_dark.svg`, `comment-processed.svg`, `comment-processed_dark.svg`, `comment-resolved.svg`, `comment-resolved_dark.svg`, `toolwindow.svg`
- Create: `editor/AiCommentsIcons.kt`, `editor/CommentGutterIconRenderer.kt`, `editor/CommentMarkerManager.kt`, `actions/AddCommentAction.kt`
- Modify: `startup/AiCommentsStartup.kt`, `src/main/resources/META-INF/plugin.xml`
- Test: `editor/CommentMarkerManagerTest.kt`

**Interfaces:**
- Consumes: `CommentStore`, `CommentsChangedListener`, `AiCommentsSettings`, `AiCommentsSettingsListener`, `ProjectPaths`, `CommentWorkflow`.
- Produces:
  - `object AiCommentsIcons { val Open, Processed, Resolved, ToolWindow: Icon; fun forStatus(status): Icon }`
  - `class CommentGutterIconRenderer(project, comments: List<Comment>)`; `val primary: Comment` (highest-priority comment), `val status`
  - `@Service(PROJECT) class CommentMarkerManager(project) : Disposable` with `fun refreshAll()`, `fun refresh(editor)`, `fun markers(editor): List<RangeHighlighter>`; each marker carries `COMMENT_IDS_KEY: Key<List<String>>` user data
  - `fun CommentStatus.priority(): Int` — lower is more important (`OPEN=0, PROCESSED=1, RESOLVED=2`) — put it in `model/CommentStatus.kt`

- [ ] **Step 1: Add the icons**

12×12 SVGs. Dark variants use lighter fills.

`icons/comment-open.svg`:
```xml
<svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 12 12"><circle cx="6" cy="6" r="4.5" fill="#3574F0"/></svg>
```
`icons/comment-open_dark.svg`:
```xml
<svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 12 12"><circle cx="6" cy="6" r="4.5" fill="#548AF7"/></svg>
```
`icons/comment-processed.svg`:
```xml
<svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 12 12"><circle cx="6" cy="6" r="4.5" fill="#369650"/></svg>
```
`icons/comment-processed_dark.svg`:
```xml
<svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 12 12"><circle cx="6" cy="6" r="4.5" fill="#5FAD65"/></svg>
```
`icons/comment-resolved.svg`:
```xml
<svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 12 12"><path d="M2.5 6.5 5 9l4.5-6" fill="none" stroke="#818594" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>
```
`icons/comment-resolved_dark.svg`:
```xml
<svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 12 12"><path d="M2.5 6.5 5 9l4.5-6" fill="none" stroke="#A8ADBD" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>
```
`icons/toolwindow.svg` (13×13, tool window stripe):
```xml
<svg xmlns="http://www.w3.org/2000/svg" width="13" height="13" viewBox="0 0 13 13"><path d="M2 2h9a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H6l-3 2.5V9H2a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1z" fill="none" stroke="#6C707E" stroke-width="1.2"/><circle cx="6.5" cy="5.5" r="1.2" fill="#6C707E"/></svg>
```

`editor/AiCommentsIcons.kt`:

```kotlin
package cz.petrgala.aicomments.editor

import com.intellij.openapi.util.IconLoader
import cz.petrgala.aicomments.model.CommentStatus
import javax.swing.Icon

object AiCommentsIcons {
    @JvmField val Open: Icon = IconLoader.getIcon("/icons/comment-open.svg", AiCommentsIcons::class.java)
    @JvmField val Processed: Icon = IconLoader.getIcon("/icons/comment-processed.svg", AiCommentsIcons::class.java)
    @JvmField val Resolved: Icon = IconLoader.getIcon("/icons/comment-resolved.svg", AiCommentsIcons::class.java)
    @JvmField val ToolWindow: Icon = IconLoader.getIcon("/icons/toolwindow.svg", AiCommentsIcons::class.java)

    fun forStatus(status: CommentStatus): Icon = when (status) {
        CommentStatus.OPEN -> Open
        CommentStatus.PROCESSED -> Processed
        CommentStatus.RESOLVED -> Resolved
    }
}
```

- [ ] **Step 2: Add status priority to the model**

Append to `model/CommentStatus.kt` (outside the enum):

```kotlin
fun CommentStatus.priority(): Int = when (this) {
    CommentStatus.OPEN -> 0
    CommentStatus.PROCESSED -> 1
    CommentStatus.RESOLVED -> 2
}
```

- [ ] **Step 3: Write the failing marker test**

`src/test/kotlin/cz/petrgala/aicomments/editor/CommentMarkerManagerTest.kt`:

```kotlin
package cz.petrgala.aicomments.editor

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.petrgala.aicomments.storage.CommentStore
import cz.petrgala.aicomments.storage.ProjectPaths
import java.nio.file.Files

class CommentMarkerManagerTest : BasePlatformTestCase() {

    private lateinit var store: CommentStore
    private lateinit var markers: CommentMarkerManager

    override fun setUp() {
        super.setUp()
        Files.deleteIfExists(ProjectPaths.commentsFile(project))
        store = project.service<CommentStore>()
        store.reload()
        markers = project.service<CommentMarkerManager>()
    }

    override fun tearDown() {
        try {
            Files.deleteIfExists(ProjectPaths.commentsFile(project))
        } finally {
            super.tearDown()
        }
    }

    fun testOpenCommentGetsMarkerOnItsLine() {
        myFixture.configureByText("Foo.php", "<?php\nline 2\nline 3\n")

        store.add("Foo.php", 2, "use FQN")

        val list = markers.markers(myFixture.editor)
        assertEquals(1, list.size)
        assertEquals(1, myFixture.editor.document.getLineNumber(list[0].startOffset))
        assertSame(AiCommentsIcons.Open, list[0].gutterIconRenderer!!.icon)
    }

    fun testResolvedCommentShowsResolvedIcon() {
        myFixture.configureByText("Foo.php", "<?php\nline 2\n")
        val c = store.add("Foo.php", 1, "text")!!

        store.resolve(c.id)

        assertSame(AiCommentsIcons.Resolved, markers.markers(myFixture.editor).single().gutterIconRenderer!!.icon)
    }

    fun testTwoCommentsOnOneLineYieldOneMarkerWithHighestStatus() {
        myFixture.configureByText("Foo.php", "<?php\nline 2\n")
        val first = store.add("Foo.php", 2, "first")!!
        store.resolve(first.id)
        store.add("Foo.php", 2, "second")

        val list = markers.markers(myFixture.editor)
        assertEquals(1, list.size)
        assertSame(AiCommentsIcons.Open, list[0].gutterIconRenderer!!.icon)
        assertEquals(2, list[0].getUserData(CommentMarkerManager.COMMENT_IDS_KEY)!!.size)
    }

    fun testLineBeyondDocumentGetsNoMarker() {
        myFixture.configureByText("Foo.php", "<?php\n")

        store.add("Foo.php", 40, "text")

        assertTrue(markers.markers(myFixture.editor).isEmpty())
    }

    fun testDeletingTheFileRemovesMarkers() {
        myFixture.configureByText("Foo.php", "<?php\n")
        store.add("Foo.php", 1, "text")
        assertEquals(1, markers.markers(myFixture.editor).size)

        Files.delete(ProjectPaths.commentsFile(project))
        store.reload()

        assertTrue(markers.markers(myFixture.editor).isEmpty())
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `./gradlew test --tests 'cz.petrgala.aicomments.editor.CommentMarkerManagerTest'`
Expected: compilation FAILS.

- [ ] **Step 5: Write the renderer**

`editor/CommentGutterIconRenderer.kt`:

```kotlin
package cz.petrgala.aicomments.editor

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import cz.petrgala.aicomments.model.Comment
import cz.petrgala.aicomments.model.CommentStatus
import cz.petrgala.aicomments.model.priority
import cz.petrgala.aicomments.ui.CommentWorkflow
import javax.swing.Icon

class CommentGutterIconRenderer(
    private val project: Project,
    private val comments: List<Comment>,
) : GutterIconRenderer(), DumbAware {

    val primary: Comment = comments.sortedWith(compareBy({ it.status.priority() }, { it.created })).first()
    val status: CommentStatus = primary.status

    override fun getIcon(): Icon = AiCommentsIcons.forStatus(status)

    override fun getTooltipText(): String = when (status) {
        CommentStatus.OPEN -> "AI comment (open): ${primary.text.take(TOOLTIP_TEXT_LENGTH)}${if (primary.text.length > TOOLTIP_TEXT_LENGTH) "…" else ""}"
        CommentStatus.PROCESSED -> "AI comment (processed): response available"
        CommentStatus.RESOLVED -> "AI comment (resolved, ${(primary.processedAt ?: primary.created).take(10)})"
    }

    override fun getClickAction(): AnAction? = if (status == CommentStatus.RESOLVED) null else object : AnAction() {
        override fun actionPerformed(e: AnActionEvent) {
            CommentWorkflow.openComment(project, primary)
        }
    }

    override fun isNavigateAction(): Boolean = status != CommentStatus.RESOLVED

    override fun getAlignment(): Alignment = Alignment.LEFT

    override fun equals(other: Any?): Boolean =
        other is CommentGutterIconRenderer && other.comments == comments

    override fun hashCode(): Int = comments.hashCode()

    private companion object {
        const val TOOLTIP_TEXT_LENGTH = 80
    }
}
```

- [ ] **Step 6: Write the marker manager**

`editor/CommentMarkerManager.kt`:

```kotlin
package cz.petrgala.aicomments.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import cz.petrgala.aicomments.model.CommentsFile
import cz.petrgala.aicomments.settings.AiCommentsSettings
import cz.petrgala.aicomments.settings.AiCommentsSettingsListener
import cz.petrgala.aicomments.storage.CommentStore
import cz.petrgala.aicomments.storage.CommentsChangedListener
import cz.petrgala.aicomments.storage.ProjectPaths

@Service(Service.Level.PROJECT)
class CommentMarkerManager(private val project: Project) : Disposable {

    init {
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                if (event.editor.project == project) refresh(event.editor)
            }
        }, this)
        val connection = project.messageBus.connect(this)
        connection.subscribe(CommentsChangedListener.TOPIC, object : CommentsChangedListener {
            override fun commentsChanged(snapshot: CommentsFile) = refreshAll()
        })
        connection.subscribe(AiCommentsSettingsListener.TOPIC, object : AiCommentsSettingsListener {
            override fun settingsChanged() = refreshAll()
        })
        refreshAll()
    }

    fun refreshAll() {
        val app = ApplicationManager.getApplication()
        if (!app.isDispatchThread) {
            app.invokeLater({ refreshAll() }, project.disposed)
            return
        }
        EditorFactory.getInstance().allEditors
            .filter { it.project == project }
            .forEach { refresh(it) }
    }

    fun refresh(editor: Editor) {
        clear(editor)
        if (!AiCommentsSettings.getInstance(project).state.showGutterIcons) return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val relativePath = ProjectPaths.relativePath(project, file) ?: return
        val document = editor.document
        val markers = project.service<CommentStore>().commentsFor(relativePath)
            .groupBy { it.line }
            .filterKeys { it in 1..document.lineCount }
            .map { (line, comments) ->
                editor.markupModel.addLineHighlighter(null, line - 1, HighlighterLayer.LAST).apply {
                    gutterIconRenderer = CommentGutterIconRenderer(project, comments)
                    putUserData(COMMENT_IDS_KEY, comments.map { it.id })
                }
            }
        editor.putUserData(MARKERS_KEY, markers)
    }

    fun markers(editor: Editor): List<RangeHighlighter> = editor.getUserData(MARKERS_KEY).orEmpty()

    private fun clear(editor: Editor) {
        markers(editor).forEach { editor.markupModel.removeHighlighter(it) }
        editor.putUserData(MARKERS_KEY, null)
    }

    override fun dispose() {
        EditorFactory.getInstance().allEditors.filter { it.project == project }.forEach { clear(it) }
    }

    companion object {
        val COMMENT_IDS_KEY: Key<List<String>> = Key.create("cz.petrgala.aicomments.commentIds")
        private val MARKERS_KEY: Key<List<RangeHighlighter>> = Key.create("cz.petrgala.aicomments.markers")
    }
}
```

- [ ] **Step 7: Instantiate the manager at startup**

`startup/AiCommentsStartup.kt` becomes:

```kotlin
package cz.petrgala.aicomments.startup

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import cz.petrgala.aicomments.editor.CommentMarkerManager
import cz.petrgala.aicomments.storage.CommentStore

class AiCommentsStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<CommentStore>().reload()
        project.service<CommentMarkerManager>()
    }
}
```

- [ ] **Step 8: Run the marker tests**

Run: `./gradlew test --tests 'cz.petrgala.aicomments.editor.CommentMarkerManagerTest'`
Expected: 5 PASS. `store.add` publishes synchronously on the EDT (tests run on the EDT), so no event-queue flushing is needed.

- [ ] **Step 9: Write `AddCommentAction`**

`actions/AddCommentAction.kt`:

```kotlin
package cz.petrgala.aicomments.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.ex.EditorGutterComponentEx
import com.intellij.openapi.project.DumbAware
import cz.petrgala.aicomments.storage.ProjectPaths
import cz.petrgala.aicomments.ui.CommentWorkflow

class AddCommentAction : AnAction(), DumbAware {

    private data class Target(val relativePath: String, val line: Int)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = targetOf(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = targetOf(e) ?: return
        CommentWorkflow.addComment(project, target.relativePath, target.line)
    }

    private fun targetOf(e: AnActionEvent): Target? {
        val project = e.project ?: return null
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
        val relativePath = ProjectPaths.relativePath(project, file) ?: return null
        val gutterLine = e.getData(EditorGutterComponentEx.LOGICAL_LINE_AT_CURSOR)
        val line = (gutterLine ?: editor.caretModel.logicalPosition.line) + 1
        return Target(relativePath, line)
    }
}
```

- [ ] **Step 10: Register the action**

Add to `plugin.xml` after `</extensions>`:

```xml
    <actions>
        <action id="cz.petrgala.aicomments.AddComment"
                class="cz.petrgala.aicomments.actions.AddCommentAction"
                text="Add AI Comment"
                description="Add an AI comment to the current line">
            <add-to-group group-id="EditorGutterPopupMenu" anchor="last"/>
            <add-to-group group-id="EditorPopupMenu" anchor="last"/>
            <keyboard-shortcut keymap="$default" first-keystroke="ctrl alt shift C"/>
            <keyboard-shortcut keymap="Mac OS X 10.5+" first-keystroke="meta alt shift C"/>
        </action>
    </actions>
```

- [ ] **Step 11: Run the IDE and check manually**

Run: `./gradlew runIde`
In the sandbox IDE open any directory as a project, open a file, right-click a line number → *Add AI Comment* → type text → *Add Comment*. Expected: blue dot on the line, `.claude/comments.json` created. Click the dot → dialog with *Mark as Resolved* → gray check. Also verify the action appears in the editor context menu and via Cmd+Alt+Shift+C.

- [ ] **Step 12: Commit**

```bash
git add -A
git commit -m "Add gutter markers and the Add AI Comment action"
```

---

### Task 8: File watcher with debounce

**Files:**
- Create: `watcher/CommentsFileWatcher.kt`
- Modify: `startup/AiCommentsStartup.kt`
- Test: `watcher/CommentsFileWatcherTest.kt`

**Interfaces:**
- Consumes: `CommentStore.reload()`, `AiCommentsSettings.state.autoRefresh` / `debounceMs`, `ProjectPaths.commentsFile`.
- Produces: `@Service(PROJECT) class CommentsFileWatcher(project) : Disposable` with `fun isCommentsFileEvent(event: VFileEvent): Boolean` and `fun scheduleReload()`.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/cz/petrgala/aicomments/watcher/CommentsFileWatcherTest.kt`:

```kotlin
package cz.petrgala.aicomments.watcher

import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.petrgala.aicomments.storage.CommentStore
import cz.petrgala.aicomments.storage.ProjectPaths
import java.nio.file.Files

class CommentsFileWatcherTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        Files.deleteIfExists(ProjectPaths.commentsFile(project))
        project.service<CommentStore>().reload()
        project.service<CommentsFileWatcher>()
    }

    override fun tearDown() {
        try {
            Files.deleteIfExists(ProjectPaths.commentsFile(project))
        } finally {
            super.tearDown()
        }
    }

    fun testExternalWriteIsPickedUpAfterDebounce() {
        val store = project.service<CommentStore>()
        val path = ProjectPaths.commentsFile(project)
        Files.createDirectories(path.parent)
        Files.writeString(path, """
            {"version":1,"metadata":{"projectName":"p","created":"2026-09-11T10:00:00Z","lastModified":"2026-09-11T10:00:00Z"},
             "comments":{"Foo.php":[{"id":"c_1_0","line":1,"author":"human","text":"external","status":"open","created":"2026-09-11T10:00:00Z","processed":false,"processedAt":null,"claudeResponse":null}]}}
        """.trimIndent())
        assertTrue(store.commentsFor("Foo.php").isEmpty())

        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
        PlatformTestUtil.waitWithEventsDispatching("comments not reloaded", { store.commentsFor("Foo.php").size == 1 }, 5)

        assertEquals("external", store.commentsFor("Foo.php")[0].text)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'cz.petrgala.aicomments.watcher.CommentsFileWatcherTest'`
Expected: compilation FAILS.

- [ ] **Step 3: Write the watcher**

`watcher/CommentsFileWatcher.kt`:

```kotlin
package cz.petrgala.aicomments.watcher

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.util.Alarm
import cz.petrgala.aicomments.settings.AiCommentsSettings
import cz.petrgala.aicomments.storage.CommentStore
import cz.petrgala.aicomments.storage.ProjectPaths

@Service(Service.Level.PROJECT)
class CommentsFileWatcher(private val project: Project) : Disposable {

    private val watchedPath = FileUtil.toSystemIndependentName(ProjectPaths.commentsFile(project).toString())
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    init {
        VirtualFileManager.getInstance().addAsyncFileListener(AsyncFileListener { events ->
            if (!AiCommentsSettings.getInstance(project).state.autoRefresh) return@AsyncFileListener null
            if (events.none(::isCommentsFileEvent)) return@AsyncFileListener null
            object : AsyncFileListener.ChangeApplier {
                override fun afterVfsChange() = scheduleReload()
            }
        }, this)
    }

    fun isCommentsFileEvent(event: VFileEvent): Boolean {
        if (FileUtil.pathsEqual(event.path, watchedPath)) return true
        val oldPath = when (event) {
            is VFileMoveEvent -> event.oldPath
            is VFilePropertyChangeEvent -> if (event.isRename) event.oldPath else null
            else -> null
        }
        return oldPath != null && FileUtil.pathsEqual(oldPath, watchedPath)
    }

    fun scheduleReload() {
        alarm.cancelAllRequests()
        alarm.addRequest({ project.service<CommentStore>().reload() }, AiCommentsSettings.getInstance(project).state.debounceMs)
    }

    override fun dispose() = Unit
}
```

- [ ] **Step 4: Instantiate at startup**

In `startup/AiCommentsStartup.kt` add `project.service<CommentsFileWatcher>()` after the marker manager line (and the import `cz.petrgala.aicomments.watcher.CommentsFileWatcher`).

- [ ] **Step 5: Run the tests**

Run: `./gradlew test --tests 'cz.petrgala.aicomments.watcher.CommentsFileWatcherTest'`
Expected: PASS. If it times out, check that `refreshAndFindFileByNioFile` produced a `VFileCreateEvent` whose `path` equals `watchedPath` (log both inside `isCommentsFileEvent` while debugging, remove afterwards).

- [ ] **Step 6: Manual check**

`./gradlew runIde`, add a comment, then edit `.claude/comments.json` in another editor (e.g. change `"status": "open"` to `"status": "processed"` and set `"claudeResponse": "done"`). Within ~1 s after the IDE window regains focus the dot turns green; clicking it shows the response with OK / Follow-up.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Reload comments when comments.json changes on disk"
```

---

### Task 9: Line sync on save

**Files:**
- Create: `editor/LineSyncOnSave.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Test: `editor/LineSyncOnSaveTest.kt`

**Interfaces:**
- Consumes: `CommentMarkerManager.markers(editor)`, `COMMENT_IDS_KEY`, `CommentStore.updateLines`, `ProjectPaths.relativePath`.
- Produces: `class LineSyncOnSave : FileDocumentManagerListener` registered as an application listener.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/cz/petrgala/aicomments/editor/LineSyncOnSaveTest.kt`:

```kotlin
package cz.petrgala.aicomments.editor

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.petrgala.aicomments.storage.CommentStore
import cz.petrgala.aicomments.storage.ProjectPaths
import java.nio.file.Files

class LineSyncOnSaveTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        Files.deleteIfExists(ProjectPaths.commentsFile(project))
        project.service<CommentStore>().reload()
        project.service<CommentMarkerManager>()
    }

    override fun tearDown() {
        try {
            Files.deleteIfExists(ProjectPaths.commentsFile(project))
        } finally {
            super.tearDown()
        }
    }

    fun testInsertingLinesAboveACommentUpdatesItsLineOnSave() {
        val store = project.service<CommentStore>()
        myFixture.configureByText("Foo.php", "a\nb\nc\n")
        val comment = store.add("Foo.php", 3, "text")!!

        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(0, "x\ny\n")
        }
        FileDocumentManager.getInstance().saveDocument(myFixture.editor.document)

        assertEquals(5, store.commentsFor("Foo.php").single { it.id == comment.id }.line)
    }

    fun testUnchangedLinesDoNotWrite() {
        val store = project.service<CommentStore>()
        myFixture.configureByText("Foo.php", "a\nb\n")
        store.add("Foo.php", 2, "text")
        val before = Files.readString(ProjectPaths.commentsFile(project))

        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(myFixture.editor.document.textLength, "z\n")
        }
        FileDocumentManager.getInstance().saveDocument(myFixture.editor.document)

        assertEquals(before, Files.readString(ProjectPaths.commentsFile(project)))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'cz.petrgala.aicomments.editor.LineSyncOnSaveTest'`
Expected: compilation FAILS.

- [ ] **Step 3: Write the listener**

`editor/LineSyncOnSave.kt`:

```kotlin
package cz.petrgala.aicomments.editor

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import cz.petrgala.aicomments.storage.CommentStore
import cz.petrgala.aicomments.storage.ProjectPaths

class LineSyncOnSave : FileDocumentManagerListener {

    override fun beforeDocumentSaving(document: Document) {
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        EditorFactory.getInstance().getEditors(document)
            .mapNotNull { editor -> editor.project?.let { it to editor } }
            .distinctBy { (project, _) -> project }
            .forEach { (project, editor) ->
                if (project.isDisposed) return@forEach
                val relativePath = ProjectPaths.relativePath(project, file) ?: return@forEach
                val store = project.service<CommentStore>()
                val stored = store.commentsFor(relativePath).associate { it.id to it.line }
                val moved = project.service<CommentMarkerManager>().markers(editor)
                    .filter { it.isValid }
                    .flatMap { marker ->
                        val line = document.getLineNumber(marker.startOffset) + 1
                        marker.getUserData(CommentMarkerManager.COMMENT_IDS_KEY).orEmpty()
                            .filter { stored[it] != null && stored[it] != line }
                            .map { it to line }
                    }
                    .toMap()
                store.updateLines(relativePath, moved)
            }
    }
}
```

- [ ] **Step 4: Register the listener**

Add to `plugin.xml` (sibling of `<extensions>`):

```xml
    <applicationListeners>
        <listener class="cz.petrgala.aicomments.editor.LineSyncOnSave"
                  topic="com.intellij.openapi.fileEditor.FileDocumentManagerListener"/>
    </applicationListeners>
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew test`
Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Write marker lines back to comments.json on document save"
```

---

### Task 10: Tool window

**Files:**
- Create: `actions/ReloadCommentsAction.kt`, `toolwindow/AiCommentsToolWindowFactory.kt`, `toolwindow/AiCommentsPanel.kt`, `toolwindow/CommentsTreeModel.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Test: `toolwindow/CommentsTreeModelTest.kt`

**Interfaces:**
- Consumes: `CommentStore`, `CommentsChangedListener`, `AiCommentsSettings`, `AiCommentsSettingsListener`, `ProjectPaths.findFile`, `AiCommentsIcons`.
- Produces:
  - `class ReloadCommentsAction : AnAction` (id `cz.petrgala.aicomments.Reload`)
  - `object CommentsTreeModel { fun build(file: CommentsFile, hideResolved: Boolean, lineCountOf: (String) -> Int?): DefaultMutableTreeNode }`, node user objects `FileNode(path, count)` and `CommentNode(path, comment, outOfRange: Boolean)`
  - `class AiCommentsPanel(project, parentDisposable) : SimpleToolWindowPanel`
  - `class AiCommentsToolWindowFactory : ToolWindowFactory, DumbAware`

- [ ] **Step 1: Write the failing tree model test**

`src/test/kotlin/cz/petrgala/aicomments/toolwindow/CommentsTreeModelTest.kt`:

```kotlin
package cz.petrgala.aicomments.toolwindow

import cz.petrgala.aicomments.model.Comment
import cz.petrgala.aicomments.model.CommentStatus
import cz.petrgala.aicomments.model.CommentsFile
import cz.petrgala.aicomments.model.withComment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.tree.DefaultMutableTreeNode

class CommentsTreeModelTest {

    private val now = "2026-09-11T10:00:00Z"
    private fun comment(id: String, line: Int, status: CommentStatus = CommentStatus.OPEN) =
        Comment(id, line, Comment.HUMAN, "text", status, now, null, null)

    private val file = CommentsFile.empty("p", now)
        .withComment("b.php", comment("c_1_0", 9))
        .withComment("a.php", comment("c_1_1", 20))
        .withComment("a.php", comment("c_1_2", 3, CommentStatus.RESOLVED))

    private fun children(node: DefaultMutableTreeNode) = node.children().toList().map { it as DefaultMutableTreeNode }

    @Test
    fun `files are sorted and comments ordered by line`() {
        val root = CommentsTreeModel.build(file, hideResolved = false) { 100 }

        val files = children(root).map { (it.userObject as FileNode).path }
        assertEquals(listOf("a.php", "b.php"), files)
        val aLines = children(children(root)[0]).map { (it.userObject as CommentNode).comment.line }
        assertEquals(listOf(3, 20), aLines)
    }

    @Test
    fun `hideResolved drops resolved comments and empty files`() {
        val onlyResolved = CommentsFile.empty("p", now).withComment("z.php", comment("c_2_0", 1, CommentStatus.RESOLVED))

        val root = CommentsTreeModel.build(file, hideResolved = true) { 100 }
        val emptyRoot = CommentsTreeModel.build(onlyResolved, hideResolved = true) { 100 }

        assertEquals(listOf(20), children(children(root)[0]).map { (it.userObject as CommentNode).comment.line })
        assertEquals(0, emptyRoot.childCount)
    }

    @Test
    fun `line beyond the file is flagged, unknown file length is not`() {
        val root = CommentsTreeModel.build(file, hideResolved = false) { path -> if (path == "a.php") 10 else null }

        val a = children(children(root)[0]).map { it.userObject as CommentNode }
        assertFalse(a[0].outOfRange)
        assertTrue(a[1].outOfRange)
        val b = children(children(root)[1]).map { it.userObject as CommentNode }
        assertFalse(b[0].outOfRange)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'cz.petrgala.aicomments.toolwindow.CommentsTreeModelTest'`
Expected: compilation FAILS.

- [ ] **Step 3: Write the tree model**

`toolwindow/CommentsTreeModel.kt`:

```kotlin
package cz.petrgala.aicomments.toolwindow

import cz.petrgala.aicomments.model.Comment
import cz.petrgala.aicomments.model.CommentStatus
import cz.petrgala.aicomments.model.CommentsFile
import javax.swing.tree.DefaultMutableTreeNode

data class FileNode(val path: String, val count: Int)

data class CommentNode(val path: String, val comment: Comment, val outOfRange: Boolean)

object CommentsTreeModel {

    fun build(file: CommentsFile, hideResolved: Boolean, lineCountOf: (String) -> Int?): DefaultMutableTreeNode {
        val root = DefaultMutableTreeNode()
        file.comments.toSortedMap().forEach { (path, comments) ->
            val visible = comments
                .filter { !hideResolved || it.status != CommentStatus.RESOLVED }
                .sortedBy { it.line }
            if (visible.isEmpty()) return@forEach
            val lineCount = lineCountOf(path)
            val fileNode = DefaultMutableTreeNode(FileNode(path, visible.size))
            visible.forEach { comment ->
                val outOfRange = lineCount != null && comment.line > lineCount
                fileNode.add(DefaultMutableTreeNode(CommentNode(path, comment, outOfRange)))
            }
            root.add(fileNode)
        }
        return root
    }
}
```

- [ ] **Step 4: Run the tree model tests**

Run: `./gradlew test --tests 'cz.petrgala.aicomments.toolwindow.CommentsTreeModelTest'`
Expected: 3 PASS.

- [ ] **Step 5: Write the reload action**

`actions/ReloadCommentsAction.kt`:

```kotlin
package cz.petrgala.aicomments.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import cz.petrgala.aicomments.storage.CommentStore

class ReloadCommentsAction : AnAction("Reload", "Reload .claude/comments.json from disk", AllIcons.Actions.Refresh), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ApplicationManager.getApplication().executeOnPooledThread { project.service<CommentStore>().reload() }
    }
}
```

- [ ] **Step 6: Write the panel**

`toolwindow/AiCommentsPanel.kt`:

```kotlin
package cz.petrgala.aicomments.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import cz.petrgala.aicomments.editor.AiCommentsIcons
import cz.petrgala.aicomments.model.CommentStatus
import cz.petrgala.aicomments.model.CommentsFile
import cz.petrgala.aicomments.model.count
import cz.petrgala.aicomments.settings.AiCommentsSettings
import cz.petrgala.aicomments.settings.AiCommentsSettingsListener
import cz.petrgala.aicomments.storage.CommentStore
import cz.petrgala.aicomments.storage.CommentsChangedListener
import cz.petrgala.aicomments.storage.ProjectPaths
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class AiCommentsPanel(private val project: Project, parentDisposable: Disposable) : SimpleToolWindowPanel(true, true) {

    private val summary = JBLabel().apply { border = JBUI.Borders.empty(4, 8) }
    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode())
    private val tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = NodeRenderer()
    }

    init {
        toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_TOOLBAR_BAR, toolbarActions(), true)
            .also { it.targetComponent = this }
            .component
        setContent(JPanel(BorderLayout()).apply {
            add(summary, BorderLayout.NORTH)
            add(JBScrollPane(tree), BorderLayout.CENTER)
        })

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean = navigateToSelection()
        }.installOn(tree)
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && navigateToSelection()) e.consume()
            }
        })

        val connection = project.messageBus.connect(parentDisposable)
        connection.subscribe(CommentsChangedListener.TOPIC, object : CommentsChangedListener {
            override fun commentsChanged(snapshot: CommentsFile) = render(snapshot)
        })
        connection.subscribe(AiCommentsSettingsListener.TOPIC, object : AiCommentsSettingsListener {
            override fun settingsChanged() = render(project.service<CommentStore>().snapshot())
        })
        render(project.service<CommentStore>().snapshot())
    }

    private fun toolbarActions() = DefaultActionGroup(
        ActionManager.getInstance().getAction("cz.petrgala.aicomments.Reload"),
        object : ToggleAction("Hide Resolved", "Hide resolved comments", AllIcons.Actions.Checked), DumbAware {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent): Boolean = AiCommentsSettings.getInstance(project).state.hideResolved
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                val settings = AiCommentsSettings.getInstance(project)
                settings.state.hideResolved = state
                settings.publishChanged(project)
            }
        },
    )

    private fun render(snapshot: CommentsFile) {
        summary.text = "Open ${snapshot.count(CommentStatus.OPEN)} · Processed ${snapshot.count(CommentStatus.PROCESSED)} · Resolved ${snapshot.count(CommentStatus.RESOLVED)}"
        val hideResolved = AiCommentsSettings.getInstance(project).state.hideResolved
        treeModel.setRoot(CommentsTreeModel.build(snapshot, hideResolved, ::lineCountOf))
        TreeUtil.expandAll(tree)
    }

    private fun lineCountOf(relativePath: String): Int? {
        val file = ProjectPaths.findFile(project, relativePath) ?: return null
        return FileDocumentManager.getInstance().getDocument(file)?.lineCount
    }

    private fun navigateToSelection(): Boolean {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return false
        val target = node.userObject as? CommentNode ?: return false
        val file = ProjectPaths.findFile(project, target.path) ?: return false
        OpenFileDescriptor(project, file, target.comment.line - 1, 0).navigate(true)
        return true
    }

    private class NodeRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            when (val data = (value as? DefaultMutableTreeNode)?.userObject) {
                is FileNode -> {
                    append(data.path)
                    append("  ${data.count}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is CommentNode -> {
                    icon = AiCommentsIcons.forStatus(data.comment.status)
                    append("L${data.comment.line}", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append(" · ${data.comment.status.json} · ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append(data.comment.text.take(TEXT_PREVIEW_LENGTH) + if (data.comment.text.length > TEXT_PREVIEW_LENGTH) "…" else "")
                    if (data.outOfRange) append("  (line out of range)", SimpleTextAttributes.ERROR_ATTRIBUTES)
                }
            }
        }
    }

    private companion object {
        const val TEXT_PREVIEW_LENGTH = 60
    }
}
```

- [ ] **Step 7: Write the factory**

`toolwindow/AiCommentsToolWindowFactory.kt`:

```kotlin
package cz.petrgala.aicomments.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import cz.petrgala.aicomments.settings.AiCommentsSettings

class AiCommentsToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun shouldBeAvailable(project: Project): Boolean =
        AiCommentsSettings.getInstance(project).state.showToolWindow

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = AiCommentsPanel(project, toolWindow.disposable)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, "", false))
    }
}
```

- [ ] **Step 8: Register in `plugin.xml`**

Inside `<extensions>`:

```xml
        <toolWindow id="AI Comments"
                    anchor="right"
                    icon="/icons/toolwindow.svg"
                    factoryClass="cz.petrgala.aicomments.toolwindow.AiCommentsToolWindowFactory"/>
```

Inside `<actions>`:

```xml
        <action id="cz.petrgala.aicomments.Reload"
                class="cz.petrgala.aicomments.actions.ReloadCommentsAction"
                text="Reload AI Comments"
                description="Reload .claude/comments.json from disk"/>
```

- [ ] **Step 9: Build, test, manual check**

Run: `./gradlew test` → PASS. Then `./gradlew runIde`: the *AI Comments* stripe button appears on the right; counts match; double-click a comment jumps to the line; *Hide Resolved* toggle filters; *Reload* re-reads after an external edit even with auto-refresh disabled in settings; disabling *Show the AI Comments tool window* in settings hides the stripe.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "Add the AI Comments tool window"
```

---

### Task 11: Claude Code skill, README, release build

**Files:**
- Create: `claude/skills/process-ai-comments/SKILL.md`, `README.md`

**Interfaces:**
- Consumes: the JSON format from Task 2.
- Produces: an installable plugin ZIP and the skill that processes the file.

- [ ] **Step 1: Write the skill**

`claude/skills/process-ai-comments/SKILL.md`:

````markdown
---
name: process-ai-comments
description: Process open AI comments stored by the PhpStorm "AI Comments" plugin in .claude/comments.json — apply the requested code changes or answer questions, then write the responses back. Use when the user says "process AI comments", "zpracuj AI komentáře", or runs /process-ai-comments.
---

# Process AI comments

The PhpStorm plugin stores per-line notes in `.claude/comments.json` at the project root.
Your job: handle every comment with `"status": "open"` and write the result back so the
plugin can show it in the editor.

## File format

```json
{
  "version": 1,
  "metadata": { "projectName": "…", "created": "…", "lastModified": "…" },
  "comments": {
    "src/Service/UserService.php": [
      {
        "id": "c_1726061700_0",
        "line": 45,
        "author": "human",
        "text": "use FQN here",
        "status": "open",
        "created": "2026-09-11T10:05:00Z",
        "processed": false,
        "processedAt": null,
        "claudeResponse": null
      }
    ]
  }
}
```

Paths are relative to the project root. `line` is 1-based. Timestamps are UTC ISO 8601
with seconds (`2026-09-11T10:25:00Z`).

## Procedure

1. Read `.claude/comments.json`. If it is missing or has no comment with `"status": "open"`,
   say so and stop.
2. For each open comment, in file order:
   - Open the file and read the commented line plus 5 lines before and after. That is the
     context the comment refers to; read more of the file only if the instruction needs it.
   - If the same line has other comments (any status), read them oldest to newest — they
     are the conversation history and the open one is a follow-up to the previous response.
   - Decide what the comment is:
     - an **instruction** ("use FQN here", "extract this to a method") → change the code;
     - a **question** (ends with `?`, or starts with "is/does/why/should/can") → answer, do
       not change the code.
   - Write `claudeResponse`: 1–3 sentences in the language of the comment saying what you
     changed (with the new name / shape) or the answer. No preamble.
3. Update the record: `"status": "processed"`, `"processed": true`,
   `"processedAt": "<now, UTC ISO 8601>"`, `"claudeResponse": "<text>"`.
4. Set `metadata.lastModified` to the same timestamp.
5. Write the whole file once, after all comments are handled, pretty-printed with two-space
   indentation, keys in the order shown above. The IDE watches the file and refreshes on
   every write; a single write means a single refresh and never a half-processed file.
6. Report: how many comments were processed and which source files changed.

## Rules

- Change only `status`, `processed`, `processedAt`, `claudeResponse` and
  `metadata.lastModified`. Never delete a record, never change `id`, `line`, `text`,
  `created` or `author`, never add records.
- Leave `processed` and `resolved` records untouched.
- If a line number no longer matches the code the comment describes (the file changed),
  look for the described code nearby; if you cannot find it, answer in `claudeResponse`
  that the line could not be matched and still mark the comment processed.
- When an instruction is ambiguous, pick the most conservative reading and say what you
  assumed in `claudeResponse`.
- Do not stage or commit anything.
````

- [ ] **Step 2: Write the README**

`README.md`:

````markdown
# AI Comments — PhpStorm plugin

Attach short notes to code lines from inside PhpStorm, let Claude Code process them, and
review the answers in the editor. Comments live in `.claude/comments.json` at the project
root; the plugin shows their state in the gutter and a tool window.

## Workflow

1. In the editor: right-click a line number (or press `Cmd+Alt+Shift+C`, `Ctrl+Alt+Shift+C`
   on Linux/Windows) → **Add AI Comment** → type the note. A blue dot appears in the gutter.
2. In Claude Code, in the same project: `/process-ai-comments` (or "process AI comments").
   Claude edits the code or answers, and writes its response back into the file.
3. The dot turns green. Click it to read the response, then **OK** (marks it resolved) or
   **Follow-up** (adds a new open comment on the same line and resolves the previous one).

Gutter icons: blue dot = open, green dot = processed, gray check = resolved. When a line has
several comments the icon shows the most important state (open > processed > resolved).

## Installation

### Plugin

1. `./gradlew buildPlugin` → `build/distributions/ai-comments-<version>.zip`
2. PhpStorm → *Settings → Plugins → ⚙ → Install Plugin from Disk…* → pick the ZIP.

Requires PhpStorm 2024.1 or newer.

### Claude Code skill

```bash
cp -r claude/skills/process-ai-comments ~/.claude/skills/
```

The skill is global, so it works in every project where the plugin is used.

## Settings

*Settings → Tools → AI Comments* (stored per project in `.idea/aiComments.xml`):

| Setting | Default |
|---|---|
| Show gutter icons | on |
| Refresh automatically when `.claude/comments.json` changes | on |
| Refresh debounce (ms) | 500 |
| Maximum comment length | 500 |
| Show the AI Comments tool window | on |
| Hide resolved comments in the tool window | off |

## File format

See `claude/skills/process-ai-comments/SKILL.md`. Add `.claude/comments.json` to
`.gitignore` if the comments should stay local.

A malformed file shows a notification with **Reset file** (the broken file is kept as
`comments.json.broken-<timestamp>`) and **Open file**. Until then the plugin is read-only.

## Development

```bash
./gradlew test           # unit + platform tests
./gradlew runIde         # sandbox PhpStorm with the plugin
./gradlew verifyPlugin   # compatibility against 2024.1 and 2026.2
./gradlew buildPlugin    # ZIP in build/distributions
```

Requires JDK 21 (`brew install --cask temurin@21`).

### Smoke checklist before a release

- [ ] Add a comment from the gutter menu, the editor menu and the shortcut
- [ ] Validation: empty text blocked, warning above 400 characters, blocked above 500
- [ ] Open comment → *Mark as Resolved* → gray check
- [ ] Edit the JSON externally (open → processed with a response) → green dot within ~1 s
- [ ] Processed comment → *OK* → resolved; *Follow-up* → new open comment, old resolved
- [ ] Insert lines above a comment, save → line number updated in the JSON
- [ ] Tool window counts, navigation on double-click and Enter, *Hide Resolved*, *Reload*
- [ ] Settings: gutter icons off removes dots, tool window off hides the stripe
- [ ] Malformed JSON → notification, *Reset file* recovers
````

- [ ] **Step 3: Verify and build**

Run: `./gradlew test verifyPlugin buildPlugin`
Expected: tests PASS; the verifier reports no compatibility problems for PhpStorm 2024.1.7 and 2026.2.2 (deprecation warnings are acceptable, "incompatible API usage" is not); `build/distributions/ai-comments-0.1.0.zip` exists.

- [ ] **Step 4: Install in the real PhpStorm and run the smoke checklist**

Install the ZIP from disk into PhpStorm 2026.2.2, restart, install the skill with the `cp -r` command, and walk through the README smoke checklist including one real `/process-ai-comments` round trip in Claude Code.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Add Claude Code skill and README"
```
