/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.descriptors;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import kotlin.collections.SetsKt;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.descriptors.impl.TypeAliasConstructorDescriptor;
import org.jetbrains.kotlin.resolve.DescriptorUtils;
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue;
import org.jetbrains.kotlin.resolve.scopes.receivers.SuperCallReceiverValue;
import org.jetbrains.kotlin.resolve.scopes.receivers.ThisClassReceiver;
import org.jetbrains.kotlin.types.DynamicTypesKt;
import org.jetbrains.kotlin.types.KotlinType;
import org.jetbrains.kotlin.util.ModuleVisibilityHelper;
import org.jetbrains.kotlin.utils.CollectionsKt;

public class DescriptorVisibilities {
  @NotNull
  public static final DescriptorVisibility PRIVATE = new DelegatedDescriptorVisibility(Visibilities.Private.INSTANCE) {
    private boolean hasContainingSourceFile(@NotNull DeclarationDescriptor descriptor) {
      return DescriptorUtils.getContainingSourceFile(descriptor) != SourceFile.NO_SOURCE_FILE;
    }

    @Override
    public boolean isVisible(
      @Nullable ReceiverValue receiver,
      @NotNull DeclarationDescriptorWithVisibility what,
      @NotNull DeclarationDescriptor from,
      boolean useSpecialRulesForPrivateSealedConstructors
    ) {
      if (DescriptorUtils.isTopLevelDeclaration(what) && hasContainingSourceFile(from)) {
        return inSameFile(what, from);
      }

      if (what instanceof ConstructorDescriptor) {
        ClassifierDescriptorWithTypeParameters classDescriptor = ((ConstructorDescriptor) what).getContainingDeclaration();
        if (useSpecialRulesForPrivateSealedConstructors
          && DescriptorUtils.isSealedClass(classDescriptor)
          && DescriptorUtils.isTopLevelDeclaration(classDescriptor)
          && from instanceof ConstructorDescriptor
          && DescriptorUtils.isTopLevelDeclaration(from.getContainingDeclaration())
          && inSameFile(what, from)) {
          return true;
        }
      }

      DeclarationDescriptor parent = what;
      while (parent != null) {
        parent = parent.getContainingDeclaration();
        if ((parent instanceof ClassDescriptor && !DescriptorUtils.isCompanionObject(parent)) ||
          parent instanceof PackageFragmentDescriptor) {
          break;
        }
      }
      if (parent == null) {
        return false;
      }
      DeclarationDescriptor fromParent = from;
      while (fromParent != null) {
        if (parent == fromParent) {
          return true;
        }
        if (fromParent instanceof PackageFragmentDescriptor) {
          return parent instanceof PackageFragmentDescriptor
            && ((PackageFragmentDescriptor) parent).getFqName().equals(((PackageFragmentDescriptor) fromParent).getFqName())
            && DescriptorUtils.areInSameModule(fromParent, parent);
        }
        fromParent = fromParent.getContainingDeclaration();
      }
      return false;
    }
  };
  @NotNull
  public static final DescriptorVisibility PUBLIC = new DelegatedDescriptorVisibility(Visibilities.Public.INSTANCE) {
    @Override
    public boolean isVisible(
      @Nullable ReceiverValue receiver,
      @NotNull DeclarationDescriptorWithVisibility what,
      @NotNull DeclarationDescriptor from,
      boolean useSpecialRulesForPrivateSealedConstructors
    ) {
      return true;
    }
  };
  @NotNull
  public static final DescriptorVisibility LOCAL = new DelegatedDescriptorVisibility(Visibilities.Local.INSTANCE) {
    @Override
    public boolean isVisible(
      @Nullable ReceiverValue receiver,
      @NotNull DeclarationDescriptorWithVisibility what,
      @NotNull DeclarationDescriptor from,
      boolean useSpecialRulesForPrivateSealedConstructors
    ) {
      throw new IllegalStateException("This method shouldn't be invoked for LOCAL visibility");
    }
  };
  @NotNull
  public static final DescriptorVisibility INHERITED = new DelegatedDescriptorVisibility(Visibilities.Inherited.INSTANCE) {
    @Override
    public boolean isVisible(
      @Nullable ReceiverValue receiver,
      @NotNull DeclarationDescriptorWithVisibility what,
      @NotNull DeclarationDescriptor from,
      boolean useSpecialRulesForPrivateSealedConstructors
    ) {
      throw new IllegalStateException("Visibility is unknown yet"); //This method shouldn't be invoked for INHERITED visibility
    }
  };
  /* Visibility for fake override invisible members (they are created for better error reporting) */
  @NotNull
  public static final DescriptorVisibility INVISIBLE_FAKE = new DelegatedDescriptorVisibility(Visibilities.InvisibleFake.INSTANCE) {
    @Override
    public boolean isVisible(
      @Nullable ReceiverValue receiver,
      @NotNull DeclarationDescriptorWithVisibility what,
      @NotNull DeclarationDescriptor from,
      boolean useSpecialRulesForPrivateSealedConstructors
    ) {
      return false;
    }
  };
  // Currently used as default visibility of FunctionDescriptor
  // It's needed to prevent NPE when requesting non-nullable visibility of descriptor before `initialize` has been called
  @NotNull
  public static final DescriptorVisibility UNKNOWN = new DelegatedDescriptorVisibility(Visibilities.Unknown.INSTANCE) {
    @Override
    public boolean isVisible(
      @Nullable ReceiverValue receiver, @NotNull DeclarationDescriptorWithVisibility what, @NotNull DeclarationDescriptor from,
      boolean useSpecialRulesForPrivateSealedConstructors
    ) {
      return false;
    }
  };
  public static final DescriptorVisibility DEFAULT_VISIBILITY = PUBLIC;
  /**
   * This value should be used for receiverValue parameter of Visibility.isVisible
   * iff there is intention to determine if member is visible without receiver related checks being performed.
   */
  public static final ReceiverValue ALWAYS_SUITABLE_RECEIVER = new ReceiverValue() {
    @NotNull
    @Override
    public KotlinType getType() {
      throw new IllegalStateException("This method should not be called");
    }

    @NotNull
    @Override
    public ReceiverValue replaceType(@NotNull KotlinType newType) {
      throw new IllegalStateException("This method should not be called");
    }

    @NotNull
    @Override
    public ReceiverValue getOriginal() {
      return this;
    }
  };
  // This constant is not intended to use somewhere else from
  @Deprecated
  public static final ReceiverValue FALSE_IF_PROTECTED = new ReceiverValue() {
    @NotNull
    @Override
    public KotlinType getType() {
      throw new IllegalStateException("This method should not be called");
    }

    @NotNull
    @Override
    public ReceiverValue replaceType(@NotNull KotlinType newType) {
      throw new IllegalStateException("This method should not be called");
    }

    @NotNull
    @Override
    public ReceiverValue getOriginal() {
      return this;
    }
  };
  private static final Map<DescriptorVisibility, Integer> ORDERED_VISIBILITIES;
  /**
   * This value should be used for receiverValue parameter of Visibility.isVisible
   * iff there is intention to determine if member is visible for any receiver.
   */
  private static final ReceiverValue IRRELEVANT_RECEIVER = new ReceiverValue() {
    @NotNull
    @Override
    public KotlinType getType() {
      throw new IllegalStateException("This method should not be called");
    }

    @NotNull
    @Override
    public ReceiverValue replaceType(@NotNull KotlinType newType) {
      throw new IllegalStateException("This method should not be called");
    }

    @NotNull
    @Override
    public ReceiverValue getOriginal() {
      return this;
    }
  };
  /**
   * This visibility is needed for the next case:
   * class A<in T>(t: T) {
   * private val t: T = t // visibility for t is PRIVATE_TO_THIS
   * <p>
   * fun test() {
   * val x: T = t // correct
   * val y: T = this.t // also correct
   * }
   * fun foo(a: A<String>) {
   * val x: String = a.t // incorrect, because a.t can be Any
   * }
   * }
   */
  @NotNull
  public static final DescriptorVisibility PRIVATE_TO_THIS = new DelegatedDescriptorVisibility(Visibilities.PrivateToThis.INSTANCE) {
    @Override
    public boolean isVisible(
      @Nullable ReceiverValue thisObject,
      @NotNull DeclarationDescriptorWithVisibility what,
      @NotNull DeclarationDescriptor from,
      boolean useSpecialRulesForPrivateSealedConstructors
    ) {
      if (PRIVATE.isVisible(thisObject, what, from, useSpecialRulesForPrivateSealedConstructors)) {
        // See Visibility.isVisible contract
        if (thisObject == ALWAYS_SUITABLE_RECEIVER) return true;
        if (thisObject == IRRELEVANT_RECEIVER) return false;

        DeclarationDescriptor classDescriptor = DescriptorUtils.getParentOfType(what, ClassDescriptor.class);

        if (classDescriptor != null && thisObject instanceof ThisClassReceiver) {
          return ((ThisClassReceiver) thisObject).getClassDescriptor().getOriginal().equals(classDescriptor.getOriginal());
        }
      }
      return false;
    }
  };
  @NotNull
  public static final DescriptorVisibility PROTECTED = new DelegatedDescriptorVisibility(Visibilities.Protected.INSTANCE) {
    @Override
    public boolean isVisible(
      @Nullable ReceiverValue receiver,
      @NotNull DeclarationDescriptorWithVisibility what,
      @NotNull DeclarationDescriptor from,
      boolean useSpecialRulesForPrivateSealedConstructors
    ) {
      ClassDescriptor givenDescriptorContainingClass = DescriptorUtils.getParentOfType(what, ClassDescriptor.class);
      ClassDescriptor fromClass = DescriptorUtils.getParentOfType(from, ClassDescriptor.class, false);
      if (fromClass == null) return false;

      if (givenDescriptorContainingClass != null && DescriptorUtils.isCompanionObject(givenDescriptorContainingClass)) {
        // Access to protected members inside companion is allowed to all subclasses
        // Receiver type does not matter because objects are final
        // NB: protected fake overrides in companion from super class should also be allowed
        ClassDescriptor companionOwner = DescriptorUtils.getParentOfType(givenDescriptorContainingClass, ClassDescriptor.class);
        if (companionOwner != null && DescriptorUtils.isSubclass(fromClass, companionOwner)) return true;
      }

      // The rest part of method checks visibility similarly to Java does for protected (see JLS p.6.6.2)

      // Protected fake overrides can have only one protected overridden (as protected is not allowed for interface members)
      DeclarationDescriptorWithVisibility whatDeclaration = DescriptorUtils.unwrapFakeOverrideToAnyDeclaration(what);

      ClassDescriptor classDescriptor = DescriptorUtils.getParentOfType(whatDeclaration, ClassDescriptor.class);
      if (classDescriptor == null) return false;

      if (DescriptorUtils.isSubclass(fromClass, classDescriptor)
        && doesReceiverFitForProtectedVisibility(receiver, whatDeclaration, fromClass)) {
        return true;
      }

      return isVisible(receiver, what, fromClass.getContainingDeclaration(), useSpecialRulesForPrivateSealedConstructors);
    }

    private boolean doesReceiverFitForProtectedVisibility(
      @Nullable ReceiverValue receiver,
      @NotNull DeclarationDescriptorWithVisibility whatDeclaration,
      @NotNull ClassDescriptor fromClass
    ) {
      //noinspection deprecation
      if (receiver == FALSE_IF_PROTECTED) return false;

      // Do not check receiver for non-callable declarations
      if (!(whatDeclaration instanceof CallableMemberDescriptor)) return true;
      // Constructor accessibility check is performed manually
      if (whatDeclaration instanceof ConstructorDescriptor) return true;

      // See Visibility.isVisible contract
      if (receiver == ALWAYS_SUITABLE_RECEIVER) return true;
      if (receiver == IRRELEVANT_RECEIVER || receiver == null) return false;

      KotlinType actualReceiverType = receiver instanceof SuperCallReceiverValue
        ? ((SuperCallReceiverValue) receiver).getThisType()
        : receiver.getType();

      return DescriptorUtils.isSubtypeOfClass(actualReceiverType, fromClass) || DynamicTypesKt.isDynamic(actualReceiverType);
    }
  };
  @NotNull
  private static final ModuleVisibilityHelper MODULE_VISIBILITY_HELPER;
  @NotNull
  public static final DescriptorVisibility INTERNAL = new DelegatedDescriptorVisibility(Visibilities.Internal.INSTANCE) {
    @Override
    public boolean isVisible(
      @Nullable ReceiverValue receiver,
      @NotNull DeclarationDescriptorWithVisibility what,
      @NotNull DeclarationDescriptor from,
      boolean useSpecialRulesForPrivateSealedConstructors
    ) {
      ModuleDescriptor whatModule = DescriptorUtils.getContainingModule(what);
      ModuleDescriptor fromModule = DescriptorUtils.getContainingModule(from);

      // Can't invert this condition because CLI compiler analyzes sources as like all in the one module
      // and for modules with circular dependency (chunk) JPS provides sources of all modules,
      // so we can't be sure that references to an internal member are correct.
      if (!fromModule.shouldSeeInternalsOf(whatModule)) return false;


      return MODULE_VISIBILITY_HELPER.isInFriendModule(what, from);
    }
  };
  public static final Set<DescriptorVisibility> INVISIBLE_FROM_OTHER_MODULES =
    Collections.unmodifiableSet(SetsKt.setOf(PRIVATE, PRIVATE_TO_THIS, INTERNAL, LOCAL));
  @NotNull
  private static final Map<Visibility, DescriptorVisibility> visibilitiesMapping = new HashMap<Visibility, DescriptorVisibility>();

