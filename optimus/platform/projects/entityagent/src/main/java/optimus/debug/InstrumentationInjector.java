/*
 * Morgan Stanley makes this available to you under the Apache License, Version 2.0 (the "License").
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package optimus.debug;

import static optimus.debug.InstrumentationConfig.CACHED_VALUE_TYPE;
import static optimus.debug.InstrumentationInjector.OBJECT_ARR_DESC;
import static optimus.debug.InstrumentationInjector.OBJECT_DESC;
import static optimus.debug.InstrumentationInjector.ENTITY_DESC;
import static optimus.debug.InstrumentationInjector.SCALA_NOTHING;
import static optimus.debug.InstrumentationInjector.OBJECT_TYPE;
import static optimus.debug.InstrumentationConfig.patchForSuffixAsNode;
import static optimus.debug.InstrumentationConfig.patchForCachingMethod;
import static optimus.debug.InstrumentationConfig.patchForBracketingLzyCompute;
import static org.objectweb.asm.Opcodes.ACC_INTERFACE;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.ArrayList;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.commons.GeneratorAdapter;

/** In doubt see: var asm = BiopsyLab.byteCodeAsAsm(cw.toByteArray()); */
public class InstrumentationInjector implements ClassFileTransformer {
  final static Type SCALA_NOTHING = Type.getType("Lscala/runtime/Nothing$;");
  final static String OBJECT_DESC = "Ljava/lang/Object;";
  final static String ENTITY_DESC = "Loptimus/platform/storable/Entity;";
  final static String ENTITY_COMPANION_BASE = "Loptimus/platform/storable/EntityCompanionBase;";
  final static String OBJECT_ARR_DESC = "[Ljava/lang/Object;";
  final static Type OBJECT_TYPE = Type.getObjectType("java/lang/Object");

  @Override
  public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
                          byte[] bytes) throws IllegalClassFormatException {

    InstrumentationConfig.ClassPatch patch = InstrumentationConfig.forClass(className);
    if (patch == null && !InstrumentationConfig.instrumentAnyGroups())
      return bytes;

    ClassReader crSource = new ClassReader(bytes);

    if(InstrumentationConfig.instrumentAllEntities && InstrumentationConfig.isEntity(className, crSource.getSuperName())) {
      InstrumentationConfig.addMarkScenarioStackAsInitializing(className);
      if (patch == null)
        patch = InstrumentationConfig.forClass(className);  // Re-read reconfigured value
    }

    boolean addHashCode = shouldAddHashCode(loader, crSource, className);
    if (patch != null && patch.methodForward == null && addHashCode)
      setForwardMethodToNewHashCode(className, patch);

    if (patch == null && addHashCode) {
      patch = new InstrumentationConfig.ClassPatch();
      setForwardMethodToNewHashCode(className, patch);
    }

    if(InstrumentationConfig.instrumentAllEntityApplies && shouldCacheApplyMethods(crSource, className)) {
      if (patch == null)
        patch = new InstrumentationConfig.ClassPatch();
      patch.cacheAllApplies = true;
    }

    if(InstrumentationConfig.instrumentAllModuleConstructors && shouldAddModuleConstructorBracketing(loader, className)) {
      InstrumentationConfig.addModuleConstructionIntercept(className);
      if (patch == null)
        patch = InstrumentationConfig.forClass(className);  // Re-read reconfigured value
      patch.bracketAllLzyComputes = true;
    }

    if (patch == null)
      return bytes;

    ClassWriter cw = new ClassWriter(crSource, ClassWriter.COMPUTE_FRAMES);
    ClassVisitor cv = new InstrumentationInjectorAdapter(patch, className, cw);
    crSource.accept(cv, ClassReader.SKIP_FRAMES);
    return cw.toByteArray();
  }

  private boolean shouldCacheApplyMethods(ClassReader crSource, String className) {
    if (!className.endsWith("$"))
      return false;    // Looking for companion objects
    String[] interfaces = crSource.getInterfaces();
    for (String iface : interfaces)
      if (ENTITY_COMPANION_BASE.equals(iface))
        return true;
    return false;
  }


  private boolean shouldAddModuleConstructorBracketing(ClassLoader loader, String className) {
    if (loader == null)
      return false;

    if (!className.endsWith("$"))
      return false;    // Looking for companion objects

    if (className.startsWith("scala"))
      return false;

    return !InstrumentationConfig.isModuleExcluded(className);
  }

  private boolean shouldAddHashCode(ClassLoader loader, ClassReader crSource, String className) {
    if (!InstrumentationConfig.instrumentAllHashCodes)
      return false;
    // Interfaces are not included
    if ((crSource.getAccess() & ACC_INTERFACE) != 0)
      return false;
    // Only class with base class Object should be patched
    if (!crSource.getSuperName().equals(OBJECT_TYPE.getInternalName()))
      return false;

    if (loader == null)
      return false;

    // Probably should be extracted
    if(className.startsWith("scala/reflect"))
      return false;

    // Presumably we know what we are doing, also ProfilerEventsWriter for sure needs to be ignored
    return !className.startsWith("sun/") && !className.startsWith("java/security");
  }

  private void setForwardMethodToNewHashCode(String className, InstrumentationConfig.ClassPatch patch) {
    var mrHashCode = new InstrumentationConfig.MethodRef(className, "hashCode", "()I");
    patch.methodForward = new InstrumentationConfig.MethodForward(mrHashCode, InstrumentedHashCodes.mrHashCode);
  }
}

