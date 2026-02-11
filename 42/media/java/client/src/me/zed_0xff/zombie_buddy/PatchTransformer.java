package me.zed_0xff.zombie_buddy;

import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;

import net.bytebuddy.jar.asm.AnnotationVisitor;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

/**
 * Transforms patch classes by replacing Patch.OnEnter/OnExit/Return annotations with 
 * net.bytebuddy.asm.Advice.* annotations in the class bytecode.
 * This allows mods to use Patch.* annotations without depending on ByteBuddy directly.
 */
final class PatchTransformer {
    
    /**
     * Transforms a patch class by replacing Patch.OnEnter/OnExit/Return annotations with 
     * net.bytebuddy.asm.Advice.* annotations in the class bytecode.
     * This allows mods to use Patch.* annotations without depending on ByteBuddy directly.
     * 
     * @param patchClass The patch class to transform
     * @param instrumentation Instrumentation instance for class redefinition
     * @param verbosity Verbosity level for logging
     * @param isMethodDelegation Whether this is a MethodDelegation patch (true) or Advice patch (false)
     */
    public static Class<?> transformPatchClass(Class<?> patchClass, Instrumentation instrumentation, int verbosity, boolean isMethodDelegation) {
        try {
            
            // Check if transformation is needed and warn about non-void return types
            boolean needsTransformation = false;
            for (Method method : patchClass.getDeclaredMethods()) {
                boolean hasOnEnter = method.isAnnotationPresent(me.zed_0xff.zombie_buddy.Patch.OnEnter.class);
                boolean hasOnExit = method.isAnnotationPresent(me.zed_0xff.zombie_buddy.Patch.OnExit.class);
                boolean hasRuntimeType = method.isAnnotationPresent(me.zed_0xff.zombie_buddy.Patch.RuntimeType.class);
                if (hasOnEnter || hasOnExit || hasRuntimeType) {
                    needsTransformation = true;
                }
                // Warn about non-void return types (check all methods, not just first one)
                if (hasOnEnter || hasOnExit) {
                    Class<?> returnType = method.getReturnType();
                    if (returnType != void.class) {
                        boolean hasSkipOnSet = false;
                        if (hasOnEnter) {
                            var ann = method.getAnnotation(me.zed_0xff.zombie_buddy.Patch.OnEnter.class);
                            if (ann.skipOn()) hasSkipOnSet = true;
                        }
                        
                        if (!hasSkipOnSet) {
                            Logger.err.println("[ZB] !!!!!!!");
                            Logger.err.println("[ZB] WARNING: Annotated method " + method.getName() + 
                                "() in patch class " + patchClass.getName() + 
                                " returns non-void. This may cause UB and diarrhea.");
                            Logger.err.println("[ZB] !!!!!!!");
                        }
                    }
                }
                if (!needsTransformation) {
                    for (java.lang.reflect.Parameter param : method.getParameters()) {
                        if (param.isAnnotationPresent(me.zed_0xff.zombie_buddy.Patch.Return.class) ||
                            param.isAnnotationPresent(me.zed_0xff.zombie_buddy.Patch.This.class) ||
                            param.isAnnotationPresent(me.zed_0xff.zombie_buddy.Patch.Argument.class) ||
                            param.isAnnotationPresent(me.zed_0xff.zombie_buddy.Patch.Local.class) ||
                            param.isAnnotationPresent(me.zed_0xff.zombie_buddy.Patch.SuperMethod.class) ||
                            param.isAnnotationPresent(me.zed_0xff.zombie_buddy.Patch.SuperCall.class)) {
                            needsTransformation = true;
                            break;
                        }
                    }
                }
            }

            if (verbosity > 1) {
                Logger.out.println("[ZB] class " + patchClass.getName() + " needs transformation: " + needsTransformation);
            }
            
            if (!needsTransformation) {
                return patchClass; // Already uses ByteBuddy annotations or has no annotations
            }

            // Read class file bytes
            String className = patchClass.getName().replace('.', '/');
            String classFileName = className + ".class";
            InputStream classStream = patchClass.getClassLoader().getResourceAsStream(classFileName);
            if (classStream == null) {
                Logger.err.println("[ZB] Could not read class file for " + patchClass.getName());
                return patchClass;
            }

            byte[] classBytes = classStream.readAllBytes();
            classStream.close();

            // Use ASM to rewrite annotation descriptors
            final boolean isMethodDelegationFinal = isMethodDelegation;
            ClassReader cr = new ClassReader(classBytes);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES);
            cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, 
                                               String signature, String[] exceptions) {
                    int lastParen = descriptor.lastIndexOf(')');
                    final boolean isNonVoid = lastParen >= 0 && lastParen < descriptor.length() - 1 && 
                                             descriptor.charAt(lastParen + 1) != 'V';
                    final boolean[] hasPatchAnnotation = {false};
                    final boolean[] hasSkipOnSet = {false};
                    final String methodName = name;
                    
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                            String originalDescriptor = descriptor;
                            String newDescriptor = rewriteAnnotationDescriptor(descriptor, isMethodDelegationFinal);
                            
                            if (originalDescriptor.equals("Lme/zed_0xff/zombie_buddy/Patch$OnEnter;") ||
                                originalDescriptor.equals("Lme/zed_0xff/zombie_buddy/Patch$OnExit;") ||
                                originalDescriptor.equals("Lme/zed_0xff/zombie_buddy/Patch$RuntimeType;")) {
                                hasPatchAnnotation[0] = true;
                            }
                            
                            AnnotationVisitor av = super.visitAnnotation(newDescriptor, visible);
                            // If we rewrote the descriptor, we need to forward all annotation values
                            if (!newDescriptor.equals(descriptor)) {
                                return new AnnotationVisitor(Opcodes.ASM9, av) {
                                    @Override
                                    public void visit(String name, Object value) {
                                        if ("skipOn".equals(name) && value instanceof Boolean) {
                                            boolean skip = (Boolean) value;
                                            hasSkipOnSet[0] = skip;
                                            // Translate boolean skipOn to ByteBuddy's expected Class<?> skipOn
                                            if (skip) {
                                                value = net.bytebuddy.jar.asm.Type.getType("Lnet/bytebuddy/asm/Advice$OnNonDefaultValue;");
                                            } else {
                                                value = net.bytebuddy.jar.asm.Type.getType("Lnet/bytebuddy/asm/Advice$NoException;");
                                            }
                                        } else if (value instanceof net.bytebuddy.jar.asm.Type) {
                                            net.bytebuddy.jar.asm.Type type = (net.bytebuddy.jar.asm.Type) value;
                                            String descriptor = type.getDescriptor();
                                            
                                            if (descriptor.equals("Lme/zed_0xff/zombie_buddy/Patch$NoException;")) {
                                                value = net.bytebuddy.jar.asm.Type.getType("Lnet/bytebuddy/asm/Advice$NoException;");
                                            } else if (descriptor.equals("Lme/zed_0xff/zombie_buddy/Patch$OnDefaultValue;")) {
                                                value = net.bytebuddy.jar.asm.Type.getType("Lnet/bytebuddy/asm/Advice$OnDefaultValue;");
                                            } else if (descriptor.equals("Lme/zed_0xff/zombie_buddy/Patch$OnNonDefaultValue;")) {
                                                value = net.bytebuddy.jar.asm.Type.getType("Lnet/bytebuddy/asm/Advice$OnNonDefaultValue;");
                                            }
                                        }
                                        super.visit(name, value);
                                    }
                                    @Override
                                    public void visitEnum(String name, String descriptor, String value) {
                                        super.visitEnum(name, descriptor, value);
                                    }
                                    @Override
                                    public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                                        return super.visitAnnotation(name, descriptor);
                                    }
                                    @Override
                                    public AnnotationVisitor visitArray(String name) {
                                        return super.visitArray(name);
                                    }
                                };
                            }
                            return av;
                        }
                        
                        @Override
                        public void visitEnd() {
                            if (hasPatchAnnotation[0] && isNonVoid && !hasSkipOnSet[0]) {
                                Logger.err.println("[ZB] WARNING: Method " + methodName + 
                                    " in patch class " + patchClass.getName() + 
                                    " is annotated with @Patch.OnEnter or @Patch.OnExit but returns non-void. This may cause UB and diarrhea.");
                            }
                            super.visitEnd();
                        }

                        @Override
                        public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) {
                            String newDescriptor = rewriteAnnotationDescriptor(descriptor, isMethodDelegationFinal);
                            if (!newDescriptor.equals(descriptor)) {
                                // We need to rewrite the annotation - create the new annotation and forward values
                                AnnotationVisitor av = super.visitParameterAnnotation(parameter, newDescriptor, visible);
                                // Return a wrapper that forwards all annotation values from the original to the new
                                return new AnnotationVisitor(Opcodes.ASM9, av) {
                                    @Override
                                    public void visit(String name, Object value) {
                                        // Forward all annotation values (readOnly, value, etc.)
                                        super.visit(name, value);
                                    }
                                    @Override
                                    public void visitEnum(String name, String descriptor, String value) {
                                        super.visitEnum(name, descriptor, value);
                                    }
                                    @Override
                                    public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                                        return super.visitAnnotation(name, descriptor);
                                    }
                                    @Override
                                    public AnnotationVisitor visitArray(String name) {
                                        return super.visitArray(name);
                                    }
                                    @Override
                                    public void visitEnd() {
                                        super.visitEnd();
                                    }
                                };
                            }
                            // No rewrite needed, use original
                            return super.visitParameterAnnotation(parameter, descriptor, visible);
                        }
                    };
                }
            }, ClassReader.EXPAND_FRAMES);

            byte[] transformedBytes = cw.toByteArray();
            
            // Define the transformed class using Instrumentation
            if (instrumentation != null) {
                try {
                    java.lang.instrument.ClassDefinition classDef = new java.lang.instrument.ClassDefinition(
                        patchClass, transformedBytes);
                    instrumentation.redefineClasses(classDef);
                    return patchClass; // Return the redefined class
                } catch (Exception e) {
                    Logger.err.println("[ZB] Failed to redefine class " + patchClass.getName() + ": " + e.getMessage());
                    if (verbosity > 0) {
                        e.printStackTrace();
                    }
                }
            }
            
            // Fallback: try to define as a new class (won't work if class is already loaded)
            try {
                java.lang.reflect.Method defineClass = ClassLoader.class.getDeclaredMethod(
                    "defineClass", String.class, byte[].class, int.class, int.class);
                defineClass.setAccessible(true);
                return (Class<?>) defineClass.invoke(patchClass.getClassLoader(), 
                    patchClass.getName() + "$ZBTransformed", transformedBytes, 0, transformedBytes.length);
            } catch (Exception e) {
                Logger.err.println("[ZB] Failed to define transformed class: " + e.getMessage());
                if (verbosity > 0) {
                    e.printStackTrace();
                }
            }
            
            return patchClass; // Fall back to original
        } catch (Exception e) {
            Logger.err.println("[ZB] Failed to transform patch class " + patchClass.getName() + ": " + e.getMessage());
            if (verbosity > 0) {
                e.printStackTrace();
            }
            return patchClass; // Fall back to original
        }
    }

    private static String rewriteAnnotationDescriptor(String descriptor, boolean isMethodDelegation) {
        if (descriptor.equals("Lme/zed_0xff/zombie_buddy/Patch$OnEnter;")) {
            return "Lnet/bytebuddy/asm/Advice$OnMethodEnter;";
        } else if (descriptor.equals("Lme/zed_0xff/zombie_buddy/Patch$OnExit;")) {
            return "Lnet/bytebuddy/asm/Advice$OnMethodExit;";
        } else if (descriptor.equals("Lme/zed_0xff/zombie_buddy/Patch$Return;")) {
            return "Lnet/bytebuddy/asm/Advice$Return;";
        } else if (descriptor.equals("Lme/zed_0xff/zombie_buddy/Patch$This;")) {
            // For MethodDelegation, use bind.annotation.This; for Advice, use asm.Advice$This
            if (isMethodDelegation) {
                return "Lnet/bytebuddy/implementation/bind/annotation/This;";
            } else {
                return "Lnet/bytebuddy/asm/Advice$This;";
            }
        } else if (descriptor.equals("Lme/zed_0xff/zombie_buddy/Patch$Argument;")) {
            // For MethodDelegation, use bind.annotation.Argument; for Advice, use asm.Advice$Argument
            if (isMethodDelegation) {
                return "Lnet/bytebuddy/implementation/bind/annotation/Argument;";
            } else {
                return "Lnet/bytebuddy/asm/Advice$Argument;";
            }
        } else if (descriptor.equals("Lme/zed_0xff/zombie_buddy/Patch$AllArguments;")) {
            // For MethodDelegation, use bind.annotation.AllArguments; for Advice, use asm.Advice$AllArguments
            if (isMethodDelegation) {
                return "Lnet/bytebuddy/implementation/bind/annotation/AllArguments;";
            } else {
                return "Lnet/bytebuddy/asm/Advice$AllArguments;";
            }
        } else if (descriptor.equals("Lme/zed_0xff/zombie_buddy/Patch$RuntimeType;")) {
            return "Lnet/bytebuddy/implementation/bind/annotation/RuntimeType;";
        } else if (descriptor.equals("Lme/zed_0xff/zombie_buddy/Patch$SuperMethod;")) {
            return "Lnet/bytebuddy/implementation/bind/annotation/SuperMethod;";
        } else if (descriptor.equals("Lme/zed_0xff/zombie_buddy/Patch$SuperCall;")) {
            return "Lnet/bytebuddy/implementation/bind/annotation/SuperCall;";
        } else if (descriptor.equals("Lme/zed_0xff/zombie_buddy/Patch$Local;")) {
            return "Lnet/bytebuddy/asm/Advice$Local;";
        }
        return descriptor;
    }
}
