/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.kapt3

import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptorWithVisibility
import org.jetbrains.kotlin.resolve.DeclarationSignatureAnonymousTypeTransformer
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.DescriptorUtils.isAnonymousObject
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeProjectionImpl
import org.jetbrains.kotlin.types.replace
import org.jetbrains.kotlin.types.typeUtil.builtIns
import org.jetbrains.kotlin.types.typeUtil.supertypes

class KaptAnonymousTypeTransformer : DeclarationSignatureAnonymousTypeTransformer {
    override fun transformAnonymousType(descriptor: DeclarationDescriptorWithVisibility, type: KotlinType): KotlinType? {
        if (DescriptorUtils.isLocal(descriptor))
            return type

        return convertPossiblyAnonymousType(type)
    }

    private fun convertPossiblyAnonymousType(type: KotlinType): KotlinType {
        val declaration = type.constructor.declarationDescriptor as? ClassDescriptor ?: return type

        if (KotlinBuiltIns.isArray(type)) {
            val elementTypeProjection = type.arguments.singleOrNull()
            if (elementTypeProjection != null && !elementTypeProjection.isStarProjection) {
                return type.builtIns.getArrayType(
                    elementTypeProjection.projectionKind,
                    convertPossiblyAnonymousType(elementTypeProjection.type)
                )
            }
        }

        val actualType = when {
            isAnonymousObject(declaration) -> {
                val possiblyAnonymousSupertype = type.supertypes().firstOrNull() ?: return type.builtIns.anyType
                convertPossiblyAnonymousType(possiblyAnonymousSupertype)
            }
            else -> type
        }

        if (actualType.arguments.isEmpty()) return actualType

        val arguments = actualType.arguments.map { typeArg ->
            if (typeArg.isStarProjection)
                return@map typeArg

            TypeProjectionImpl(typeArg.projectionKind, convertPossiblyAnonymousType(typeArg.type))
        }

        return actualType.replace(newArguments = arguments)
    }
}