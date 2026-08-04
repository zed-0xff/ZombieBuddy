package me.zed_0xff.zombie_buddy.transformers;

import java.util.HashMap;
import java.util.Map;

import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.named;

/** Per-target shadow metadata from {@code @Shadow} stubs; consumed by {@link me.zed_0xff.zombie_buddy.transformers.asmtree.ShadowRewrite}. */
public final class ShadowContext {
    public record TargetFieldInfo(String name, String descriptor, boolean isStatic) {}

    public record TargetMethodInfo(String name, String descriptor, boolean isStatic) {}

    public record TargetCastInfo(String name, String descriptor) {}

    private final String  m_targetBinaryName;
    private final String  m_targetInternalName;
    private final boolean m_targetClassPublic;
    private boolean       m_needsDefaultCtorAccess;
    private final Map<String, Map<String, String>> m_shadowFieldMappings  = new HashMap<>(); // shadow internal → shadow field → target field
    private final Map<String, Map<String, String>> m_shadowMethodMappings = new HashMap<>(); // shadow internal → shadow method → target method
    private final Map<String, TargetFieldInfo>  m_targetFields  = new HashMap<>();
    private final Map<String, TargetMethodInfo> m_targetMethods = new HashMap<>();
    private final Map<String, TargetCastInfo>   m_targetCasts   = new HashMap<>();

    private ShadowContext(String targetBinaryName, String targetInternalName, boolean targetClassPublic) {
        m_targetBinaryName = targetBinaryName;
        m_targetInternalName = targetInternalName;
        m_targetClassPublic = targetClassPublic;
    }

    public static ShadowContext fromTarget(String targetBinaryName, TypeDescription targetTd) {
        ShadowContext ctx = new ShadowContext(targetBinaryName, targetTd.getInternalName(), targetTd.isPublic());
        if (!hasPublicNoArgConstructor(targetTd)) {
            ctx.m_needsDefaultCtorAccess = true;
        }

        return ctx;
    }

    public String targetBinaryName() {
        return m_targetBinaryName;
    }

    public String targetInternalName() {
        return m_targetInternalName;
    }

    public boolean targetClassPublic() {
        return m_targetClassPublic;
    }

    public boolean needsDefaultCtorAccess() {
        return m_needsDefaultCtorAccess;
    }

    public boolean needsInstanceFactory() {
        return !m_targetClassPublic || m_needsDefaultCtorAccess;
    }

    public Map<String, Map<String, String>> shadowFieldMappings() {
        return copyShadowMappings(m_shadowFieldMappings);
    }

    public Map<String, Map<String, String>> shadowMethodMappings() {
        return copyShadowMappings(m_shadowMethodMappings);
    }

    private static Map<String, Map<String, String>> copyShadowMappings(Map<String, Map<String, String>> mappings) {
        if (mappings.isEmpty()) {
            return Map.of();
        }

        Map<String, Map<String, String>> copy = new HashMap<>();
        for (var e : mappings.entrySet()) {
            copy.put(e.getKey(), Map.copyOf(e.getValue()));
        }

        return Map.copyOf(copy);
    }

    public Map<String, TargetFieldInfo> targetFields() {
        return m_targetFields.isEmpty() ? Map.of() : Map.copyOf(m_targetFields);
    }

    public Map<String, TargetMethodInfo> targetMethods() {
        return m_targetMethods.isEmpty() ? Map.of() : Map.copyOf(m_targetMethods);
    }

    public Map<String, TargetCastInfo> targetCasts() {
        return m_targetCasts.isEmpty() ? Map.of() : Map.copyOf(m_targetCasts);
    }

    public void addShadowField(String shadowInternalName, FieldDescription.InDefinedShape shadowField, TypeDescription targetTd, String targetFieldName) {
        if (shadowInternalName == null || shadowField == null || targetFieldName == null) {
            return;
        }

        m_shadowFieldMappings.computeIfAbsent(shadowInternalName, k -> new HashMap<>()).put(shadowField.getName(), targetFieldName);

        String descriptor = shadowField.getDescriptor();
        boolean isStatic = shadowField.isStatic();
        for (FieldDescription.InDefinedShape targetField : targetTd.getDeclaredFields().filter(named(targetFieldName))) {
            descriptor = targetField.getDescriptor();
            isStatic = targetField.isStatic();
            break;
        }

        m_targetFields.put(targetFieldName, new TargetFieldInfo(targetFieldName, descriptor, isStatic));
    }

    public void addShadowMethod(String shadowInternalName, MethodDescription.InDefinedShape shadowMethod, TypeDescription targetTd, String targetMethodName) {
        if (shadowInternalName == null || shadowMethod == null || targetMethodName == null) {
            return;
        }

        m_shadowMethodMappings.computeIfAbsent(shadowInternalName, k -> new HashMap<>()).put(shadowMethod.getName(), targetMethodName);

        String descriptor = shadowMethod.getDescriptor();
        boolean isStatic = shadowMethod.isStatic();
        for (MethodDescription.InDefinedShape targetMethod : targetTd.getDeclaredMethods().filter(named(targetMethodName))) {
            if (!targetMethod.getDescriptor().equals(descriptor)) {
                continue;
            }

            descriptor = targetMethod.getDescriptor();
            isStatic = targetMethod.isStatic();
            break;
        }

        m_targetMethods.put(targetMethodName + descriptor, new TargetMethodInfo(targetMethodName, descriptor, isStatic));
    }

    public void addShadowCast(MethodDescription.InDefinedShape shadowMethod, String descriptor) {
        if (shadowMethod == null || descriptor == null) {
            return;
        }

        m_targetCasts.put(shadowMethod.getName() + descriptor, new TargetCastInfo(shadowMethod.getName(), descriptor));
    }

    public void mergeFrom(ShadowContext other) {
        if (other == null || !m_targetBinaryName.equals(other.m_targetBinaryName)) {
            return;
        }

        m_needsDefaultCtorAccess |= other.m_needsDefaultCtorAccess;

        for (var e : other.m_shadowFieldMappings.entrySet()) {
            m_shadowFieldMappings.computeIfAbsent(e.getKey(), k -> new HashMap<>()).putAll(e.getValue());
        }

        for (var e : other.m_shadowMethodMappings.entrySet()) {
            m_shadowMethodMappings.computeIfAbsent(e.getKey(), k -> new HashMap<>()).putAll(e.getValue());
        }

        m_targetFields.putAll(other.m_targetFields);
        m_targetMethods.putAll(other.m_targetMethods);
        m_targetCasts.putAll(other.m_targetCasts);
    }

    private static boolean hasPublicNoArgConstructor(TypeDescription targetTd) {
        for (MethodDescription.InDefinedShape method : targetTd.getDeclaredMethods().filter(isConstructor())) {
            if (method.getParameters().isEmpty() && method.isPublic()) {
                return true;
            }
        }

        return false;
    }
}
