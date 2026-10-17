package dev.kotrace.buildlogic

import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * [CheckAndroidApi] on a jar holding one compiled class, against a small `api-versions.xml` where
 * `ThreadLocal.withInitial` arrives at API 26 and `Long.hashCode` at 24, as in the real SDK.
 */
class CheckAndroidApiTest {

    @get:Rule val tmp = TemporaryFolder()

    private object CallsWithInitial {
        val flag: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }
    }

    private object CallsLongHashCode {
        fun hash(value: Long): Int = value.hashCode()
    }

    private val apiVersions by lazy {
        tmp.newFile("api-versions.xml").apply {
            writeText(
                """
                <api version="3">
                    <class name="java/lang/Object" since="1">
                        <method name="&lt;init&gt;()V"/>
                    </class>
                    <class name="java/lang/Boolean" since="1">
                        <extends name="java/lang/Object"/>
                        <method name="valueOf(Z)Ljava/lang/Boolean;"/>
                    </class>
                    <class name="java/lang/Long" since="1">
                        <extends name="java/lang/Object"/>
                        <method name="hashCode(J)I" since="24"/>
                    </class>
                    <class name="java/lang/ThreadLocal" since="1">
                        <extends name="java/lang/Object"/>
                        <method name="withInitial(Ljava/util/function/Supplier;)Ljava/lang/ThreadLocal;" since="26"/>
                    </class>
                </api>
                """.trimIndent(),
            )
        }
    }

    private fun jarOf(type: Class<*>): File {
        val entry = type.name.replace('.', '/') + ".class"
        return tmp.newFile("${type.simpleName}.jar").apply {
            ZipOutputStream(outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry(entry))
                type.classLoader.getResourceAsStream(entry)!!.use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun check(jar: File, database: File? = apiVersions): Result<Unit> {
        val project = ProjectBuilder.builder().withProjectDir(tmp.newFolder()).build()
        val task = project.tasks.register("checkAndroidApi", CheckAndroidApi::class.java) {
            this.jar.set(jar)
            if (database != null) apiVersions.set(database)
            apiVersionsPath.set("platforms/android-36/data/api-versions.xml")
            minSdk.set(21)
            report.set(project.layout.buildDirectory.file("android-api.txt"))
        }.get()
        return runCatching { task.check() }
    }

    @Test fun `a call Android added after the minimum SDK fails the check`() {
        val failure = check(jarOf(CallsWithInitial::class.java)).exceptionOrNull()

        val message = failure?.message.orEmpty()
        assertTrue(message, message.contains("Android API 26: java/lang/ThreadLocal.withInitial"))
        assertEquals("only that call is reported", 1, message.lines().count { it.trimStart().startsWith("Android API") })
    }

    @Test fun `a call D8 backports passes, however new`() {
        assertTrue(check(jarOf(CallsLongHashCode::class.java)).isSuccess)
    }

    @Test fun `a missing SDK is reported with the fix`() {
        val failure = check(jarOf(CallsLongHashCode::class.java), database = null).exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("No Android API database at platforms/android-36"))
    }
}
