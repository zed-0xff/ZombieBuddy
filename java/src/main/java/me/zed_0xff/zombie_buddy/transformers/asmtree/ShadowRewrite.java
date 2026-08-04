package me.zed_0xff.zombie_buddy.transformers.asmtree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import me.zed_0xff.zombie_buddy.ShadowHandles;
import me.zed_0xff.zombie_buddy.annotations.Shadow;
import me.zed_0xff.zombie_buddy.transformers.ClassContext;
import me.zed_0xff.zombie_buddy.transformers.ShadowContext;
import me.zed_0xff.zombie_buddy.transformers.ShadowContext.TargetCastInfo;
import me.zed_0xff.zombie_buddy.transformers.ShadowContext.TargetFieldInfo;
import me.zed_0xff.zombie_buddy.transformers.ShadowContext.TargetMethodInfo;
import me.zed_0xff.zombie_buddy.transformers.Transformer;

/** Rewrites direct target field/method access and {@code new} on shadow targets to inlined {@link java.lang.invoke.VarHandle} / {@link java.lang.invoke.MethodHandle} calls. */
public class ShadowRewrite extends Transformer {
    private static final String HANDLES_INTERNAL  = Type.getInternalName(ShadowHandles.class);
    private static final String VH_INTERNAL       = "java/lang/invoke/VarHandle";
    private static final String MH_INTERNAL       = "java/lang/invoke/MethodHandle";
    private static final String INTEGER_INTERNAL  = "java/lang/Integer";
    private static final int ACC_HANDLE = Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;

    @Override
    public Result transform(byte[] classBytes, ClassContext ctx) {
        if (ctx.jarContext().shadowDescriptorMappings().isEmpty()) {
            return NOOP_RESULT;
        }

        if (ctx.getOriginalTypeDesc().getDeclaredAnnotations().ofType(Shadow.class) != null) {
            return NOOP_RESULT;
        }

        try {
            m_ctx = ctx;
            ClassNode cn = new ClassNode();
            new ClassReader(classBytes).accept(cn, 0);

            if (!rewriteClass(cn)) {
                return NOOP_RESULT;
            }

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            byte[] newBytes = cw.toByteArray();
            ctx.setClassBytes(newBytes);

            return new Result(newBytes, Resolution.REPLACE);
        } finally {
            m_ctx = null;
        }
    }

    private boolean rewriteClass(ClassNode cn) {
        Map<String, ShadowContext> contextsByInternal = contextsByTargetInternal();
        if (contextsByInternal.isEmpty()) {
            return false;
        }

        RewriteState state = new RewriteState(cn.name, contextsByInternal);
        boolean changed = false;

        for (MethodNode mn : cn.methods) {
            if ("<init>".equals(mn.name)) {
                continue;
            }

            changed |= rewriteMethod(mn, state);
        }

        if (!state.m_newFields.isEmpty()) {
            cn.fields.addAll(state.m_newFields);
            changed = true;
        }

        if (state.m_clinitInsns.size() > 0) {
            MethodNode clinit = findOrCreateClinit(cn);
            clinit.instructions.insertBefore(clinit.instructions.getFirst(), state.m_clinitInsns);
            changed = true;
        }

        return changed;
    }

    private Map<String, ShadowContext> contextsByTargetInternal() {
        Map<String, ShadowContext> out = new HashMap<>();
        for (ShadowContext ctx : m_ctx.jarContext().shadowContexts().values()) {
            out.put(ctx.targetInternalName(), ctx);
        }

        return out;
    }

    private boolean rewriteMethod(MethodNode mn, RewriteState state) {
        boolean changed = false;
        AbstractInsnNode insn = mn.instructions.getFirst();

        while (insn != null) {
            AbstractInsnNode next = insn.getNext();

            if (insn instanceof FieldInsnNode fin) {
                if (rewriteFieldAccess(mn, fin, state)) {
                    changed = true;
                }
            } else if (insn instanceof MethodInsnNode min) {
                if (rewriteMethodInvoke(mn, min, state)) {
                    changed = true;
                }
            } else if (insn.getOpcode() == Opcodes.NEW && insn instanceof TypeInsnNode tin) {
                AbstractInsnNode afterNew = rewriteNewInstance(mn, tin, state);
                if (afterNew != null) {
                    changed = true;
                    next = afterNew;
                }
            }

            insn = next;
        }

        return changed;
    }

