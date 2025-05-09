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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Protobuf type {@code org.jetbrains.kotlin.backend.common.serialization.proto.IrWhen}
 */
public final class IrWhen extends
        GeneratedMessageLite implements
        // @@protoc_insertion_point(message_implements:org.jetbrains.kotlin.backend.common.serialization.proto.IrWhen)
        IrWhenOrBuilder {
    public static final int BRANCH_FIELD_NUMBER = 1;
    public static final int ORIGIN_NAME_FIELD_NUMBER = 2;
    private static final IrWhen defaultInstance;
    private static final long serialVersionUID = 0L;
    public static Parser<IrWhen> PARSER =
            new AbstractParser<IrWhen>() {
                @Override
                public IrWhen parsePartialFrom(
                        CodedInputStream input,
                        ExtensionRegistryLite extensionRegistry)
                        throws InvalidProtocolBufferException {
                    return new IrWhen(input, extensionRegistry);
                }
            };

    static {
        defaultInstance = new IrWhen(true);
        defaultInstance.initFields();
    }

    private final ByteString unknownFields;
    private int bitField0_;
    private List<IrStatement> branch_;
    private int originName_;
    private byte memoizedIsInitialized = -1;
    private int memoizedSerializedSize = -1;

    // Use IrWhen.newBuilder() to construct.
    private IrWhen(GeneratedMessageLite.Builder builder) {
        super(builder);
        this.unknownFields = builder.getUnknownFields();
    }

    private IrWhen(boolean noInit) {
        this.unknownFields = ByteString.EMPTY;
    }

    private IrWhen(
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
                        if (!((mutable_bitField0_ & 0x00000001) == 0x00000001)) {
                            branch_ = new ArrayList<IrStatement>();
                            mutable_bitField0_ |= 0x00000001;
                        }
                        branch_.add(input.readMessage(IrStatement.PARSER, extensionRegistry));
                        break;
                    }
                    case 16: {
                        bitField0_ |= 0x00000001;
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
            if (((mutable_bitField0_ & 0x00000001) == 0x00000001)) {
                branch_ = Collections.unmodifiableList(branch_);
            }
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

    public static IrWhen getDefaultInstance() {
        return defaultInstance;
    }

    public static IrWhen parseFrom(
            ByteString data)
            throws InvalidProtocolBufferException {
        return PARSER.parseFrom(data);
    }

    public static IrWhen parseFrom(
            ByteString data,
            ExtensionRegistryLite extensionRegistry)
            throws InvalidProtocolBufferException {
        return PARSER.parseFrom(data, extensionRegistry);
    }

    public static IrWhen parseFrom(byte[] data)
            throws InvalidProtocolBufferException {
        return PARSER.parseFrom(data);
    }

    public static IrWhen parseFrom(
            byte[] data,
            ExtensionRegistryLite extensionRegistry)
            throws InvalidProtocolBufferException {
        return PARSER.parseFrom(data, extensionRegistry);
    }

    public static IrWhen parseFrom(InputStream input)
            throws IOException {
        return PARSER.parseFrom(input);
    }

    public static IrWhen parseFrom(
            InputStream input,
            ExtensionRegistryLite extensionRegistry)
            throws IOException {
        return PARSER.parseFrom(input, extensionRegistry);
    }

    public static IrWhen parseDelimitedFrom(InputStream input)
            throws IOException {
        return PARSER.parseDelimitedFrom(input);
    }

    public static IrWhen parseDelimitedFrom(
            InputStream input,
            ExtensionRegistryLite extensionRegistry)
            throws IOException {
        return PARSER.parseDelimitedFrom(input, extensionRegistry);
    }

    public static IrWhen parseFrom(
            CodedInputStream input)
            throws IOException {
        return PARSER.parseFrom(input);
    }

    public static IrWhen parseFrom(
            CodedInputStream input,
            ExtensionRegistryLite extensionRegistry)
            throws IOException {
        return PARSER.parseFrom(input, extensionRegistry);
    }

    public static Builder newBuilder() {
        return Builder.create();
    }

    public static Builder newBuilder(IrWhen prototype) {
        return newBuilder().mergeFrom(prototype);
    }

    @Override
    public IrWhen getDefaultInstanceForType() {
        return defaultInstance;
    }

    @Override
    public Parser<IrWhen> getParserForType() {
        return PARSER;
    }

    /**
     * <code>repeated .org.jetbrains.kotlin.backend.common.serialization.proto.IrStatement branch = 1;</code>
     */
    @Override
    public List<IrStatement> getBranchList() {
        return branch_;
    }

    /**
     * <code>repeated .org.jetbrains.kotlin.backend.common.serialization.proto.IrStatement branch = 1;</code>
     */
    public List<? extends IrStatementOrBuilder>
    getBranchOrBuilderList() {
        return branch_;
    }

    /**
     * <code>repeated .org.jetbrains.kotlin.backend.common.serialization.proto.IrStatement branch = 1;</code>
     */
    @Override
    public int getBranchCount() {
        return branch_.size();
    }

    /**
     * <code>repeated .org.jetbrains.kotlin.backend.common.serialization.proto.IrStatement branch = 1;</code>
     */
    @Override
    public IrStatement getBranch(int index) {
        return branch_.get(index);
    }

    /**
     * <code>repeated .org.jetbrains.kotlin.backend.common.serialization.proto.IrStatement branch = 1;</code>
     */
    public IrStatementOrBuilder getBranchOrBuilder(
            int index) {
        return branch_.get(index);
    }

    /**
     * <code>optional int32 origin_name = 2;</code>
     */
    @Override
    public boolean hasOriginName() {
        return ((bitField0_ & 0x00000001) == 0x00000001);
    }

    /**
     * <code>optional int32 origin_name = 2;</code>
     */
    @Override
    public int getOriginName() {
        return originName_;
    }

    private void initFields() {
        branch_ = Collections.emptyList();
        originName_ = 0;
    }

    @Override
    public boolean isInitialized() {
        byte isInitialized = memoizedIsInitialized;
        if (isInitialized == 1) return true;
        if (isInitialized == 0) return false;

        for (int i = 0; i < getBranchCount(); i++) {
            if (!getBranch(i).isInitialized()) {
                memoizedIsInitialized = 0;
                return false;
            }
        }
        memoizedIsInitialized = 1;
        return true;
    }

    @Override
    public void writeTo(CodedOutputStream output)
            throws IOException {
        getSerializedSize();
        for (int i = 0; i < branch_.size(); i++) {
            output.writeMessage(1, branch_.get(i));
        }
        if (((bitField0_ & 0x00000001) == 0x00000001)) {
            output.writeInt32(2, originName_);
        }
        output.writeRawBytes(unknownFields);
    }

    @Override
    public int getSerializedSize() {
        int size = memoizedSerializedSize;
        if (size != -1) return size;

        size = 0;
        for (int i = 0; i < branch_.size(); i++) {
            size += CodedOutputStream
                    .computeMessageSize(1, branch_.get(i));
        }
        if (((bitField0_ & 0x00000001) == 0x00000001)) {
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
     * Protobuf type {@code org.jetbrains.kotlin.backend.common.serialization.proto.IrWhen}
     */
    public static final class Builder extends
            GeneratedMessageLite.Builder<
                    IrWhen, Builder>
            implements
            // @@protoc_insertion_point(builder_implements:org.jetbrains.kotlin.backend.common.serialization.proto.IrWhen)
            IrWhenOrBuilder {
        private int bitField0_;
        private List<IrStatement> branch_ =
                Collections.emptyList();
        private int originName_;

        // Construct using org.jetbrains.kotlin.backend.common.serialization.proto.IrWhen.newBuilder()
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
            branch_ = Collections.emptyList();
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
        public IrWhen getDefaultInstanceForType() {
            return getDefaultInstance();
        }

        @Override
        public IrWhen build() {
            IrWhen result = buildPartial();
            if (!result.isInitialized()) {
                throw newUninitializedMessageException(result);
            }
            return result;
        }

        @Override
        public IrWhen buildPartial() {
            IrWhen result = new IrWhen(this);
            int from_bitField0_ = bitField0_;
            int to_bitField0_ = 0;
            if (((bitField0_ & 0x00000001) == 0x00000001)) {
                branch_ = Collections.unmodifiableList(branch_);
                bitField0_ &= ~0x00000001;
            }
            result.branch_ = branch_;
            if (((from_bitField0_ & 0x00000002) == 0x00000002)) {
                to_bitField0_ |= 0x00000001;
            }
            result.originName_ = originName_;
            result.bitField0_ = to_bitField0_;
            return result;
        }

        @Override
        public Builder mergeFrom(IrWhen other) {
            if (other == getDefaultInstance())
                return this;
            if (!other.branch_.isEmpty()) {
                if (branch_.isEmpty()) {
                    branch_ = other.branch_;
                    bitField0_ &= ~0x00000001;
                } else {
                    ensureBranchIsMutable();
                    branch_.addAll(other.branch_);
                }

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
            for (int i = 0; i < getBranchCount(); i++) {
                if (!getBranch(i).isInitialized()) {

                    return false;
                }
            }
            return true;
        }

        @Override
        public Builder mergeFrom(
                CodedInputStream input,
                ExtensionRegistryLite extensionRegistry)
                throws IOException {
            IrWhen parsedMessage = null;
            try {
                parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
            } catch (InvalidProtocolBufferException e) {
                parsedMessage = (IrWhen) e.getUnfinishedMessage();
                throw e;
            } finally {
                if (parsedMessage != null) {
                    mergeFrom(parsedMessage);
                }
            }
            return this;
        }

        private void ensureBranchIsMutable() {
            if (!((bitField0_ & 0x00000001) == 0x00000001)) {
                branch_ = new ArrayList<IrStatement>(branch_);
                bitField0_ |= 0x00000001;
            }
        }

        /**
         * <code>repeated .org.jetbrains.kotlin.backend.common.serialization.proto.IrStatement branch = 1;</code>
         */
        @Override
        public List<IrStatement> getBranchList() {
            return Collections.unmodifiableList(branch_);
        }

        /**
         * <code>repeated .org.jetbrains.kotlin.backend.common.serialization.proto.IrStatement branch = 1;</code>
         */
        @Override
        public int getBranchCount() {
            return branch_.size();
        }

        /**
         * <code>repeated .org.jetbrains.kotlin.backend.common.serialization.proto.IrStatement branch = 1;</code>
         */
        @Override
        public IrStatement getBranch(int index) {
            return branch_.get(index);
        }

        /**
         * <code>repeated .org.jetbrains.kotlin.backend.common.serialization.proto.IrStatement branch = 1;</code>
         */
        public Builder setBranch(
                int index, IrStatement value) {
            if (value == null) {
                throw new NullPointerException();
            }
            ensureBranchIsMutable();
            branch_.set(index, value);

            return this;
        }

        /**
         * <code>repeated .org.jetbrains.kotlin.backend.common.serialization.proto.IrStatement branch = 1;</code>
         */
        public Builder setBranch(
                int index, IrStatement.Builder builderForValue) {
            ensureBranchIsMutable();
            branch_.set(index, builderForValue.build());

            return this;
        }

        /**
         * <code>repeated .org.jetbrains.kotlin.backend.common.serialization.proto.IrStatement branch = 1;</code>
         */
        public Builder addBranch(IrStatement value) {
            if (value == null) {
                throw new NullPointerException();
            }
            ensureBranchIsMutable();
            branch_.add(value);

            return this;
        }

        /**
         * <code>repeated .org.jetbrains.kotlin.backend.common.serialization.proto.IrStatement branch = 1;</code>
         */
        public Builder addBranch(
                int index, IrStatement value) {
            if (value == null) {
                throw new NullPointerException();
            }
            ensureBranchIsMutable();
            branch_.add(index, value);

            return this;
        }

        /**
         * <code>repeated .org.jetbrains.kotlin.backend.common.serialization.proto.IrStatement branch = 1;</code>
         */
        public Builder addBranch(
                IrStatement.Builder builderForValue) {
            ensureBranchIsMutable();
            branch_.add(builderForValue.build());

            return this;
        }

        /**
         * <code>repeated .org.jetbrains.kotlin.backend.common.serialization.proto.IrStatement branch = 1;</code>
         */
        public Builder addBranch(
                int index, IrStatement.Builder builderForValue) {
            ensureBranchIsMutable();
            branch_.add(index, builderForValue.build());

            return this;
        }

        /**
         * <code>repeated .org.jetbrains.kotlin.backend.common.serialization.proto.IrStatement branch = 1;</code>
         */
        public Builder addAllBranch(
                Iterable<? extends IrStatement> values) {
            ensureBranchIsMutable();
            addAll(
                    values, branch_);

            return this;
        }

        /**
         * <code>repeated .org.jetbrains.kotlin.backend.common.serialization.proto.IrStatement branch = 1;</code>
         */
        public Builder clearBranch() {
            branch_ = Collections.emptyList();
            bitField0_ &= ~0x00000001;

            return this;
        }

        /**
         * <code>repeated .org.jetbrains.kotlin.backend.common.serialization.proto.IrStatement branch = 1;</code>
         */
        public Builder removeBranch(int index) {
            ensureBranchIsMutable();
            branch_.remove(index);

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

        // @@protoc_insertion_point(builder_scope:org.jetbrains.kotlin.backend.common.serialization.proto.IrWhen)
    }

    // @@protoc_insertion_point(class_scope:org.jetbrains.kotlin.backend.common.serialization.proto.IrWhen)
}
