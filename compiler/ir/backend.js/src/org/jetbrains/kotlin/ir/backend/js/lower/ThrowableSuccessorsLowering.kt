/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.atMostOne
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.backend.js.ir.JsIrBuilder
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrDelegatingConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.types.makeNotNull
import org.jetbrains.kotlin.ir.util.isThrowable
import org.jetbrains.kotlin.ir.visitors.IrElementTransformer
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.utils.DFS

class ThrowableSuccessorsLowering(context: JsIrBackendContext) : FileLoweringPass {
    private val unitType = context.irBuiltIns.unitType
    private val nothingType = context.irBuiltIns.nothingNType
    private val stringType = context.irBuiltIns.stringType

    private val propertyGetter = context.intrinsics.jsGetJSField.symbol
    private val propertySetter = context.intrinsics.jsSetJSField.symbol

    private val messageName = JsIrBuilder.buildString(stringType, "message")
    private val causeName = JsIrBuilder.buildString(stringType, "cause")
    private val nameName = JsIrBuilder.buildString(stringType, "name")

    private val throwableClass = context.symbolTable.referenceClass(
        context.getClass(JsIrBackendContext.KOTLIN_PACKAGE_FQN.child(Name.identifier("Throwable")))).owner
    private val throwableConstructors = throwableClass.declarations.filterIsInstance<IrConstructor>()

    private val defaultCtor = throwableConstructors.single { it.valueParameters.size == 0 }
    private val toString =
        throwableClass.declarations.filterIsInstance<IrSimpleFunction>().single { it.name == Name.identifier("toString") }

    private val messageGetter =
        throwableClass.declarations.filterIsInstance<IrFunction>().atMostOne { it.name == Name.special("<get-message>") }
            ?: throwableClass.declarations.filterIsInstance<IrProperty>().atMostOne { it.name == Name.identifier("message") }?.getter!!
    private val causeGetter =
        throwableClass.declarations.filterIsInstance<IrFunction>().atMostOne { it.name == Name.special("<get-cause>") }
            ?: throwableClass.declarations.filterIsInstance<IrProperty>().atMostOne { it.name == Name.identifier("cause") }?.getter!!

    private val captureStackFunction = context.symbolTable.referenceSimpleFunction(context.getInternalFunctions("captureStack").single())

    override fun lower(irFile: IrFile) {
        irFile.transformChildren(ThrowableConstructorTransformer(), irFile)
        irFile.transformChildrenVoid(ThrowablePropertiesTransformer())
    }