    private boolean rewriteFieldAccess(MethodNode mn, FieldInsnNode fin, RewriteState state) {
        ShadowContext ctx = state.m_contexts.get(fin.owner);
        if (ctx == null) {
            return false;
        }

        TargetFieldInfo field = ctx.targetFields().get(fin.name);
        if (field == null || !field.descriptor().equals(fin.desc)) {
            return false;
        }

        String handleName = state.handleField(ctx, field);
        InsnList repl = emitVarHandleAccess(fin.getOpcode(), state.m_patchInternal, handleName, field);
        mn.instructions.insert(fin, repl);
        mn.instructions.remove(fin);

        return true;
    }

    private boolean rewriteMethodInvoke(MethodNode mn, MethodInsnNode min, RewriteState state) {
        if (min.getOpcode() == Opcodes.INVOKESPECIAL && "<init>".equals(min.name)) {
            return false;
        }

        ShadowContext ctx = state.m_contexts.get(min.owner);
        if (ctx == null) {
            return false;
        }

        TargetCastInfo cast = ctx.targetCasts().get(min.name + min.desc);
        if (cast != null && min.getOpcode() == Opcodes.INVOKESTATIC) {
            mn.instructions.remove(min);
            return true;
        }

        TargetMethodInfo method = ctx.targetMethods().get(min.name + min.desc);
        if (method == null) {
            return false;
        }

        String handleName = state.handleMethod(ctx, method);
        InsnList repl = emitMethodHandleInvoke(min.getOpcode(), state.m_patchInternal, handleName, method);
        mn.instructions.insert(min, repl);
        mn.instructions.remove(min);

        return true;
    }

