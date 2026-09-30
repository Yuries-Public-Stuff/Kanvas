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

final class ImageFilterTransformer implements ClassFileTransformer {
    private static final String TARGET =
        "org/jetbrains/skia/ImageFilter$Companion";
    private static final String STORE =
        "dev/yurie/display/agent/ImageFilterCaptureStore";

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
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
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

                        boolean only = name.equals("makeDropShadowOnly");
                        boolean full = name.equals("makeDropShadow");
                        if (!(only || full)) return;
                        if (!descriptor.equals(
                            "(FFFFILorg/jetbrains/skia/ImageFilter;" +
                            "Lorg/jetbrains/skia/IRect;)" +
                            "Lorg/jetbrains/skia/ImageFilter;"
                        )) return;

                        dup();
                        loadArg(0);
                        loadArg(1);
                        loadArg(2);
                        loadArg(3);
                        loadArg(4);
                        push(full);
                        invokeStatic(
                            Type.getObjectType(STORE),
                            new Method(
                                "dropShadow",
                                "(Ljava/lang/Object;FFFFIZ)V"
                            )
                        );
                    }
                };
            }
        };

        reader.accept(visitor, 0);
        System.err.println(
            "[Kanvas] installed drop-shadow filter capture"
        );
        return writer.toByteArray();
    }
}
