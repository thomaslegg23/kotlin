/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle

import com.intellij.ide.projectWizard.NewProjectWizard
import com.intellij.ide.projectWizard.ProjectTypeStep
import com.intellij.ide.projectWizard.ProjectWizardTestCase
import com.intellij.ide.util.newProjectWizard.AbstractProjectWizard
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.Result
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.service.project.ExternalProjectRefreshCallback
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.externalSystem.settings.ExternalProjectSettings
import com.intellij.openapi.externalSystem.settings.ExternalSystemSettingsListenerAdapter
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.SimpleJavaSdkType
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.ui.configuration.ModulesProvider
import com.intellij.openapi.util.Couple
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.CharsetToolkit
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.IdeaTestUtil
import com.intellij.testFramework.UsefulTestCase
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.containers.ContainerUtilRt
import junit.framework.TestCase
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.ide.konan.gradle.KotlinGradleNativeMultiplatformModuleBuilder
import org.jetbrains.kotlin.idea.codeInsight.gradle.AbstractModelBuilderTest
import org.jetbrains.kotlin.idea.codeInsight.gradle.GradleImportingTestCase
import org.jetbrains.kotlin.idea.configuration.*
import org.jetbrains.kotlin.utils.PrintingLogger
import org.jetbrains.plugins.gradle.service.project.wizard.GradleModuleBuilder
import org.jetbrains.plugins.gradle.settings.DistributionType
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleSettings
import org.jetbrains.plugins.gradle.util.GradleConstants
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.StringWriter
import java.util.*

class GradleMultiplatformWizardAlternativeTests : ProjectWizardTestCase<AbstractProjectWizard>() {

    override fun createWizard(project: Project?, directory: File): AbstractProjectWizard {
        return NewProjectWizard(project, ModulesProvider.EMPTY_MODULES_PROVIDER, directory.path)
    }

    private fun applyOnProjectSettings(f: GradleProjectSettings.() -> Unit) {
        val systemSettings = ExternalSystemApiUtil.getSettings(myProject, externalSystemId)
        val projectSettings = GradleProjectSettings()
        projectSettings.f()
        val projects = ContainerUtilRt.newHashSet<Any>(systemSettings.getLinkedProjectsSettings())
        projects.remove(projectSettings)
        projects.add(projectSettings)
        systemSettings.setLinkedProjectsSettings(projects)
    }

    private fun testImportFromBuilder(
        builder: KotlinGradleAbstractMultiplatformModuleBuilder, nameRoot: String, metadataInside: Boolean = false
    ) {
        // Temporary workaround for duplicated bundled template
        class PrintingFactory : Logger.Factory {
            override fun getLoggerInstance(category: String): Logger {
                return PrintingLogger(System.out)
            }
        }
        Logger.setFactory(PrintingFactory::class.java)

        // For some reason with any other name it does not work (???)
        val projectName = "test${nameRoot}new"
        val project = createProject { step ->
            if (step is ProjectTypeStep) {
                TestCase.assertTrue(step.setSelectedTemplate("Kotlin", builder.presentableName))
                val steps = myWizard.sequence.selectedSteps
                TestCase.assertEquals(4, steps.size)
                val projectBuilder = myWizard.projectBuilder
                UsefulTestCase.assertInstanceOf(projectBuilder, builder::class.java)
                (projectBuilder as GradleModuleBuilder).name = projectName

                applyOnProjectSettings {
                    distributionType = DistributionType.DEFAULT_WRAPPED
                }
            }
        }
        // TODO: do not use snapshot!

        TestCase.assertEquals(projectName, project.name)
        val modules = ModuleManager.getInstance(project).modules
        TestCase.assertEquals(1, modules.size)
        val module = modules[0]
        TestCase.assertTrue(ModuleRootManager.getInstance(module).isSdkInherited)
        TestCase.assertEquals(projectName, module.name)

        val root = ProjectRootManager.getInstance(project).contentRoots[0]
        val settingsScript = VfsUtilCore.findRelativeFile("settings.gradle", root)
        TestCase.assertNotNull(settingsScript)
        TestCase.assertEquals(
            String.format("rootProject.name = '%s'\n\n", projectName) +
                    if (metadataInside) "\nenableFeaturePreview('GRADLE_METADATA')\n" else "",
            StringUtil.convertLineSeparators(VfsUtilCore.loadText(settingsScript!!))
        )

        val buildScript = VfsUtilCore.findRelativeFile("build.gradle", root)!!
        println(StringUtil.convertLineSeparators(VfsUtilCore.loadText(buildScript)))

        doImportProject()
        doTestProject()
    }

