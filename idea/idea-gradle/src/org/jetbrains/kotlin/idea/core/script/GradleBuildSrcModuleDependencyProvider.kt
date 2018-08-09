/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.core.script

import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.idea.configuration.buildSrcModuleProperty
import org.jetbrains.kotlin.idea.core.script.dependencies.ScriptRelatedModulesProvider

class GradleBuildSrcModuleDependencyProvider : ScriptRelatedModulesProvider() {
    override fun getRelatedModules(file: VirtualFile, project: Project): List<Module> {
        return ModuleManager.getInstance(project).modules.filter { it.buildSrcModuleProperty == true }
    }
}