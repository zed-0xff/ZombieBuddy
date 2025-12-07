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
     */
    public static Class<?> transformPatchClass(Class<?> patchClass, Instrumentation instrumentation, int verbosity) {
        try {
            // Check if transformation is needed
            boolean needsTransformation = false;
            for (Method method : patchClass.getDeclaredMethods()) {
                for (java.lang.annotation.Annotation ann : method.getAnnotations()) {
                    String annType = ann.annotationType().getName();
                    if (annType.equals("me.zed_0xff.zombie_buddy.Patch$OnEnter") ||
                        annType.equals("me.zed_0xff.zombie_buddy.Patch$OnExit")) {
                        needsTransformation = true;
                        break;
                    }
                }
                if (!needsTransformation) {
                    for (java.lang.annotation.Annotation[] paramAnns : method.getParameterAnnotations()) {
                        for (java.lang.annotation.Annotation ann : paramAnns) {
                            String annName = ann.annotationType().getName();
                            if (annName.equals("me.zed_0xff.zombie_buddy.Patch$Return") ||
                                annName.equals("me.zed_0xff.zombie_buddy.Patch$This")) {
                                needsTransformation = true;
                                break;
                            }
                        }
                        if (needsTransformation) break;
                    }
                }
                if (needsTransformation) break;
            }
            
            if (!needsTransformation) {
                return patchClass; // Already uses ByteBuddy annotations or has no annotations
            }

            // Read class file bytes
            String className = patchClass.getName().replace('.', '/');
            String classFileName = className + ".class";
            InputStream classStream = patchClass.getClassLoader().getResourceAsStream(classFileName);
            if (classStream == null) {
                System.err.println("[ZB] Could not read class file for " + patchClass.getName());
                return patchClass;
            }

            byte[] classBytes = classStream.readAllBytes();
            classStream.close();

            // Use ASM to rewrite annotation descriptors
            ClassReader cr = new ClassReader(classBytes);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES);
            cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, 
                                               String signature, String[] exceptions) {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                            String newDescriptor = rewriteAnnotationDescriptor(descriptor);
                            AnnotationVisitor av = super.visitAnnotation(newDescriptor, visible);
                            // If we rewrote the descriptor, we need to forward all annotation values
                            if (!newDescriptor.equals(descriptor)) {
                                return new AnnotationVisitor(Opcodes.ASM9, av) {
                                    @Override
                                    public void visit(String name, Object value) {
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
                        public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) {
                            String newDescriptor = rewriteAnnotationDescriptor(descriptor);
                            AnnotationVisitor av = super.visitParameterAnnotation(parameter, newDescriptor, visible);
                            if (!newDescriptor.equals(descriptor)) {
                                return new AnnotationVisitor(Opcodes.ASM9, av) {
                                    @Override
                                    public void visit(String name, Object value) {
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
                    System.err.println("[ZB] Failed to redefine class " + patchClass.getName() + ": " + e.getMessage());
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
                System.err.println("[ZB] Failed to define transformed class: " + e.getMessage());
                if (verbosity > 0) {
                    e.printStackTrace();
                }
            }
            
            return patchClass; // Fall back to original
        } catch (Exception e) {
            System.err.println("[ZB] Failed to transform patch class " + patchClass.getName() + ": " + e.getMessage());
            if (verbosity > 0) {
                e.printStackTrace();
            }
            return patchClass; // Fall back to original
        }
    }

    private static String rewriteAnnotationDescriptor(String descriptor) {
        if (descriptor.equals("Lme/zed_0xff/zombie_buddy/Patch$OnEnter;")) {
            return "Lnet/bytebuddy/asm/Advice$OnMethodEnter;";
        } else if (descriptor.equals("Lme/zed_0xff/zombie_buddy/Patch$OnExit;")) {
            return "Lnet/bytebuddy/asm/Advice$OnMethodExit;";
        } else if (descriptor.equals("Lme/zed_0xff/zombie_buddy/Patch$Return;")) {
            return "Lnet/bytebuddy/asm/Advice$Return;";
        } else if (descriptor.equals("Lme/zed_0xff/zombie_buddy/Patch$This;")) {
            return "Lnet/bytebuddy/asm/Advice$This;";
        }
        return descriptor;
    }
}
