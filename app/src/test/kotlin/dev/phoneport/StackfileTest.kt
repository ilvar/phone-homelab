package dev.phoneport

import dev.phoneport.core.Repository
import org.junit.Assert.*
import org.junit.Test

class StackfileTest {
    private val backend = Backend(ResourceNetwork())
    @Test fun githubDefaultBranchUsesHeadWithoutApiLookup() {
        assertEquals("https://raw.githubusercontent.com/portainer/templates/HEAD/stacks/wordpress/docker-compose.yml",
            backend.stackfileUrl(Repository("https://github.com/portainer/templates.git", "stacks/wordpress/docker-compose.yml")).toString())
    }
    @Test fun githubReferenceIsPreserved() {
        assertEquals("https://raw.githubusercontent.com/user/repo/v3/compose.yml",
            backend.stackfileUrl(Repository("https://github.com/user/repo", "compose.yml", "v3")).toString())
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsTraversal() { backend.stackfileUrl(Repository("https://github.com/user/repo", "../secret")) }
    @Test fun resourcesRestrictLogosToCatalogHosts() {
        val network = ResourceNetwork()
        assertTrue(network.allowed("https://raw.githubusercontent.com/user/repo/main/logo.png"))
        assertFalse(network.allowed("https://unrelated.example/logo.png"))
        assertFalse(network.allowed("http://raw.githubusercontent.com/logo.png"))
    }
}
