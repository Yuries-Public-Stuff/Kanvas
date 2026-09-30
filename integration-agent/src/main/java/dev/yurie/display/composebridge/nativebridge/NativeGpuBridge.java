package dev.yurie.display.composebridge.nativebridge;

import java.io.File;
import java.util.Locale;

public final class NativeGpuBridge {
    public static final int AUTO = 0;
    public static final int VULKAN = 1;
    public static final int METAL = 2;
    public static final int OPENGL = 3;
    public static final int D3D9 = 4;
    public static final int GDI = 5;

    public static final int STATUS_OK = 0;
    public static final int STATUS_SWAPCHAIN_OUT_OF_DATE = 9;

    private static volatile boolean loaded;

    private NativeGpuBridge() {}

    public static synchronized void ensureLoaded() {
        if (loaded) return;

        String explicit = System.getProperty(
            "kanvas.nativeLibrary",
            System.getProperty("kotlin.display.nativeLibrary", "")
        );
        if (!explicit.isBlank()) {
            System.load(new File(explicit).getAbsolutePath());
            loaded = true;
            return;
        }

        String home = System.getProperty(
            "kanvas.home",
            System.getProperty(
                "kotlin.display.home",
                System.getenv().getOrDefault(
                    "KANVAS_HOME",
                    System.getenv().getOrDefault("KOTLIN_DISPLAY_HOME", "")
                )
            )
        );
        if (!home.isBlank()) {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            File build = new File(home, "native/build");
            String[] names = os.contains("mac")
                ? new String[]{"libkotlin_display_native.dylib"}
                : os.contains("win")
                    ? new String[]{
                        "kotlin_display_native.dll",
                        "libkotlin_display_native.dll"
                    }
                    : new String[]{"libkotlin_display_native.so"};

            File[] directories = {
                build,
                new File(build, "Debug"),
                new File(build, "Release"),
                new File(build, "RelWithDebInfo"),
                new File(build, "MinSizeRel")
            };
            for (File directory : directories) {
                for (String name : names) {
                    File candidate = new File(directory, name);
                    if (!candidate.isFile()) continue;
                    System.load(candidate.getAbsolutePath());
                    loaded = true;
                    return;
                }
            }
        }

        try {
            System.loadLibrary("kanvas_native");
        } catch (UnsatisfiedLinkError failure) {
            System.loadLibrary("kotlin_display_native");
        }
        loaded = true;
    }

    public static native long create(int width, int height, String title, int backend);
    public static native long createAttached(Object host, int width, int height, int backend);
    public static native int resize(long handle, int width, int height);
    public static native int activeBackend(long handle);
    public static native int uploadTexture(
        long handle,
        int textureId,
        int width,
        int height,
        int[] argb
    );
    public static native int uploadTextureAddress(
        long handle,
        int textureId,
        int width,
        int height,
        long address,
        int rowBytes
    );
    public static native void releaseTexture(long handle, int textureId);
    public static native boolean poll(long handle);
    public static native long size(long handle);
    public static native boolean nextPointer(long handle, int[] output);
    public static native int present(
        long handle,
        int[] kinds,
        float[] geometry,
        float[] colors,
        float[] uvs
    );
    public static native void destroy(long handle);
}
