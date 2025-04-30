/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.types

import org.jetbrains.kotlin.builtins.functions.FunctionTypeKind
import org.jetbrains.kotlin.builtins.functions.FunctionTypeKindExtractor
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.FirSessionComponent
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

abstract class FirFunctionTypeKindService : FirSessionComponent {
    protected abstract val extractor: FunctionTypeKindExtractor

    fun getKindByClassNamePrefix(packageFqName: FqName, className: String): FunctionTypeKind? {
        return extractor.getFunctionalClassKindWithArity(packageFqName, className)?.kind
    }

    fun hasKindWithSpecificPackage(packageFqName: FqName): Boolean {
        return extractor.hasKindWithSpecificPackage(packageFqName)
    }

    /**
     * Returns all package names for which [getKindByClassNamePrefix] may return a [FunctionTypeKind].
     */
    fun getFunctionKindPackageNames(): Set<FqName> = extractor.getFunctionKindPackageNames()

    /**
     * Whether [getKindByClassNamePrefix] may return a [FunctionTypeKind] added by a compiler plugin.
     */
    fun hasExtensionKinds(): Boolean = extractor.hasExtensionKinds()

    abstract fun extractSingleSpecialKindForFunction(functionSymbol: FirFunctionSymbol<*>): FunctionTypeKind?
    abstract fun extractAllSpecialKindsForFunction(functionSymbol: FirFunctionSymbol<*>): List<FunctionTypeKind>
    abstract fun extractAllSpecialKindsForFunctionTypeRef(typeRef: FirFunctionTypeRef): List<FunctionTypeKind>

    // extension kind: 컴파일러 플러그인으로 추가된 FunctionTypeKind
    // 근데 extract~~SpecialKindForFunction으로도 extension kind를 조회하고 있음!!!
    // 함수 이름에 속지 않기.
    abstract fun extractSingleExtensionKindForDeserializedConeType(classId: ClassId, annotations: List<FirAnnotation>): FunctionTypeKind?
}

val FirSession.functionTypeService: FirFunctionTypeKindService by FirSession.sessionComponentAccessor()
