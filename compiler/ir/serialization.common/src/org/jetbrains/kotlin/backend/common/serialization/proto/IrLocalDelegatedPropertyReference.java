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
 * Protobuf type {@code org.jetbrains.kotlin.backend.common.serialization.proto.IrLocalDelegatedPropertyReference}
 */
public final class IrLocalDelegatedPropertyReference extends
        GeneratedMessageLite implements
        // @@protoc_insertion_point(message_implements:org.jetbrains.kotlin.backend.common.serialization.proto.IrLocalDelegatedPropertyReference)
        IrLocalDelegatedPropertyReferenceOrBuilder {
    public static final int DELEGATE_FIELD_NUMBER = 1;
    public static final int GETTER_FIELD_NUMBER = 2;
    public static final int SETTER_FIELD_NUMBER = 3;
    public static final int SYMBOL_FIELD_NUMBER = 4;
    public static final int ORIGIN_NAME_FIELD_NUMBER = 5;
    private static final IrLocalDelegatedPropertyReference defaultInstance;
    private static final long serialVersionUID = 0L;
    public static Parser<IrLocalDelegatedPropertyReference> PARSER =
            new AbstractParser<IrLocalDelegatedPropertyReference>() {
                @Override
                public IrLocalDelegatedPropertyReference parsePartialFrom(
                        CodedInputStream input,
                        ExtensionRegistryLite extensionRegistry)
                        throws InvalidProtocolBufferException {
                    return new IrLocalDelegatedPropertyReference(input, extensionRegistry);
                }
            };

    static {
        defaultInstance = new IrLocalDelegatedPropertyReference(true);
        defaultInstance.initFields();
    }

    private final ByteString unknownFields;
    private int bitField0_;
    private long delegate_;
    private long getter_;
    private long setter_;
    private long symbol_;
    private int originName_;
    private byte memoizedIsInitialized = -1;
    private int memoizedSerializedSize = -1;

    // Use IrLocalDelegatedPropertyReference.newBuilder() to construct.
    private IrLocalDelegatedPropertyReference(GeneratedMessageLite.Builder builder) {
        super(builder);
        this.unknownFields = builder.getUnknownFields();
    }
    private IrLocalDelegatedPropertyReference(boolean noInit) {
        this.unknownFields = ByteString.EMPTY;
    }

    private IrLocalDelegatedPropertyReference(
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
                        delegate_ = input.readInt64();
                        break;
                    }
                    case 16: {
                        bitField0_ |= 0x00000002;
                        getter_ = input.readInt64();
                        break;
                    }
                    case 24: {
                        bitField0_ |= 0x00000004;
                        setter_ = input.readInt64();
                        break;
                    }
                    case 32: {
                        bitField0_ |= 0x00000008;
                        symbol_ = input.readInt64();
                        break;
                    }
                    case 40: {
                        bitField0_ |= 0x00000010;
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

    public static IrLocalDelegatedPropertyReference getDefaultInstance() {
        return defaultInstance;
    }

    public static IrLocalDelegatedPropertyReference parseFrom(
            ByteString data)
            throws InvalidProtocolBufferException {
        return PARSER.parseFrom(data);
    }

    public static IrLocalDelegatedPropertyReference parseFrom(
            ByteString data,
            ExtensionRegistryLite extensionRegistry)
            throws InvalidProtocolBufferException {
        return PARSER.parseFrom(data, extensionRegistry);
    }

    public static IrLocalDelegatedPropertyReference parseFrom(byte[] data)
            throws InvalidProtocolBufferException {
        return PARSER.parseFrom(data);
    }

    public static IrLocalDelegatedPropertyReference parseFrom(
            byte[] data,
            ExtensionRegistryLite extensionRegistry)
            throws InvalidProtocolBufferException {
        return PARSER.parseFrom(data, extensionRegistry);
    }

    public static IrLocalDelegatedPropertyReference parseFrom(InputStream input)
            throws IOException {
        return PARSER.parseFrom(input);
    }

    public static IrLocalDelegatedPropertyReference parseFrom(
            InputStream input,
            ExtensionRegistryLite extensionRegistry)
            throws IOException {
        return PARSER.parseFrom(input, extensionRegistry);
    }

    public static IrLocalDelegatedPropertyReference parseDelimitedFrom(InputStream input)
            throws IOException {
        return PARSER.parseDelimitedFrom(input);
    }

    public static IrLocalDelegatedPropertyReference parseDelimitedFrom(
            InputStream input,
            ExtensionRegistryLite extensionRegistry)
            throws IOException {
        return PARSER.parseDelimitedFrom(input, extensionRegistry);
    }

    public static IrLocalDelegatedPropertyReference parseFrom(
            CodedInputStream input)
            throws IOException {
        return PARSER.parseFrom(input);
    }

    public static IrLocalDelegatedPropertyReference parseFrom(
            CodedInputStream input,
            ExtensionRegistryLite extensionRegistry)
            throws IOException {
        return PARSER.parseFrom(input, extensionRegistry);
    }

    public static Builder newBuilder() {
        return Builder.create();
    }

    public static Builder newBuilder(IrLocalDelegatedPropertyReference prototype) {
        return newBuilder().mergeFrom(prototype);
    }

    @Override
    public IrLocalDelegatedPropertyReference getDefaultInstanceForType() {
        return defaultInstance;
    }

    @Override
    public Parser<IrLocalDelegatedPropertyReference> getParserForType() {
        return PARSER;
    }

    /**
     * <code>required int64 delegate = 1;</code>
     */
    @Override
    public boolean hasDelegate() {
        return ((bitField0_ & 0x00000001) == 0x00000001);
    }

    /**
     * <code>required int64 delegate = 1;</code>
     */
    @Override
    public long getDelegate() {
        return delegate_;
    }

    /**
     * <code>optional int64 getter = 2;</code>
     */
    @Override
    public boolean hasGetter() {
        return ((bitField0_ & 0x00000002) == 0x00000002);
    }

    /**
     * <code>optional int64 getter = 2;</code>
     */
    @Override
    public long getGetter() {
        return getter_;
    }

    /**
     * <code>optional int64 setter = 3;</code>
     */
    @Override
    public boolean hasSetter() {
        return ((bitField0_ & 0x00000004) == 0x00000004);
    }

    /**
     * <code>optional int64 setter = 3;</code>
     */
    @Override
    public long getSetter() {
        return setter_;
    }

    /**
     * <code>required int64 symbol = 4;</code>
     */
    @Override
    public boolean hasSymbol() {
        return ((bitField0_ & 0x00000008) == 0x00000008);
    }

    /**
     * <code>required int64 symbol = 4;</code>
     */
    @Override
    public long getSymbol() {
        return symbol_;
    }

    /**
     * <code>optional int32 origin_name = 5;</code>
     */
    @Override
    public boolean hasOriginName() {
        return ((bitField0_ & 0x00000010) == 0x00000010);
    }

    /**
     * <code>optional int32 origin_name = 5;</code>
     */
    @Override
    public int getOriginName() {
        return originName_;
    }

    private void initFields() {
        delegate_ = 0L;
        getter_ = 0L;
        setter_ = 0L;
        symbol_ = 0L;
        originName_ = 0;
    }

    @Override
    public boolean isInitialized() {
        byte isInitialized = memoizedIsInitialized;
        if (isInitialized == 1) return true;
        if (isInitialized == 0) return false;

        if (!hasDelegate()) {
            memoizedIsInitialized = 0;
            return false;
        }
        if (!hasSymbol()) {
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
            output.writeInt64(1, delegate_);
        }
        if (((bitField0_ & 0x00000002) == 0x00000002)) {
            output.writeInt64(2, getter_);
        }
        if (((bitField0_ & 0x00000004) == 0x00000004)) {
            output.writeInt64(3, setter_);
        }
        if (((bitField0_ & 0x00000008) == 0x00000008)) {
            output.writeInt64(4, symbol_);
        }
        if (((bitField0_ & 0x00000010) == 0x00000010)) {
            output.writeInt32(5, originName_);
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
                    .computeInt64Size(1, delegate_);
        }
        if (((bitField0_ & 0x00000002) == 0x00000002)) {
            size += CodedOutputStream
                    .computeInt64Size(2, getter_);
        }
        if (((bitField0_ & 0x00000004) == 0x00000004)) {
            size += CodedOutputStream
                    .computeInt64Size(3, setter_);
        }
        if (((bitField0_ & 0x00000008) == 0x00000008)) {
            size += CodedOutputStream
                    .computeInt64Size(4, symbol_);
        }
        if (((bitField0_ & 0x00000010) == 0x00000010)) {
            size += CodedOutputStream
                    .computeInt32Size(5, originName_);
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
     * Protobuf type {@code org.jetbrains.kotlin.backend.common.serialization.proto.IrLocalDelegatedPropertyReference}
     */
    public static final class Builder extends
            GeneratedMessageLite.Builder<
                    IrLocalDelegatedPropertyReference, Builder>
            implements
            // @@protoc_insertion_point(builder_implements:org.jetbrains.kotlin.backend.common.serialization.proto.IrLocalDelegatedPropertyReference)
            IrLocalDelegatedPropertyReferenceOrBuilder {
        private int bitField0_;
        private long delegate_;
        private long getter_;
        private long setter_;
        private long symbol_;
        private int originName_;

        // Construct using org.jetbrains.kotlin.backend.common.serialization.proto.IrLocalDelegatedPropertyReference.newBuilder()
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
            delegate_ = 0L;
            bitField0_ &= ~0x00000001;
            getter_ = 0L;
            bitField0_ &= ~0x00000002;
            setter_ = 0L;
            bitField0_ &= ~0x00000004;
            symbol_ = 0L;
            bitField0_ &= ~0x00000008;
            originName_ = 0;
            bitField0_ &= ~0x00000010;
            return this;
        }

        @Override
        public Builder clone() {
            return create().mergeFrom(buildPartial());
        }

        @Override
        public IrLocalDelegatedPropertyReference getDefaultInstanceForType() {
            return getDefaultInstance();
        }

        @Override
        public IrLocalDelegatedPropertyReference build() {
            IrLocalDelegatedPropertyReference result = buildPartial();
            if (!result.isInitialized()) {
                throw newUninitializedMessageException(result);
            }
            return result;
        }

        @Override
        public IrLocalDelegatedPropertyReference buildPartial() {
            IrLocalDelegatedPropertyReference result = new IrLocalDelegatedPropertyReference(this);
            int from_bitField0_ = bitField0_;
            int to_bitField0_ = 0;
            if (((from_bitField0_ & 0x00000001) == 0x00000001)) {
                to_bitField0_ |= 0x00000001;
            }
            result.delegate_ = delegate_;
            if (((from_bitField0_ & 0x00000002) == 0x00000002)) {
                to_bitField0_ |= 0x00000002;
            }
            result.getter_ = getter_;
            if (((from_bitField0_ & 0x00000004) == 0x00000004)) {
                to_bitField0_ |= 0x00000004;
            }
            result.setter_ = setter_;
            if (((from_bitField0_ & 0x00000008) == 0x00000008)) {
                to_bitField0_ |= 0x00000008;
            }
            result.symbol_ = symbol_;
            if (((from_bitField0_ & 0x00000010) == 0x00000010)) {
                to_bitField0_ |= 0x00000010;
            }
            result.originName_ = originName_;
            result.bitField0_ = to_bitField0_;
            return result;
        }

        @Override
        public Builder mergeFrom(IrLocalDelegatedPropertyReference other) {
            if (other == getDefaultInstance())
                return this;
            if (other.hasDelegate()) {
                setDelegate(other.getDelegate());
            }
            if (other.hasGetter()) {
                setGetter(other.getGetter());
            }
            if (other.hasSetter()) {
                setSetter(other.getSetter());
            }
            if (other.hasSymbol()) {
                setSymbol(other.getSymbol());
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
            if (!hasDelegate()) {

                return false;
            }
            if (!hasSymbol()) {

                return false;
            }
            return true;
        }

        @Override
        public Builder mergeFrom(
                CodedInputStream input,
                ExtensionRegistryLite extensionRegistry)
                throws IOException {
            IrLocalDelegatedPropertyReference parsedMessage = null;
            try {
                parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
            } catch (InvalidProtocolBufferException e) {
                parsedMessage = (IrLocalDelegatedPropertyReference) e.getUnfinishedMessage();
                throw e;
            } finally {
                if (parsedMessage != null) {
                    mergeFrom(parsedMessage);
                }
            }
            return this;
        }

        /**
         * <code>required int64 delegate = 1;</code>
         */
        @Override
        public boolean hasDelegate() {
            return ((bitField0_ & 0x00000001) == 0x00000001);
        }

        /**
         * <code>required int64 delegate = 1;</code>
         */
        @Override
        public long getDelegate() {
            return delegate_;
        }

        /**
         * <code>required int64 delegate = 1;</code>
         */
        public Builder setDelegate(long value) {
            bitField0_ |= 0x00000001;
            delegate_ = value;

            return this;
        }

        /**
         * <code>required int64 delegate = 1;</code>
         */
        public Builder clearDelegate() {
            bitField0_ &= ~0x00000001;
            delegate_ = 0L;

            return this;
        }

        /**
         * <code>optional int64 getter = 2;</code>
         */
        @Override
        public boolean hasGetter() {
            return ((bitField0_ & 0x00000002) == 0x00000002);
        }

        /**
         * <code>optional int64 getter = 2;</code>
         */
        @Override
        public long getGetter() {
            return getter_;
        }

        /**
         * <code>optional int64 getter = 2;</code>
         */
        public Builder setGetter(long value) {
            bitField0_ |= 0x00000002;
            getter_ = value;

            return this;
        }

        /**
         * <code>optional int64 getter = 2;</code>
         */
        public Builder clearGetter() {
            bitField0_ &= ~0x00000002;
            getter_ = 0L;

            return this;
        }

        /**
         * <code>optional int64 setter = 3;</code>
         */
        @Override
        public boolean hasSetter() {
            return ((bitField0_ & 0x00000004) == 0x00000004);
        }

        /**
         * <code>optional int64 setter = 3;</code>
         */
        @Override
        public long getSetter() {
            return setter_;
        }

        /**
         * <code>optional int64 setter = 3;</code>
         */
        public Builder setSetter(long value) {
            bitField0_ |= 0x00000004;
            setter_ = value;

            return this;
        }

        /**
         * <code>optional int64 setter = 3;</code>
         */
        public Builder clearSetter() {
            bitField0_ &= ~0x00000004;
            setter_ = 0L;

            return this;
        }

        /**
         * <code>required int64 symbol = 4;</code>
         */
        @Override
        public boolean hasSymbol() {
            return ((bitField0_ & 0x00000008) == 0x00000008);
        }

        /**
         * <code>required int64 symbol = 4;</code>
         */
        @Override
        public long getSymbol() {
            return symbol_;
        }

        /**
         * <code>required int64 symbol = 4;</code>
         */
        public Builder setSymbol(long value) {
            bitField0_ |= 0x00000008;
            symbol_ = value;

            return this;
        }

        /**
         * <code>required int64 symbol = 4;</code>
         */
        public Builder clearSymbol() {
            bitField0_ &= ~0x00000008;
            symbol_ = 0L;

            return this;
        }

        /**
         * <code>optional int32 origin_name = 5;</code>
         */
        @Override
        public boolean hasOriginName() {
            return ((bitField0_ & 0x00000010) == 0x00000010);
        }

        /**
         * <code>optional int32 origin_name = 5;</code>
         */
        @Override
        public int getOriginName() {
            return originName_;
        }

        /**
         * <code>optional int32 origin_name = 5;</code>
         */
        public Builder setOriginName(int value) {
            bitField0_ |= 0x00000010;
            originName_ = value;

            return this;
        }

        /**
         * <code>optional int32 origin_name = 5;</code>
         */
        public Builder clearOriginName() {
            bitField0_ &= ~0x00000010;
            originName_ = 0;

            return this;
        }

        // @@protoc_insertion_point(builder_scope:org.jetbrains.kotlin.backend.common.serialization.proto.IrLocalDelegatedPropertyReference)
    }

    // @@protoc_insertion_point(class_scope:org.jetbrains.kotlin.backend.common.serialization.proto.IrLocalDelegatedPropertyReference)
}
