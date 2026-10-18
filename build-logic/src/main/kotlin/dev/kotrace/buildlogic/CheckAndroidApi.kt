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
 * Android SDK's `api-versions.xml`, through superclasses and interfaces, counting the level each of those links
 * arrived at. A member referenced through one of the jar's own classes, such as an inherited platform method called
 * on a subclass, is resolved through the jar's classes to the platform type that declares it. Two kinds of reference
 * pass although newer:
 * - the bootstrap methods in `java/lang/invoke` behind lambdas and string templates, which D8 rewrites into plain
 *   code, and the class references they bring; any other call into `java/lang/invoke` is checked. The exemption is
 *   per constant pool entry, so a direct call to a bootstrap method itself (`LambdaMetafactory.metafactory`) in a
 *   class that also uses it as one passes too: telling them apart needs the method bodies, which this does not read;
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
        val classes = ZipFile(jar.get().asFile).use { zip ->
            zip.entries().asSequence().filter { it.name.endsWith(".class") }.map { entry ->
                zip.getInputStream(entry).use { ClassFile.read(entry.name, DataInputStream(it.buffered())) }
            }.toList()
        }
        val ownClasses = classes.associateBy { it.name }
        val findings = sortedSetOf<String>()
        classes.forEach { file ->
            file.refs.forEach { ref ->
                val member = ref.member
                val (owner, since) = when {
                    ref.owner.isPlatform() -> ref.owner to api.since(ref.owner, member)
                    member != null -> ownClasses.platformMember(api, ref.owner, member) ?: return@forEach
                    else -> return@forEach
                }
                if (ref.isBootstrap(owner) || (member != null && "$owner.$member" in d8Backported)) return@forEach
                val problem = when {
                    since == null -> "absent from Android"
                    since > floor -> "Android API $since"
                    else -> return@forEach
                }
                val via = if (owner == ref.owner) "" else " via ${ref.owner}"
                findings += "$problem: $owner${member?.let { ".$it" }.orEmpty()}$via (${file.entry})"
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

    /** A bootstrap a lambda or string template names, or a class reference into `java/lang/invoke`, never a call. */
    private fun Ref.isBootstrap(owner: String) =
        owner.startsWith("java/lang/invoke/") && (member == null || bootstrapTarget)

    /**
     * The platform type [member], referenced through the jar's own class [owner], resolves to, and its level: the
     * superclass first, then the interfaces, as the JVM looks it up. Null when the jar declares it, or a type outside
     * both the jar and the platform (the Kotlin standard library, another module) may: neither is checked here.
     */
    private fun Map<String, ClassFile>.platformMember(api: AndroidApi, owner: String, member: String): Pair<String, Int?>? {
        val own = this[owner] ?: return if (owner.isPlatform()) owner to api.since(owner, member) else null
        if (member in own.declared) return null
        var absent: Pair<String, Int?>? = null
        for (parent in own.supers) {
            val found = platformMember(api, parent, member) ?: return null
            if (found.second != null) return found
            if (absent == null) absent = found
        }
        return absent
    }

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

private fun String.isPlatform() = startsWith("java/") || startsWith("javax/") || startsWith("android/")

/** The API levels from [from] up to, not including, [until]; [until] is [Int.MAX_VALUE] while still present. */
private data class Levels(val from: Int, val until: Int = Int.MAX_VALUE) {
    infix fun within(other: Levels): Levels? =
        Levels(maxOf(from, other.from), minOf(until, other.until)).takeIf { it.from < it.until }
}

/** The lowest level from which these ranges cover every later one, or null when none reaches the current level. */
private fun List<Levels>.floor(): Int? {
    var floor = filter { it.until == Int.MAX_VALUE }.minOfOrNull { it.from } ?: return null
    while (true) floor = filter { it.from < floor && it.until >= floor }.minOfOrNull { it.from } ?: return floor
}

/**
 * The API levels in an Android SDK's `api-versions.xml`: when each class and member was added and removed, and when
 * each superclass and interface link was, since a class can gain an interface later than it appeared.
 */
private class AndroidApi(
    private val classes: Map<String, Levels>,
    private val members: Map<String, Map<String, Levels>>,
    private val supers: Map<String, List<Pair<String, Levels>>>,
) {
    /** The level [owner] or, when [member] is given, that member on it is present from, or null when it is not now. */
    fun since(owner: String, member: String?): Int? =
        if (member == null) listOfNotNull(classes[owner]).floor() else levels(owner, member).floor()

    /**
     * When [member] is on [owner]: declared there, and inherited through each link while it holds. Both count, since
     * a declaration can be removed when the member moves to a supertype (`DataSource.getLoginTimeout` at API 9).
     * A constructor is never inherited, so only its own class's counts.
     */
    private fun levels(owner: String, member: String): List<Levels> {
        val ownerLevels = classes[owner] ?: return emptyList()
        val declared = listOfNotNull(members[owner]?.get(member))
        if (member.startsWith("<init>")) return declared.mapNotNull { it within ownerLevels }
        val inherited = supers[owner].orEmpty()
            .flatMap { (parent, link) -> levels(parent, member).mapNotNull { it within link } }
            .ifEmpty { if (owner == OBJECT) emptyList() else levels(OBJECT, member) }
        return (declared + inherited).mapNotNull { it within ownerLevels }
    }

    companion object {
        private const val OBJECT = "java/lang/Object"

        fun parse(file: File): AndroidApi {
            val classes = HashMap<String, Levels>()
            val members = HashMap<String, HashMap<String, Levels>>()
            val supers = HashMap<String, MutableList<Pair<String, Levels>>>()
            var current: String? = null
            file.inputStream().buffered().use { input ->
                val xml = XMLInputFactory.newInstance().createXMLStreamReader(input)
                while (xml.hasNext()) {
                    if (xml.next() != XMLStreamConstants.START_ELEMENT) continue
                    val name = xml.getAttributeValue(null, "name")
                    val levels = Levels(
                        from = xml.getAttributeValue(null, "since")?.toInt() ?: 1,
                        until = xml.getAttributeValue(null, "removed")?.toInt() ?: Int.MAX_VALUE,
                    )
                    when (xml.localName) {
                        "class" -> {
                            current = name
                            classes[name] = levels
                        }
                        // A member with no level of its own arrived with its class, which `levels` applies.
                        "method", "field" -> members.getOrPut(current!!) { HashMap() }[name] = levels
                        "extends", "implements" -> supers.getOrPut(current!!) { mutableListOf() } += name to levels
                    }
                }
            }
            return AndroidApi(classes, members, supers)
        }
    }
}

/**
 * A class reference ([member] null), or a method (name plus descriptor) or field (name) reference;
 * [bootstrapTarget] when the class's `BootstrapMethods` name it, as a lambda's or string template's call site does.
 * A method handle among a bootstrap's arguments, such as a method reference's target, is an ordinary call.
 */
private data class Ref(val owner: String, val member: String?, val bootstrapTarget: Boolean = false)

/**
 * One class file of the jar: its [name], its superclass then interfaces ([supers]), the methods (name plus
 * descriptor) and fields (name) it [declared], and every class, method and field reference in its constant pool.
 */
private class ClassFile(
    val entry: String,
    val name: String,
    val supers: List<String>,
    val declared: Set<String>,
    val refs: List<Ref>,
) {
    companion object {
        fun read(entry: String, input: DataInputStream): ClassFile {
            check(input.readInt() == 0xCAFEBABE.toInt()) { "not a class file" }
            input.readUnsignedShort() // minor version
            input.readUnsignedShort() // major version
            val count = input.readUnsignedShort()
            val utf8 = arrayOfNulls<String>(count)
            val classNames = IntArray(count)
            val nameAndTypes = arrayOfNulls<IntArray>(count)
            val memberRefs = mutableListOf<IntArray>() // constant pool index, class index, name-and-type index, tag
            val handleTargets = IntArray(count) // the member reference each method handle names
            var index = 1
            while (index < count) {
                when (val tag = input.readUnsignedByte()) {
                    1 -> utf8[index] = input.readUTF()
                    7 -> classNames[index] = input.readUnsignedShort()
                    9, 10, 11 -> memberRefs += intArrayOf(index, input.readUnsignedShort(), input.readUnsignedShort(), tag)
                    12 -> nameAndTypes[index] = intArrayOf(input.readUnsignedShort(), input.readUnsignedShort())
                    3, 4 -> input.readInt()
                    5, 6 -> { input.readLong(); index++ } // a long or double takes two slots
                    8, 16, 19, 20 -> input.readUnsignedShort()
                    15 -> { input.readUnsignedByte(); handleTargets[index] = input.readUnsignedShort() }
                    17, 18 -> input.readInt()
                    else -> error("unknown constant pool tag $tag")
                }
                index++
            }
            fun className(classIndex: Int): String? = utf8[classNames[classIndex]]?.takeUnless { it.startsWith("[") }

            input.readUnsignedShort() // access flags
            val name = className(input.readUnsignedShort())!!
            val superclass = input.readUnsignedShort().takeIf { it != 0 }?.let(::className)
            val interfaces = List(input.readUnsignedShort()) { className(input.readUnsignedShort())!! }
            val declared = HashSet<String>()
            repeat(2) { table -> // fields, then methods
                repeat(input.readUnsignedShort()) {
                    input.readUnsignedShort() // access flags
                    val memberName = utf8[input.readUnsignedShort()]!!
                    val descriptor = utf8[input.readUnsignedShort()]!!
                    declared += if (table == 0) memberName else memberName + descriptor
                    repeat(input.readUnsignedShort()) { input.skipAttribute() }
                }
            }
            val bootstraps = HashSet<Int>()
            repeat(input.readUnsignedShort()) {
                val attribute = utf8[input.readUnsignedShort()]
                val length = input.readLength()
                if (attribute != "BootstrapMethods") {
                    input.skipNBytes(length)
                } else {
                    repeat(input.readUnsignedShort()) {
                        bootstraps += handleTargets[input.readUnsignedShort()]
                        repeat(input.readUnsignedShort()) { input.readUnsignedShort() } // static arguments
                    }
                }
            }

            val refs = mutableListOf<Ref>()
            classNames.forEachIndexed { i, _ -> if (classNames[i] != 0) className(i)?.let { refs += Ref(it, null) } }
            memberRefs.forEach { (poolIndex, classIndex, nameAndTypeIndex, tag) ->
                val owner = className(classIndex) ?: return@forEach
                val (nameIndex, descriptorIndex) = nameAndTypes[nameAndTypeIndex]!!
                val memberName = utf8[nameIndex]!!
                refs += Ref(owner, if (tag == 9) memberName else memberName + utf8[descriptorIndex], poolIndex in bootstraps)
            }
            return ClassFile(entry, name, listOfNotNull(superclass) + interfaces, declared, refs)
        }
    }
}

private fun DataInputStream.readLength(): Long = readInt().toUInt().toLong()

private fun DataInputStream.skipAttribute() {
    readUnsignedShort() // attribute name
    skipNBytes(readLength())
}
