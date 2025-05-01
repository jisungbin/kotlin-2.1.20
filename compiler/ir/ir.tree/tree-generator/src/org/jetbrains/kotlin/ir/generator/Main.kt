/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.generator

import java.io.File
import org.jetbrains.kotlin.generators.tree.printer.TreeGenerator
import org.jetbrains.kotlin.ir.generator.model.Element
import org.jetbrains.kotlin.ir.generator.print.DeepCopyIrTreeWithSymbolsPrinter
import org.jetbrains.kotlin.ir.generator.print.ElementPrinter
import org.jetbrains.kotlin.ir.generator.print.ImplementationPrinter
import org.jetbrains.kotlin.ir.generator.print.LegacyVisitorVoidPrinter
import org.jetbrains.kotlin.ir.generator.print.TransformerPrinter
import org.jetbrains.kotlin.ir.generator.print.TransformerVoidPrinter
import org.jetbrains.kotlin.ir.generator.print.TypeVisitorPrinter
import org.jetbrains.kotlin.ir.generator.print.TypeVisitorVoidPrinter
import org.jetbrains.kotlin.ir.generator.print.VisitorPrinter
import org.jetbrains.kotlin.ir.generator.print.VisitorVoidPrinter
import org.jetbrains.kotlin.ir.generator.print.symbol.DeclaredSymbolRemapperInterfacePrinter
import org.jetbrains.kotlin.ir.generator.print.symbol.ReferencedSymbolRemapperInterfacePrinter
import org.jetbrains.kotlin.ir.generator.print.symbol.SymbolImplementationPrinter
import org.jetbrains.kotlin.ir.generator.print.symbol.SymbolPrinter
import org.jetbrains.kotlin.ir.generator.print.symbol.SymbolRemapperInterfacePrinter
import org.jetbrains.kotlin.ir.generator.print.symbol.printSymbolRemapper
import org.jetbrains.kotlin.utils.bind

const val BASE_PACKAGE = "org.jetbrains.kotlin.ir"

internal const val TREE_GENERATOR_README = "compiler/ir/ir.tree/tree-generator/ReadMe.md"

typealias Model = org.jetbrains.kotlin.generators.tree.Model<Element>

fun main(args: Array<String>) {
  val generationPath = args.firstOrNull()?.let { File(it) }
    ?: File("compiler/ir/ir.tree/gen").canonicalFile

  val symbolModel = IrSymbolTree.build()
  val model = IrTree.build()
  TreeGenerator(generationPath, TREE_GENERATOR_README).run {
    generateTree(
      model,
      elementBaseType,
      ::ElementPrinter,
      listOf(
        legacyVisitorType to ::VisitorPrinter,
        legacyVisitorVoidType to ::LegacyVisitorVoidPrinter,
        legacyTransformerType to ::TransformerPrinter.bind(model.rootElement),
        irVisitorVoidType to ::VisitorVoidPrinter,
        elementTransformerVoidType to ::TransformerVoidPrinter,
        typeVisitorType to ::TypeVisitorPrinter.bind(model.rootElement),
        typeVisitorVoidType to ::TypeVisitorVoidPrinter.bind(model.rootElement),
        deepCopyIrTreeWithSymbolsType to ::DeepCopyIrTreeWithSymbolsPrinter
      ),
      ImplementationConfigurator,
      createImplementationPrinter = ::ImplementationPrinter,
      enableBaseTransformerTypeDetection = false,
      addFiles = {
        add(printSymbolRemapper(generationPath, model, declaredSymbolRemapperType, ::DeclaredSymbolRemapperInterfacePrinter))
        add(printSymbolRemapper(generationPath, model, referencedSymbolRemapperType, ::ReferencedSymbolRemapperInterfacePrinter))
        add(printSymbolRemapper(generationPath, model, symbolRemapperType, ::SymbolRemapperInterfacePrinter))
      }
    )

    generateTree(
      symbolModel,
      pureAbstractElement = null,
      ::SymbolPrinter.bind(model),
      createVisitorPrinters = emptyList(),
      SymbolImplementationConfigurator,
      createImplementationPrinter = ::SymbolImplementationPrinter,
      enableBaseTransformerTypeDetection = false,
      putElementsInSingleFile = Packages.symbols to "IrSymbol",
      putImplementationsInSingleFile = Packages.symbolsImpl to "IrSymbolImpl",
    )
  }
}
