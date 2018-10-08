/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.scripting.idea.plugin

import com.intellij.openapi.extensions.Extensions
import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.idea.core.script.ScriptDefinitionContributor
import org.jetbrains.kotlin.idea.core.script.loadDefinitionsFromTemplates
import org.jetbrains.kotlin.script.KotlinScriptDefinition
import kotlin.script.experimental.intellij.ScriptDefinitionsProvider

class BridgeScriptDefinitionsContributor(private val project: Project) : ScriptDefinitionContributor {
    override val id: String = "BridgeScriptDefinitionsContributor"

    override fun getDefinitions(): List<KotlinScriptDefinition> {
        val extensions = Extensions.getArea(project).getExtensionPoint(ScriptDefinitionsProvider.EP_NAME).extensions
        return extensions.flatMap { provider ->
            val explicitClasses = provider.getDefinitionClasses().toList()
            val classPath = provider.getDeifinitionsClassPath().toList()
            val explicitDefinitions =
                if (explicitClasses.isNotEmpty()) loadDefinitionsFromTemplates(
                    explicitClasses, classPath
                    //, templatesProvider.environment.orEmpty(), templatesProvider.additionalResolverClasspath
                )
                else emptyList()
            val discoveredDefinitions = emptyList<KotlinScriptDefinition>() // TODO
            explicitDefinitions + discoveredDefinitions
        }
    }
}
