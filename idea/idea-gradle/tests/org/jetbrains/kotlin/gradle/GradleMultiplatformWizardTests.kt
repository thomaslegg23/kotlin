/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle

import com.intellij.ide.highlighter.ModuleFileType
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.diagnostic.DefaultLogger
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.EmptyModuleType
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.ui.configuration.DefaultModulesProvider
import com.intellij.openapi.util.io.FileUtil
import org.jetbrains.kotlin.idea.codeInsight.gradle.GradleImportingTestCase
import org.jetbrains.kotlin.idea.configuration.KotlinGradleAbstractMultiplatformModuleBuilder
import org.jetbrains.kotlin.idea.configuration.KotlinGradleWebMultiplatformModuleBuilder
import org.jetbrains.kotlin.utils.PrintingLogger
import org.junit.Test
import org.junit.runners.Parameterized

class GradleMultiplatformWizardTests : GradleImportingTestCase() {
    private fun testImportFromBuilder(builder: KotlinGradleAbstractMultiplatformModuleBuilder) {
        var exception: Throwable? = null
        var success = false
        runInEdt {
            ApplicationManager.getApplication().runWriteAction {
                try {
                    // Configure project & builder
                    val name = "test"
                    val nameWithExtension = name + ModuleFileType.DOT_DEFAULT_EXTENSION
                    builder.name = name
                    val projectPath = FileUtil.toSystemIndependentName(projectPath)
                    builder.moduleFilePath = "$projectPath/$nameWithExtension"
                    builder.contentEntryPath = projectPath
                    val context = WizardContext(myProject, null)
                    val module = ModuleManager.getInstance(myProject).newModule(nameWithExtension, EmptyModuleType.getInstance().id)
                    val modulesProvider = DefaultModulesProvider.createForProject(myProject)
                    context.modulesProvider = modulesProvider
                    builder.createWizardSteps(context, modulesProvider)

                    // Temporary workaround for duplicated bundled template
                    class PrintingFactory : Logger.Factory {
                        override fun getLoggerInstance(category: String): Logger {
                            return PrintingLogger(System.out)
                        }
                    }
                    Logger.setFactory(PrintingFactory::class.java)

                    // Use not SNAPSHOT plugin
                    // (?) Use GradleRunner as in inspection plugin tests to run skeleton project tests (?)
                    builder.setupModule(module)
                    importProject()
                    success = true
                } catch (e: Throwable) {
                    exception = e
                }
            }
        }
        while (!success && exception == null) {
            Thread.sleep(1000L)
        }
        throw exception ?: return
    }

    @Test
    fun testWeb() {
        testImportFromBuilder(KotlinGradleWebMultiplatformModuleBuilder())
    }

    companion object {
        @Parameterized.Parameters(name = "{index}: with Gradle-{0}")
        @Throws(Throwable::class)
        @JvmStatic
        fun data() = listOf(arrayOf("4.7"))
    }
}