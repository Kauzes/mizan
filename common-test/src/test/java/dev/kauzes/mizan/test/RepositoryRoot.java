package dev.kauzes.mizan.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Finds the checkout, so a test can read the files the platform is actually deployed from. */
final class RepositoryRoot {

    private RepositoryRoot() {
    }

    static Path path() {
        Path candidate = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (candidate != null && !Files.exists(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("could not find the repository root from user.dir");
        }
        return candidate;
    }

    static String read(String relativePath) {
        try {
            return Files.readString(path().resolve(relativePath));
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + relativePath, e);
        }
    }
}