    private AbstractInsnNode rewriteNewInstance(MethodNode mn, TypeInsnNode tin, RewriteState state) {
        ShadowContext ctx = state.m_contexts.get(tin.desc);
        if (ctx == null || !ctx.needsInstanceFactory()) {
            return null;
        }

        AbstractInsnNode next = tin.getNext();
        if (!(next instanceof InsnNode dup) || dup.getOpcode() != Opcodes.DUP) {
            return null;
        }

        AbstractInsnNode initInsn = dup.getNext();
        if (!(initInsn instanceof MethodInsnNode min) || min.getOpcode() != Opcodes.INVOKESPECIAL || !"<init>".equals(min.name) || !"()V".equals(min.desc) || !tin.desc.equals(min.owner)) {
            return null;
        }

        AbstractInsnNode afterNew = min.getNext();

        InsnList repl = new InsnList();
        repl.add(new LdcInsnNode(ctx.targetBinaryName()));
        repl.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HANDLES_INTERNAL, "newInstance", "(Ljava/lang/String;)Ljava/lang/Object;", false));

        mn.instructions.insert(tin, repl);
        mn.instructions.remove(tin);
        mn.instructions.remove(dup);
        mn.instructions.remove(min);

        return afterNew;
    }

    private static InsnList emitVarHandleAccess(int opcode, String patchInternal, String handleName, TargetFieldInfo field) {
        VarHandleAccess access = VarHandleAccess.forDescriptor(field.descriptor(), field.isStatic());
        InsnList insns = new InsnList();
        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, patchInternal, handleName, "Ljava/lang/invoke/VarHandle;"));

        switch (opcode) {
            case Opcodes.GETSTATIC -> insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, VH_INTERNAL, "get", access.getDesc(), false));
            case Opcodes.GETFIELD -> {
                insns.add(new InsnNode(Opcodes.SWAP));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, VH_INTERNAL, "get", access.getDesc(), false));
            }
            case Opcodes.PUTSTATIC -> {
                insns.add(new InsnNode(Opcodes.SWAP));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, VH_INTERNAL, "set", access.setDesc(), false));
            }
            case Opcodes.PUTFIELD -> {
                insns.add(new InsnNode(Opcodes.SWAP));
                insns.add(new InsnNode(Opcodes.SWAP));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, VH_INTERNAL, "set", access.setDesc(), false));
            }
            default -> throw new IllegalArgumentException("unsupported opcode: " + opcode);
        }

        return insns;
    }

    private static InsnList emitMethodHandleInvoke(int opcode, String patchInternal, String handleName, TargetMethodInfo method) {
        boolean isStatic = method.isStatic() || opcode == Opcodes.INVOKESTATIC;
        InsnList insns = new InsnList();
        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, patchInternal, handleName, "Ljava/lang/invoke/MethodHandle;"));

        if (!isStatic) {
            insns.add(new InsnNode(Opcodes.SWAP));
        }

        String invokeDesc = isStatic ? "()Ljava/lang/Object;" : "(Ljava/lang/Object;)Ljava/lang/Object;";
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MH_INTERNAL, "invoke", invokeDesc, false));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, INTEGER_INTERNAL));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, INTEGER_INTERNAL, "intValue", "()I", false));

        return insns;
    }

    private static MethodNode findOrCreateClinit(ClassNode cn) {
        for (MethodNode mn : cn.methods) {
            if ("<clinit>".equals(mn.name)) {
                return mn;
            }
        }

        MethodNode clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(clinit);

        return clinit;
    }

    private record VarHandleAccess(String getDesc, String setDesc) {
        static VarHandleAccess forDescriptor(String desc, boolean isStatic) {
            String type = desc;
            if (isStatic) {
                return new VarHandleAccess("()" + type, "(" + type + ")V");
            }

            return new VarHandleAccess("(Ljava/lang/Object;)" + type, "(Ljava/lang/Object;" + type + ")V");
        }
    }

    private static final class RewriteState {
        private final String m_patchInternal;
        private final Map<String, ShadowContext> m_contexts;
        private final List<FieldNode> m_newFields = new ArrayList<>();
        private final InsnList m_clinitInsns = new InsnList();
        private final Map<String, String> m_fieldHandles = new LinkedHashMap<>();
        private final Map<String, String> m_methodHandles = new LinkedHashMap<>();
        private int m_fieldCounter;
        private int m_methodCounter;

        private RewriteState(String patchInternal, Map<String, ShadowContext> contexts) {
            m_patchInternal = patchInternal;
            m_contexts = contexts;
        }

        private String handleField(ShadowContext ctx, TargetFieldInfo field) {
            String key = ctx.targetInternalName() + "|" + field.name() + "|" + field.descriptor();
            return m_fieldHandles.computeIfAbsent(key, k -> {
                String name = "zb$vh$" + m_fieldCounter++;
                m_newFields.add(new FieldNode(ACC_HANDLE, name, "Ljava/lang/invoke/VarHandle;", null, null));
                emitVarHandleInit(ctx, field, name);

                return name;
            });
        }

        private String handleMethod(ShadowContext ctx, TargetMethodInfo method) {
            String key = ctx.targetInternalName() + "|" + method.name() + "|" + method.descriptor();
            return m_methodHandles.computeIfAbsent(key, k -> {
                String name = "zb$mh$" + m_methodCounter++;
                m_newFields.add(new FieldNode(ACC_HANDLE, name, "Ljava/lang/invoke/MethodHandle;", null, null));
                emitMethodHandleInit(ctx, method, name);

                return name;
            });
        }

        private void emitVarHandleInit(ShadowContext ctx, TargetFieldInfo field, String handleName) {
            m_clinitInsns.add(new LdcInsnNode(ctx.targetBinaryName()));
            m_clinitInsns.add(new LdcInsnNode(field.name()));
            m_clinitInsns.add(new LdcInsnNode(field.descriptor()));
            m_clinitInsns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HANDLES_INTERNAL, "varHandle", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/invoke/VarHandle;", false));
            m_clinitInsns.add(new FieldInsnNode(Opcodes.PUTSTATIC, m_patchInternal, handleName, "Ljava/lang/invoke/VarHandle;"));
        }

        private void emitMethodHandleInit(ShadowContext ctx, TargetMethodInfo method, String handleName) {
            m_clinitInsns.add(new LdcInsnNode(ctx.targetBinaryName()));
            m_clinitInsns.add(new LdcInsnNode(method.name()));
            m_clinitInsns.add(new LdcInsnNode(method.descriptor()));
            m_clinitInsns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HANDLES_INTERNAL, "methodHandle", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/invoke/MethodHandle;", false));
            m_clinitInsns.add(new FieldInsnNode(Opcodes.PUTSTATIC, m_patchInternal, handleName, "Ljava/lang/invoke/MethodHandle;"));
        }
    }
}
