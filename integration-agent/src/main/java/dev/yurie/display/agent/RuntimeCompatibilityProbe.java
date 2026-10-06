package dev.yurie.display.agent;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.security.CodeSource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight compatibility probe for external projects.
 *
 * Runs in a separate JVM using the target application's real JavaExec
 * classpath. It never starts the application.
 */
public final class RuntimeCompatibilityProbe {
    private static final Pattern VERSIONED_JAR =
        Pattern.compile("-(\\d+(?:\\.\\d+)+(?:[-.][A-Za-z0-9]+)*)\\.jar$");

    private RuntimeCompatibilityProbe() {}

    public static void main(String[] args) {
        Map<String, String[]> probes = new LinkedHashMap<>();
        probes.put(
            "skiko",
            new String[] {
                "org.jetbrains.skiko.SkiaLayer",
                "org.jetbrains.skia.Canvas"
            }
        );
        probes.put(
            "compose-ui",
            new String[] {
                "androidx.compose.ui.awt.ComposeWindow",
                "androidx.compose.ui.awt.ComposePanel",
                "androidx.compose.ui.scene.ComposeScene"
            }
        );
        probes.put(
            "compose-runtime",
            new String[] {
                "androidx.compose.runtime.Composer"
            }
        );

        boolean foundAny = false;
        for (Map.Entry<String, String[]> entry : probes.entrySet()) {
            ProbeResult result = firstPresent(entry.getValue());
            if (result == null) {
                System.out.println(
                    "KANVAS_PROBE kind=" + entry.getKey() + " status=missing"
                );
                continue;
            }

            foundAny = true;
            System.out.println(
                "KANVAS_PROBE kind=" + entry.getKey() +
                " status=found" +
                " class=" + result.className +
                " version=" + safe(result.version) +
                " source=" + safe(result.source)
            );
        }

        if (!foundAny) {
            System.exit(3);
        }
    }

    private static ProbeResult firstPresent(String[] classNames) {
        for (String className : classNames) {
            try {
                Class<?> type = Class.forName(
                    className,
                    false,
                    Thread.currentThread().getContextClassLoader()
                );
                return inspect(type);
            } catch (ClassNotFoundException ignored) {
                // Try the next representative class.
            } catch (LinkageError error) {
                System.out.println(
                    "KANVAS_PROBE kind=internal status=linkage-error" +
                    " class=" + className +
                    " detail=" + safe(error.toString())
                );
            }
        }
        return null;
    }

    private static ProbeResult inspect(Class<?> type) {
        String source = "";
        String version = "";

        Package pkg = type.getPackage();
        if (pkg != null) {
            if (pkg.getImplementationVersion() != null) {
                version = pkg.getImplementationVersion();
            } else if (pkg.getSpecificationVersion() != null) {
                version = pkg.getSpecificationVersion();
            }
        }

        try {
            CodeSource codeSource = type.getProtectionDomain().getCodeSource();
            if (codeSource != null && codeSource.getLocation() != null) {
                URL url = codeSource.getLocation();
                source = url.toString();

                if (version.isBlank()) {
                    version = versionFromSource(url);
                }
            }
        } catch (SecurityException ignored) {
            // Source/version may remain blank.
        }

        return new ProbeResult(type.getName(), version, source);
    }

    private static String versionFromSource(URL url) {
        try {
            URI uri = url.toURI();
            File file = new File(uri);
            String name = file.getName();

            Matcher matcher = VERSIONED_JAR.matcher(name);
            if (matcher.find()) {
                return matcher.group(1);
            }

            String normalized = file.getAbsolutePath().replace('\\', '/');
            String marker = "/modules-2/files-2.1/";
            int index = normalized.indexOf(marker);
            if (index >= 0) {
                String[] parts = normalized
                    .substring(index + marker.length())
                    .split("/");
                if (parts.length >= 3) {
                    return parts[2];
                }
            }
        } catch (Exception ignored) {
            // Leave version unknown.
        }
        return "";
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value
            .replace("\\", "\\\\")
            .replace(" ", "%20")
            .replace("\n", "%0A")
            .replace("\r", "%0D");
    }

    private record ProbeResult(
        String className,
        String version,
        String source
    ) {}
}
