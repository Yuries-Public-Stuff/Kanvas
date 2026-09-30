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

final class PathEffectTransformer implements ClassFileTransformer {
    private static final String TARGET =
        "org/jetbrains/skia/PathEffect$Companion";
    private static final String STORE =
        "dev/yurie/display/agent/PathEffectCaptureStore";

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
                        if (opcode != ARETURN ||
                            !name.equals("makeDash") ||
                            !descriptor.equals(
                                "([FF)Lorg/jetbrains/skia/PathEffect;"
                            )) {
                            return;
                        }

                        dup();
                        loadArg(0);
                        loadArg(1);
                        invokeStatic(
                            Type.getObjectType(STORE),
                            new Method(
                                "dash",
                                "(Ljava/lang/Object;[FF)V"
                            )
                        );
                    }
                };
            }
        };

        reader.accept(visitor, 0);
        System.err.println(
            "[Kanvas] installed dash path-effect capture"
        );
        return writer.toByteArray();
    }
}
