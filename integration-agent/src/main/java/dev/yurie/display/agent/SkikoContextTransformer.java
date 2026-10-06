package dev.yurie.display.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.commons.Method;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

// Stops Skiko from presenting over the takeover surface.
final class SkikoContextTransformer implements ClassFileTransformer {
    private static final String TARGET =
        "org/jetbrains/skiko/context/ContextHandler";
    private static final String HOOKS =
        "dev/yurie/display/agent/ComposeCaptureHooks";

    @Override
    public byte[] transform(
        Module module,
        ClassLoader loader,
        String className,
        Class<?> classBeingRedefined,
        ProtectionDomain protectionDomain,
        byte[] classfileBuffer
    ) {
        if (!TARGET.equals(className)) return null;

        try {
            final boolean[] matched = {false};
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(
                reader,
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS
            );

            ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions
                ) {
                    MethodVisitor base = super.visitMethod(
                        access,
                        name,
                        descriptor,
                        signature,
                        exceptions
                    );

                    if (!name.equals("draw") ||
                        !descriptor.equals(
                            "(Lorg/jetbrains/skiko/LayerDrawScope;)V"
                        )) {
                        return base;
                    }

                    matched[0] = true;
                    return new AdviceAdapter(
                        Opcodes.ASM9,
                        base,
                        access,
                        name,
                        descriptor
                    ) {
                        @Override
                        protected void onMethodEnter() {
                            invokeStatic(
                                Type.getObjectType(HOOKS),
                                new Method(
                                    "suppressSkikoPresentationPass",
                                    "()Z"
                                )
                            );
                            org.objectweb.asm.Label normal =
                                new org.objectweb.asm.Label();
                            ifZCmp(EQ, normal);
                            visitInsn(RETURN);
                            mark(normal);
                        }
                    };
                }
            };

            reader.accept(visitor, ClassReader.EXPAND_FRAMES);

            if (!matched[0]) {
                KanvasAgent.audit(
                    "SKIKO_CONTEXT_TRANSFORM_FAILED no draw(LayerDrawScope)V in " +
                    className.replace('/', '.')
                );
                return null;
            }

            System.err.println(
                "[Kanvas] installed Skiko presentation routing"
            );
            KanvasAgent.audit(
                "TRANSFORMED_SKIKO_CONTEXT " +
                className.replace('/', '.')
            );
            return writer.toByteArray();
        } catch (Throwable failure) {
            String detail =
                failure.getClass().getName() + ": " +
                String.valueOf(failure.getMessage());
            System.err.println(
                "[Kanvas] Skiko context transform failed: " + detail
            );
            KanvasAgent.audit(
                "SKIKO_CONTEXT_TRANSFORM_FAILED " + detail
            );
            return null;
        }
    }
}
