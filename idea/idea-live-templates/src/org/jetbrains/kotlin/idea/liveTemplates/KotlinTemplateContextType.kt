/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.liveTemplates

import com.intellij.codeInsight.template.EverywhereContextType
import com.intellij.codeInsight.template.TemplateContextType
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import org.jetbrains.annotations.NonNls
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*

abstract class KotlinTemplateContextType private constructor(
    @NonNls id: String,
    presentableName: String,
    baseContextType: java.lang.Class<out TemplateContextType>?
) : TemplateContextType(id, presentableName, baseContextType) {

    protected open val isCommentInContext: Boolean
        get() = false

    override fun isInContext(file: PsiFile, offset: Int): Boolean {
        if (!PsiUtilCore.getLanguageAtOffset(file, offset).isKindOf(KotlinLanguage.INSTANCE)) {
            return false
        }

        var element = file.findElementAt(offset)
        if (element == null) {
            element = file.findElementAt(offset - 1)
        }

        if (element is PsiWhiteSpace) {
            return false
        } else if (PsiTreeUtil.getParentOfType(element, PsiComment::class.java, false) != null) {
            return isCommentInContext
        } else if (PsiTreeUtil.getParentOfType(element, KtPackageDirective::class.java) != null || PsiTreeUtil.getParentOfType(
                element,
                KtImportDirective::class.java
            ) != null
        ) {
            return false
        } else if (element is LeafPsiElement) {
            val elementType = element.elementType
            if (elementType === KtTokens.IDENTIFIER) {
                val parent = element.parent
                if (parent is KtReferenceExpression) {
                    val parentOfParent = parent.getParent()
                    val qualifiedExpression = PsiTreeUtil.getParentOfType(element, KtQualifiedExpression::class.java)
                    if (qualifiedExpression != null && qualifiedExpression.selectorExpression === parentOfParent) {
                        return false
                    }
                }
            }
        }

        return element != null && isInContext(element)
    }

    protected abstract fun isInContext(element: PsiElement): Boolean

    class Generic : KotlinTemplateContextType("KOTLIN", KotlinLanguage.NAME, EverywhereContextType::class.java) {

        override val isCommentInContext: Boolean
            get() = true

        override fun isInContext(element: PsiElement): Boolean {
            return true
        }
    }

    class TopLevel : KotlinTemplateContextType("KOTLIN_TOPLEVEL", "Top-level", Generic::class.java) {

        override fun isInContext(element: PsiElement): Boolean {
            var e: PsiElement? = element
            while (e != null) {
                if (e is KtModifierList) {
                    // skip property/function/class or object which is owner of modifier list
                    e = e.parent
                    if (e != null) {
                        e = e.parent
                    }
                    continue
                }
                if (e is KtProperty || e is KtNamedFunction || e is KtClassOrObject) {
                    return false
                }
                if (e is KtScriptInitializer) {
                    return false
                }
                e = e.parent
            }
            return true
        }
    }

    class ObjectDeclaration : KotlinTemplateContextType("KOTLIN_OBJECT_DECLARATION", "Object declaration", Generic::class.java) {

        override fun isInContext(element: PsiElement): Boolean {
            val objectDeclaration = getParentClassOrObject(element, KtObjectDeclaration::class.java)
            return objectDeclaration != null && !objectDeclaration.isObjectLiteral()
        }
    }

    class Class : KotlinTemplateContextType("KOTLIN_CLASS", "Class", Generic::class.java) {

        override fun isInContext(element: PsiElement): Boolean {
            return getParentClassOrObject(element, KtClassOrObject::class.java) != null
        }
    }

    class Statement : KotlinTemplateContextType("KOTLIN_STATEMENT", "Statement", Generic::class.java) {

        override fun isInContext(element: PsiElement): Boolean {
            val parentStatement =
                PsiTreeUtil.findFirstParent(element) { e -> e is KtExpression && KtPsiUtil.isStatementContainer(e.getParent()) }
                    ?: return false

            // We are in the leftmost position in parentStatement
            return element.textOffset == parentStatement.textOffset
        }
    }

    class Expression : KotlinTemplateContextType("KOTLIN_EXPRESSION", "Expression", Generic::class.java) {

        override fun isInContext(element: PsiElement): Boolean {
            return (element.parent is KtExpression && element.parent !is KtConstantExpression &&
                    element.parent.parent !is KtDotQualifiedExpression
                    && element.parent !is KtParameter)
        }
    }

    class Comment : KotlinTemplateContextType("KOTLIN_COMMENT", "Comment", Generic::class.java) {

        override val isCommentInContext: Boolean
            get() = true

        override fun isInContext(element: PsiElement): Boolean {
            return false
        }
    }

    companion object {

        private fun <T : PsiElement> getParentClassOrObject(element: PsiElement, klass: java.lang.Class<out T>): T? {
            var e: PsiElement? = element
            while (e != null && !klass.isInstance(e)) {
                if (e is KtModifierList) {
                    // skip property/function/class or object which is owner of modifier list
                    e = e.parent
                    if (e != null) {
                        e = e.parent
                    }
                    continue
                }
                if (e is KtProperty || e is KtNamedFunction) {
                    return null
                }
                e = e.parent
            }


            @Suppress("UNCHECKED_CAST")
            return e as? T
        }
    }
}