/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.compiler.plugins.kotlin.lower

import androidx.compose.compiler.plugins.kotlin.ComposeClassIds.StabilityInferred
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.library.metadata.KlibMetadataSerializerProtocol
import org.jetbrains.kotlin.metadata.ProtoBuf
import org.jetbrains.kotlin.metadata.deserialization.Flags.HAS_ANNOTATIONS
import org.jetbrains.kotlin.metadata.serialization.MutableVersionRequirementTable
import org.jetbrains.kotlin.serialization.DescriptorSerializer
import org.jetbrains.kotlin.serialization.DescriptorSerializerPlugin
import org.jetbrains.kotlin.serialization.SerializerExtension

/**
 * A static final int is synthesized onto all classes in order to allow for stability to be
 * determined at runtime. We need to know from other modules whether or not this field got
 * synthesized or not though, and to do that, we also synthesize an annotation on the class.
 *
 * The kotlin metadata has a flag to indicate whether or not there are any annotations on the
 * class or not. If the flag is false, then a synthesized annotation will never be seen from
 * another module, so we have to use this plugin to flip the flag for all classes that we
 * synthesize the annotation on, even if the source of the class didn't have any annotations.
 */
// 정적 최종 int는 런타임에 안정성을 판단할 수 있도록 모든 클래스에 합성됩니다.
// 하지만 다른 모듈에서 이 필드가 합성되었는지 여부를 알아야 하므로 이를 위해 클래스에 대한
// 어노테이션도 합성합니다.
//
// kotlin 메타데이터에는 클래스에 어노테이션이 있는지 여부를 나타내는 플래그가 있습니다.
// 플래그가 거짓이면 다른 모듈에서 합성된 어노테이션을 볼 수 없으므로 클래스 소스에
// 어노테이션이 없더라도 이 플러그인을 사용하여 어노테이션을 합성하는 모든 클래스에 대해
// 플래그를 뒤집어야 합니다.
class ClassStabilityFieldSerializationPlugin(
    // 항상 null임
    val classStabilityInferredCollection: ClassStabilityInferredCollection? = null,
) : DescriptorSerializerPlugin {
    private val hasAnnotationFlag = HAS_ANNOTATIONS.toFlags(true)

    private fun createAnnotationProto(
        extension: SerializerExtension,
        value: Int,
    ): ProtoBuf.Annotation {
        return ProtoBuf.Annotation.newBuilder().apply {
            id = extension.stringTable.getQualifiedClassNameIndex(StabilityInferred)
            // Same as in StabilityInferred definition
            val ix = extension.stringTable.getStringIndex("parameters")
            addArgument(ProtoBuf.Annotation.Argument.newBuilder().apply {
                setNameId(ix)
                setValue(
                    ProtoBuf.Annotation.Argument.Value.newBuilder()
                        .setIntValue(value.toLong())
                        .setType(ProtoBuf.Annotation.Argument.Value.Type.INT)
                )
            })
        }.build()
    }

    override fun afterClass(
        descriptor: ClassDescriptor,
        proto: ProtoBuf.Class.Builder,
        versionRequirementTable: MutableVersionRequirementTable,
        childSerializer: DescriptorSerializer,
        extension: SerializerExtension,
    ) {
        // 안정성 추론이 불가능한 클래스 타입
        if (
            descriptor.visibility != DescriptorVisibilities.PUBLIC ||
            descriptor.kind == ClassKind.ENUM_CLASS ||
            descriptor.kind == ClassKind.ENUM_ENTRY ||
            descriptor.kind == ClassKind.INTERFACE ||
            descriptor.kind == ClassKind.ANNOTATION_CLASS ||
            descriptor.isExpect ||
            descriptor.isInner ||
            descriptor.isCompanionObject ||
            descriptor.isInline
        ) return

        if (proto.flags and hasAnnotationFlag == 0) {
            proto.flags = proto.flags or hasAnnotationFlag
        }
    }
}
