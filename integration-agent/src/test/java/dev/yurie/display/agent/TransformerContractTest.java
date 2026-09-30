package dev.yurie.display.agent;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.*;

final class TransformerContractTest {
    @Test
    void frameTransformerHooksDrawBoundary() {
        byte[] original = classWithVoidMethod(
            "org/jetbrains/skiko/SkiaLayer",
            "draw",
            "(Lorg/jetbrains/skia/Canvas;)V"
        );

        byte[] transformed = new SkikoFrameTransformer().transform(
            null,
            null,
            "org/jetbrains/skiko/SkiaLayer",
            null,
            null,
            original
        );

        assertNotNull(transformed);
        assertTrue(hasStaticCall(
            transformed,
            "dev/yurie/display/agent/ComposeCaptureHooks",
            "frameStartLayer"
        ));
        assertTrue(hasStaticCall(
            transformed,
            "dev/yurie/display/agent/ComposeCaptureHooks",
            "frameEnd"
        ));
    }

    @Test
    void contextTransformerHooksLegacyPresentationBypass() {
        byte[] original = classWithVoidMethod(
            "org/jetbrains/skiko/context/ContextHandler",
            "draw",
            "(Lorg/jetbrains/skiko/LayerDrawScope;)V"
        );

        byte[] transformed = new SkikoContextTransformer().transform(
            null,
            null,
            "org/jetbrains/skiko/context/ContextHandler",
            null,
            null,
            original
        );

        assertNotNull(transformed);
        assertTrue(hasStaticCall(
            transformed,
            "dev/yurie/display/agent/ComposeCaptureHooks",
            "takeoverEnabled"
        ));
    }

    @Test
    void paragraphTransformerPassesParagraphAndCanvasToHook() {
        ClassWriter writer = basicClass(
            "org/jetbrains/skia/paragraph/Paragraph"
        );
        MethodVisitor method = writer.visitMethod(
            Opcodes.ACC_PUBLIC,
            "paint",
            "(Lorg/jetbrains/skia/Canvas;FF)" +
                "Lorg/jetbrains/skia/paragraph/Paragraph;",
            null,
            null
        );
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, 4);
        method.visitEnd();
        writer.visitEnd();

        byte[] transformed = new ParagraphTransformer().transform(
            null,
            null,
            "org/jetbrains/skia/paragraph/Paragraph",
            null,
            null,
            writer.toByteArray()
        );

        assertNotNull(transformed);
        assertTrue(hasStaticCall(
            transformed,
            "dev/yurie/display/agent/ComposeCaptureHooks",
            "drawParagraph"
        ));
    }

    @Test
    void paragraphBuilderTransformerCapturesLifecycle() {
        ClassWriter writer = basicClass(
            "org/jetbrains/skia/paragraph/ParagraphBuilder"
        );

        MethodVisitor build = writer.visitMethod(
            Opcodes.ACC_PUBLIC,
            "build",
            "()Lorg/jetbrains/skia/paragraph/Paragraph;",
            null,
            null
        );
        build.visitCode();
        build.visitInsn(Opcodes.ACONST_NULL);
        build.visitInsn(Opcodes.ARETURN);
        build.visitMaxs(1, 1);
        build.visitEnd();
        writer.visitEnd();

        byte[] transformed =
            new ParagraphBuilderTransformer().transform(
                null,
                null,
                "org/jetbrains/skia/paragraph/ParagraphBuilder",
                null,
                null,
                writer.toByteArray()
            );

        assertNotNull(transformed);
        assertTrue(hasStaticCall(
            transformed,
            "dev/yurie/display/agent/ParagraphCaptureStore",
            "built"
        ));
    }

    @Test
    void transformersIgnoreUnrelatedClasses() {
        byte[] other = classWithVoidMethod(
            "example/Other",
            "update",
            "(J)V"
        );

        assertNull(new SkikoFrameTransformer().transform(
            null, null, "example/Other", null, null, other
        ));
        assertNull(new SkikoContextTransformer().transform(
            null, null, "example/Other", null, null, other
        ));
        assertNull(new ParagraphTransformer().transform(
            null, null, "example/Other", null, null, other
        ));
    }

    private static byte[] classWithVoidMethod(
        String name,
        String methodName,
        String descriptor
    ) {
        ClassWriter writer = basicClass(name);
        MethodVisitor method = writer.visitMethod(
            Opcodes.ACC_PUBLIC,
            methodName,
            descriptor,
            null,
            null
        );
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, argumentSlots(descriptor) + 1);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassWriter basicClass(String name) {
        ClassWriter writer =
            new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(
            Opcodes.V17,
            Opcodes.ACC_PUBLIC,
            name,
            null,
            "java/lang/Object",
            null
        );

        MethodVisitor ctor = writer.visitMethod(
            Opcodes.ACC_PUBLIC,
            "<init>",
            "()V",
            null,
            null
        );
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            "java/lang/Object",
            "<init>",
            "()V",
            false
        );
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();
        return writer;
    }

    private static int argumentSlots(String descriptor) {
        int slots = 0;
        for (org.objectweb.asm.Type type :
            org.objectweb.asm.Type.getArgumentTypes(descriptor)) {
            slots += type.getSize();
        }
        return slots;
    }

    private static boolean hasStaticCall(
        byte[] bytes,
        String owner,
        String name
    ) {
        boolean[] found = {false};
        new ClassReader(bytes).accept(
            new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(
                    int access,
                    String methodName,
                    String descriptor,
                    String signature,
                    String[] exceptions
                ) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(
                            int opcode,
                            String callOwner,
                            String callName,
                            String callDescriptor,
                            boolean isInterface
                        ) {
                            if (opcode == Opcodes.INVOKESTATIC &&
                                owner.equals(callOwner) &&
                                name.equals(callName)) {
                                found[0] = true;
                            }
                        }
                    };
                }
            },
            0
        );
        return found[0];
    }
}