class InstrumentationInjectorAdapter extends ClassVisitor implements Opcodes {
  private InstrumentationConfig.ClassPatch classPatch;
  private String className;
  private boolean seenForwardedMethod;

  InstrumentationInjectorAdapter(InstrumentationConfig.ClassPatch patch, String className, ClassVisitor cv) {
    super(ASM9, cv);
    this.classPatch = patch;
    this.className = className;
  }

  private static boolean maybeSimpleGetter(int access, String name, String desc) {
    if((access & ACC_STATIC) != 0)    // 1. Only care about "normal" vals
      return false;
    if (!desc.startsWith("()"))       // 2. Takes no args
      return false;
    if (name.contains("$"))           // 3. Some hidden method we probably don't care about
      return false;

    // 4. Return type can't be void or scala.Nothing
    Type fieldType = Type.getReturnType(desc);
    return fieldType != Type.VOID_TYPE && !SCALA_NOTHING.equals(fieldType);
  }

  private boolean isCreateEntityMethod(String desc) {
    var returnType = Type.getReturnType(desc).getInternalName();
    return returnType.equals(className.substring(0, className.length() - 1));
  }

  public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
    if (classPatch.methodForward != null && name.equals(classPatch.methodForward.from.method))
      seenForwardedMethod = true;

    MethodVisitor mv = cv.visitMethod(access, name, desc, signature, exceptions);
    InstrumentationConfig.MethodPatch methodPatch = classPatch.forMethod(name, desc);
    if (methodPatch != null)
      return new InstrumentationInjectorMethodVisitor(methodPatch, mv, access, name, desc);
    else if(classPatch.cacheAllApplies && name.equals("apply") && isCreateEntityMethod(desc))
      return new InstrumentationInjectorMethodVisitor(patchForCachingMethod(className, name), mv, access, name, desc);
    else if (classPatch.traceValsAsNodes && maybeSimpleGetter(access, name, desc))
      return new InstrumentationInjectorMethodVisitor(patchForSuffixAsNode(className, name), mv, access, name, desc);
    else if(classPatch.bracketAllLzyComputes && name.endsWith("$lzycompute"))
      return new InstrumentationInjectorMethodVisitor(patchForBracketingLzyCompute(className, name), mv, access, name, desc);
    else
      return mv; // Just copy the entire method
  }

  private void writeEqualsForCachingOverride() {
    var mv = cv.visitMethod(ACC_PUBLIC, "equalsForCachingInternal", "(" + ENTITY_DESC + ")Z", null, null);
    mv.visitCode();
    Label label0 = new Label();
    mv.visitLabel(label0);
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    Label label1 = new Label();
    mv.visitJumpInsn(IF_ACMPNE, label1);
    mv.visitInsn(ICONST_1);
    Label label2 = new Label();
    mv.visitJumpInsn(GOTO, label2);
    mv.visitLabel(label1);
    mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
    mv.visitInsn(ICONST_0);
    mv.visitLabel(label2);
    mv.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] { Opcodes.INTEGER });
    mv.visitInsn(IRETURN);
    Label label3 = new Label();
    mv.visitLabel(label3);
    mv.visitLocalVariable("this", "L" + className + ";", null, label0, label3, 0);
    mv.visitLocalVariable("other", ENTITY_DESC, null, label0, label3, 1);
    mv.visitMaxs(0, 0); // The real values will be populated automatically
    mv.visitEnd();
  }

  private void writeImplementForwardCall(InstrumentationConfig.MethodForward forwards) {
    assert forwards.from.descriptor != null;
    var mv = cv.visitMethod(ACC_PUBLIC, forwards.from.method, forwards.from.descriptor, null, null);
    var mv2 = new GeneratorAdapter(mv, ACC_PUBLIC, forwards.from.method, forwards.from.descriptor);
    var argTypes = mv2.getArgumentTypes();
    var forwardsToArgs = new ArrayList<Type>();
    forwardsToArgs.add(OBJECT_TYPE);

    mv2.loadThis();
    for (int i = 0; i < argTypes.length; i++) {
      mv2.loadArg(i);
      forwardsToArgs.add(argTypes[i]);
    }

    Type forwardsToType = Type.getMethodType(mv2.getReturnType(), forwardsToArgs.toArray(new Type[0]));
    mv2.visitMethodInsn(INVOKESTATIC, forwards.to.cls, forwards.to.method, forwardsToType.getDescriptor(), false);
    mv2.returnValue();
    mv2.visitMaxs(0,0); // The real values will be populated automatically
    mv2.visitEnd();
  }

  @Override
  public void visitEnd() {
    if (classPatch.methodForward != null && !seenForwardedMethod)
      writeImplementForwardCall(classPatch.methodForward);

    if (classPatch.poisonCacheEquality)
      writeEqualsForCachingOverride();

    for (var fpatch : classPatch.fieldPatches) {
      var type = fpatch.type != null ? fpatch.type : OBJECT_DESC;
      cv.visitField(0, fpatch.name, type, null, null);
    }
    super.visitEnd();
  }
}

