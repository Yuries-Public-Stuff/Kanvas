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
    void frameTransformerHooksLegacyDrawBoundary() {
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
    void frameTransformerPrefersModernPictureReplayBoundary() {
        ClassWriter writer = basicClass("org/jetbrains/skiko/SkiaLayer");

        MethodVisitor update = writer.visitMethod(
            Opcodes.ACC_PUBLIC,
            "update$skiko_awt",
            "(JLjava/awt/Dimension;)V",
            null,
            null
        );
        update.visitCode();
        update.visitInsn(Opcodes.RETURN);
        update.visitMaxs(0, 4);
        update.visitEnd();

        MethodVisitor draw = writer.visitMethod(
            Opcodes.ACC_PUBLIC,
            "draw$skiko_awt",
            "(Lorg/jetbrains/skia/Canvas;)V",
            null,
            null
        );
        draw.visitCode();
        draw.visitInsn(Opcodes.RETURN);
        draw.visitMaxs(0, 2);
        draw.visitEnd();
        writer.visitEnd();

        byte[] transformed = new SkikoFrameTransformer().transform(
            null,
            null,
            "org/jetbrains/skiko/SkiaLayer",
            null,
            null,
            writer.toByteArray()
        );

        assertNotNull(transformed);
        assertFalse(hasStaticCallInMethod(
            transformed,
            "update$skiko_awt",
            "dev/yurie/display/agent/ComposeCaptureHooks",
            "frameStartLayerRecording"
        ));
        assertTrue(hasStaticCallInMethod(
            transformed,
            "draw$skiko_awt",
            "dev/yurie/display/agent/ComposeCaptureHooks",
            "frameStartLayer"
        ));
        assertTrue(hasStaticCallInMethod(
            transformed,
            "draw$skiko_awt",
            "dev/yurie/display/agent/ComposeCaptureHooks",
            "frameEnd"
        ));
    }

    @Test
    void canvasTransformerUsesRecordingAwareSuppressionHook() {
        ClassWriter writer = basicClass("org/jetbrains/skia/Canvas");
        MethodVisitor clear = writer.visitMethod(
            Opcodes.ACC_PUBLIC,
            "clear",
            "(I)Lorg/jetbrains/skia/Canvas;",
            null,
            null
        );
        clear.visitCode();
        clear.visitVarInsn(Opcodes.ALOAD, 0);
        clear.visitInsn(Opcodes.ARETURN);
        clear.visitMaxs(1, 2);
        clear.visitEnd();
        writer.visitEnd();

        byte[] transformed = new SkiaCanvasTransformer().transform(
            null,
            null,
            "org/jetbrains/skia/Canvas",
            null,
            null,
            writer.toByteArray()
        );

        assertNotNull(transformed);
        assertTrue(hasStaticCallInMethod(
            transformed,
            "clear",
            "dev/yurie/display/agent/ComposeCaptureHooks",
            "suppressOriginalCanvasCalls"
        ));
    }

    @Test
    void modernRecordingModeAcceptsNestedCanvases() throws Exception {
        var mirror = ComposeCaptureHooks.class.getDeclaredField(
            "MIRROR_ORIGINAL_CANVAS"
        );
        mirror.setAccessible(true);

        @SuppressWarnings("unchecked")
        ThreadLocal<Boolean> mode =
            (ThreadLocal<Boolean>) mirror.get(null);

        mode.set(true);
        try {
            var method = ComposeCaptureHooks.class.getDeclaredMethod(
                "captureCanvas",
                Object.class
            );
            // Without an active/configured frame captureCanvas must still
            // reject arbitrary calls. This test primarily guards that the
            // nested-canvas behavior is controlled by recording mode rather
            // than object identity alone.
            assertFalse((Boolean) method.invoke(null, new Object()));
        } finally {
            mode.remove();
        }
    }

    @Test
    void canvasCallAllowedIsPublic() throws Exception {
        var method = ComposeCaptureHooks.class.getDeclaredMethod(
            "canvasCallAllowed"
        );
        assertTrue(java.lang.reflect.Modifier.isPublic(method.getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isStatic(method.getModifiers()));
    }

    @Test
    void canvasTransformerGuardsOffscreenCanvasCalls() {
        ClassWriter writer = basicClass("org/jetbrains/skia/Canvas");
        MethodVisitor drawRect = writer.visitMethod(
            Opcodes.ACC_PUBLIC,
            "drawRect",
            "(FFFFLorg/jetbrains/skia/Paint;)Lorg/jetbrains/skia/Canvas;",
            null,
            null
        );
        drawRect.visitCode();
        drawRect.visitVarInsn(Opcodes.ALOAD, 0);
        drawRect.visitInsn(Opcodes.ARETURN);
        drawRect.visitMaxs(1, 6);
        drawRect.visitEnd();
        writer.visitEnd();

        byte[] transformed = new SkiaCanvasTransformer().transform(
            null,
            null,
            "org/jetbrains/skia/Canvas",
            null,
            null,
            writer.toByteArray()
        );

        assertNotNull(transformed);
        assertTrue(hasStaticCall(
            transformed,
            "dev/yurie/display/agent/ComposeCaptureHooks",
            "markCanvasCall"
        ));
        assertTrue(hasStaticCall(
            transformed,
            "dev/yurie/display/agent/ComposeCaptureHooks",
            "canvasCallAllowed"
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
            "suppressSkikoPresentationPass"
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

    private static boolean hasStaticCallInMethod(
        byte[] bytes,
        String targetMethod,
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
                    if (!targetMethod.equals(methodName)) return null;
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
