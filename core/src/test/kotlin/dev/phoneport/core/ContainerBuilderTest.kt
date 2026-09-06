package dev.phoneport.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ContainerBuilderTest {
    @Test fun mapsEnvironmentPortsAndVolumes() {
        val t = Template("Test", 1, "nginx:latest", env = listOf(EnvField("KEY", "Key"), EnvField("FIXED", "Fixed", "yes", preset = true)),
            ports = listOf("8080:80/tcp", "53/udp"), volumes = listOf(Volume("/data"), Volume("/config", "/srv/config", true)))
        val result = ContainerBuilder.build(t, mapOf("KEY" to "a=b", "FIXED" to "no"))
        assertEquals(listOf("KEY=a=b", "FIXED=yes"), result["Env"]!!.jsonArray.map { it.jsonPrimitive.content })
        val config = result["HostConfig"]!!.jsonObject
        assertEquals("/srv/config:/config:ro", config["Binds"]!!.jsonArray.single().jsonPrimitive.content)
        assertTrue(result["Volumes"]!!.jsonObject.containsKey("/data"))
        val ports = config["PortBindings"]!!.jsonObject
        assertEquals("8080", ports["80/tcp"]!!.jsonArray.first().jsonObject["HostPort"]!!.jsonPrimitive.content)
        assertEquals("", ports["53/udp"]!!.jsonArray.first().jsonObject["HostPort"]!!.jsonPrimitive.content)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsMissingRequiredEnv() {
        ContainerBuilder.build(Template("App", 1, "mysql", env = listOf(EnvField("PASSWORD", "Password"))), emptyMap())
    }
    @Test fun preservesQuotedCommandArguments() {
        assertEquals(listOf("server", "/data", "--console-address", ":9001"), ContainerBuilder.splitCommand("server /data --console-address ':9001'"))
        assertEquals(listOf("echo", "hello world", ""), ContainerBuilder.splitCommand("echo \"hello world\" ''"))
    }
    @Test(expected = IllegalArgumentException::class) fun invalidPortDoesNotSilentlyDisappear() { ContainerBuilder.parsePort("70000:80") }
}
