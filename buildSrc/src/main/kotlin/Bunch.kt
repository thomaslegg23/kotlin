/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

import org.gradle.api.Project

interface BunchPredicate {
    fun matches(bunch: Bunch): Boolean

    operator fun invoke(block: () -> Unit): Any? {
        if (matches(BunchConfigurator.currentBunch)) {
            block()
            return emptyList()
        }

        return null
    }
}

val BunchPredicate.not: BunchPredicate get() = object : BunchPredicate {
    override fun matches(bunch: Bunch) = !this@not.matches(bunch)
}

fun BunchPredicate.or(other: BunchPredicate): BunchPredicate = object : BunchPredicate {
    override fun matches(bunch: Bunch) = this@or.matches(bunch) || other.matches(bunch)
}

enum class Platform : BunchPredicate {
    P173, P181, P182, P183;

    val version: Int = name.drop(1).toInt()

    override fun matches(bunch: Bunch) = bunch.platform == this

    companion object {
        operator fun get(version: Int): Platform {
            return Platform.values().firstOrNull { it.version == version }
                ?: error("Can't find platform $version")
        }
    }
}

enum class Bunch(val platform: Platform) : BunchPredicate {
    IJ173(Platform.P173),
    IJ181(Platform.P181),
    IJ182(Platform.P182),
    IJ183(Platform.P183),

    AS31(Platform.P173),
    AS32(Platform.P181),
    AS33(Platform.P182);

    val kind = Kind.values().first { it.shortName == name.take(2) }
    val version = name.dropWhile { !it.isDigit() }.toInt()

    override fun matches(bunch: Bunch) = bunch == this

    enum class Kind(val shortName: String) {
        AndroidStudio("AS"), IntelliJ("IJ")
    }

    companion object {
        val IJ: BunchPredicate = BunchKindPredicate(Kind.IntelliJ)
        val AS: BunchPredicate = BunchKindPredicate(Kind.AndroidStudio)
    }
}

val Platform.orHigher get() = object : BunchPredicate {
    override fun matches(bunch: Bunch) = bunch.platform.version >= version
}

val Platform.orLower get() = object : BunchPredicate {
    override fun matches(bunch: Bunch) = bunch.platform.version <= version
}

val Bunch.orHigher get() = object : BunchPredicate {
    override fun matches(bunch: Bunch) = bunch.kind == kind && bunch.version >= version
}

val Bunch.orLower get() = object : BunchPredicate {
    override fun matches(bunch: Bunch) = bunch.kind == kind && bunch.version <= version
}

object BunchConfigurator {
    lateinit var currentBunch: Bunch

    fun setCurrentBunch(project: Project) {
        val platformVersion = project.rootProject.extensions.extraProperties["versions.platform"].toString()
        val bunchName = if (platformVersion.startsWith("AS")) platformVersion else "IJ$platformVersion"
        currentBunch = Bunch.valueOf(bunchName)
    }
}

private class BunchKindPredicate(val kind: Bunch.Kind) : BunchPredicate {
    override fun matches(bunch: Bunch) = bunch.kind == kind
}