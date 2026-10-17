package dev.kotrace.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.DataInputStream
import java.io.File
import java.util.zip.ZipFile
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/**
 * Fails when a JVM module's jar calls a Java API that Android lacks at [minSdk] (ADR-027). The core and
 * `kotrace-okhttp` run on Android apps, but they compile against the JDK, so nothing else sees an Android API level:
 * `-Xjdk-release` bounds the Java version, not the Android one, and an Android module's lint does not read their
 * sources. A call the device lacks throws `NoSuchMethodError` there, at run time only.
 *
 * It reads every class, method and field reference in the jar's constant pools and looks each one up in the
 * Android SDK's `api-versions.xml`, through superclasses and interfaces. Two kinds of reference pass although newer:
 * - `java/lang/invoke`: the bootstraps of lambdas and string templates, which D8 rewrites into plain code;
 * - [d8Backported]: calls D8 replaces with its own code at any minimum API, checked on a dexed jar.
 *
 * [apiVersions] is optional so a missing SDK is reported here with the fix, rather than as a validation error.
 */
@CacheableTask
abstract class CheckAndroidApi : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val jar: RegularFileProperty

    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.NONE)
    abstract val apiVersions: RegularFileProperty

    @get:Input abstract val apiVersionsPath: Property<String>

    @get:Input abstract val minSdk: Property<Int>

    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val database = apiVersions.orNull?.asFile
            ?: error(
                "No Android API database at ${apiVersionsPath.get()}. Install that SDK platform, and set " +
                    "`sdk.dir` in local.properties or ANDROID_HOME.",
            )
        val api = AndroidApi.parse(database)
        val floor = minSdk.get()
        val findings = sortedSetOf<String>()
        ZipFile(jar.get().asFile).use { zip ->
            zip.entries().asSequence().filter { it.name.endsWith(".class") }.forEach { entry ->
                val refs = zip.getInputStream(entry).use { ClassRefs.read(DataInputStream(it.buffered())) }
                refs.forEach { ref ->
                    if (!ref.owner.isPlatform() || ref.owner.startsWith("java/lang/invoke/")) return@forEach
                    if (ref.member != null && "${ref.owner}.${ref.member}" in d8Backported) return@forEach
                    val since = if (ref.member == null) api.classSince(ref.owner) else api.memberSince(ref.owner, ref.member)
                    val problem = when {
                        since == null -> "absent from Android"
                        since > floor -> "Android API $since"
                        else -> return@forEach
                    }
                    findings += "$problem: ${ref.owner}${ref.member?.let { ".$it" }.orEmpty()} (${entry.name})"
                }
            }
        }
        report.get().asFile.apply { parentFile.mkdirs() }.writeText(findings.joinToString("\n", postfix = "\n"))
        if (findings.isEmpty()) return
        error(
            "${jar.get().asFile.name} references Java API that Android lacks at minSdk $floor:\n" +
                findings.joinToString("\n") { "  $it" } +
                "\nUse an older equivalent, or raise the floor deliberately (ADR-027).",
        )
    }

    private fun String.isPlatform() = startsWith("java/") || startsWith("javax/") || startsWith("android/")

    private companion object {
        /**
         * Calls D8 rewrites into its own code whatever the minimum API, so a newer level does not matter. Each entry
         * was confirmed on a jar dexed with `d8 --min-api 21`; extend it only the same way.
         */
        val d8Backported = setOf(
            "java/lang/Boolean.hashCode(Z)I",
            "java/lang/Byte.hashCode(B)I",
            "java/lang/Character.hashCode(C)I",
            "java/lang/Double.hashCode(D)I",
            "java/lang/Float.hashCode(F)I",
            "java/lang/Integer.hashCode(I)I",
            "java/lang/Long.hashCode(J)I",
            "java/lang/Short.hashCode(S)I",
        )
    }
}

