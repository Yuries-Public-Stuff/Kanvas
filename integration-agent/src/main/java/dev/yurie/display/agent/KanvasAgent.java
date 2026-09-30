package dev.yurie.display.agent;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.ProtectionDomain;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

// Tracks default renderer activity for takeover checks.
public final class KanvasAgent {
    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean FAILED = new AtomicBoolean();
    private static volatile String AUDIT_PATH = "";

    private static final String[] DEFAULT_RENDERER_PREFIXES = {
        "org/jetbrains/skiko/SkiaLayer",
        "org/jetbrains/skiko/SkiaWindow",
        "org/jetbrains/skiko/redrawer/",
        "org/jetbrains/skia/Canvas",
        "androidx/compose/ui/awt/ComposeLayer"
    };

    private static final String[] COMPOSE_SCENE_PREFIXES = {
        "androidx/compose/ui/ComposeScene",
        "androidx/compose/ui/scene/"
    };

    private KanvasAgent() {}

    public static void premain(String args, Instrumentation instrumentation) {
        final boolean strict = Boolean.parseBoolean(
            property(
                "kanvas.strictRenderer",
                "kotlin.display.strictDefaultRenderer",
                "false"
            )
        );
        final String auditPath = property(
            "kanvas.auditPath",
            "kotlin.display.auditPath",
            ""
        );
        AUDIT_PATH = auditPath;
        final String capturePath = property(
            "kanvas.capturePath",
            "kotlin.display.capturePath",
            ""
        );
        final boolean captureSkiaCanvas = Boolean.parseBoolean(
            property(
                "kanvas.capture",
                "kotlin.display.captureSkiaCanvas",
                "true"
            )
        );
        final boolean takeover = Boolean.parseBoolean(
            property(
                "kanvas.takeover",
                "kotlin.display.takeover",
                "true"
            )
        );

        write(auditPath, "START " + Instant.now() + " strict=" + strict +
            " captureSkiaCanvas=" + captureSkiaCanvas + " takeover=" + takeover);
        ComposeCaptureHooks.configure(capturePath, captureSkiaCanvas, takeover, strict);
        if (captureSkiaCanvas) {
            instrumentation.addTransformer(new SkikoFrameTransformer(), false);
            instrumentation.addTransformer(new SkikoContextTransformer(), false);
            instrumentation.addTransformer(new SkiaCanvasTransformer(), false);
            instrumentation.addTransformer(new ParagraphTransformer(), false);
            instrumentation.addTransformer(new ParagraphBuilderTransformer(), false);
            instrumentation.addTransformer(new ShaderTransformer(), false);
            instrumentation.addTransformer(new ImageFilterTransformer(), false);
            instrumentation.addTransformer(new MaskFilterTransformer(), false);
            instrumentation.addTransformer(new ColorFilterTransformer(), false);
            instrumentation.addTransformer(new PathEffectTransformer(), false);
        }

        ClassFileTransformer transformer = new ClassFileTransformer() {
            @Override
            public byte[] transform(
                Module module,
                ClassLoader loader,
                String className,
                Class<?> classBeingRedefined,
                ProtectionDomain protectionDomain,
                byte[] classfileBuffer
            ) {
                if (className == null) return null;

                recordRuntimeSource(auditPath, className, protectionDomain);

                if (isComposeSceneClass(className) && SEEN.add("compose:" + className)) {
                    write(auditPath, "COMPOSE_SCENE_LOADED " + className.replace('/', '.'));
                }

                if (!isDefaultRendererClass(className)) return null;
                if (!SEEN.add("renderer:" + className)) return null;

                String message = (takeover ? "DEFAULT_RENDERER_INTERCEPTED " : "DEFAULT_RENDERER_LOADED ") +
                    className.replace('/', '.');
                System.err.println("[Kanvas] " + message);
                write(auditPath, message);

                if (strict && !takeover && FAILED.compareAndSet(false, true)) {
                    System.err.println(
                        "[Kanvas] strict renderer ownership failed: " +
                        "the default Skia/Skiko path was loaded without takeover."
                    );
                    write(auditPath, "STRICT_FAIL");
                    Runtime.getRuntime().halt(86);
                }
                return null;
            }
        };

        instrumentation.addTransformer(transformer, false);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            ComposeCaptureHooks.finish();
            if (!FAILED.get()) write(auditPath, "END defaultRendererClasses=" + SEEN.size());
        }, "kanvas-render-audit"));
    }


    private static String property(
        String name,
        String legacyName,
        String fallback
    ) {
        String value = System.getProperty(name);
        if (value != null) return value;
        return System.getProperty(legacyName, fallback);
    }

    private static void recordRuntimeSource(
        String auditPath,
        String className,
        ProtectionDomain protectionDomain
    ) {
        boolean interesting =
            className.equals("org/jetbrains/skia/Canvas") ||
            className.equals("org/jetbrains/skiko/SkiaLayerRenderDelegate") ||
            className.startsWith("androidx/compose/ui/awt/ComposeLayer") ||
            className.startsWith("androidx/compose/ui/scene/");
        if (!interesting || protectionDomain == null ||
            protectionDomain.getCodeSource() == null ||
            protectionDomain.getCodeSource().getLocation() == null) {
            return;
        }

        String source = protectionDomain.getCodeSource().getLocation().toString();
        String key = "runtime-source:" + source;
        if (SEEN.add(key)) {
            write(
                auditPath,
                "RUNTIME_SOURCE class=" + className.replace('/', '.') +
                " source=" + source
            );
        }
    }

    private static boolean isDefaultRendererClass(String className) {
        for (String prefix : DEFAULT_RENDERER_PREFIXES) {
            if (className.startsWith(prefix)) return true;
        }
        return false;
    }

    private static boolean isComposeSceneClass(String className) {
        for (String prefix : COMPOSE_SCENE_PREFIXES) {
            if (className.startsWith(prefix)) return true;
        }
        return false;
    }

    static void audit(String line) {
        write(AUDIT_PATH, line);
    }

    private static void write(String auditPath, String line) {
        if (auditPath == null || auditPath.isBlank()) return;
        try {
            Path path = Path.of(auditPath);
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(
                path,
                line + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            System.err.println("[Kanvas] renderer audit write failed: " + e.getMessage());
        }
    }
}
