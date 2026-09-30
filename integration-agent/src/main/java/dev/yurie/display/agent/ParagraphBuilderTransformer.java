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

final class ParagraphBuilderTransformer implements ClassFileTransformer {
    private static final String TARGET =
        "org/jetbrains/skia/paragraph/ParagraphBuilder";
    private static final String STORE =
        "dev/yurie/display/agent/ParagraphCaptureStore";

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
        final boolean[] sawBuild = {false};
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

                if (name.equals("build") &&
                    descriptor.equals(
                        "()Lorg/jetbrains/skia/paragraph/Paragraph;"
                    )) {
                    sawBuild[0] = true;
                }

                return new AdviceAdapter(
                    Opcodes.ASM9,
                    base,
                    access,
                    name,
                    descriptor
                ) {
                    @Override
                    protected void onMethodEnter() {
                        if (name.equals("pushStyle") &&
                            descriptor.equals(
                                "(Lorg/jetbrains/skia/paragraph/TextStyle;)" +
                                "Lorg/jetbrains/skia/paragraph/ParagraphBuilder;"
                            )) {
                            loadThis();
                            loadArg(0);
                            invokeStatic(
                                Type.getObjectType(STORE),
                                new Method(
                                    "pushStyle",
                                    "(Ljava/lang/Object;Ljava/lang/Object;)V"
                                )
                            );
                        } else if (
                            name.equals("popStyle") &&
                            descriptor.equals(
                                "()Lorg/jetbrains/skia/paragraph/ParagraphBuilder;"
                            )
                        ) {
                            loadThis();
                            invokeStatic(
                                Type.getObjectType(STORE),
                                new Method(
                                    "popStyle",
                                    "(Ljava/lang/Object;)V"
                                )
                            );
                        } else if (
                            name.equals("addText") &&
                            descriptor.equals(
                                "(Ljava/lang/String;)" +
                                "Lorg/jetbrains/skia/paragraph/ParagraphBuilder;"
                            )
                        ) {
                            loadThis();
                            loadArg(0);
                            invokeStatic(
                                Type.getObjectType(STORE),
                                new Method(
                                    "addText",
                                    "(Ljava/lang/Object;Ljava/lang/String;)V"
                                )
                            );
                        }
                    }

                    @Override
                    protected void onMethodExit(int opcode) {
                        if (name.equals("<init>") &&
                            descriptor.equals(
                                "(Lorg/jetbrains/skia/paragraph/ParagraphStyle;" +
                                "Lorg/jetbrains/skia/paragraph/FontCollection;)V"
                            ) &&
                            opcode == RETURN) {
                            loadThis();
                            loadArg(0);
                            invokeStatic(
                                Type.getObjectType(STORE),
                                new Method(
                                    "builderCreated",
                                    "(Ljava/lang/Object;Ljava/lang/Object;)V"
                                )
                            );
                        } else if (
                            name.equals("build") &&
                            descriptor.equals(
                                "()Lorg/jetbrains/skia/paragraph/Paragraph;"
                            ) &&
                            opcode == ARETURN
                        ) {
                            dup();
                            loadThis();
                            invokeStatic(
                                Type.getObjectType(STORE),
                                new Method(
                                    "built",
                                    "(Ljava/lang/Object;Ljava/lang/Object;)V"
                                )
                            );
                        }
                    }
                };
            }
        };

        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        if (!sawBuild[0]) {
            KanvasAgent.audit(
                "PARAGRAPH_BUILDER_TRANSFORM_FAILED no build() in " +
                className.replace('/', '.')
            );
            return null;
        }
        System.err.println(
            "[Kanvas] installed paragraph builder style capture"
        );
        KanvasAgent.audit(
            "TRANSFORMED_PARAGRAPH_BUILDER " +
            className.replace('/', '.')
        );
        return writer.toByteArray();
        } catch (Throwable failure) {
            String detail =
                failure.getClass().getName() + ": " +
                String.valueOf(failure.getMessage());
            System.err.println(
                "[Kanvas] paragraph-builder transform failed: " +
                detail
            );
            KanvasAgent.audit(
                "PARAGRAPH_BUILDER_TRANSFORM_FAILED " + detail
            );
            return null;
        }
    }
}
