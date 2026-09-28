package dev.yurie.display.agent;

import dev.yurie.display.composebridge.nativebridge.NativeGpuBridge;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

final class NativeBridgeContractTest {
    @Test
    void presentCarriesGeometryColorAndUvPayloads() throws Exception {
        Method present = NativeGpuBridge.class.getDeclaredMethod(
            "present",
            long.class,
            int[].class,
            float[].class,
            float[].class,
            float[].class
        );

        assertEquals(int.class, present.getReturnType());
    }

    @Test
    void textureResourceLifecycleIsExposed() throws Exception {
        assertEquals(
            int.class,
            NativeGpuBridge.class.getDeclaredMethod(
                "uploadTexture",
                long.class,
                int.class,
                int.class,
                int.class,
                int[].class
            ).getReturnType()
        );

        assertEquals(
            int.class,
            NativeGpuBridge.class.getDeclaredMethod(
                "uploadTextureAddress",
                long.class,
                int.class,
                int.class,
                int.class,
                long.class,
                int.class
            ).getReturnType()
        );

        assertEquals(
            void.class,
            NativeGpuBridge.class.getDeclaredMethod(
                "releaseTexture",
                long.class,
                int.class
            ).getReturnType()
        );

        assertEquals(
            int.class,
            NativeGpuBridge.class.getDeclaredMethod(
                "activeBackend",
                long.class
            ).getReturnType()
        );
    }
}
