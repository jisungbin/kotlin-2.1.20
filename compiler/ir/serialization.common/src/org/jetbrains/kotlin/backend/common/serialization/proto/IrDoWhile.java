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
 * Protobuf type {@code org.jetbrains.kotlin.backend.common.serialization.proto.IrDoWhile}
 */
public final class IrDoWhile extends
        GeneratedMessageLite implements
        // @@protoc_insertion_point(message_implements:org.jetbrains.kotlin.backend.common.serialization.proto.IrDoWhile)
        IrDoWhileOrBuilder {
    public static final int LOOP_FIELD_NUMBER = 1;
    private static final IrDoWhile defaultInstance;
    private static final long serialVersionUID = 0L;
    public static Parser<IrDoWhile> PARSER =
            new AbstractParser<IrDoWhile>() {
                @Override
                public IrDoWhile parsePartialFrom(
                        CodedInputStream input,
                        ExtensionRegistryLite extensionRegistry)
                        throws InvalidProtocolBufferException {
                    return new IrDoWhile(input, extensionRegistry);
                }
            };

    static {
        defaultInstance = new IrDoWhile(true);
        defaultInstance.initFields();
    }

    private final ByteString unknownFields;
    private int bitField0_;
    private Loop loop_;
    private byte memoizedIsInitialized = -1;
    private int memoizedSerializedSize = -1;
    // Use IrDoWhile.newBuilder() to construct.
    private IrDoWhile(GeneratedMessageLite.Builder builder) {
        super(builder);
        this.unknownFields = builder.getUnknownFields();
    }
    private IrDoWhile(boolean noInit) {
        this.unknownFields = ByteString.EMPTY;
    }

    private IrDoWhile(
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
                        Loop.Builder subBuilder = null;
                        if (((bitField0_ & 0x00000001) == 0x00000001)) {
                            subBuilder = loop_.toBuilder();
                        }
                        loop_ = input.readMessage(Loop.PARSER, extensionRegistry);
                        if (subBuilder != null) {
                            subBuilder.mergeFrom(loop_);
                            loop_ = subBuilder.buildPartial();
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

    public static IrDoWhile getDefaultInstance() {
        return defaultInstance;
    }

    public static IrDoWhile parseFrom(
            ByteString data)
            throws InvalidProtocolBufferException {
        return PARSER.parseFrom(data);
    }

    public static IrDoWhile parseFrom(
            ByteString data,
            ExtensionRegistryLite extensionRegistry)
            throws InvalidProtocolBufferException {
        return PARSER.parseFrom(data, extensionRegistry);
    }

    public static IrDoWhile parseFrom(byte[] data)
            throws InvalidProtocolBufferException {
        return PARSER.parseFrom(data);
    }

    public static IrDoWhile parseFrom(
            byte[] data,
            ExtensionRegistryLite extensionRegistry)
            throws InvalidProtocolBufferException {
        return PARSER.parseFrom(data, extensionRegistry);
    }

    public static IrDoWhile parseFrom(InputStream input)
            throws IOException {
        return PARSER.parseFrom(input);
    }

    public static IrDoWhile parseFrom(
            InputStream input,
            ExtensionRegistryLite extensionRegistry)
            throws IOException {
        return PARSER.parseFrom(input, extensionRegistry);
    }

    public static IrDoWhile parseDelimitedFrom(InputStream input)
            throws IOException {
        return PARSER.parseDelimitedFrom(input);
    }

    public static IrDoWhile parseDelimitedFrom(
            InputStream input,
            ExtensionRegistryLite extensionRegistry)
            throws IOException {
        return PARSER.parseDelimitedFrom(input, extensionRegistry);
    }

    public static IrDoWhile parseFrom(
            CodedInputStream input)
            throws IOException {
        return PARSER.parseFrom(input);
    }

    public static IrDoWhile parseFrom(
            CodedInputStream input,
            ExtensionRegistryLite extensionRegistry)
            throws IOException {
        return PARSER.parseFrom(input, extensionRegistry);
    }

    public static Builder newBuilder() {
        return Builder.create();
    }

    public static Builder newBuilder(IrDoWhile prototype) {
        return newBuilder().mergeFrom(prototype);
    }

    @Override
    public IrDoWhile getDefaultInstanceForType() {
        return defaultInstance;
    }

    @Override
    public Parser<IrDoWhile> getParserForType() {
        return PARSER;
    }

    /**
     * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.Loop loop = 1;</code>
     */
    @Override
    public boolean hasLoop() {
        return ((bitField0_ & 0x00000001) == 0x00000001);
    }

    /**
     * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.Loop loop = 1;</code>
     */
    @Override
    public Loop getLoop() {
        return loop_;
    }

    private void initFields() {
        loop_ = Loop.getDefaultInstance();
    }

    @Override
    public boolean isInitialized() {
        byte isInitialized = memoizedIsInitialized;
        if (isInitialized == 1) return true;
        if (isInitialized == 0) return false;

        if (!hasLoop()) {
            memoizedIsInitialized = 0;
            return false;
        }
        if (!getLoop().isInitialized()) {
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
            output.writeMessage(1, loop_);
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
                    .computeMessageSize(1, loop_);
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
     * Protobuf type {@code org.jetbrains.kotlin.backend.common.serialization.proto.IrDoWhile}
     */
    public static final class Builder extends
            GeneratedMessageLite.Builder<
                    IrDoWhile, Builder>
            implements
            // @@protoc_insertion_point(builder_implements:org.jetbrains.kotlin.backend.common.serialization.proto.IrDoWhile)
            IrDoWhileOrBuilder {
        private int bitField0_;
        private Loop loop_ = Loop.getDefaultInstance();

        // Construct using org.jetbrains.kotlin.backend.common.serialization.proto.IrDoWhile.newBuilder()
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
            loop_ = Loop.getDefaultInstance();
            bitField0_ &= ~0x00000001;
            return this;
        }

        @Override
        public Builder clone() {
            return create().mergeFrom(buildPartial());
        }

        @Override
        public IrDoWhile getDefaultInstanceForType() {
            return getDefaultInstance();
        }

        @Override
        public IrDoWhile build() {
            IrDoWhile result = buildPartial();
            if (!result.isInitialized()) {
                throw newUninitializedMessageException(result);
            }
            return result;
        }

        @Override
        public IrDoWhile buildPartial() {
            IrDoWhile result = new IrDoWhile(this);
            int from_bitField0_ = bitField0_;
            int to_bitField0_ = 0;
            if (((from_bitField0_ & 0x00000001) == 0x00000001)) {
                to_bitField0_ |= 0x00000001;
            }
            result.loop_ = loop_;
            result.bitField0_ = to_bitField0_;
            return result;
        }

        @Override
        public Builder mergeFrom(IrDoWhile other) {
            if (other == getDefaultInstance())
                return this;
            if (other.hasLoop()) {
                mergeLoop(other.getLoop());
            }
            setUnknownFields(
                    getUnknownFields().concat(other.unknownFields));
            return this;
        }

        @Override
        public boolean isInitialized() {
            if (!hasLoop()) {

                return false;
            }
            if (!getLoop().isInitialized()) {

                return false;
            }
            return true;
        }

        @Override
        public Builder mergeFrom(
                CodedInputStream input,
                ExtensionRegistryLite extensionRegistry)
                throws IOException {
            IrDoWhile parsedMessage = null;
            try {
                parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
            } catch (InvalidProtocolBufferException e) {
                parsedMessage = (IrDoWhile) e.getUnfinishedMessage();
                throw e;
            } finally {
                if (parsedMessage != null) {
                    mergeFrom(parsedMessage);
                }
            }
            return this;
        }

        /**
         * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.Loop loop = 1;</code>
         */
        @Override
        public boolean hasLoop() {
            return ((bitField0_ & 0x00000001) == 0x00000001);
        }

        /**
         * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.Loop loop = 1;</code>
         */
        @Override
        public Loop getLoop() {
            return loop_;
        }

        /**
         * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.Loop loop = 1;</code>
         */
        public Builder setLoop(Loop value) {
            if (value == null) {
                throw new NullPointerException();
            }
            loop_ = value;

            bitField0_ |= 0x00000001;
            return this;
        }

        /**
         * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.Loop loop = 1;</code>
         */
        public Builder setLoop(
                Loop.Builder builderForValue) {
            loop_ = builderForValue.build();

            bitField0_ |= 0x00000001;
            return this;
        }

        /**
         * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.Loop loop = 1;</code>
         */
        public Builder mergeLoop(Loop value) {
            if (((bitField0_ & 0x00000001) == 0x00000001) &&
                    loop_ != Loop.getDefaultInstance()) {
                loop_ =
                        Loop.newBuilder(loop_).mergeFrom(value).buildPartial();
            } else {
                loop_ = value;
            }

            bitField0_ |= 0x00000001;
            return this;
        }

        /**
         * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.Loop loop = 1;</code>
         */
        public Builder clearLoop() {
            loop_ = Loop.getDefaultInstance();

            bitField0_ &= ~0x00000001;
            return this;
        }

        // @@protoc_insertion_point(builder_scope:org.jetbrains.kotlin.backend.common.serialization.proto.IrDoWhile)
    }

    // @@protoc_insertion_point(class_scope:org.jetbrains.kotlin.backend.common.serialization.proto.IrDoWhile)
}