class InstrumentationInjectorMethodVisitor extends AdviceAdapter implements Opcodes {
  private InstrumentationConfig.MethodPatch patch;
  private Label __localValueStart = new Label();
  private Label __localValueEnd = new Label();
  private int __localValue;     // When local passing is enabled this will point to a slot for local var
  private Type localValueType;
  private String localValueDesc;
  private int methodID;         // If allocation requested

  InstrumentationInjectorMethodVisitor(InstrumentationConfig.MethodPatch patch, MethodVisitor mv, int access,
                                       String name, String descriptor) {
    super(ASM9, mv, access, name, descriptor);
    this.patch = patch;
    if (patch.passLocalValue) {
      localValueType = patch.prefix.descriptor != null
                       ? Type.getMethodType(patch.prefix.descriptor).getReturnType()
                       : OBJECT_TYPE;
      localValueDesc = localValueType.getDescriptor();
    }
  }

  private void dupReturnValueOrNullForVoid(int opcode) {
    if (opcode == RETURN)
      visitInsn(ACONST_NULL);
    else if (opcode == ARETURN || opcode == ATHROW)
      dup();
    else if (opcode == LRETURN || opcode == DRETURN)
      dup2(); // double/long take two slots
    else {
      dup();
      box(Type.getReturnType(this.methodDesc));
    }
  }

  private String loadMethodID() {
    if (methodID == 0)
      methodID = InstrumentationConfig.allocateID(patch.from);
    mv.visitIntInsn(SIPUSH, methodID);
    return "I";
  }

  private String loadThisOrNull() {
    if ((methodAccess & ACC_STATIC) != 0 || getName().equals("<init>"))
      mv.visitInsn(ACONST_NULL);    // static will just pass null and ctor will temporarily pass null
    else
      loadThis();
    return OBJECT_DESC;
  }