/** The API levels in an Android SDK's `api-versions.xml`: when each class and member was added. */
private class AndroidApi(
    private val classes: Map<String, Int>,
    private val members: Map<String, Map<String, Int>>,
    private val supers: Map<String, List<String>>,
) {
    fun classSince(name: String): Int? = classes[name]

    /** The level of [member] on [owner] or, when [owner] does not declare it, on the type it inherits it from. */
    fun memberSince(owner: String, member: String): Int? {
        val ownerSince = classes[owner] ?: return null
        members[owner]?.get(member)?.let { return maxOf(it, ownerSince) }
        val inherited = supers[owner].orEmpty().mapNotNull { memberSince(it, member) }.minOrNull()
            ?: if (owner == "java/lang/Object") null else memberSince("java/lang/Object", member)
        return inherited?.let { maxOf(it, ownerSince) }
    }

    companion object {
        fun parse(file: File): AndroidApi {
            val classes = HashMap<String, Int>()
            val members = HashMap<String, HashMap<String, Int>>()
            val supers = HashMap<String, MutableList<String>>()
            var current: String? = null
            var currentSince = 1
            file.inputStream().buffered().use { input ->
                val xml = XMLInputFactory.newInstance().createXMLStreamReader(input)
                while (xml.hasNext()) {
                    if (xml.next() != XMLStreamConstants.START_ELEMENT) continue
                    val name = xml.getAttributeValue(null, "name")
                    val since = xml.getAttributeValue(null, "since")?.toInt()
                    when (xml.localName) {
                        "class" -> {
                            current = name
                            currentSince = since ?: 1
                            classes[name] = currentSince
                        }
                        "method", "field" -> members.getOrPut(current!!) { HashMap() }[name] = since ?: currentSince
                        "extends", "implements" -> supers.getOrPut(current!!) { mutableListOf() } += name
                    }
                }
            }
            return AndroidApi(classes, members, supers)
        }
    }
}

/** A class reference ([member] null), or a method (name plus descriptor) or field (name) reference. */
private data class Ref(val owner: String, val member: String?)

/** Every class, method and field reference in one class file's constant pool. */
private object ClassRefs {
    fun read(input: DataInputStream): List<Ref> {
        check(input.readInt() == 0xCAFEBABE.toInt()) { "not a class file" }
        input.readUnsignedShort() // minor version
        input.readUnsignedShort() // major version
        val count = input.readUnsignedShort()
        val utf8 = arrayOfNulls<String>(count)
        val classNames = IntArray(count)
        val nameAndTypes = arrayOfNulls<IntArray>(count)
        val memberRefs = mutableListOf<IntArray>() // class index, name-and-type index, tag
        var index = 1
        while (index < count) {
            when (val tag = input.readUnsignedByte()) {
                1 -> utf8[index] = input.readUTF()
                7 -> classNames[index] = input.readUnsignedShort()
                9, 10, 11 -> memberRefs += intArrayOf(input.readUnsignedShort(), input.readUnsignedShort(), tag)
                12 -> nameAndTypes[index] = intArrayOf(input.readUnsignedShort(), input.readUnsignedShort())
                3, 4 -> input.readInt()
                5, 6 -> { input.readLong(); index++ } // a long or double takes two slots
                8, 16, 19, 20 -> input.readUnsignedShort()
                15 -> { input.readUnsignedByte(); input.readUnsignedShort() }
                17, 18 -> input.readInt()
                else -> error("unknown constant pool tag $tag")
            }
            index++
        }
        fun className(classIndex: Int): String? = utf8[classNames[classIndex]]?.takeUnless { it.startsWith("[") }
        val refs = mutableListOf<Ref>()
        classNames.forEachIndexed { i, _ -> if (classNames[i] != 0) className(i)?.let { refs += Ref(it, null) } }
        memberRefs.forEach { (classIndex, nameAndTypeIndex, tag) ->
            val owner = className(classIndex) ?: return@forEach
            val (nameIndex, descriptorIndex) = nameAndTypes[nameAndTypeIndex]!!
            val name = utf8[nameIndex]!!
            refs += Ref(owner, if (tag == 9) name else name + utf8[descriptorIndex])
        }
        return refs
    }
}
