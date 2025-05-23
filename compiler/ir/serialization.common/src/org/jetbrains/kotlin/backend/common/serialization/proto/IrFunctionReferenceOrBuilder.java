// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: compiler/ir/serialization.common/src/KotlinIr.proto

package org.jetbrains.kotlin.backend.common.serialization.proto;

import org.jetbrains.kotlin.protobuf.MessageLiteOrBuilder;

public interface IrFunctionReferenceOrBuilder extends
        // @@protoc_insertion_point(interface_extends:org.jetbrains.kotlin.backend.common.serialization.proto.IrFunctionReference)
        MessageLiteOrBuilder {

    /**
     * <code>required int64 symbol = 1;</code>
     */
    boolean hasSymbol();

    /**
     * <code>required int64 symbol = 1;</code>
     */
    long getSymbol();

    /**
     * <code>optional int32 origin_name = 2;</code>
     */
    boolean hasOriginName();

    /**
     * <code>optional int32 origin_name = 2;</code>
     */
    int getOriginName();

    /**
     * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.MemberAccessCommon member_access = 3;</code>
     */
    boolean hasMemberAccess();

    /**
     * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.MemberAccessCommon member_access = 3;</code>
     */
    MemberAccessCommon getMemberAccess();

    /**
     * <code>optional int64 reflection_target_symbol = 4;</code>
     */
    boolean hasReflectionTargetSymbol();

    /**
     * <code>optional int64 reflection_target_symbol = 4;</code>
     */
    long getReflectionTargetSymbol();
}
