// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: compiler/ir/serialization.common/src/KotlinIr.proto

package org.jetbrains.kotlin.backend.common.serialization.proto;

import org.jetbrains.kotlin.protobuf.MessageLiteOrBuilder;

public interface IrEnumEntryOrBuilder extends
        // @@protoc_insertion_point(interface_extends:org.jetbrains.kotlin.backend.common.serialization.proto.IrEnumEntry)
        MessageLiteOrBuilder {

    /**
     * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.IrDeclarationBase base = 1;</code>
     */
    boolean hasBase();

    /**
     * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.IrDeclarationBase base = 1;</code>
     */
    IrDeclarationBase getBase();

    /**
     * <code>required int32 name = 2;</code>
     */
    boolean hasName();

    /**
     * <code>required int32 name = 2;</code>
     */
    int getName();

    /**
     * <code>optional int32 initializer = 3;</code>
     */
    boolean hasInitializer();

    /**
     * <code>optional int32 initializer = 3;</code>
     */
    int getInitializer();

    /**
     * <code>optional .org.jetbrains.kotlin.backend.common.serialization.proto.IrClass corresponding_class = 4;</code>
     */
    boolean hasCorrespondingClass();

    /**
     * <code>optional .org.jetbrains.kotlin.backend.common.serialization.proto.IrClass corresponding_class = 4;</code>
     */
    IrClass getCorrespondingClass();
}
