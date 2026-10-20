package com.gustler.backend.arrival.profiling;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.jar.Attributes.Name;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.bytebuddy.agent.builder.AgentBuilder;

public final class TimingAgentJar {
    private TimingAgentJar() {
    }

    public static Path create(Path destination) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Premain-Class", ArrivalTimingAgent.class.getName());
        manifest.getMainAttributes().putValue("Multi-Release", "true");
        Path library = Path.of(AgentBuilder.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(destination), manifest);
            ZipFile zip = new ZipFile(library.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || name.startsWith("META-INF/") && !name.startsWith("META-INF/versions/")) {
                    continue;
                }
                jar.putNextEntry(new JarEntry(name));
                try (InputStream input = zip.getInputStream(entry)) {
                    input.transferTo(jar);
                }
                jar.closeEntry();
            }
            appendClass(jar, ArrivalTimingAgent.class);
        }
        return destination.toAbsolutePath();
    }

    private static void appendClass(JarOutputStream jar, Class<?> type) throws IOException {
        String name = type.getName().replace('.', '/') + ".class";
        jar.putNextEntry(new JarEntry(name));
        try (InputStream source = type.getClassLoader().getResourceAsStream(name)) {
            if (source == null) {
                throw new IOException("계측 클래스가 없다: " + name);
            }
            source.transferTo(jar);
        }
        jar.closeEntry();
        for (Class<?> nested : type.getDeclaredClasses()) {
            appendClass(jar, nested);
        }
    }
}