  static {
    Map<DescriptorVisibility, Integer> visibilities = CollectionsKt.newHashMapWithExpectedSize(4);
    visibilities.put(PRIVATE_TO_THIS, 0);
    visibilities.put(PRIVATE, 0);
    visibilities.put(INTERNAL, 1);
    visibilities.put(PROTECTED, 1);
    visibilities.put(PUBLIC, 2);
    ORDERED_VISIBILITIES = Collections.unmodifiableMap(visibilities);
  }

  static {
    Iterator<ModuleVisibilityHelper> iterator = ServiceLoader.load(ModuleVisibilityHelper.class, ModuleVisibilityHelper.class.getClassLoader()).iterator();
    MODULE_VISIBILITY_HELPER = iterator.hasNext() ? iterator.next() : ModuleVisibilityHelper.EMPTY.INSTANCE;
  }

  static {
    recordVisibilityMapping(PRIVATE);
    recordVisibilityMapping(PRIVATE_TO_THIS);
    recordVisibilityMapping(PROTECTED);
    recordVisibilityMapping(INTERNAL);
    recordVisibilityMapping(PUBLIC);
    recordVisibilityMapping(LOCAL);
    recordVisibilityMapping(INHERITED);
    recordVisibilityMapping(INVISIBLE_FAKE);
    recordVisibilityMapping(UNKNOWN);
  }

