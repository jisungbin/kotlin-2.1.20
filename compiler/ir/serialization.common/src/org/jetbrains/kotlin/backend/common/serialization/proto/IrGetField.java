// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: compiler/ir/serialization.common/src/KotlinIr.proto

package org.jetbrains.kotlin.backend.common.serialization.proto;

import org.jetbrains.kotlin.protobuf.AbstractParser;
import org.jetbrains.kotlin.protobuf.ByteString;
import org.jetbrains.kotlin.protobuf.CodedInputStream;
import org.jetbrains.kotlin.protobuf.CodedOutputStream;
import org.jetbrains.kotlin.protobuf.ExtensionRegistryLite;
import org.jetbrains.kotlin.protobuf.GeneratedMessageLite;
import org.jetbrains.kotlin.protobuf.InvalidProtocolBufferException;
import org.jetbrains.kotlin.protobuf.Parser;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectStreamException;

/**
 * Protobuf type {@code org.jetbrains.kotlin.backend.common.serialization.proto.IrGetField}
 */
public final class IrGetField extends
        GeneratedMessageLite implements
        // @@protoc_insertion_point(message_implements:org.jetbrains.kotlin.backend.common.serialization.proto.IrGetField)
        IrGetFieldOrBuilder {
    public static final int FIELD_ACCESS_FIELD_NUMBER = 1;
    public static final int ORIGIN_NAME_FIELD_NUMBER = 2;
    private static final IrGetField defaultInstance;
    private static final long serialVersionUID = 0L;
    public static Parser<IrGetField> PARSER =
            new AbstractParser<IrGetField>() {
                @Override
                public IrGetField parsePartialFrom(
                        CodedInputStream input,
                        ExtensionRegistryLite extensionRegistry)
                        throws InvalidProtocolBufferException {
                    return new IrGetField(input, extensionRegistry);
                }
            };

    static {
        defaultInstance = new IrGetField(true);
        defaultInstance.initFields();
    }

    private final ByteString unknownFields;
    private int bitField0_;
    private FieldAccessCommon fieldAccess_;
    private int originName_;
    private byte memoizedIsInitialized = -1;
    private int memoizedSerializedSize = -1;

    // Use IrGetField.newBuilder() to construct.
    private IrGetField(GeneratedMessageLite.Builder builder) {
        super(builder);
        this.unknownFields = builder.getUnknownFields();
    }

    private IrGetField(boolean noInit) {
        this.unknownFields = ByteString.EMPTY;
    }

    private IrGetField(
            CodedInputStream input,
            ExtensionRegistryLite extensionRegistry)
            throws InvalidProtocolBufferException {
        initFields();
        int mutable_bitField0_ = 0;
        ByteString.Output unknownFieldsOutput =
                ByteString.newOutput();
        CodedOutputStream unknownFieldsCodedOutput =
                CodedOutputStream.newInstance(
                        unknownFieldsOutput, 1);
        try {
            boolean done = false;
            while (!done) {
                int tag = input.readTag();
                switch (tag) {
                    case 0:
                        done = true;
                        break;
                    default: {
                        if (!parseUnknownField(input, unknownFieldsCodedOutput,
                                extensionRegistry, tag)) {
                            done = true;
                        }
                        break;
                    }
                    case 10: {
                        FieldAccessCommon.Builder subBuilder = null;
                        if (((bitField0_ & 0x00000001) == 0x00000001)) {
                            subBuilder = fieldAccess_.toBuilder();
                        }
                        fieldAccess_ = input.readMessage(FieldAccessCommon.PARSER, extensionRegistry);
                        if (subBuilder != null) {
                            subBuilder.mergeFrom(fieldAccess_);
                            fieldAccess_ = subBuilder.buildPartial();
                        }
                        bitField0_ |= 0x00000001;
                        break;
                    }
                    case 16: {
                        bitField0_ |= 0x00000002;
                        originName_ = input.readInt32();
                        break;
                    }
                }
            }
        } catch (InvalidProtocolBufferException e) {
            throw e.setUnfinishedMessage(this);
        } catch (IOException e) {
            throw new InvalidProtocolBufferException(
                    e.getMessage()).setUnfinishedMessage(this);
        } finally {
            try {
                unknownFieldsCodedOutput.flush();
            } catch (IOException e) {
                // Should not happen
            } finally {
                unknownFields = unknownFieldsOutput.toByteString();
            }
            makeExtensionsImmutable();
        }
    }

    public static IrGetField getDefaultInstance() {
        return defaultInstance;
    }

    public static IrGetField parseFrom(
            ByteString data)
            throws InvalidProtocolBufferException {
        return PARSER.parseFrom(data);
    }

    public static IrGetField parseFrom(
            ByteString data,
            ExtensionRegistryLite extensionRegistry)
            throws InvalidProtocolBufferException {
        return PARSER.parseFrom(data, extensionRegistry);
    }

    public static IrGetField parseFrom(byte[] data)
            throws InvalidProtocolBufferException {
        return PARSER.parseFrom(data);
    }

    public static IrGetField parseFrom(
            byte[] data,
            ExtensionRegistryLite extensionRegistry)
            throws InvalidProtocolBufferException {
        return PARSER.parseFrom(data, extensionRegistry);
    }

    public static IrGetField parseFrom(InputStream input)
            throws IOException {
        return PARSER.parseFrom(input);
    }

    public static IrGetField parseFrom(
            InputStream input,
            ExtensionRegistryLite extensionRegistry)
            throws IOException {
        return PARSER.parseFrom(input, extensionRegistry);
    }

    public static IrGetField parseDelimitedFrom(InputStream input)
            throws IOException {
        return PARSER.parseDelimitedFrom(input);
    }

    public static IrGetField parseDelimitedFrom(
            InputStream input,
            ExtensionRegistryLite extensionRegistry)
            throws IOException {
        return PARSER.parseDelimitedFrom(input, extensionRegistry);
    }

    public static IrGetField parseFrom(
            CodedInputStream input)
            throws IOException {
        return PARSER.parseFrom(input);
    }

    public static IrGetField parseFrom(
            CodedInputStream input,
            ExtensionRegistryLite extensionRegistry)
            throws IOException {
        return PARSER.parseFrom(input, extensionRegistry);
    }

    public static Builder newBuilder() {
        return Builder.create();
    }

    public static Builder newBuilder(IrGetField prototype) {
        return newBuilder().mergeFrom(prototype);
    }

    @Override
    public IrGetField getDefaultInstanceForType() {
        return defaultInstance;
    }

    @Override
    public Parser<IrGetField> getParserForType() {
        return PARSER;
    }

    /**
     * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.FieldAccessCommon field_access = 1;</code>
     */
    @Override
    public boolean hasFieldAccess() {
        return ((bitField0_ & 0x00000001) == 0x00000001);
    }

    /**
     * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.FieldAccessCommon field_access = 1;</code>
     */
    @Override
    public FieldAccessCommon getFieldAccess() {
        return fieldAccess_;
    }

    /**
     * <code>optional int32 origin_name = 2;</code>
     */
    @Override
    public boolean hasOriginName() {
        return ((bitField0_ & 0x00000002) == 0x00000002);
    }

    /**
     * <code>optional int32 origin_name = 2;</code>
     */
    @Override
    public int getOriginName() {
        return originName_;
    }

    private void initFields() {
        fieldAccess_ = FieldAccessCommon.getDefaultInstance();
        originName_ = 0;
    }

    @Override
    public boolean isInitialized() {
        byte isInitialized = memoizedIsInitialized;
        if (isInitialized == 1) return true;
        if (isInitialized == 0) return false;

        if (!hasFieldAccess()) {
            memoizedIsInitialized = 0;
            return false;
        }
        if (!getFieldAccess().isInitialized()) {
            memoizedIsInitialized = 0;
            return false;
        }
        memoizedIsInitialized = 1;
        return true;
    }

    @Override
    public void writeTo(CodedOutputStream output)
            throws IOException {
        getSerializedSize();
        if (((bitField0_ & 0x00000001) == 0x00000001)) {
            output.writeMessage(1, fieldAccess_);
        }
        if (((bitField0_ & 0x00000002) == 0x00000002)) {
            output.writeInt32(2, originName_);
        }
        output.writeRawBytes(unknownFields);
    }

    @Override
    public int getSerializedSize() {
        int size = memoizedSerializedSize;
        if (size != -1) return size;

        size = 0;
        if (((bitField0_ & 0x00000001) == 0x00000001)) {
            size += CodedOutputStream
                    .computeMessageSize(1, fieldAccess_);
        }
        if (((bitField0_ & 0x00000002) == 0x00000002)) {
            size += CodedOutputStream
                    .computeInt32Size(2, originName_);
        }
        size += unknownFields.size();
        memoizedSerializedSize = size;
        return size;
    }

    @Override
    protected Object writeReplace()
            throws ObjectStreamException {
        return super.writeReplace();
    }

    @Override
    public Builder newBuilderForType() {
        return newBuilder();
    }

    @Override
    public Builder toBuilder() {
        return newBuilder(this);
    }

    /**
     * Protobuf type {@code org.jetbrains.kotlin.backend.common.serialization.proto.IrGetField}
     */
    public static final class Builder extends
            GeneratedMessageLite.Builder<
                    IrGetField, Builder>
            implements
            // @@protoc_insertion_point(builder_implements:org.jetbrains.kotlin.backend.common.serialization.proto.IrGetField)
            IrGetFieldOrBuilder {
        private int bitField0_;
        private FieldAccessCommon fieldAccess_ = FieldAccessCommon.getDefaultInstance();
        private int originName_;

        // Construct using org.jetbrains.kotlin.backend.common.serialization.proto.IrGetField.newBuilder()
        private Builder() {
            maybeForceBuilderInitialization();
        }

        private static Builder create() {
            return new Builder();
        }

        private void maybeForceBuilderInitialization() {
        }

        @Override
        public Builder clear() {
            super.clear();
            fieldAccess_ = FieldAccessCommon.getDefaultInstance();
            bitField0_ &= ~0x00000001;
            originName_ = 0;
            bitField0_ &= ~0x00000002;
            return this;
        }

        @Override
        public Builder clone() {
            return create().mergeFrom(buildPartial());
        }

        @Override
        public IrGetField getDefaultInstanceForType() {
            return getDefaultInstance();
        }

        @Override
        public IrGetField build() {
            IrGetField result = buildPartial();
            if (!result.isInitialized()) {
                throw newUninitializedMessageException(result);
            }
            return result;
        }

        @Override
        public IrGetField buildPartial() {
            IrGetField result = new IrGetField(this);
            int from_bitField0_ = bitField0_;
            int to_bitField0_ = 0;
            if (((from_bitField0_ & 0x00000001) == 0x00000001)) {
                to_bitField0_ |= 0x00000001;
            }
            result.fieldAccess_ = fieldAccess_;
            if (((from_bitField0_ & 0x00000002) == 0x00000002)) {
                to_bitField0_ |= 0x00000002;
            }
            result.originName_ = originName_;
            result.bitField0_ = to_bitField0_;
            return result;
        }

        @Override
        public Builder mergeFrom(IrGetField other) {
            if (other == getDefaultInstance())
                return this;
            if (other.hasFieldAccess()) {
                mergeFieldAccess(other.getFieldAccess());
            }
            if (other.hasOriginName()) {
                setOriginName(other.getOriginName());
            }
            setUnknownFields(
                    getUnknownFields().concat(other.unknownFields));
            return this;
        }

        @Override
        public boolean isInitialized() {
            if (!hasFieldAccess()) {

                return false;
            }
            if (!getFieldAccess().isInitialized()) {

                return false;
            }
            return true;
        }

        @Override
        public Builder mergeFrom(
                CodedInputStream input,
                ExtensionRegistryLite extensionRegistry)
                throws IOException {
            IrGetField parsedMessage = null;
            try {
                parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
            } catch (InvalidProtocolBufferException e) {
                parsedMessage = (IrGetField) e.getUnfinishedMessage();
                throw e;
            } finally {
                if (parsedMessage != null) {
                    mergeFrom(parsedMessage);
                }
            }
            return this;
        }

        /**
         * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.FieldAccessCommon field_access = 1;</code>
         */
        @Override
        public boolean hasFieldAccess() {
            return ((bitField0_ & 0x00000001) == 0x00000001);
        }

        /**
         * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.FieldAccessCommon field_access = 1;</code>
         */
        @Override
        public FieldAccessCommon getFieldAccess() {
            return fieldAccess_;
        }

        /**
         * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.FieldAccessCommon field_access = 1;</code>
         */
        public Builder setFieldAccess(FieldAccessCommon value) {
            if (value == null) {
                throw new NullPointerException();
            }
            fieldAccess_ = value;

            bitField0_ |= 0x00000001;
            return this;
        }

        /**
         * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.FieldAccessCommon field_access = 1;</code>
         */
        public Builder setFieldAccess(
                FieldAccessCommon.Builder builderForValue) {
            fieldAccess_ = builderForValue.build();

            bitField0_ |= 0x00000001;
            return this;
        }

        /**
         * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.FieldAccessCommon field_access = 1;</code>
         */
        public Builder mergeFieldAccess(FieldAccessCommon value) {
            if (((bitField0_ & 0x00000001) == 0x00000001) &&
                    fieldAccess_ != FieldAccessCommon.getDefaultInstance()) {
                fieldAccess_ =
                        FieldAccessCommon.newBuilder(fieldAccess_).mergeFrom(value).buildPartial();
            } else {
                fieldAccess_ = value;
            }

            bitField0_ |= 0x00000001;
            return this;
        }

        /**
         * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.FieldAccessCommon field_access = 1;</code>
         */
        public Builder clearFieldAccess() {
            fieldAccess_ = FieldAccessCommon.getDefaultInstance();

            bitField0_ &= ~0x00000001;
            return this;
        }

        /**
         * <code>optional int32 origin_name = 2;</code>
         */
        @Override
        public boolean hasOriginName() {
            return ((bitField0_ & 0x00000002) == 0x00000002);
        }

        /**
         * <code>optional int32 origin_name = 2;</code>
         */
        @Override
        public int getOriginName() {
            return originName_;
        }

        /**
         * <code>optional int32 origin_name = 2;</code>
         */
        public Builder setOriginName(int value) {
            bitField0_ |= 0x00000002;
            originName_ = value;

            return this;
        }

        /**
         * <code>optional int32 origin_name = 2;</code>
         */
        public Builder clearOriginName() {
            bitField0_ &= ~0x00000002;
            originName_ = 0;

            return this;
        }

        // @@protoc_insertion_point(builder_scope:org.jetbrains.kotlin.backend.common.serialization.proto.IrGetField)
    }

    // @@protoc_insertion_point(class_scope:org.jetbrains.kotlin.backend.common.serialization.proto.IrGetField)
}
