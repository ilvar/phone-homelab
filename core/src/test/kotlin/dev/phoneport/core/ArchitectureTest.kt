package dev.phoneport.core

import org.junit.Assert.*
import org.junit.Test

class ArchitectureTest {
    @Test fun arm64Manifest() { assertEquals(Architecture.ARM64, detect("linux", "arm64")) }
    @Test fun amd64Manifest() { assertEquals(Architecture.AMD64_ONLY, detect("linux", "amd64")) }
    @Test fun windowsAndOtherArchitecturesAreUnknown() {
        assertEquals(Architecture.UNKNOWN, detect("windows", "arm64"))
        assertEquals(Architecture.UNKNOWN, detect("linux", "arm"))
    }
    @Test fun absentIndexAndMalformedAreUnknown() {
        assertEquals(Architecture.UNKNOWN, ArchitectureDetector.detect("{}"))
        assertEquals(Architecture.UNKNOWN, ArchitectureDetector.detect("broken"))
    }
    @Test fun mixedManifestPrefersArm64() {
        assertEquals(Architecture.ARM64, ArchitectureDetector.detect("""{"manifests":[{"platform":{"os":"linux","architecture":"amd64"}},{"platform":{"os":"linux","architecture":"arm64","variant":"v8"}}]}"""))
    }
    @Test fun referencesHandleTagsDigestsAndRegistries() {
        assertEquals(ImageReference("library/nginx", "latest", true), ImageReference.parse("nginx"))
        assertEquals(ImageReference("user/app", "v1", true), ImageReference.parse("docker.io/user/app:v1"))
        assertEquals("sha256:123", ImageReference.parse("nginx@sha256:123").reference)
        assertFalse(ImageReference.parse("ghcr.io/user/app:latest").dockerHub)
        assertFalse(ImageReference.parse("localhost:5000/app").dockerHub)
    }
    private fun detect(os: String, arch: String) = ArchitectureDetector.detect("""{"manifests":[{"platform":{"os":"$os","architecture":"$arch"}}]}""")
}
