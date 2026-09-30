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

// Adds frame boundaries around Skiko picture replay.
final class SkikoFrameTransformer implements ClassFileTransformer {
    private static final String TARGET = "org/jetbrains/skiko/SkiaLayer";
    private static final String HOOKS = "dev/yurie/display/agent/ComposeCaptureHooks";

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

        final boolean[] matched = {false};
        ClassReader reader = new ClassReader(classfileBuffer);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions
            ) {
                MethodVisitor base = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals("draw") ||
                    !descriptor.equals("(Lorg/jetbrains/skia/Canvas;)V")) {
                    return base;
                }

                matched[0] = true;
                return new AdviceAdapter(Opcodes.ASM9, base, access, name, descriptor) {
                    @Override
                    protected void onMethodEnter() {
                        loadThis();
                        push(0L);
                        invokeStatic(
                            Type.getObjectType(HOOKS),
                            new Method(
                                "frameStartLayer",
                                "(Ljava/lang/Object;J)V"
                            )
                        );
                    }

                    @Override
                    protected void onMethodExit(int opcode) {
                        invokeStatic(
                            Type.getObjectType(HOOKS),
                            new Method("frameEnd", "()V")
                        );
                    }
                };
            }
        };

        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        if (!matched[0]) {
            KanvasAgent.audit(
                "SKIKO_FRAME_TRANSFORM_FAILED no draw(Canvas)V in " +
                className.replace('/', '.')
            );
            return null;
        }
        System.err.println("[Kanvas] installed Skiko frame boundary hooks");
        KanvasAgent.audit("TRANSFORMED_SKIKO_FRAME " + className.replace('/', '.'));
        ComposeCaptureHooks.markFrameTransformerReady();
        return writer.toByteArray();
    }
}