  private DescriptorVisibilities() {
  }

  public static boolean isVisible(
    @Nullable ReceiverValue receiver,
    @NotNull DeclarationDescriptorWithVisibility what,
    @NotNull DeclarationDescriptor from,
    boolean useSpecialRulesForPrivateSealedConstructors
  ) {
    return findInvisibleMember(receiver, what, from, useSpecialRulesForPrivateSealedConstructors) == null;
  }

  /**
   * @see DescriptorVisibility.isVisible contract
   */
  public static boolean isVisibleIgnoringReceiver(
    @NotNull DeclarationDescriptorWithVisibility what,
    @NotNull DeclarationDescriptor from,
    boolean useSpecialRulesForPrivateSealedConstructors
  ) {
    return findInvisibleMember(ALWAYS_SUITABLE_RECEIVER, what, from, useSpecialRulesForPrivateSealedConstructors) == null;
  }

  /**
   * @see DescriptorVisibility.isVisible contract
   * @see DescriptorVisibilities.RECEIVER_DOES_NOT_EXIST
   */
  public static boolean isVisibleWithAnyReceiver(
    @NotNull DeclarationDescriptorWithVisibility what,
    @NotNull DeclarationDescriptor from,
    boolean useSpecialRulesForPrivateSealedConstructors
  ) {
    return findInvisibleMember(IRRELEVANT_RECEIVER, what, from, useSpecialRulesForPrivateSealedConstructors) == null;
  }

