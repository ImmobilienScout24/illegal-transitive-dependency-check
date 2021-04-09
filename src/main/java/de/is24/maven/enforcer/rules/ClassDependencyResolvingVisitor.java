package de.is24.maven.enforcer.rules;

import org.apache.maven.plugin.logging.Log;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;


final class ClassDependencyResolvingVisitor extends ClassVisitor {
  private final Repository repository;
  private final Log logger;
  private final AnnotationVisitor annotationVisitor = new ClassDependencyAnnotationVisitor();
  private final FieldVisitor fieldVisitor = new ClassDependencyFieldVisitor();
  private final MethodVisitor methodVisitor = new ClassDependencyMethodVisitor();
  private final SignatureVisitor signatureVisitor = new ClassDependencySignatureVisitor();

  ClassDependencyResolvingVisitor(Repository repository, Log logger) {
    super(Opcodes.ASM8);
    this.repository = repository;
    this.logger = logger;
  }

  @Override
  public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
    final String className = Types.readInternalTypeName(name);
    logger.debug("Add new type '" + className + "'.");
    repository.addType(className);

    if (superName != null) {
      final String superTypeName = Types.readInternalTypeName(superName);
      addDependency("super type", superTypeName);
    }

    if (interfaces != null) {
      for (String iface : interfaces) {
        final String interfaceType = Types.readInternalTypeName(iface);
        addDependency("interface type", interfaceType);
      }
    }

    processSignature(signature);
  }

  @Override
  public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
    final String fieldType = Types.readTypeDescription(desc);
    addDependency("field type", fieldType);

    // add initial field value if any
    if (value != null) {
      final String fieldValueType = Types.readValueType(value);
      addDependency("field value type", fieldValueType);
    }
    processSignature(signature);

    return fieldVisitor;
  }

  @Override
  public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
    return delegateToAnnotationVisitor(desc);
  }

  @Override
  public void visitInnerClass(String name, String outerName, String innerName, int access) {
    final String innerClassName = Types.readInternalTypeName(name);
    addDependency("inner class", innerClassName);
  }

  @Override
  public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
    final Type[] argumentTypes = Type.getArgumentTypes(desc);

    for (Type argumentType : argumentTypes) {
      final String parameterTypeName = Types.readType(argumentType);
      addDependency("annotation's method parameter type",
        parameterTypeName);
    }

    final Type returnType = Type.getReturnType(desc);
    final String returnTypeName = Types.readType(returnType);
    addDependency("annotation's method return type", returnTypeName);

    if (exceptions != null) {
      for (String exception : exceptions) {
        final String exceptionName = Types.readInternalTypeName(exception);
        addDependency("exception type", exceptionName);
      }
    }

    processSignature(signature);

    return methodVisitor;
  }

  private AnnotationVisitor delegateToAnnotationVisitor(String desc) {
    final String annotationType = Types.readTypeDescription(desc);
    addDependency("annotation", annotationType);

    return annotationVisitor;
  }

  private void addDependency(String typeDescription, String typeName) {
    if (logger.isDebugEnabled()) {
      logger.debug("Add " + typeDescription + " '" + typeName + "' as dependency.");
    }
    repository.addDependency(typeName);
  }

  private void processSignature(String signature) {
    if (signature != null) {
      final SignatureReader signatureReader = new SignatureReader(
        signature);
      signatureReader.accept(signatureVisitor);
    }
  }

  private final class ClassDependencySignatureVisitor extends SignatureVisitor {
    private ClassDependencySignatureVisitor() {
      super(Opcodes.ASM8);
    }

    @Override
    public void visitClassType(String name) {
      final String classType = Types.readInternalTypeName(name);
      addDependency("class type", classType);
    }
  }

  private final class ClassDependencyAnnotationVisitor extends AnnotationVisitor {
    private ClassDependencyAnnotationVisitor() {
      super(Opcodes.ASM8);
    }

    @Override
    public void visit(String name, Object value) {
      final String valueType = Types.readValueType(value);
      addDependency("annotation's value type", valueType);
    }

    @Override
    public void visitEnum(String name, String desc, String value) {
      final String enumType = Types.readTypeDescription(desc);
      addDependency("annotation's enum type", enumType);
    }

    @Override
    public AnnotationVisitor visitAnnotation(String name, String desc) {
      final String annotationType = Types.readTypeDescription(desc);
      addDependency("annotation's annotation type", annotationType);

      return this;
    }

    @Override
    public AnnotationVisitor visitArray(String name) {
      return this;
    }
  }

  private final class ClassDependencyFieldVisitor extends FieldVisitor {
    private ClassDependencyFieldVisitor() {
      super(Opcodes.ASM8);
    }

    @Override
    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
      return delegateToAnnotationVisitor(desc);
    }
  }

  private final class ClassDependencyMethodVisitor extends MethodVisitor {
    private ClassDependencyMethodVisitor() {
      super(Opcodes.ASM8);
    }

    @Override
    public AnnotationVisitor visitAnnotationDefault() {
      return annotationVisitor;
    }

    @Override
    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
      return delegateToAnnotationVisitor(desc);
    }

    @Override
    public AnnotationVisitor visitParameterAnnotation(int parameter, String desc, boolean visible) {
      return delegateToAnnotationVisitor(desc);
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
      final String typeName = Types.readInternalTypeName(type);
      addDependency("Type instruction type", typeName);
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String desc) {
      final String fieldType = Types.readTypeDescription(desc);
      addDependency("field instruction type", fieldType);
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
      final String ownerType = Types.readInternalTypeName(owner);
      addDependency("method owner", ownerType);

      final Type[] argumentTypes = Type.getArgumentTypes(desc);

      for (Type argumentType : argumentTypes) {
        final String parameterTypeName = Types.readType(argumentType);
        addDependency("method parameter type", parameterTypeName);
      }

      final Type returnType = Type.getReturnType(desc);
      final String returnTypeName = Types.readType(returnType);
      addDependency("method return type", returnTypeName);
    }

    @Override
    public void visitLdcInsn(Object cst) {
      final String constantTypeName = Types.readValueType(cst);
      addDependency("constant's type", constantTypeName);
    }

    @Override
    public void visitMultiANewArrayInsn(String desc, int dims) {
      final String arrayType = Types.readTypeDescription(desc);
      addDependency("array's type", arrayType);
    }

    @Override
    public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
      if (type != null) {
        final String exceptionType = Types.readInternalTypeName(type);
        addDependency("exception type", exceptionType);
      }
    }

    @Override
    public void visitLocalVariable(String name, String desc, String signature, Label start, Label end, int index) {
      final String localVariableType = Types.readTypeDescription(desc);
      addDependency("local variable", localVariableType);

      processSignature(signature);
    }
  }
}
