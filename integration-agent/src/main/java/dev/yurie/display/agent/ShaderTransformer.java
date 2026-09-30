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

// Captures Skia gradient constructors.
final class ShaderTransformer implements ClassFileTransformer {
    private static final String TARGET = "org/jetbrains/skia/Shader$Companion";
    private static final String STORE =
        "dev/yurie/display/agent/ShaderCaptureStore";

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

                        if (name.equals("makeLinearGradient")) {
                            captureLinear(descriptor);
                        } else if (name.equals("makeRadialGradient")) {
                            captureRadial(descriptor);
                        } else if (name.equals("makeSweepGradient")) {
                            captureSweep(descriptor);
                        }
                    }

                    private void captureLinear(String descriptor) {
                        if (descriptor.equals(
                            "(FFFF[I[FLorg/jetbrains/skia/GradientStyle;)" +
                            "Lorg/jetbrains/skia/Shader;"
                        )) {
                            dup();
                            for (int i = 0; i < 4; i++) loadArg(i);
                            loadArg(4);
                            loadArg(5);
                            loadArg(6);
                            invokeStatic(
                                Type.getObjectType(STORE),
                                new Method(
                                    "linearArgb",
                                    "(Ljava/lang/Object;FFFF[I[FLjava/lang/Object;)V"
                                )
                            );
                            return;
                        }

                        if (descriptor.equals(
                            "(FFFF[Lorg/jetbrains/skia/Color4f;" +
                            "Lorg/jetbrains/skia/ColorSpace;[F" +
                            "Lorg/jetbrains/skia/GradientStyle;)" +
                            "Lorg/jetbrains/skia/Shader;"
                        )) {
                            dup();
                            for (int i = 0; i < 4; i++) loadArg(i);
                            loadArg(4);
                            loadArg(6);
                            loadArg(7);
                            invokeStatic(
                                Type.getObjectType(STORE),
                                new Method(
                                    "linearColor4f",
                                    "(Ljava/lang/Object;FFFF[Ljava/lang/Object;[FLjava/lang/Object;)V"
                                )
                            );
                        }
                    }

                    private void captureRadial(String descriptor) {
                        if (descriptor.equals(
                            "(FFF[I[FLorg/jetbrains/skia/GradientStyle;)" +
                            "Lorg/jetbrains/skia/Shader;"
                        )) {
                            dup();
                            loadArg(0);
                            loadArg(1);
                            loadArg(2);
                            loadArg(3);
                            loadArg(4);
                            loadArg(5);
                            invokeStatic(
                                Type.getObjectType(STORE),
                                new Method(
                                    "radialArgb",
                                    "(Ljava/lang/Object;FFF[I[FLjava/lang/Object;)V"
                                )
                            );
                            return;
                        }

                        if (descriptor.equals(
                            "(FFF[Lorg/jetbrains/skia/Color4f;" +
                            "Lorg/jetbrains/skia/ColorSpace;[F" +
                            "Lorg/jetbrains/skia/GradientStyle;)" +
                            "Lorg/jetbrains/skia/Shader;"
                        )) {
                            dup();
                            loadArg(0);
                            loadArg(1);
                            loadArg(2);
                            loadArg(3);
                            loadArg(5);
                            loadArg(6);
                            invokeStatic(
                                Type.getObjectType(STORE),
                                new Method(
                                    "radialColor4f",
                                    "(Ljava/lang/Object;FFF[Ljava/lang/Object;[FLjava/lang/Object;)V"
                                )
                            );
                        }
                    }

                    private void captureSweep(String descriptor) {
                        if (descriptor.equals(
                            "(FFFF[I[FLorg/jetbrains/skia/GradientStyle;)" +
                            "Lorg/jetbrains/skia/Shader;"
                        )) {
                            dup();
                            for (int i = 0; i < 4; i++) loadArg(i);
                            loadArg(4);
                            loadArg(5);
                            loadArg(6);
                            invokeStatic(
                                Type.getObjectType(STORE),
                                new Method(
                                    "sweepArgb",
                                    "(Ljava/lang/Object;FFFF[I[FLjava/lang/Object;)V"
                                )
                            );
                            return;
                        }

                        if (descriptor.equals(
                            "(FFFF[Lorg/jetbrains/skia/Color4f;" +
                            "Lorg/jetbrains/skia/ColorSpace;[F" +
                            "Lorg/jetbrains/skia/GradientStyle;)" +
                            "Lorg/jetbrains/skia/Shader;"
                        )) {
                            dup();
                            for (int i = 0; i < 4; i++) loadArg(i);
                            loadArg(4);
                            loadArg(6);
                            loadArg(7);
                            invokeStatic(
                                Type.getObjectType(STORE),
                                new Method(
                                    "sweepColor4f",
                                    "(Ljava/lang/Object;FFFF[Ljava/lang/Object;[FLjava/lang/Object;)V"
                                )
                            );
                        }
                    }
                };
            }
        };

        reader.accept(visitor, 0);
        System.err.println(
            "[Kanvas] installed Skiko 0.9.4.2 gradient capture"
        );
        return writer.toByteArray();
    }
}
