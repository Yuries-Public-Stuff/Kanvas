package dev.yurie.display.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

// Routes Compose Skia draws into the GPU takeover.
final class SkiaCanvasTransformer implements ClassFileTransformer {
    static final String CANVAS = "org/jetbrains/skia/Canvas";
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
        if (!CANVAS.equals(className)) return null;

        try {
            ClassReader reader = new ClassReader(classfileBuffer);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                int access,
                String name,
                String descriptor,
                String signature,
                String[] exceptions
            ) {
                MethodVisitor base = super.visitMethod(access, name, descriptor, signature, exceptions);
                if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_STATIC)) != 0) return base;
                if ((access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0) return base;
                if (name.equals("<init>") || name.equals("<clinit>") || name.endsWith("$default")) return base;

                return new AdviceAdapter(Opcodes.ASM9, base, access, name, descriptor) {
                    @Override
                    protected void onMethodEnter() {
                        Type[] args = Type.getArgumentTypes(methodDesc);

                        /*
                         * Only intercept the Canvas created by
                         * SkiaLayer's PictureRecorder for this Compose frame.
                         * Offscreen canvases must execute their real Skia
                         * methods so cached vectors/layers/images are
                         * rasterized correctly before the primary canvas
                         * consumes them.
                         */
                        loadThis();
                        invokeStatic(
                            Type.getObjectType(HOOKS),
                            new org.objectweb.asm.commons.Method(
                                "markCanvasCall",
                                "(Ljava/lang/Object;)V"
                            )
                        );

                        /*
                         * Offscreen Skia canvases must run their original
                         * methods untouched. Stop injecting takeover work
                         * before any draw/transform hook can mutate the
                         * primary frame state.
                         */
                        org.objectweb.asm.Label originalCanvas =
                            new org.objectweb.asm.Label();
                        invokeStatic(
                            Type.getObjectType(HOOKS),
                            new org.objectweb.asm.commons.Method(
                                "canvasCallAllowed",
                                "()Z"
                            )
                        );
                        ifZCmp(EQ, originalCanvas);

                        /*
                         * Java returns below stop generation of additional
                         * hook code for the current method. The finally block
                         * still emits originalCanvas, so an offscreen Canvas
                         * can jump over all Kanvas hooks and execute the
                         * original Skia method body.
                         */
                        try {

                        if (name.equals("clear") && descriptor.equals("(I)Lorg/jetbrains/skia/Canvas;")) {
                            loadArg(0);
                            invokeStatic(Type.getObjectType(HOOKS),
                                new org.objectweb.asm.commons.Method("clear", "(I)V"));
                            returnCanvasWhenTakeover();
                            return;
                        }

                        if (name.equals("drawRect") &&
                            descriptor.equals("(FFFFLorg/jetbrains/skia/Paint;)Lorg/jetbrains/skia/Canvas;")) {
                            for (int i = 0; i < 5; i++) loadArg(i);
                            invokeStatic(Type.getObjectType(HOOKS),
                                new org.objectweb.asm.commons.Method(
                                    "drawRect",
                                    "(FFFFLjava/lang/Object;)V"
                                ));
                            returnCanvasWhenTakeover();
                            return;
                        }

                        if (name.equals("drawRect") &&
                            descriptor.equals("(Lorg/jetbrains/skia/Rect;Lorg/jetbrains/skia/Paint;)Lorg/jetbrains/skia/Canvas;")) {
                            loadArg(0);
                            loadArg(1);
                            invokeStatic(Type.getObjectType(HOOKS),
                                new org.objectweb.asm.commons.Method(
                                    "drawRectObject",
                                    "(Ljava/lang/Object;Ljava/lang/Object;)Z"
                                ));
                            returnCanvasWhenCaptured();
                            return;
                        }

                        if (name.equals("drawOval") &&
                            descriptor.equals("(Lorg/jetbrains/skia/Rect;Lorg/jetbrains/skia/Paint;)Lorg/jetbrains/skia/Canvas;")) {
                            loadArg(0);
                            loadArg(1);
                            invokeStatic(Type.getObjectType(HOOKS),
                                new org.objectweb.asm.commons.Method(
                                    "drawOvalObject",
                                    "(Ljava/lang/Object;Ljava/lang/Object;)Z"
                                ));
                            returnCanvasWhenCaptured();
                            return;
                        }

                        if (name.equals("drawRRect") &&
                            descriptor.equals("(Lorg/jetbrains/skia/RRect;Lorg/jetbrains/skia/Paint;)Lorg/jetbrains/skia/Canvas;")) {
                            loadArg(0);
                            loadArg(1);
                            invokeStatic(Type.getObjectType(HOOKS),
                                new org.objectweb.asm.commons.Method(
                                    "drawRRectObject",
                                    "(Ljava/lang/Object;Ljava/lang/Object;)Z"
                                ));
                            returnCanvasWhenCaptured();
                            return;
                        }

                        if (name.equals("drawRRect") &&
                            descriptor.equals("(FFFF[FLorg/jetbrains/skia/Paint;)Lorg/jetbrains/skia/Canvas;")) {
                            for (int i = 0; i < 6; i++) loadArg(i);
                            invokeStatic(Type.getObjectType(HOOKS),
                                new org.objectweb.asm.commons.Method(
                                    "drawRRect",
                                    "(FFFF[FLjava/lang/Object;)V"
                                ));
                            returnCanvasWhenTakeover();
                            return;
                        }

                        if (name.equals("save") && descriptor.equals("()I")) {
                            invokeStatic(Type.getObjectType(HOOKS),
                                new org.objectweb.asm.commons.Method("save", "()V"));
                            return;
                        }

                        if (name.equals("restore") &&
                            descriptor.equals("()Lorg/jetbrains/skia/Canvas;")) {
                            invokeStatic(Type.getObjectType(HOOKS),
                                new org.objectweb.asm.commons.Method("restore", "()V"));
                            return;
                        }

                        if (name.equals("translate") &&
                            descriptor.equals("(FF)Lorg/jetbrains/skia/Canvas;")) {
                            loadArg(0);
                            loadArg(1);
                            invokeStatic(Type.getObjectType(HOOKS),
                                new org.objectweb.asm.commons.Method("translate", "(FF)V"));
                            return;
                        }

                        if (name.equals("scale") &&
                            descriptor.equals("(FF)Lorg/jetbrains/skia/Canvas;")) {
                            loadArg(0);
                            loadArg(1);
                            invokeStatic(Type.getObjectType(HOOKS),
                                new org.objectweb.asm.commons.Method("scale", "(FF)V"));
                            return;
                        }

                        if (name.equals("rotate") &&
                            descriptor.equals("(F)Lorg/jetbrains/skia/Canvas;")) {
                            loadArg(0);
                            invokeStatic(Type.getObjectType(HOOKS),
                                new org.objectweb.asm.commons.Method("rotate", "(F)V"));
                            return;
                        }

                        if (name.equals("rotate") &&
                            descriptor.equals("(FFF)Lorg/jetbrains/skia/Canvas;")) {
                            loadArg(0);
                            loadArg(1);
                            loadArg(2);
                            invokeStatic(Type.getObjectType(HOOKS),
                                new org.objectweb.asm.commons.Method("rotateAround", "(FFF)V"));
                            return;
                        }

                        if (name.equals("skew") &&
                            descriptor.equals("(FF)Lorg/jetbrains/skia/Canvas;")) {
                            loadArg(0);
                            loadArg(1);
                            invokeStatic(Type.getObjectType(HOOKS),
                                new org.objectweb.asm.commons.Method("skew", "(FF)V"));
                            return;
                        }

                        if (name.equals("concat") &&
                            (descriptor.equals("(Lorg/jetbrains/skia/Matrix33;)Lorg/jetbrains/skia/Canvas;") ||
                             descriptor.equals("(Lorg/jetbrains/skia/Matrix44;)Lorg/jetbrains/skia/Canvas;"))) {
                            loadArg(0);
                            invokeStatic(Type.getObjectType(HOOKS),
                                new org.objectweb.asm.commons.Method(
                                    "concat",
                                    "(Ljava/lang/Object;)Z"
                                ));
                            pop();
                            return;
                        }

                        if (name.equals("setMatrix") &&
                            descriptor.equals("(Lorg/jetbrains/skia/Matrix33;)Lorg/jetbrains/skia/Canvas;")) {
                            loadArg(0);
                            invokeStatic(Type.getObjectType(HOOKS),
                                new org.objectweb.asm.commons.Method(
                                    "setMatrix",
                                    "(Ljava/lang/Object;)Z"
                                ));
                            pop();
                            return;
                        }

                        if (name.equals("resetMatrix") &&
                            descriptor.equals("()Lorg/jetbrains/skia/Canvas;")) {
                            invokeStatic(Type.getObjectType(HOOKS),
                                new org.objectweb.asm.commons.Method("resetMatrix", "()V"));
                            return;
                        }

                        if (name.equals("saveLayer") &&
                            descriptor.equals("(Lorg/jetbrains/skia/Canvas$SaveLayerRec;)I")) {
                            loadArg(0);
                            invokeStatic(Type.getObjectType(HOOKS),
                                new org.objectweb.asm.commons.Method(
                                    "saveLayerRec",
                                    "(Ljava/lang/Object;)V"
                                ));
                            return;
                        }

                        if (name.equals("restoreToCount") &&
                            descriptor.equals("(I)Lorg/jetbrains/skia/Canvas;")) {
                            loadArg(0);
                            invokeStatic(Type.getObjectType(HOOKS),
                                new org.objectweb.asm.commons.Method(
                                    "restoreToCount",
                                    "(I)V"
                                ));
                            return;
                        }

                        if (name.equals("saveLayer") &&
                            descriptor.equals("(FFFFLorg/jetbrains/skia/Paint;)I")) {
                            loadArg(4);
                            invokeStatic(Type.getObjectType(HOOKS),
                                new org.objectweb.asm.commons.Method(
                                    "saveLayer",
                                    "(Ljava/lang/Object;)V"
                                ));
                            return;
                        }

                        if (name.equals("saveLayer") &&
                            descriptor.equals("(Lorg/jetbrains/skia/Rect;Lorg/jetbrains/skia/Paint;)I")) {
                            loadArg(1);
                            invokeStatic(Type.getObjectType(HOOKS),
                                new org.objectweb.asm.commons.Method(
                                    "saveLayer",
                                    "(Ljava/lang/Object;)V"
                                ));
                            return;
                        }

                        if (name.equals("clipPath") &&
                            descriptor.equals("(Lorg/jetbrains/skia/Path;Lorg/jetbrains/skia/ClipMode;Z)Lorg/jetbrains/skia/Canvas;")) {
                            loadArg(0);
                            loadArg(1);
                            loadArg(2);
                            invokeStatic(Type.getObjectType(HOOKS),
                                new org.objectweb.asm.commons.Method(
                                    "clipPath",
                                    "(Ljava/lang/Object;Ljava/lang/Object;Z)Z"
                                ));
                            pop();
                            return;
                        }

                        if (name.equals("clipRRect") &&
                            descriptor.equals("(FFFF[FLorg/jetbrains/skia/ClipMode;Z)Lorg/jetbrains/skia/Canvas;")) {
                            for (int i = 0; i < 7; i++) loadArg(i);
                            invokeStatic(Type.getObjectType(HOOKS),
                                new org.objectweb.asm.commons.Method(
                                    "clipRRect",
                                    "(FFFF[FLjava/lang/Object;Z)V"
                                ));
                            return;
                        }

                        if (name.equals("clipRect") &&
                            descriptor.equals("(FFFFLorg/jetbrains/skia/ClipMode;Z)Lorg/jetbrains/skia/Canvas;")) {
                            for (int i = 0; i < 6; i++) loadArg(i);
                            invokeStatic(Type.getObjectType(HOOKS),
                                new org.objectweb.asm.commons.Method(
                                    "clipRect",
                                    "(FFFFLjava/lang/Object;Z)V"
                                ));
                            return;
                        }

                        if (name.equals("drawDRRect") &&
                            descriptor.equals("(Lorg/jetbrains/skia/RRect;Lorg/jetbrains/skia/RRect;Lorg/jetbrains/skia/Paint;)Lorg/jetbrains/skia/Canvas;")) {
                            loadArg(0);
                            loadArg(1);
                            loadArg(2);
                            invokeStatic(Type.getObjectType(HOOKS),
                                new org.objectweb.asm.commons.Method(
                                    "drawDRRect",
                                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z"
                                ));
                            returnCanvasWhenCaptured();
                            return;
                        }

                        if (name.equals("drawOval") &&
                            descriptor.equals("(FFFFLorg/jetbrains/skia/Paint;)Lorg/jetbrains/skia/Canvas;")) {
                            for (int i = 0; i < 5; i++) loadArg(i);
                            invokeStatic(Type.getObjectType(HOOKS),
                                new org.objectweb.asm.commons.Method(
                                    "drawOval",
                                    "(FFFFLjava/lang/Object;)V"
                                ));
                            returnCanvasWhenTakeover();
                            return;
                        }

                        if (name.equals("drawCircle") &&
                            descriptor.equals("(FFFLorg/jetbrains/skia/Paint;)Lorg/jetbrains/skia/Canvas;")) {
                            for (int i = 0; i < 4; i++) loadArg(i);
                            invokeStatic(Type.getObjectType(HOOKS),
                                new org.objectweb.asm.commons.Method(
                                    "drawCircle",
                                    "(FFFLjava/lang/Object;)V"
                                ));
                            returnCanvasWhenTakeover();
                            return;
                        }

                        if (name.equals("drawPoint") &&
                            descriptor.equals("(FFLorg/jetbrains/skia/Paint;)Lorg/jetbrains/skia/Canvas;")) {
                            loadArg(0);
                            loadArg(1);
                            loadArg(2);
                            invokeStatic(Type.getObjectType(HOOKS),
                                new org.objectweb.asm.commons.Method(
                                    "drawPoint",
                                    "(FFLjava/lang/Object;)V"
                                ));
                            returnCanvasWhenTakeover();
                            return;
                        }

                        if (name.equals("drawPoints") &&
                            descriptor.equals("([FLorg/jetbrains/skia/Paint;)Lorg/jetbrains/skia/Canvas;")) {
                            loadArg(0);
                            loadArg(1);
                            invokeStatic(Type.getObjectType(HOOKS),
                                new org.objectweb.asm.commons.Method(
                                    "drawPoints",
                                    "([FLjava/lang/Object;)V"
                                ));
                            returnCanvasWhenTakeover();
                            return;
                        }

                        if (name.equals("drawLines") &&
                            descriptor.equals("([FLorg/jetbrains/skia/Paint;)Lorg/jetbrains/skia/Canvas;")) {
                            loadArg(0);
                            loadArg(1);
                            invokeStatic(Type.getObjectType(HOOKS),
                                new org.objectweb.asm.commons.Method(
                                    "drawLines",
                                    "([FLjava/lang/Object;)V"
                                ));
                            returnCanvasWhenTakeover();
                            return;
                        }

                        if (name.equals("drawPolygon") &&
                            descriptor.equals("([FLorg/jetbrains/skia/Paint;)Lorg/jetbrains/skia/Canvas;")) {
                            loadArg(0);
                            loadArg(1);
                            invokeStatic(Type.getObjectType(HOOKS),
                                new org.objectweb.asm.commons.Method(
                                    "drawPolygon",
                                    "([FLjava/lang/Object;)V"
                                ));
                            returnCanvasWhenTakeover();
                            return;
                        }

                        if (name.equals("drawPaint") &&
                            descriptor.equals("(Lorg/jetbrains/skia/Paint;)Lorg/jetbrains/skia/Canvas;")) {
                            loadArg(0);
                            invokeStatic(Type.getObjectType(HOOKS),
                                new org.objectweb.asm.commons.Method(
                                    "drawPaint",
                                    "(Ljava/lang/Object;)V"
                                ));
                            returnCanvasWhenTakeover();
                            return;
                        }

                        if (name.equals("drawLine") &&
                            descriptor.equals("(FFFFLorg/jetbrains/skia/Paint;)Lorg/jetbrains/skia/Canvas;")) {
                            for (int i = 0; i < 5; i++) loadArg(i);
                            invokeStatic(Type.getObjectType(HOOKS),
                                new org.objectweb.asm.commons.Method(
                                    "drawLine",
                                    "(FFFFLjava/lang/Object;)V"
                                ));
                            returnCanvasWhenTakeover();
                            return;
                        }

                        if (name.equals("drawArc") &&
                            descriptor.equals("(FFFFFFZLorg/jetbrains/skia/Paint;)Lorg/jetbrains/skia/Canvas;")) {
                            for (int i = 0; i < 8; i++) loadArg(i);
                            invokeStatic(Type.getObjectType(HOOKS),
                                new org.objectweb.asm.commons.Method(
                                    "drawArc",
                                    "(FFFFFFZLjava/lang/Object;)Z"
                                ));
                            returnCanvasWhenCaptured();
                            return;
                        }

                        if (name.equals("drawPath") &&
                            descriptor.equals("(Lorg/jetbrains/skia/Path;Lorg/jetbrains/skia/Paint;)Lorg/jetbrains/skia/Canvas;")) {
                            loadArg(0);
                            loadArg(1);
                            invokeStatic(Type.getObjectType(HOOKS),
                                new org.objectweb.asm.commons.Method(
                                    "drawPath",
                                    "(Ljava/lang/Object;Ljava/lang/Object;)Z"
                                ));
                            returnCanvasWhenCaptured();
                            return;
                        }

                        if (name.equals("drawRegion") &&
                            descriptor.equals("(Lorg/jetbrains/skia/Region;Lorg/jetbrains/skia/Paint;)Lorg/jetbrains/skia/Canvas;")) {
                            loadArg(0);
                            loadArg(1);
                            invokeStatic(Type.getObjectType(HOOKS),
                                new org.objectweb.asm.commons.Method(
                                    "drawRegion",
                                    "(Ljava/lang/Object;Ljava/lang/Object;)Z"
                                ));
                            returnCanvasWhenCaptured();
                            return;
                        }

                        if (name.equals("clipRegion") &&
                            descriptor.equals("(Lorg/jetbrains/skia/Region;Lorg/jetbrains/skia/ClipMode;)Lorg/jetbrains/skia/Canvas;")) {
                            loadArg(0);
                            loadArg(1);
                            invokeStatic(Type.getObjectType(HOOKS),
                                new org.objectweb.asm.commons.Method(
                                    "clipRegion",
                                    "(Ljava/lang/Object;Ljava/lang/Object;)Z"
                                ));
                            pop();
                            return;
                        }

                        if (name.equals("drawImageNine") &&
                            descriptor.equals("(Lorg/jetbrains/skia/Image;Lorg/jetbrains/skia/IRect;Lorg/jetbrains/skia/Rect;Lorg/jetbrains/skia/FilterMode;Lorg/jetbrains/skia/Paint;)Lorg/jetbrains/skia/Canvas;")) {
                            for (int i = 0; i < 5; i++) loadArg(i);
                            invokeStatic(Type.getObjectType(HOOKS),
                                new org.objectweb.asm.commons.Method(
                                    "drawImageNine",
                                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z"
                                ));
                            returnCanvasWhenCaptured();
                            return;
                        }

                        if (name.equals("drawImageRect") &&
                            descriptor.equals("(Lorg/jetbrains/skia/Image;FFFFFFFFLorg/jetbrains/skia/SamplingMode;Lorg/jetbrains/skia/Paint;Z)Lorg/jetbrains/skia/Canvas;")) {
                            loadArg(0);
                            for (int i = 1; i <= 8; i++) loadArg(i);
                            loadArg(10);
                            invokeStatic(Type.getObjectType(HOOKS),
                                new org.objectweb.asm.commons.Method(
                                    "drawImageRect",
                                    "(Ljava/lang/Object;FFFFFFFFLjava/lang/Object;)Z"
                                ));
                            returnCanvasWhenCaptured();
                            return;
                        }

                        if (name.equals("drawString") &&
                            descriptor.equals("(Ljava/lang/String;FFLorg/jetbrains/skia/Font;Lorg/jetbrains/skia/Paint;)Lorg/jetbrains/skia/Canvas;")) {
                            for (int i = 0; i < 5; i++) loadArg(i);
                            invokeStatic(Type.getObjectType(HOOKS),
                                new org.objectweb.asm.commons.Method(
                                    "drawString",
                                    "(Ljava/lang/String;FFLjava/lang/Object;Ljava/lang/Object;)V"
                                ));
                            returnCanvasWhenTakeover();
                            return;
                        }

                        if (name.equals("drawPicture") &&
                            descriptor.equals(
                                "(Lorg/jetbrains/skia/Picture;" +
                                "Lorg/jetbrains/skia/Matrix33;" +
                                "Lorg/jetbrains/skia/Paint;)" +
                                "Lorg/jetbrains/skia/Canvas;"
                            )) {
                            loadArg(0);
                            loadArg(1);
                            loadArg(2);
                            invokeStatic(
                                Type.getObjectType(HOOKS),
                                new org.objectweb.asm.commons.Method(
                                    "drawPicture",
                                    "(Ljava/lang/Object;" +
                                    "Ljava/lang/Object;" +
                                    "Ljava/lang/Object;)Z"
                                )
                            );
                            returnCanvasWhenCaptured();
                            return;
                        }

                        if (isInterestingDraw(name, args) &&
                            !isDelegatingWrapper(name, descriptor)) {
                            visitLdcInsn(name + descriptor);
                            invokeStatic(Type.getObjectType(HOOKS),
                                new org.objectweb.asm.commons.Method(
                                    "generic",
                                    "(Ljava/lang/String;)V"
                                ));
                        }

                        } finally {
                            mark(originalCanvas);
                        }
                    }

                    private void returnCanvasWhenCaptured() {
                        org.objectweb.asm.Label mirror =
                            new org.objectweb.asm.Label();
                        org.objectweb.asm.Label normal =
                            new org.objectweb.asm.Label();

                        /*
                         * Modern Skiko records Compose into PictureRecorder.
                         * We mirror those calls into Kanvas but must let Skia
                         * execute too, otherwise Compose's recording canvas
                         * state/picture becomes invalid.
                         *
                         * The captured boolean is already on the stack here.
                         */
                        invokeStatic(
                            Type.getObjectType(HOOKS),
                            new org.objectweb.asm.commons.Method(
                                "suppressOriginalCanvasCalls",
                                "()Z"
                            )
                        );
                        ifZCmp(EQ, mirror);

                        // Legacy/direct mode: suppress only when Kanvas
                        // actually captured the operation.
                        ifZCmp(EQ, normal);
                        loadThis();
                        returnValue();

                        mark(mirror);
                        pop();
                        mark(normal);
                    }

                    private void returnCanvasWhenTakeover() {
                        org.objectweb.asm.Label normal =
                            new org.objectweb.asm.Label();
                        invokeStatic(
                            Type.getObjectType(HOOKS),
                            new org.objectweb.asm.commons.Method(
                                "suppressOriginalCanvasCalls",
                                "()Z"
                            )
                        );
                        ifZCmp(EQ, normal);
                        loadThis();
                        returnValue();
                        mark(normal);
                    }
                };
            }
        };

        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        System.err.println("[Kanvas] installed Skia Canvas capture hooks");
        KanvasAgent.audit("TRANSFORMED_SKIA_CANVAS " + className.replace('/', '.'));
        ComposeCaptureHooks.markCanvasTransformerReady();
        return writer.toByteArray();
        } catch (Throwable failure) {
            String detail = failure.getClass().getName() + ": " + String.valueOf(failure.getMessage());
            System.err.println("[Kanvas] Skia Canvas transform failed: " + detail);
            KanvasAgent.audit("SKIA_CANVAS_TRANSFORM_FAILED " + detail);
            return null;
        }
    }

    private static boolean isDelegatingWrapper(
        String name,
        String descriptor
    ) {
        if (name.equals("drawImage")) return true;
        if (name.equals("drawRectShadow") ||
            name.equals("drawRectShadowNoclip")) return true;
        if (name.equals("drawTextLine")) return true;

        if (name.equals("drawRect")) {
            return descriptor.startsWith("(Lorg/jetbrains/skia/Rect;");
        }
        if (name.equals("drawOval")) {
            return descriptor.startsWith("(Lorg/jetbrains/skia/Rect;");
        }
        if (name.equals("drawRRect")) {
            return descriptor.startsWith("(Lorg/jetbrains/skia/RRect;");
        }

        if (name.equals("drawPoints") ||
            name.equals("drawLines") ||
            name.equals("drawPolygon")) {
            return descriptor.startsWith("([Lorg/jetbrains/skia/Point;");
        }

        if (name.equals("drawImageRect")) {
            return !descriptor.equals(
                "(Lorg/jetbrains/skia/Image;FFFFFFFF" +
                "Lorg/jetbrains/skia/SamplingMode;" +
                "Lorg/jetbrains/skia/Paint;Z)" +
                "Lorg/jetbrains/skia/Canvas;"
            );
        }

        if (name.equals("clipRect")) {
            return !descriptor.equals(
                "(FFFFLorg/jetbrains/skia/ClipMode;Z)" +
                "Lorg/jetbrains/skia/Canvas;"
            );
        }

        if (name.equals("clipRRect")) {
            return !descriptor.equals(
                "(FFFF[FLorg/jetbrains/skia/ClipMode;Z)" +
                "Lorg/jetbrains/skia/Canvas;"
            );
        }

        if (name.equals("clipPath")) {
            return !descriptor.equals(
                "(Lorg/jetbrains/skia/Path;" +
                "Lorg/jetbrains/skia/ClipMode;Z)" +
                "Lorg/jetbrains/skia/Canvas;"
            );
        }

        if (name.equals("clipRegion")) {
            return !descriptor.equals(
                "(Lorg/jetbrains/skia/Region;" +
                "Lorg/jetbrains/skia/ClipMode;)" +
                "Lorg/jetbrains/skia/Canvas;"
            );
        }

        return false;
    }

    private static boolean isInterestingDraw(String name, Type[] args) {
        if (name.startsWith("draw")) return true;
        return name.startsWith("clip") ||
            name.equals("rotate") ||
            name.equals("skew") ||
            name.equals("concat") ||
            name.equals("setMatrix") ||
            name.equals("resetMatrix") ||
            name.startsWith("saveLayer");
    }
}
