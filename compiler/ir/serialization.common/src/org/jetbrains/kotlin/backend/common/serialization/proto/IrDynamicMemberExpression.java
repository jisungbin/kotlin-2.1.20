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
 * Protobuf type {@code org.jetbrains.kotlin.backend.common.serialization.proto.IrDynamicMemberExpression}
 */
public final class IrDynamicMemberExpression extends
        GeneratedMessageLite implements
        // @@protoc_insertion_point(message_implements:org.jetbrains.kotlin.backend.common.serialization.proto.IrDynamicMemberExpression)
        IrDynamicMemberExpressionOrBuilder {
    public static final int MEMBER_NAME_FIELD_NUMBER = 1;
    public static final int RECEIVER_FIELD_NUMBER = 2;
    private static final IrDynamicMemberExpression defaultInstance;
    private static final long serialVersionUID = 0L;
    public static Parser<IrDynamicMemberExpression> PARSER =
            new AbstractParser<IrDynamicMemberExpression>() {
                @Override
                public IrDynamicMemberExpression parsePartialFrom(
                        CodedInputStream input,
                        ExtensionRegistryLite extensionRegistry)
                        throws InvalidProtocolBufferException {
                    return new IrDynamicMemberExpression(input, extensionRegistry);
                }
            };

    static {
        defaultInstance = new IrDynamicMemberExpression(true);
        defaultInstance.initFields();
    }

    private final ByteString unknownFields;
    private int bitField0_;
    private int memberName_;
    private IrExpression receiver_;
    private byte memoizedIsInitialized = -1;
    private int memoizedSerializedSize = -1;

    // Use IrDynamicMemberExpression.newBuilder() to construct.
    private IrDynamicMemberExpression(GeneratedMessageLite.Builder builder) {
        super(builder);
        this.unknownFields = builder.getUnknownFields();
    }

    private IrDynamicMemberExpression(boolean noInit) {
        this.unknownFields = ByteString.EMPTY;
    }

    private IrDynamicMemberExpression(
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
                    case 8: {
                        bitField0_ |= 0x00000001;
                        memberName_ = input.readInt32();
                        break;
                    }
                    case 18: {
                        IrExpression.Builder subBuilder = null;
                        if (((bitField0_ & 0x00000002) == 0x00000002)) {
                            subBuilder = receiver_.toBuilder();
                        }
                        receiver_ = input.readMessage(IrExpression.PARSER, extensionRegistry);
                        if (subBuilder != null) {
                            subBuilder.mergeFrom(receiver_);
                            receiver_ = subBuilder.buildPartial();
                        }
                        bitField0_ |= 0x00000002;
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

    public static IrDynamicMemberExpression getDefaultInstance() {
        return defaultInstance;
    }

    public static IrDynamicMemberExpression parseFrom(
            ByteString data)
            throws InvalidProtocolBufferException {
        return PARSER.parseFrom(data);
    }

    public static IrDynamicMemberExpression parseFrom(
            ByteString data,
            ExtensionRegistryLite extensionRegistry)
            throws InvalidProtocolBufferException {
        return PARSER.parseFrom(data, extensionRegistry);
    }

    public static IrDynamicMemberExpression parseFrom(byte[] data)
            throws InvalidProtocolBufferException {
        return PARSER.parseFrom(data);
    }

    public static IrDynamicMemberExpression parseFrom(
            byte[] data,
            ExtensionRegistryLite extensionRegistry)
            throws InvalidProtocolBufferException {
        return PARSER.parseFrom(data, extensionRegistry);
    }

    public static IrDynamicMemberExpression parseFrom(InputStream input)
            throws IOException {
        return PARSER.parseFrom(input);
    }

    public static IrDynamicMemberExpression parseFrom(
            InputStream input,
            ExtensionRegistryLite extensionRegistry)
            throws IOException {
        return PARSER.parseFrom(input, extensionRegistry);
    }

    public static IrDynamicMemberExpression parseDelimitedFrom(InputStream input)
            throws IOException {
        return PARSER.parseDelimitedFrom(input);
    }

    public static IrDynamicMemberExpression parseDelimitedFrom(
            InputStream input,
            ExtensionRegistryLite extensionRegistry)
            throws IOException {
        return PARSER.parseDelimitedFrom(input, extensionRegistry);
    }

    public static IrDynamicMemberExpression parseFrom(
            CodedInputStream input)
            throws IOException {
        return PARSER.parseFrom(input);
    }

    public static IrDynamicMemberExpression parseFrom(
            CodedInputStream input,
            ExtensionRegistryLite extensionRegistry)
            throws IOException {
        return PARSER.parseFrom(input, extensionRegistry);
    }

    public static Builder newBuilder() {
        return Builder.create();
    }

    public static Builder newBuilder(IrDynamicMemberExpression prototype) {
        return newBuilder().mergeFrom(prototype);
    }

    @Override
    public IrDynamicMemberExpression getDefaultInstanceForType() {
        return defaultInstance;
    }

    @Override
    public Parser<IrDynamicMemberExpression> getParserForType() {
        return PARSER;
    }

    /**
     * <code>required int32 member_name = 1;</code>
     */
    @Override
    public boolean hasMemberName() {
        return ((bitField0_ & 0x00000001) == 0x00000001);
    }

    /**
     * <code>required int32 member_name = 1;</code>
     */
    @Override
    public int getMemberName() {
        return memberName_;
    }

    /**
     * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.IrExpression receiver = 2;</code>
     */
    @Override
    public boolean hasReceiver() {
        return ((bitField0_ & 0x00000002) == 0x00000002);
    }

    /**
     * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.IrExpression receiver = 2;</code>
     */
    @Override
    public IrExpression getReceiver() {
        return receiver_;
    }

    private void initFields() {
        memberName_ = 0;
        receiver_ = IrExpression.getDefaultInstance();
    }

    @Override
    public boolean isInitialized() {
        byte isInitialized = memoizedIsInitialized;
        if (isInitialized == 1) return true;
        if (isInitialized == 0) return false;

        if (!hasMemberName()) {
            memoizedIsInitialized = 0;
            return false;
        }
        if (!hasReceiver()) {
            memoizedIsInitialized = 0;
            return false;
        }
        if (!getReceiver().isInitialized()) {
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
            output.writeInt32(1, memberName_);
        }
        if (((bitField0_ & 0x00000002) == 0x00000002)) {
            output.writeMessage(2, receiver_);
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
                    .computeInt32Size(1, memberName_);
        }
        if (((bitField0_ & 0x00000002) == 0x00000002)) {
            size += CodedOutputStream
                    .computeMessageSize(2, receiver_);
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
     * Protobuf type {@code org.jetbrains.kotlin.backend.common.serialization.proto.IrDynamicMemberExpression}
     */
    public static final class Builder extends
            GeneratedMessageLite.Builder<
                    IrDynamicMemberExpression, Builder>
            implements
            // @@protoc_insertion_point(builder_implements:org.jetbrains.kotlin.backend.common.serialization.proto.IrDynamicMemberExpression)
            IrDynamicMemberExpressionOrBuilder {
        private int bitField0_;
        private int memberName_;
        private IrExpression receiver_ = IrExpression.getDefaultInstance();

        // Construct using org.jetbrains.kotlin.backend.common.serialization.proto.IrDynamicMemberExpression.newBuilder()
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
            memberName_ = 0;
            bitField0_ &= ~0x00000001;
            receiver_ = IrExpression.getDefaultInstance();
            bitField0_ &= ~0x00000002;
            return this;
        }

        @Override
        public Builder clone() {
            return create().mergeFrom(buildPartial());
        }

        @Override
        public IrDynamicMemberExpression getDefaultInstanceForType() {
            return getDefaultInstance();
        }

        @Override
        public IrDynamicMemberExpression build() {
            IrDynamicMemberExpression result = buildPartial();
            if (!result.isInitialized()) {
                throw newUninitializedMessageException(result);
            }
            return result;
        }

        @Override
        public IrDynamicMemberExpression buildPartial() {
            IrDynamicMemberExpression result = new IrDynamicMemberExpression(this);
            int from_bitField0_ = bitField0_;
            int to_bitField0_ = 0;
            if (((from_bitField0_ & 0x00000001) == 0x00000001)) {
                to_bitField0_ |= 0x00000001;
            }
            result.memberName_ = memberName_;
            if (((from_bitField0_ & 0x00000002) == 0x00000002)) {
                to_bitField0_ |= 0x00000002;
            }
            result.receiver_ = receiver_;
            result.bitField0_ = to_bitField0_;
            return result;
        }

        @Override
        public Builder mergeFrom(IrDynamicMemberExpression other) {
            if (other == getDefaultInstance())
                return this;
            if (other.hasMemberName()) {
                setMemberName(other.getMemberName());
            }
            if (other.hasReceiver()) {
                mergeReceiver(other.getReceiver());
            }
            setUnknownFields(
                    getUnknownFields().concat(other.unknownFields));
            return this;
        }

        @Override
        public boolean isInitialized() {
            if (!hasMemberName()) {

                return false;
            }
            if (!hasReceiver()) {

                return false;
            }
            if (!getReceiver().isInitialized()) {

                return false;
            }
            return true;
        }

        @Override
        public Builder mergeFrom(
                CodedInputStream input,
                ExtensionRegistryLite extensionRegistry)
                throws IOException {
            IrDynamicMemberExpression parsedMessage = null;
            try {
                parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
            } catch (InvalidProtocolBufferException e) {
                parsedMessage = (IrDynamicMemberExpression) e.getUnfinishedMessage();
                throw e;
            } finally {
                if (parsedMessage != null) {
                    mergeFrom(parsedMessage);
                }
            }
            return this;
        }

        /**
         * <code>required int32 member_name = 1;</code>
         */
        @Override
        public boolean hasMemberName() {
            return ((bitField0_ & 0x00000001) == 0x00000001);
        }

        /**
         * <code>required int32 member_name = 1;</code>
         */
        @Override
        public int getMemberName() {
            return memberName_;
        }

        /**
         * <code>required int32 member_name = 1;</code>
         */
        public Builder setMemberName(int value) {
            bitField0_ |= 0x00000001;
            memberName_ = value;

            return this;
        }

        /**
         * <code>required int32 member_name = 1;</code>
         */
        public Builder clearMemberName() {
            bitField0_ &= ~0x00000001;
            memberName_ = 0;

            return this;
        }

        /**
         * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.IrExpression receiver = 2;</code>
         */
        @Override
        public boolean hasReceiver() {
            return ((bitField0_ & 0x00000002) == 0x00000002);
        }

        /**
         * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.IrExpression receiver = 2;</code>
         */
        @Override
        public IrExpression getReceiver() {
            return receiver_;
        }

        /**
         * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.IrExpression receiver = 2;</code>
         */
        public Builder setReceiver(IrExpression value) {
            if (value == null) {
                throw new NullPointerException();
            }
            receiver_ = value;

            bitField0_ |= 0x00000002;
            return this;
        }

        /**
         * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.IrExpression receiver = 2;</code>
         */
        public Builder setReceiver(
                IrExpression.Builder builderForValue) {
            receiver_ = builderForValue.build();

            bitField0_ |= 0x00000002;
            return this;
        }

        /**
         * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.IrExpression receiver = 2;</code>
         */
        public Builder mergeReceiver(IrExpression value) {
            if (((bitField0_ & 0x00000002) == 0x00000002) &&
                    receiver_ != IrExpression.getDefaultInstance()) {
                receiver_ =
                        IrExpression.newBuilder(receiver_).mergeFrom(value).buildPartial();
            } else {
                receiver_ = value;
            }

            bitField0_ |= 0x00000002;
            return this;
        }

        /**
         * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.IrExpression receiver = 2;</code>
         */
        public Builder clearReceiver() {
            receiver_ = IrExpression.getDefaultInstance();

            bitField0_ &= ~0x00000002;
            return this;
        }

        // @@protoc_insertion_point(builder_scope:org.jetbrains.kotlin.backend.common.serialization.proto.IrDynamicMemberExpression)
    }

    // @@protoc_insertion_point(class_scope:org.jetbrains.kotlin.backend.common.serialization.proto.IrDynamicMemberExpression)
}
