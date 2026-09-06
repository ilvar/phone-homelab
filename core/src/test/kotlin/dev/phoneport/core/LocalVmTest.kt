package dev.phoneport.core

import org.junit.Assert.*
import org.junit.Test

class LocalVmTest {
    private val password = "aVeryLongPassword123"

    @Test fun `start forwards the configured port to the guest Portainer`() {
        val script = LocalVm.start(VmSpec(port = 9443, cpus = 2, memoryMb = 2048))
        assertTrue(script.contains("hostfwd=tcp:127.0.0.1:9443-:9000"))
        assertTrue(script.contains("-smp '2'"))
        assertTrue(script.contains("-m '2048'"))
    }

    @Test fun `start is idempotent and refuses an unprovisioned disk`() {
        val script = LocalVm.start(VmSpec())
        assertTrue(script.contains("if xargs kill -0 < '${LocalVm.DIR}/qemu.pid' 2> /dev/null; then echo '> already running'; exit 0; fi"))
        assertTrue(script.contains("test -f '${LocalVm.DIR}/disk.qcow2'"))
    }

    @Test fun `provision downloads once and deletes the plaintext cloud-config`() {
        val script = LocalVm.provision(VmSpec(diskGb = 20), password)
        assertTrue(script.contains("if [ ! -f '${LocalVm.DIR}/disk.qcow2' ]; then"))
        assertTrue(script.contains("qemu-img resize '${LocalVm.DIR}/disk.part' '20G'"))
        assertTrue(script.contains("--admin-password-file /run/portainer-admin"))
        // The password reaches the guest only through the seed image.
        assertTrue(script.indexOf(password) < script.indexOf("rm -f '${LocalVm.DIR}/user-data'"))
        assertTrue(script.trimEnd().endsWith("echo '> provisioned'"))
    }

    @Test fun `scripts use no shell variables so the Termux environment cannot change them`() {
        val scripts = listOf(LocalVm.provision(VmSpec(), password), LocalVm.start(VmSpec()), LocalVm.stop(), LocalVm.status(), LocalVm.console())
        // '%s' in printf is fine; a bare $ would be an unresolved expansion.
        scripts.forEach { assertFalse(it, it.contains('$')) }
    }

    @Test fun `weak or injectable admin passwords are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { LocalVm.provision(VmSpec(), "short") }
        assertThrows(IllegalArgumentException::class.java) { LocalVm.provision(VmSpec(), "'; rm -rf / #padding") }
        assertThrows(IllegalArgumentException::class.java) { LocalVm.provision(VmSpec(), "has space in it here") }
    }

    @Test fun `spec limits are enforced`() {
        assertThrows(IllegalArgumentException::class.java) { VmSpec(port = 80).validate() }
        assertThrows(IllegalArgumentException::class.java) { VmSpec(memoryMb = 128).validate() }
        assertThrows(IllegalArgumentException::class.java) { VmSpec(cpus = 0).validate() }
        assertThrows(IllegalArgumentException::class.java) { VmSpec(diskGb = 1).validate() }
        assertThrows(IllegalArgumentException::class.java) { VmSpec(imageUrl = "http://example.test/i.qcow2").validate() }
        assertThrows(IllegalArgumentException::class.java) { VmSpec(portainerImage = "portainer' ; sh").validate() }
        VmSpec().validate()
    }

    @Test fun `base url points at the forwarded loopback port`() {
        assertEquals("http://127.0.0.1:9000", VmSpec().baseUrl)
        assertEquals("http://127.0.0.1:9500", VmSpec(port = 9500).baseUrl)
        assertTrue(privateHost(normalizeBaseUrl(VmSpec().baseUrl).host))
    }
}
