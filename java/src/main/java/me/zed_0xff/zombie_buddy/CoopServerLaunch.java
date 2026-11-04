package me.zed_0xff.zombie_buddy;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

import java.lang.instrument.Instrumentation;

/** Adds one list hook in CoopMaster, without patching the GC method or ProcessBuilder itself. */
final class CoopServerLaunch {
    private static final String HELPER = "me/zed_0xff/zombie_buddy/CoopServerJvmArguments";

    private CoopServerLaunch() {}

    static void install(Instrumentation instrumentation) {
        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.Listener.StreamWriting.toSystemOut().withErrorsOnly())
                .type(named("zombie.network.CoopMaster"))
                .transform((builder, type, loader, module, domain) -> builder.visit(wrapper()))
                .installOn(instrumentation);
    }

    static AsmVisitorWrapper wrapper() {
        return new AsmVisitorWrapper.ForDeclaredMethods()
                .method(
                        named("launchServer")
                                .and(
                                        takesArguments(
                                                String.class,
                                                String.class,
                                                int.class,
                                                boolean.class)),
                        (type, method, visitor, context, pool, writerFlags, readerFlags) ->
                                new MethodVisitor(Opcodes.ASM9, visitor) {
                                    int constructors;
                                    boolean inherited;

                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String name,
                                            String descriptor,
                                            boolean isInterface) {
                                        if (opcode == Opcodes.INVOKESTATIC
                                                && owner.equals(HELPER)
                                                && name.equals("inherit")) inherited = true;
                                        if (opcode == Opcodes.INVOKESPECIAL
                                                && owner.equals("java/lang/ProcessBuilder")
                                                && name.equals("<init>")
                                                && descriptor.equals("(Ljava/util/List;)V")) {
                                            constructors++;
                                            if (!inherited)
                                                super.visitMethodInsn(
                                                        Opcodes.INVOKESTATIC,
                                                        HELPER,
                                                        "inherit",
                                                        "(Ljava/util/List;)Ljava/util/List;",
                                                        false);
                                        }
                                        super.visitMethodInsn(
                                                opcode, owner, name, descriptor, isInterface);
                                    }

                                    @Override
                                    public void visitEnd() {
                                        if (constructors != 1)
                                            throw new IllegalStateException(
                                                    "Expected one Coop ProcessBuilder constructor,"
                                                        + " got "
                                                            + constructors);
                                        super.visitEnd();
                                    }
                                });
    }
}
