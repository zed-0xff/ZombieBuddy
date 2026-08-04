package me.zed_0xff.zombie_buddy.transformers.bytebuddy;

import java.util.Map;

import me.zed_0xff.zombie_buddy.Logger;
import me.zed_0xff.zombie_buddy.annotations.Shadow;
import me.zed_0xff.zombie_buddy.transformers.JarContext;
import me.zed_0xff.zombie_buddy.transformers.ShadowContext;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.commons.ClassRemapper;
import net.bytebuddy.jar.asm.commons.Remapper;

import static net.bytebuddy.matcher.ElementMatchers.named;

/** Collects {@link Shadow} type mappings, then rewrites non-shadow classes to reference patch targets instead of shadow stubs. */
public class Unshadow extends AbstractTransformer {

    /** Scan {@code classNames} for {@link Shadow} types and store shadow → target mappings on {@code jctx}. */
    public static void collectShadowDescriptorMappings(JarContext jctx, Iterable<String> classNames) {
        for (String className : classNames) {
            TypeDescription shadowTd = jctx.getOrigTypeDesc(className);
            if (shadowTd == null) {
                continue;
            }

            var shadowAnn = shadowTd.getDeclaredAnnotations().ofType(Shadow.class);
            if (shadowAnn == null) {
                continue;
            }

            String targetBin = shadowAnn.load().className();
            TypeDescription targetTd = jctx.getOrigTypeDesc(targetBin);
            if (targetTd == null) {
                Logger.once.warn("Unshadow: unresolved shadow target", targetBin);
                continue;
            }

            String shadowInternal = shadowTd.getInternalName();
            jctx.putShadowDescriptorMapping(shadowInternal, targetTd.getInternalName());

            ShadowContext accessCtx = ShadowContext.fromTarget(targetBin, targetTd);

            for (FieldDescription.InDefinedShape field : shadowTd.getDeclaredFields()) {
                var fieldAnn = field.getDeclaredAnnotations().ofType(Shadow.Field.class);
                if (fieldAnn == null) {
                    continue;
                }

                String targetField = resolveTargetFieldName(field, fieldAnn.load(), targetTd);
                if (targetField == null) {
                    continue;
                }

                accessCtx.addShadowField(shadowInternal, field, targetTd, targetField);
            }

            for (MethodDescription.InDefinedShape method : shadowTd.getDeclaredMethods()) {
                var castAnn = method.getDeclaredAnnotations().ofType(Shadow.Cast.class);
                if (castAnn != null) {
                    if (isValidCastMethod(method)) {
                        accessCtx.addShadowCast(method, remapDescriptor(method.getDescriptor(), shadowInternal, targetTd.getInternalName()));
                    }

                    continue;
                }

                var methodAnn = method.getDeclaredAnnotations().ofType(Shadow.Method.class);
                if (methodAnn == null) {
                    continue;
                }

                String targetMethod = resolveTargetMethodName(method, methodAnn.load(), targetTd);
                if (targetMethod == null) {
                    continue;
                }

                accessCtx.addShadowMethod(shadowInternal, method, targetTd, targetMethod);
            }

            jctx.putShadowContext(targetBin, accessCtx);
        }
    }

    private static boolean isValidCastMethod(MethodDescription.InDefinedShape method) {
        if (!method.isStatic() || method.getParameters().size() != 1 || method.getDescriptor().endsWith(")V")) {
            Logger.once.warn("@Shadow.Cast method must be static, take one argument, and return a value", method.getDeclaringType().asErasure().getName() + "#" + method.getName() + method.getDescriptor());
            return false;
        }

        return true;
    }

    private static String remapDescriptor(String descriptor, String shadowInternal, String targetInternal) {
        return descriptor.replace("L" + shadowInternal + ";", "L" + targetInternal + ";");
    }

    private static String resolveTargetFieldName(FieldDescription field, Shadow.Field ann, TypeDescription targetTd) {
        String[] value = ann.value();
        if (value.length == 1) {
            return value[0];
        }

        if (value.length == 0) {
            return field.getName();
        }

        for (String candidate : value) {
            if (!targetTd.getDeclaredFields().filter(named(candidate)).isEmpty()) {
                return candidate;
            }
        }

        Logger.once.warn("Unshadow: unresolved shadow field", field.getName(), "on", targetTd.getName());
        return null;
    }

    private static String resolveTargetMethodName(MethodDescription method, Shadow.Method ann, TypeDescription targetTd) {
        String[] value = ann.value();
        if (value.length == 1) {
            return value[0];
        }

        if (value.length == 0) {
            return method.getName();
        }

        for (String candidate : value) {
            if (!targetTd.getDeclaredMethods().filter(named(candidate)).isEmpty()) {
                return candidate;
            }
        }

        Logger.once.warn("Unshadow: unresolved shadow method", method.getName(), "on", targetTd.getName());
        return null;
    }

    @Override
    protected ClassVisitor createVisitor(ClassWriter cw, byte[] classBytes) {
        if (m_ctx.getCurrentTypeDesc().getDeclaredAnnotations().ofType(Shadow.class) != null) {
            return cw;
        }

        Map<String, String> typeMappings = m_ctx.jarContext().shadowDescriptorMappings();
        if (typeMappings.isEmpty()) {
            return cw;
        }

        Map<String, Map<String, String>> fieldMappings  = m_ctx.jarContext().shadowFieldMappings();
        Map<String, Map<String, String>> methodMappings = m_ctx.jarContext().shadowMethodMappings();

        return new ClassRemapper(cw, new Remapper(ASM_API) {
            @Override
            public String map(String internalName) {
                String mapped = typeMappings.get(internalName);
                if (mapped != null) {
                    setModified();
                    return mapped;
                }

                return internalName;
            }

            @Override
            public String mapFieldName(String owner, String name, String descriptor) {
                Map<String, String> fields = fieldMappings.get(owner);
                if (fields == null) {
                    return name;
                }

                String mapped = fields.get(name);
                if (mapped != null) {
                    setModified();
                    return mapped;
                }

                return name;
            }

            @Override
            public String mapMethodName(String owner, String name, String descriptor) {
                Map<String, String> methods = methodMappings.get(owner);
                if (methods == null) {
                    return name;
                }

                String mapped = methods.get(name);
                if (mapped != null) {
                    setModified();
                    return mapped;
                }

                return name;
            }
        });
    }
}
