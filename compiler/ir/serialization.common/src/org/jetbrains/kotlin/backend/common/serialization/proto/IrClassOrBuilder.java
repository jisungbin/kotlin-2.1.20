// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: compiler/ir/serialization.common/src/KotlinIr.proto

package org.jetbrains.kotlin.backend.common.serialization.proto;

import org.jetbrains.kotlin.protobuf.MessageLiteOrBuilder;

import java.util.List;

public interface IrClassOrBuilder extends
        // @@protoc_insertion_point(interface_extends:org.jetbrains.kotlin.backend.common.serialization.proto.IrClass)
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
     * <code>optional .org.jetbrains.kotlin.backend.common.serialization.proto.IrValueParameter this_receiver = 3;</code>
     */
    boolean hasThisReceiver();

    /**
     * <code>optional .org.jetbrains.kotlin.backend.common.serialization.proto.IrValueParameter this_receiver = 3;</code>
     */
    IrValueParameter getThisReceiver();

    /**
     * <code>repeated .org.jetbrains.kotlin.backend.common.serialization.proto.IrTypeParameter type_parameter = 4;</code>
     */
    List<IrTypeParameter>
    getTypeParameterList();

    /**
     * <code>repeated .org.jetbrains.kotlin.backend.common.serialization.proto.IrTypeParameter type_parameter = 4;</code>
     */
    IrTypeParameter getTypeParameter(int index);

    /**
     * <code>repeated .org.jetbrains.kotlin.backend.common.serialization.proto.IrTypeParameter type_parameter = 4;</code>
     */
    int getTypeParameterCount();

    /**
     * <code>repeated .org.jetbrains.kotlin.backend.common.serialization.proto.IrDeclaration declaration = 5;</code>
     */
    List<IrDeclaration>
    getDeclarationList();

    /**
     * <code>repeated .org.jetbrains.kotlin.backend.common.serialization.proto.IrDeclaration declaration = 5;</code>
     */
    IrDeclaration getDeclaration(int index);

    /**
     * <code>repeated .org.jetbrains.kotlin.backend.common.serialization.proto.IrDeclaration declaration = 5;</code>
     */
    int getDeclarationCount();

    /**
     * <code>repeated int32 super_type = 6 [packed = true];</code>
     */
    List<Integer> getSuperTypeList();

    /**
     * <code>repeated int32 super_type = 6 [packed = true];</code>
     */
    int getSuperTypeCount();

    /**
     * <code>repeated int32 super_type = 6 [packed = true];</code>
     */
    int getSuperType(int index);

    /**
     * <code>optional .org.jetbrains.kotlin.backend.common.serialization.proto.IrInlineClassRepresentation inline_class_representation = 7;</code>
     */
    boolean hasInlineClassRepresentation();

    /**
     * <code>optional .org.jetbrains.kotlin.backend.common.serialization.proto.IrInlineClassRepresentation inline_class_representation = 7;</code>
     */
    IrInlineClassRepresentation getInlineClassRepresentation();

    /**
     * <code>optional .org.jetbrains.kotlin.backend.common.serialization.proto.IrMultiFieldValueClassRepresentation multi_field_value_class_representation = 9;</code>
     */
    boolean hasMultiFieldValueClassRepresentation();

    /**
     * <code>optional .org.jetbrains.kotlin.backend.common.serialization.proto.IrMultiFieldValueClassRepresentation multi_field_value_class_representation = 9;</code>
     */
    IrMultiFieldValueClassRepresentation getMultiFieldValueClassRepresentation();

    /**
     * <code>repeated int64 sealed_subclass = 8 [packed = true];</code>
     */
    List<Long> getSealedSubclassList();

    /**
     * <code>repeated int64 sealed_subclass = 8 [packed = true];</code>
     */
    int getSealedSubclassCount();

    /**
     * <code>repeated int64 sealed_subclass = 8 [packed = true];</code>
     */
    long getSealedSubclass(int index);
}
