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

/** Captures Skia paragraph paint calls used by Compose text. */
final class ParagraphTransformer implements ClassFileTransformer {
    private static final String TARGET = "org/jetbrains/skia/paragraph/Paragraph";
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
                if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                    return base;
                }
                if (!name.equals("paint") ||
                    !descriptor.equals(
                        "(Lorg/jetbrains/skia/Canvas;FF)Lorg/jetbrains/skia/paragraph/Paragraph;"
                    )) {
                    return base;
                }

                matched[0] = true;
                return new AdviceAdapter(Opcodes.ASM9, base, access, name, descriptor) {
                    @Override
                    protected void onMethodEnter() {
                        loadThis();
                        loadArg(0);
                        loadArg(1);
                        loadArg(2);
                        invokeStatic(
                            Type.getObjectType(HOOKS),
                            new Method(
                                "drawParagraph",
                                "(Ljava/lang/Object;Ljava/lang/Object;FF)Z"
                            )
                        );

                        org.objectweb.asm.Label normal = new org.objectweb.asm.Label();
                        ifZCmp(EQ, normal);
                        loadThis();
                        returnValue();
                        mark(normal);
                    }
                };
            }
        };

        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        if (!matched[0]) {
            KanvasAgent.audit(
                "PARAGRAPH_TRANSFORM_FAILED no paint(Canvas,FF) in " +
                className.replace('/', '.')
            );
            return null;
        }
        System.err.println("[Kanvas] installed paragraph paint takeover");
        KanvasAgent.audit(
            "TRANSFORMED_PARAGRAPH " + className.replace('/', '.')
        );
        return writer.toByteArray();
        } catch (Throwable failure) {
            String detail =
                failure.getClass().getName() + ": " +
                String.valueOf(failure.getMessage());
            System.err.println(
                "[Kanvas] paragraph transform failed: " + detail
            );
            KanvasAgent.audit(
                "PARAGRAPH_TRANSFORM_FAILED " + detail
            );
            return null;
        }
    }
}
