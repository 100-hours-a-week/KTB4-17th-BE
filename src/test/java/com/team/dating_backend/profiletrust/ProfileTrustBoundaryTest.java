package com.team.dating_backend.profiletrust;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProfileTrustBoundaryTest {

    @Test
    void 다른_도메인의_운영코드와_테스트는_profiletrust에_의존하지_않는다() throws IOException {
        for (String sourceSet : new String[]{"main", "test"}) {
            Path root = Path.of("src", sourceSet, "java", "com", "team", "dating_backend");
            try (var files = Files.walk(root)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.startsWith(root.resolve("profiletrust"))).toList()) {
                    assertThat(Files.readString(file)).as(file.toString())
                        .doesNotContain("com.team.dating_backend.profiletrust");
                }
            }
        }
    }
}