  // Note that this method returns false if `from` declaration is `init` initializer
  // because initializer does not have source element
  public static boolean inSameFile(@NotNull DeclarationDescriptor what, @NotNull DeclarationDescriptor from) {
    SourceFile fromContainingFile = DescriptorUtils.getContainingSourceFile(from);
    if (fromContainingFile != SourceFile.NO_SOURCE_FILE) {
      return fromContainingFile.equals(DescriptorUtils.getContainingSourceFile(what));
    }
    return false;
  }

  @Nullable
  public static DeclarationDescriptorWithVisibility findInvisibleMember(
    @Nullable ReceiverValue receiver,
    @NotNull DeclarationDescriptorWithVisibility what,
    @NotNull DeclarationDescriptor from,
    boolean useSpecialRulesForPrivateSealedConstructors
  ) {
    DeclarationDescriptorWithVisibility parent = (DeclarationDescriptorWithVisibility) what.getOriginal();
    while (parent != null && parent.getVisibility() != LOCAL) {
      if (!parent.getVisibility().isVisible(receiver, parent, from, useSpecialRulesForPrivateSealedConstructors)) {
        return parent;
      }
      parent = DescriptorUtils.getParentOfType(parent, DeclarationDescriptorWithVisibility.class);
    }

    if (what instanceof TypeAliasConstructorDescriptor) {
      DeclarationDescriptorWithVisibility invisibleUnderlying =
        findInvisibleMember(
          receiver,
          ((TypeAliasConstructorDescriptor) what).getUnderlyingConstructorDescriptor(),
          from,
          useSpecialRulesForPrivateSealedConstructors
        );
      if (invisibleUnderlying != null) return invisibleUnderlying;
    }

    return null;
  }