    @Throws(IOException::class)
    private fun createProjectSubFile(relativePath: String): VirtualFile {
        val f = File(myProject.basePath!!, relativePath)
        FileUtil.ensureExists(f.parentFile)
        FileUtil.ensureCanCreateFile(f)
        val created = f.createNewFile()
        if (!created) {
            throw AssertionError("Unable to create the project sub file: " + f.absolutePath)
        }
        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f)!!
    }

    private fun createProjectSubFile(relativePath: String, content: String): VirtualFile {
        val file = createProjectSubFile(relativePath)
        runWrite {
            file.setBinaryContent(content.toByteArray(CharsetToolkit.UTF8_CHARSET), file.modificationStamp, file.timeStamp)
        }
        return file
    }

    private fun runWrite(f: () -> Unit) {
        object : WriteAction<Any>() {
            override fun run(result: Result<Any>) {
                f()
            }
        }.execute()
    }

    private fun doImportProject() {
        ExternalSystemApiUtil.subscribe(
            myProject,
            GradleConstants.SYSTEM_ID,
            object : ExternalSystemSettingsListenerAdapter<ExternalProjectSettings>() {
                override fun onProjectsLinked(settings: Collection<ExternalProjectSettings>) {
                    val item = ContainerUtil.getFirstItem<Any>(settings)
                    if (item is GradleProjectSettings) {
                        item.gradleJvm = DEFAULT_SDK
                    }
                }
            })

        GradleSettings.getInstance(myProject).gradleVmOptions = "-Xmx128m -XX:MaxPermSize=64m"
        val wrapperJarFrom = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(GradleImportingTestCase.wrapperJar())!!
        val wrapperJarFromTo = createProjectSubFile("gradle/wrapper/gradle-wrapper.jar")
        runWrite {
            wrapperJarFromTo.setBinaryContent(wrapperJarFrom.contentsToByteArray())
        }
        val properties = Properties()
        properties.setProperty("distributionBase", "GRADLE_USER_HOME")
        properties.setProperty("distributionPath", "wrapper/dists")
        properties.setProperty("zipStoreBase", "GRADLE_USER_HOME")
        properties.setProperty("zipStorePath", "wrapper/dists")
        val distributionUri = AbstractModelBuilderTest.DistributionLocator().getDistributionFor(GradleVersion.version("4.7"))
        properties.setProperty("distributionUrl", distributionUri.toString())

        val writer = StringWriter()
        properties.store(writer, null)

        createProjectSubFile("gradle/wrapper/gradle-wrapper.properties", writer.toString())

        applyOnProjectSettings {
            distributionType = DistributionType.DEFAULT_WRAPPED
            externalProjectPath = myProject.basePath!!
        }

        val error = Ref.create<Couple<String>>()
        ExternalSystemUtil.refreshProjects(
            ImportSpecBuilder(myProject, externalSystemId)
                .use(ProgressExecutionMode.MODAL_SYNC)
                .callback(object : ExternalProjectRefreshCallback {
                    override fun onSuccess(externalProject: DataNode<ProjectData>?) {
                        if (externalProject == null) {
                            System.err.println("Got null External project after import")
                            return
                        }
                        ServiceManager.getService(ProjectDataManager::class.java).importData(externalProject, myProject, true)
                        println("External project was successfully imported")
                    }

                    override fun onFailure(errorMessage: String, errorDetails: String?) {
                        error.set(Couple.of(errorMessage, errorDetails))
                    }
                })
                .forceWhenUptodate()
        )

        if (!error.isNull) {
            var failureMsg = "Import failed: " + error.get().first
            if (StringUtil.isNotEmpty(error.get().second)) {
                failureMsg += "\nError details: \n" + error.get().second
            }
            TestCase.fail(failureMsg)
        }
    }

    private fun doTestProject() {
        val taskName = "test"
        val result = GradleRunner.create()
            .withProjectDir(File(myProject.basePath))
            .withArguments("--no-daemon")
            .withArguments("--info", "--stacktrace", taskName)
            // This applies classpath from pluginUnderTestMetadata
            .withPluginClasspath()
            // NB: this is necessary to apply actual plugin
            .apply { withPluginClasspath(pluginClasspath) }
            .build()
        val outcome = result.task(taskName)?.outcome
        assertEquals(TaskOutcome.SUCCESS, outcome)
    }

    @Test
    fun testMobile() {
        testImportFromBuilder(KotlinGradleMobileMultiplatformModuleBuilder(), "Mobile")
    }

    @Test
    fun testMobileShared() {
        testImportFromBuilder(KotlinGradleMobileSharedMultiplatformModuleBuilder(), "MobileShared", metadataInside = true)
    }

    @Test
    fun testNative() {
        testImportFromBuilder(KotlinGradleNativeMultiplatformModuleBuilder(), "Native")
    }

    @Test
    fun testShared() {
        testImportFromBuilder(KotlinGradleSharedMultiplatformModuleBuilder(), "Shared", metadataInside = true)
    }

    @Test
    fun testWeb() {
        testImportFromBuilder(KotlinGradleWebMultiplatformModuleBuilder(), "Web")
    }

    override fun setUp() {
        super.setUp()
        val javaHome = IdeaTestUtil.requireRealJdkHome()
        ApplicationManager.getApplication().runWriteAction {
            addSdk(SimpleJavaSdkType().createJdk(DEFAULT_SDK, javaHome))
            addSdk(SimpleJavaSdkType().createJdk("_other", javaHome))

            println("ProjectWizardTestCase.configureJdk:")
            println(Arrays.asList(*ProjectJdkTable.getInstance().allJdks))
        }
    }

    companion object {
        val externalSystemId = GradleConstants.SYSTEM_ID
    }
}