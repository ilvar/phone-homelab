package dev.phoneport.core

import org.junit.Assert.*
import org.junit.Test

class TemplateParserTest {
    private fun fixture(name: String) = javaClass.getResource("/catalogs/$name.json")!!.readText()
    @Test fun officialV3() {
        val result = TemplateParser.parse(fixture("official"), "official")
        assertTrue(result.templates.size > 50)
        assertTrue(result.hiddenSwarm > 0)
        val registry = result.templates.first { it.title == "Registry" }
        assertEquals("registry:latest", registry.image)
        assertEquals(listOf("5000/tcp"), registry.ports)
        assertEquals("/var/lib/registry", registry.volumes.single().container)
        assertTrue(result.templates.any { it.type == 3 && it.repository != null })
    }
    @Test fun communityV3() {
        val result = TemplateParser.parse(fixture("lissy93"), "lissy")
        assertTrue(result.templates.size > 500)
        assertTrue(result.templates.none { it.type == 2 })
        assertTrue(result.templates.any { it.env.any { e -> e.select.isNotEmpty() } })
        assertEquals("true", result.templates.first { it.title == "Baserow (container)" }.labels["traefik.enable"])
    }
    @Test fun piHostedV2() {
        val result = TemplateParser.parse(fixture("pi-hosted"), "pi")
        assertTrue(result.templates.size > 200)
        assertTrue(result.templates.any { it.ports.contains("53:53/udp") })
        assertTrue(result.templates.any { it.type == 3 })
    }
    @Test fun linuxserverMovedNoticeIsNotDeployable() {
        val result = TemplateParser.parse(fixture("linuxserver"), "lsio")
        assertEquals(0, result.templates.size)
        assertEquals(1, result.dropped)
    }
    @Test fun malformedEntriesDoNotPoisonValidNeighbors() {
        val result = TemplateParser.parse(fixture("malformed"), "test")
        assertEquals(4, result.dropped); assertEquals(1, result.hiddenSwarm)
        assertEquals(listOf("Lenient", "After malformed"), result.templates.map { it.title })
        val t = result.templates.first()
        assertEquals(listOf("Web"), t.categories)
        assertEquals("42", t.env.first().default); assertTrue(t.env.first().preset)
        assertEquals("true", t.env.last().default); assertTrue(t.volumes.single().readOnly)
    }
    @Test fun dedupPreservesAllSourcesButNotDifferentImages() {
        val a = Template("App", 1, "nginx", sources = setOf("a"))
        val result = TemplateParser.deduplicate(listOf(a, a.copy(sources = setOf("b")), a.copy(image = "alpine")))
        assertEquals(2, result.size); assertEquals(setOf("a", "b"), result.first().sources)
    }
    @Test(expected = IllegalArgumentException::class) fun unsupportedVersionFailsClearly() {
        TemplateParser.parse("""{"version":"9","templates":[]}""", "test")
    }
}
