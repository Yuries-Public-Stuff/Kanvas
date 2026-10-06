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

// Adds frame boundaries around the final Skiko picture replay.
final class SkikoFrameTransformer implements ClassFileTransformer {
    private static final String TARGET = "org/jetbrains/skiko/SkiaLayer";
    private static final String HOOKS =
        "dev/yurie/display/agent/ComposeCaptureHooks";
    private static final String CANVAS =
        "Lorg/jetbrains/skia/Canvas;";

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

        ClassReader reader = new ClassReader(classfileBuffer);

        final boolean[] hasDrawBoundary = {false};
        final boolean[] hasRecordingBoundary = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(
                int access,
                String name,
                String descriptor,
                String signature,
                String[] exceptions
            ) {
                if (isDrawBoundary(access, name, descriptor)) {
                    hasDrawBoundary[0] = true;
                }
                if (isRecordingBoundary(access, name, descriptor)) {
                    hasRecordingBoundary[0] = true;
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        /*
         * Modern Skiko records Compose during update(), then replays the
         * finished Picture from draw(Canvas). The final draw is the only
         * phase that contains the complete visual payload, so prefer it
         * whenever present. update() remains a fallback for unusual/older
         * layouts that do not expose a draw(Canvas) boundary.
         */
        final boolean preferDraw = hasDrawBoundary[0];
        final boolean preferRecording = !preferDraw && hasRecordingBoundary[0];
        final boolean[] matched = {false};

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

                boolean draw =
                    preferDraw &&
                    isDrawBoundary(access, name, descriptor);
                boolean recording =
                    preferRecording &&
                    isRecordingBoundary(access, name, descriptor);

                if (!draw && !recording) return base;

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
                        loadThis();

                        if (draw) {
                            push(0L);
                            invokeStatic(
                                Type.getObjectType(HOOKS),
                                new Method(
                                    "frameStartLayer",
                                    "(Ljava/lang/Object;J)V"
                                )
                            );
                        } else {
                            loadArg(0);
                            Type[] args = Type.getArgumentTypes(methodDesc);
                            if (args.length > 1 &&
                                (args[1].getSort() == Type.OBJECT ||
                                 args[1].getSort() == Type.ARRAY)) {
                                loadArg(1);
                            } else {
                                visitInsn(ACONST_NULL);
                            }
                            invokeStatic(
                                Type.getObjectType(HOOKS),
                                new Method(
                                    "frameStartLayerRecording",
                                    "(Ljava/lang/Object;JLjava/lang/Object;)V"
                                )
                            );
                        }
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
            String detail =
                "no supported draw(Canvas)/update(...) frame boundary in " +
                className.replace('/', '.');
            KanvasAgent.audit("SKIKO_FRAME_TRANSFORM_FAILED " + detail);
            ComposeCaptureHooks.markFrameTransformerFailed(detail);
            return null;
        }

        String mode;
        if (preferDraw) {
            mode = "picture-replay";
            ComposeCaptureHooks.markModernSkikoDrawBoundary();
        } else {
            mode = "recording-fallback";
        }

        System.err.println(
            "[Kanvas] installed Skiko frame boundary hooks (" + mode + ")"
        );
        KanvasAgent.audit(
            "TRANSFORMED_SKIKO_FRAME " +
            className.replace('/', '.') +
            " mode=" + mode
        );
        ComposeCaptureHooks.markFrameTransformerReady();
        return writer.toByteArray();
    }

    private static boolean isDrawBoundary(
        int access,
        String name,
        String descriptor
    ) {
        if ((access & Opcodes.ACC_STATIC) != 0) return false;
        if (!baseName(name).equals("draw")) return false;
        return descriptor.equals("(" + CANVAS + ")V");
    }

    private static boolean isRecordingBoundary(
        int access,
        String name,
        String descriptor
    ) {
        if ((access & Opcodes.ACC_STATIC) != 0) return false;
        if (!baseName(name).equals("update")) return false;
        Type[] args = Type.getArgumentTypes(descriptor);
        return Type.getReturnType(descriptor).equals(Type.VOID_TYPE) &&
            args.length >= 1 &&
            args[0].equals(Type.LONG_TYPE);
    }

    private static String baseName(String name) {
        int mangled = name.indexOf('$');
        return mangled < 0 ? name : name.substring(0, mangled);
    }
}