  /*package*/
  @Nullable
  static Integer compareLocal(@NotNull DescriptorVisibility first, @NotNull DescriptorVisibility second) {
    if (first == second) return 0;
    Integer firstIndex = ORDERED_VISIBILITIES.get(first);
    Integer secondIndex = ORDERED_VISIBILITIES.get(second);
    if (firstIndex == null || secondIndex == null || firstIndex.equals(secondIndex)) {
      return null;
    }
    return firstIndex - secondIndex;
  }

  @Nullable
  public static Integer compare(@NotNull DescriptorVisibility first, @NotNull DescriptorVisibility second) {
    Integer result = first.compareTo(second);
    if (result != null) {
      return result;
    }
    Integer oppositeResult = second.compareTo(first);
    if (oppositeResult != null) {
      return -oppositeResult;
    }
    return null;
  }

  public static boolean isPrivate(@NotNull DescriptorVisibility visibility) {
    return visibility == PRIVATE || visibility == PRIVATE_TO_THIS;
  }

  private static void recordVisibilityMapping(DescriptorVisibility visibility) {
    visibilitiesMapping.put(visibility.getDelegate(), visibility);
  }

  @NotNull
  public static DescriptorVisibility toDescriptorVisibility(@NotNull Visibility visibility) {
    DescriptorVisibility correspondingVisibility = visibilitiesMapping.get(visibility);
    if (correspondingVisibility == null) {
      throw new IllegalArgumentException("Inapplicable visibility: " + visibility);
    }
    return correspondingVisibility;
  }
}