  private void ifNotZeroReturn(InstrumentationConfig.FieldPatch fpatch) {
    loadThis();
    mv.visitFieldInsn(GETFIELD, patch.from.cls, fpatch.name, fpatch.type);
    Label label1 = new Label();
    mv.visitJumpInsn(IFEQ, label1);
    loadThis();
    mv.visitFieldInsn(GETFIELD, patch.from.cls, fpatch.name, fpatch.type);
    mv.visitInsn(IRETURN);
    mv.visitLabel(label1);
  }

  private void injectMethodPrefix() {
    if (patch.cacheInField != null)
      ifNotZeroReturn(patch.cacheInField);

    if (patch.prefix == null)
      return;

    if (patch.passLocalValue) {
      visitLabel(__localValueStart);
      __localValue = newLocal(localValueType);
    }

    var descriptor = "(";
    if (patch.prefixWithID)
      descriptor += loadMethodID();

    if (patch.prefixWithArgs) {
      descriptor += loadThisOrNull();
      loadArgArray();
      descriptor += OBJECT_ARR_DESC;
    }
    if (patch.passLocalValue || patch.storeToField != null)
      descriptor += ")" + OBJECT_DESC;
    else
      descriptor += ")V";

    // If descriptor was supplied just use that
    if (patch.prefix.descriptor != null)
      descriptor = patch.prefix.descriptor;

    mv.visitMethodInsn(Opcodes.INVOKESTATIC, patch.prefix.cls, patch.prefix.method, descriptor, false);
    if (patch.passLocalValue) {
      mv.visitVarInsn(localValueType.getOpcode(ISTORE), __localValue);
    } else if (patch.storeToField != null) {
      loadThis();
      swap();
      mv.visitFieldInsn(PUTFIELD, patch.from.cls, patch.storeToField.name, OBJECT_DESC);
    }

    if (patch.checkAndReturn) {
      mv.visitVarInsn(ALOAD, __localValue);
      mv.visitFieldInsn(GETFIELD, CACHED_VALUE_TYPE, "hasResult", "Z");
      var continueLabel = new Label();
      mv.visitJumpInsn(IFEQ, continueLabel);
      mv.visitVarInsn(ALOAD, __localValue);
      mv.visitFieldInsn(GETFIELD, CACHED_VALUE_TYPE, "result", OBJECT_DESC);
      mv.visitTypeInsn(CHECKCAST, Type.getReturnType(methodDesc).getInternalName());
      mv.visitInsn(ARETURN);
      mv.visitLabel(continueLabel);
    }
  }

  @Override
  public void visitCode() {
    super.visitCode();
    injectMethodPrefix();
  }

  @Override
  protected void onMethodEnter() {
    // Consider adding code to report 'this' in the case of constructor
  }

  @Override
  protected void onMethodExit(int opcode) {
    if (patch.cacheInField != null) {
      dup();
      loadThis();
      swap();
      mv.visitFieldInsn(PUTFIELD, patch.from.cls, patch.cacheInField.name, patch.cacheInField.type);
    }

    if (patch.suffix == null)
      return;

    var descriptor = "(";

    if (patch.suffixWithReturnValue) {
      dupReturnValueOrNullForVoid(opcode);
      descriptor += OBJECT_DESC;
    }
    if (patch.passLocalValue) {
      mv.visitVarInsn(localValueType.getOpcode(ILOAD), __localValue);
      descriptor += localValueType.getDescriptor();
    }
    if (patch.suffixWithID)
      descriptor += loadMethodID();
    if (patch.suffixWithThis)
      descriptor += loadThisOrNull();

    descriptor += ")V";

    mv.visitMethodInsn(Opcodes.INVOKESTATIC, patch.suffix.cls, patch.suffix.method, descriptor, false);
  }

  @Override
  public void visitMaxs(int maxStack, int maxLocals) {
    if (patch.passLocalValue) {
      visitLabel(__localValueEnd);
      mv.visitLocalVariable("__locValue", localValueDesc, null, __localValueStart, __localValueEnd, __localValue);
    }
    super.visitMaxs(maxStack, maxLocals);
  }
}