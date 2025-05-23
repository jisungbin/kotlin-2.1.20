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
 * Protobuf type {@code org.jetbrains.kotlin.backend.common.serialization.proto.IrConstructor}
 */
public final class IrConstructor extends
        GeneratedMessageLite implements
        // @@protoc_insertion_point(message_implements:org.jetbrains.kotlin.backend.common.serialization.proto.IrConstructor)
        IrConstructorOrBuilder {
    public static final int BASE_FIELD_NUMBER = 1;
    private static final IrConstructor defaultInstance;
    private static final long serialVersionUID = 0L;
    public static Parser<IrConstructor> PARSER =
            new AbstractParser<IrConstructor>() {
                @Override
                public IrConstructor parsePartialFrom(
                        CodedInputStream input,
                        ExtensionRegistryLite extensionRegistry)
                        throws InvalidProtocolBufferException {
                    return new IrConstructor(input, extensionRegistry);
                }
            };

    static {
        defaultInstance = new IrConstructor(true);
        defaultInstance.initFields();
    }

    private final ByteString unknownFields;
    private int bitField0_;
    private IrFunctionBase base_;
    private byte memoizedIsInitialized = -1;
    private int memoizedSerializedSize = -1;
    // Use IrConstructor.newBuilder() to construct.
    private IrConstructor(GeneratedMessageLite.Builder builder) {
        super(builder);
        this.unknownFields = builder.getUnknownFields();
    }
    private IrConstructor(boolean noInit) {
        this.unknownFields = ByteString.EMPTY;
    }

    private IrConstructor(
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
                        IrFunctionBase.Builder subBuilder = null;
                        if (((bitField0_ & 0x00000001) == 0x00000001)) {
                            subBuilder = base_.toBuilder();
                        }
                        base_ = input.readMessage(IrFunctionBase.PARSER, extensionRegistry);
                        if (subBuilder != null) {
                            subBuilder.mergeFrom(base_);
                            base_ = subBuilder.buildPartial();
                        }
                        bitField0_ |= 0x00000001;
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

    public static IrConstructor getDefaultInstance() {
        return defaultInstance;
    }

    public static IrConstructor parseFrom(
            ByteString data)
            throws InvalidProtocolBufferException {
        return PARSER.parseFrom(data);
    }

    public static IrConstructor parseFrom(
            ByteString data,
            ExtensionRegistryLite extensionRegistry)
            throws InvalidProtocolBufferException {
        return PARSER.parseFrom(data, extensionRegistry);
    }

    public static IrConstructor parseFrom(byte[] data)
            throws InvalidProtocolBufferException {
        return PARSER.parseFrom(data);
    }

    public static IrConstructor parseFrom(
            byte[] data,
            ExtensionRegistryLite extensionRegistry)
            throws InvalidProtocolBufferException {
        return PARSER.parseFrom(data, extensionRegistry);
    }

    public static IrConstructor parseFrom(InputStream input)
            throws IOException {
        return PARSER.parseFrom(input);
    }

    public static IrConstructor parseFrom(
            InputStream input,
            ExtensionRegistryLite extensionRegistry)
            throws IOException {
        return PARSER.parseFrom(input, extensionRegistry);
    }

    public static IrConstructor parseDelimitedFrom(InputStream input)
            throws IOException {
        return PARSER.parseDelimitedFrom(input);
    }

    public static IrConstructor parseDelimitedFrom(
            InputStream input,
            ExtensionRegistryLite extensionRegistry)
            throws IOException {
        return PARSER.parseDelimitedFrom(input, extensionRegistry);
    }

    public static IrConstructor parseFrom(
            CodedInputStream input)
            throws IOException {
        return PARSER.parseFrom(input);
    }

    public static IrConstructor parseFrom(
            CodedInputStream input,
            ExtensionRegistryLite extensionRegistry)
            throws IOException {
        return PARSER.parseFrom(input, extensionRegistry);
    }

    public static Builder newBuilder() {
        return Builder.create();
    }

    public static Builder newBuilder(IrConstructor prototype) {
        return newBuilder().mergeFrom(prototype);
    }

    @Override
    public IrConstructor getDefaultInstanceForType() {
        return defaultInstance;
    }

    @Override
    public Parser<IrConstructor> getParserForType() {
        return PARSER;
    }

    /**
     * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.IrFunctionBase base = 1;</code>
     */
    @Override
    public boolean hasBase() {
        return ((bitField0_ & 0x00000001) == 0x00000001);
    }

    /**
     * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.IrFunctionBase base = 1;</code>
     */
    @Override
    public IrFunctionBase getBase() {
        return base_;
    }

    private void initFields() {
        base_ = IrFunctionBase.getDefaultInstance();
    }

    @Override
    public boolean isInitialized() {
        byte isInitialized = memoizedIsInitialized;
        if (isInitialized == 1) return true;
        if (isInitialized == 0) return false;

        if (!hasBase()) {
            memoizedIsInitialized = 0;
            return false;
        }
        if (!getBase().isInitialized()) {
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
            output.writeMessage(1, base_);
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
                    .computeMessageSize(1, base_);
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
     * Protobuf type {@code org.jetbrains.kotlin.backend.common.serialization.proto.IrConstructor}
     */
    public static final class Builder extends
            GeneratedMessageLite.Builder<
                    IrConstructor, Builder>
            implements
            // @@protoc_insertion_point(builder_implements:org.jetbrains.kotlin.backend.common.serialization.proto.IrConstructor)
            IrConstructorOrBuilder {
        private int bitField0_;
        private IrFunctionBase base_ = IrFunctionBase.getDefaultInstance();

        // Construct using org.jetbrains.kotlin.backend.common.serialization.proto.IrConstructor.newBuilder()
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
            base_ = IrFunctionBase.getDefaultInstance();
            bitField0_ &= ~0x00000001;
            return this;
        }

        @Override
        public Builder clone() {
            return create().mergeFrom(buildPartial());
        }

        @Override
        public IrConstructor getDefaultInstanceForType() {
            return getDefaultInstance();
        }

        @Override
        public IrConstructor build() {
            IrConstructor result = buildPartial();
            if (!result.isInitialized()) {
                throw newUninitializedMessageException(result);
            }
            return result;
        }

        @Override
        public IrConstructor buildPartial() {
            IrConstructor result = new IrConstructor(this);
            int from_bitField0_ = bitField0_;
            int to_bitField0_ = 0;
            if (((from_bitField0_ & 0x00000001) == 0x00000001)) {
                to_bitField0_ |= 0x00000001;
            }
            result.base_ = base_;
            result.bitField0_ = to_bitField0_;
            return result;
        }

        @Override
        public Builder mergeFrom(IrConstructor other) {
            if (other == getDefaultInstance())
                return this;
            if (other.hasBase()) {
                mergeBase(other.getBase());
            }
            setUnknownFields(
                    getUnknownFields().concat(other.unknownFields));
            return this;
        }

        @Override
        public boolean isInitialized() {
            if (!hasBase()) {

                return false;
            }
            if (!getBase().isInitialized()) {

                return false;
            }
            return true;
        }

        @Override
        public Builder mergeFrom(
                CodedInputStream input,
                ExtensionRegistryLite extensionRegistry)
                throws IOException {
            IrConstructor parsedMessage = null;
            try {
                parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
            } catch (InvalidProtocolBufferException e) {
                parsedMessage = (IrConstructor) e.getUnfinishedMessage();
                throw e;
            } finally {
                if (parsedMessage != null) {
                    mergeFrom(parsedMessage);
                }
            }
            return this;
        }

        /**
         * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.IrFunctionBase base = 1;</code>
         */
        @Override
        public boolean hasBase() {
            return ((bitField0_ & 0x00000001) == 0x00000001);
        }

        /**
         * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.IrFunctionBase base = 1;</code>
         */
        @Override
        public IrFunctionBase getBase() {
            return base_;
        }

        /**
         * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.IrFunctionBase base = 1;</code>
         */
        public Builder setBase(IrFunctionBase value) {
            if (value == null) {
                throw new NullPointerException();
            }
            base_ = value;

            bitField0_ |= 0x00000001;
            return this;
        }

        /**
         * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.IrFunctionBase base = 1;</code>
         */
        public Builder setBase(
                IrFunctionBase.Builder builderForValue) {
            base_ = builderForValue.build();

            bitField0_ |= 0x00000001;
            return this;
        }

        /**
         * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.IrFunctionBase base = 1;</code>
         */
        public Builder mergeBase(IrFunctionBase value) {
            if (((bitField0_ & 0x00000001) == 0x00000001) &&
                    base_ != IrFunctionBase.getDefaultInstance()) {
                base_ =
                        IrFunctionBase.newBuilder(base_).mergeFrom(value).buildPartial();
            } else {
                base_ = value;
            }

            bitField0_ |= 0x00000001;
            return this;
        }

        /**
         * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.IrFunctionBase base = 1;</code>
         */
        public Builder clearBase() {
            base_ = IrFunctionBase.getDefaultInstance();

            bitField0_ &= ~0x00000001;
            return this;
        }

        // @@protoc_insertion_point(builder_scope:org.jetbrains.kotlin.backend.common.serialization.proto.IrConstructor)
    }

    // @@protoc_insertion_point(class_scope:org.jetbrains.kotlin.backend.common.serialization.proto.IrConstructor)
}
