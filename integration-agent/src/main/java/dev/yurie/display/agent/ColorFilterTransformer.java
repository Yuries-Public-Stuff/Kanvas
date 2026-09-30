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

final class ColorFilterTransformer implements ClassFileTransformer {
    private static final String TARGET =
        "org/jetbrains/skia/ColorFilter$Companion";
    private static final String STORE =
        "dev/yurie/display/agent/ColorFilterCaptureStore";

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
                if (name.equals("<init>") || name.equals("<clinit>")) {
                    return base;
                }

                return new AdviceAdapter(
                    Opcodes.ASM9,
                    base,
                    access,
                    name,
                    descriptor
                ) {
                    @Override
                    protected void onMethodExit(int opcode) {
                        if (opcode != ARETURN) return;

                        if (name.equals("makeBlend") &&
                            descriptor.equals(
                                "(ILorg/jetbrains/skia/BlendMode;)" +
                                "Lorg/jetbrains/skia/ColorFilter;"
                            )) {
                            dup();
                            loadArg(0);
                            loadArg(1);
                            invokeStatic(
                                Type.getObjectType(STORE),
                                new Method(
                                    "blend",
                                    "(Ljava/lang/Object;ILjava/lang/Object;)V"
                                )
                            );
                            return;
                        }

                        Type[] args = Type.getArgumentTypes(descriptor);
                        if (name.startsWith("makeMatrix") &&
                            args.length == 1 &&
                            args[0].getSort() == Type.ARRAY &&
                            args[0].getElementType().equals(Type.FLOAT_TYPE)) {
                            dup();
                            loadArg(0);
                            invokeStatic(
                                Type.getObjectType(STORE),
                                new Method(
                                    "matrix",
                                    "(Ljava/lang/Object;[F)V"
                                )
                            );
                        }
                    }
                };
            }
        };

        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        System.err.println(
            "[Kanvas] installed common color-filter capture"
        );
        KanvasAgent.audit("TRANSFORMED_COLOR_FILTER " + className.replace('/', '.'));
        return writer.toByteArray();
        } catch (Throwable failure) {
            String detail = failure.getClass().getName() + ": " + String.valueOf(failure.getMessage());
            System.err.println("[Kanvas] color-filter transform failed: " + detail);
            KanvasAgent.audit("COLOR_FILTER_TRANSFORM_FAILED " + detail);
            return null;
        }
    }
}