    inner class ThrowableConstructorTransformer : IrElementTransformer<IrDeclarationParent> {
        override fun visitFunction(declaration: IrFunction, data: IrDeclarationParent) = super.visitFunction(declaration, declaration)

        override fun visitDelegatingConstructorCall(expression: IrDelegatingConstructorCall, data: IrDeclarationParent): IrElement {
            if (expression.symbol.owner !in throwableConstructors) return super.visitDelegatingConstructorCall(expression, data)

            expression.transformChildren(this, data)

            val (messageArg, causeArg, paramStatements) = extractConstructorParameters(expression, data)

            val newDelegation = expression.run {
                IrDelegatingConstructorCallImpl(startOffset, endOffset, type, defaultCtor.symbol, defaultCtor.descriptor)
            }

            val klass = (data as IrConstructor).parent as IrClass

            val receiver = IrGetValueImpl(expression.startOffset, expression.endOffset, klass.thisReceiver!!.symbol)

            val nameArg = JsIrBuilder.buildString(stringType, klass.name.asString())

            val fillStatements = fillThrowableInstance(expression, receiver, messageArg, causeArg, nameArg)

            return expression.run {
                IrCompositeImpl(startOffset, endOffset, type, origin, paramStatements + newDelegation + fillStatements)
            }
        }

        override fun visitCall(expression: IrCall, data: IrDeclarationParent): IrElement {
            if (expression.symbol.owner !in throwableConstructors) return super.visitCall(expression, data)

            expression.transformChildren(this, data)

            val (messageArg, causeArg, paramStatements) = extractConstructorParameters(expression, data)

            val newInstance = expression.run {
                IrCallImpl(startOffset, endOffset, type, defaultCtor.symbol, defaultCtor.descriptor)
            }

            val newVar = JsIrBuilder.buildVar(newInstance.type, data, initializer = newInstance)

            val receiver = JsIrBuilder.buildGetValue(newVar.symbol)

            val nameArg = JsIrBuilder.buildString(stringType, "Throwable")

            val fillStatements = fillThrowableInstance(expression, receiver, messageArg, causeArg, nameArg)

            return expression.run {
                IrCompositeImpl(startOffset, endOffset, type, origin, paramStatements + newVar + fillStatements + receiver)
            }
        }

        private fun fillThrowableInstance(
            expression: IrFunctionAccessExpression,
            receiver: IrExpression,
            messageArg: IrExpression,
            causeArg: IrExpression,
            name: IrExpression
        ): List<IrStatement> {
            val setMessage = IrCallImpl(expression.startOffset, expression.endOffset, unitType, propertySetter).apply {
                putValueArgument(0, receiver)
                putValueArgument(1, messageName)
                putValueArgument(2, messageArg)
            }
            val setCause = IrCallImpl(expression.startOffset, expression.endOffset, unitType, propertySetter).apply {
                putValueArgument(0, receiver)
                putValueArgument(1, causeName)
                putValueArgument(2, causeArg)
            }
            val setName = IrCallImpl(expression.startOffset, expression.endOffset, unitType, propertySetter).apply {
                putValueArgument(0, receiver)
                putValueArgument(1, nameName)
                putValueArgument(2, name)
            }

            val setStackTrace = IrCallImpl(expression.startOffset, expression.endOffset, unitType, captureStackFunction).apply {
                putValueArgument(0, receiver)
            }

            return listOf(setMessage, setCause, setName, setStackTrace)
        }

        private fun extractConstructorParameters(
            expression: IrFunctionAccessExpression,
            parent: IrDeclarationParent
        ): Triple<IrExpression, IrExpression, List<IrStatement>> {
            val nullValue = IrConstImpl.constNull(expression.startOffset, expression.endOffset, nothingType)
            // Wrap parameters into variables to keep original evaluation order
            return when {
                expression.valueArgumentsCount == 0 -> Triple(nullValue, nullValue, emptyList())
                expression.valueArgumentsCount == 2 -> {
                    val msg = expression.getValueArgument(0)!!
                    val cus = expression.getValueArgument(1)!!
                    val irValM = JsIrBuilder.buildVar(msg.type, parent, initializer = msg)
                    val irValC = JsIrBuilder.buildVar(cus.type, parent, initializer = cus)
                    Triple(JsIrBuilder.buildGetValue(irValM.symbol), JsIrBuilder.buildGetValue(irValC.symbol), listOf(irValM, irValC))
                }
                else -> {
                    val arg = expression.getValueArgument(0)!!
                    val irVal = JsIrBuilder.buildVar(arg.type, parent, initializer = arg)
                    val argValue = JsIrBuilder.buildGetValue(irVal.symbol)
                    when {
                        arg.type.makeNotNull().isThrowable() -> {
                            val messageExpr = JsIrBuilder.buildCall(toString.symbol, stringType).apply {
                                dispatchReceiver = argValue
                            }
                            Triple(messageExpr, argValue, listOf(irVal))
                        }
                        else -> Triple(argValue, nullValue, listOf(irVal))
                    }
                }
            }
        }
    }

    inner class ThrowablePropertiesTransformer : IrElementTransformerVoid() {
        override fun visitCall(expression: IrCall): IrExpression {
            expression.transformChildrenVoid(this)
            // TODO: should we something special here for <super> case
            val owner = expression.symbol.owner
            return when {
                owner.isSameOrFakeOverride(messageGetter) -> {
                    IrCallImpl(expression.startOffset, expression.endOffset, expression.type, propertyGetter).apply {
                        putValueArgument(0, expression.dispatchReceiver!!)
                        putValueArgument(1, messageName)
                    }
                }
                owner.isSameOrFakeOverride(causeGetter) -> {
                    IrCallImpl(expression.startOffset, expression.endOffset, expression.type, propertyGetter).apply {
                        putValueArgument(0, expression.dispatchReceiver!!)
                        putValueArgument(1, causeName)
                    }
                }
                else -> expression
            }
        }
    }

    private fun IrFunction.isSameOrFakeOverride(base: IrFunction): Boolean {
        // TODO: fix issue between LazyIr and multiple declarations per descriptor
        if (origin != IrDeclarationOrigin.FAKE_OVERRIDE) return descriptor == base.descriptor
        val simpleFunction = this as? IrSimpleFunction ?: return false

        var directFakeOverride = origin == IrDeclarationOrigin.FAKE_OVERRIDE
        return DFS.ifAny(simpleFunction.overriddenSymbols, { it.owner.overriddenSymbols }, {
            val result = directFakeOverride && it.descriptor == base.descriptor
            directFakeOverride = directFakeOverride && (it.owner.origin == IrDeclarationOrigin.FAKE_OVERRIDE)
            result
        })
    }
}