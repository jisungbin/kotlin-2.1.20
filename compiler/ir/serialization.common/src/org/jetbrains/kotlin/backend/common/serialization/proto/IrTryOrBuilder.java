// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: compiler/ir/serialization.common/src/KotlinIr.proto

package org.jetbrains.kotlin.backend.common.serialization.proto;

import org.jetbrains.kotlin.protobuf.MessageLiteOrBuilder;

import java.util.List;

public interface IrTryOrBuilder extends
        // @@protoc_insertion_point(interface_extends:org.jetbrains.kotlin.backend.common.serialization.proto.IrTry)
        MessageLiteOrBuilder {

    /**
     * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.IrExpression result = 1;</code>
     */
    boolean hasResult();

    /**
     * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.IrExpression result = 1;</code>
     */
    IrExpression getResult();

    /**
     * <code>repeated .org.jetbrains.kotlin.backend.common.serialization.proto.IrStatement catch = 2;</code>
     */
    List<IrStatement>
    getCatchList();

    /**
     * <code>repeated .org.jetbrains.kotlin.backend.common.serialization.proto.IrStatement catch = 2;</code>
     */
    IrStatement getCatch(int index);

    /**
     * <code>repeated .org.jetbrains.kotlin.backend.common.serialization.proto.IrStatement catch = 2;</code>
     */
    int getCatchCount();

    /**
     * <code>optional .org.jetbrains.kotlin.backend.common.serialization.proto.IrExpression finally = 3;</code>
     */
    boolean hasFinally();

    /**
     * <code>optional .org.jetbrains.kotlin.backend.common.serialization.proto.IrExpression finally = 3;</code>
     */
    IrExpression getFinally();
}
