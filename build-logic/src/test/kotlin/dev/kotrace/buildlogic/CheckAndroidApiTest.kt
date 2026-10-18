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
 * [CheckAndroidApi] on a jar of compiled classes, against a small `api-versions.xml` with the levels of the real SDK:
 * `ThreadLocal.withInitial` arrives at API 26, `Long.hashCode` and `Random.ints` at 24,
 * `URLClassLoader` implements `Closeable` from 24, `MethodHandles` arrives at 26, `ClassNotFoundException` moves
 * from `Exception` to `ReflectiveOperationException` at 19, `DataSource.getLoginTimeout` moves to
 * `CommonDataSource` at 9, and `RuntimeException` gets the four-argument constructor at 24, after `Throwable` at 19.
 */
class CheckAndroidApiTest {

    @get:Rule val tmp = TemporaryFolder()

    private object CallsWithInitial {
        val flag: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }
    }

    private object CallsLongHashCode {
        fun hash(value: Long): Int = value.hashCode()
    }

    private object CallsUrlClassLoaderClose {
        fun close(loader: java.net.URLClassLoader) = loader.close()
    }

    private object CallsReparentedMessage {
        fun message(e: ClassNotFoundException): String? = e.message
    }

    private class Dice : java.util.Random()

    private object CallsThroughSubclass {
        fun rolls(dice: Dice) = dice.ints()
    }

    private object CallsPublicLookup {
        fun lookup(): Any = java.lang.invoke.MethodHandles.publicLookup()
    }

    private class QuietFailure : RuntimeException("quiet", null, false, false)

    private object CallsMovedLoginTimeout {
        fun timeout(source: javax.sql.DataSource) = source.loginTimeout
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
                    <class name="java/lang/Throwable" since="1">
                        <extends name="java/lang/Object"/>
                        <method name="&lt;init&gt;(Ljava/lang/String;Ljava/lang/Throwable;ZZ)V" since="19"/>
                        <method name="getMessage()Ljava/lang/String;"/>
                    </class>
                    <class name="java/lang/RuntimeException" since="1">
                        <extends name="java/lang/Exception"/>
                        <method name="&lt;init&gt;(Ljava/lang/String;Ljava/lang/Throwable;ZZ)V" since="24"/>
                    </class>
                    <class name="java/lang/Exception" since="1">
                        <extends name="java/lang/Throwable"/>
                    </class>
                    <class name="java/lang/ReflectiveOperationException" since="19">
                        <extends name="java/lang/Exception"/>
                    </class>
                    <class name="java/lang/ClassNotFoundException" since="1">
                        <extends name="java/lang/Exception" removed="19"/>
                        <extends name="java/lang/ReflectiveOperationException" since="19"/>
                    </class>
                    <class name="java/io/Closeable" since="1">
                        <extends name="java/lang/Object"/>
                        <method name="close()V"/>
                    </class>
                    <class name="java/net/URLClassLoader" since="1">
                        <extends name="java/lang/Object"/>
                        <implements name="java/io/Closeable" since="24"/>
                    </class>
                    <class name="java/util/Random" since="1">
                        <extends name="java/lang/Object"/>
                        <method name="&lt;init&gt;()V"/>
                        <method name="ints()Ljava/util/stream/IntStream;" since="24"/>
                    </class>
                    <class name="java/lang/invoke/MethodHandles" since="26">
                        <extends name="java/lang/Object"/>
                        <method name="publicLookup()Ljava/lang/invoke/MethodHandles${'$'}Lookup;"/>
                    </class>
                    <class name="javax/sql/CommonDataSource" since="9">
                        <extends name="java/lang/Object"/>
                        <method name="getLoginTimeout()I"/>
                    </class>
                    <class name="javax/sql/DataSource" since="1">
                        <extends name="java/lang/Object"/>
                        <implements name="javax/sql/CommonDataSource" since="9"/>
                        <method name="getLoginTimeout()I" removed="9"/>
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

    private fun jarOf(vararg types: Class<*>): File =
        tmp.newFile("${types.first().simpleName}.jar").apply {
            ZipOutputStream(outputStream()).use { zip ->
                types.forEach { type ->
                    val entry = type.name.replace('.', '/') + ".class"
                    zip.putNextEntry(ZipEntry(entry))
                    type.classLoader.getResourceAsStream(entry)!!.use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }

    private fun Result<Unit>.reported(): List<String> =
        exceptionOrNull()?.message.orEmpty().lines().map { it.trim() }.filter { it.startsWith("Android API") || it.startsWith("absent") }

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

    @Test fun `an inherited method counts the level its interface link arrived at`() {
        assertEquals(
            listOf("Android API 24: java/net/URLClassLoader.close()V"),
            check(jarOf(CallsUrlClassLoaderClose::class.java)).reported().map { it.substringBefore(" (") },
        )
    }

    @Test fun `a class that changed superclass keeps the members both of them give it`() {
        assertTrue(check(jarOf(CallsReparentedMessage::class.java)).isSuccess)
    }

    @Test fun `a platform method called through the jar's own subclass is checked on the platform type`() {
        val reported = check(jarOf(CallsThroughSubclass::class.java, Dice::class.java)).reported()

        assertTrue(
            reported.toString(),
            reported.any {
                it.startsWith(
                    "Android API 24: java/util/Random.ints()Ljava/util/stream/IntStream; " +
                        "via dev/kotrace/buildlogic/CheckAndroidApiTest\$Dice",
                )
            },
        )
    }

    @Test fun `a direct call into java·lang·invoke is checked, unlike a lambda's bootstrap`() {
        assertEquals(
            listOf(
                "Android API 26: java/lang/invoke/MethodHandles.publicLookup()Ljava/lang/invoke/MethodHandles\$Lookup;",
            ),
            check(jarOf(CallsPublicLookup::class.java)).reported().map { it.substringBefore(" (") },
        )
    }

    @Test fun `a method reference into java·lang·invoke is a call, though a lambda's handle names it`() {
        val reported = check(jarOf(ReferencesPublicLookup::class.java)).reported()

        assertTrue(reported.toString(), reported.any { it.startsWith("Android API 26: java/lang/invoke/MethodHandles.publicLookup") })
    }

    @Test fun `a constructor counts only its own class, never a superclass's`() {
        val reported = check(jarOf(QuietFailure::class.java)).reported()

        assertTrue(
            reported.toString(),
            reported.any { it.startsWith("Android API 24: java/lang/RuntimeException.<init>(Ljava/lang/String;Ljava/lang/Throwable;ZZ)V") },
        )
    }

    @Test fun `a member whose declaration moved to a supertype stays available`() {
        assertTrue(check(jarOf(CallsMovedLoginTimeout::class.java)).isSuccess)
    }

    @Test fun `a missing SDK is reported with the fix`() {
        val failure = check(jarOf(CallsLongHashCode::class.java), database = null).exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("No Android API database at platforms/android-36"))
    }
}
