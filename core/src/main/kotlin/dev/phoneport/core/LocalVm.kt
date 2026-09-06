package dev.phoneport.core

import kotlinx.serialization.Serializable

// PhonePort cannot read Termux's sandbox, so the local VM is driven entirely by shell text that
// Termux executes on our behalf. Every path is absolute and every script is idempotent: the scripts
// carry no shell variables, so nothing here depends on the state of the Termux login environment.
const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
const val TERMUX_HOME = "/data/data/com.termux/files/home"
const val TERMUX_BASH = "$TERMUX_PREFIX/bin/bash"
const val DEBIAN_ARM64_IMAGE = "https://cloud.debian.org/images/cloud/bookworm/latest/debian-12-genericcloud-arm64.qcow2"

@Serializable data class VmSpec(
    val port: Int = 9000, val memoryMb: Int = 3072, val cpus: Int = 4, val diskGb: Int = 12,
    val imageUrl: String = DEBIAN_ARM64_IMAGE, val portainerImage: String = "portainer/portainer-ce:2.21.4",
) {
    fun validate() {
        require(port in 1024..65535) { "VM port must be between 1024 and 65535" }
        require(memoryMb in 512..16384) { "VM memory must be between 512 MB and 16 GB" }
        require(cpus in 1..8) { "VM CPU count must be between 1 and 8" }
        require(diskGb in 4..128) { "VM disk must be between 4 GB and 128 GB" }
        require(imageUrl.startsWith("https://")) { "The guest image must be downloaded over HTTPS" }
        require(shellSafe(imageUrl) && shellSafe(portainerImage)) { "Image names must not contain quotes or whitespace" }
    }
    val baseUrl get() = "http://127.0.0.1:$port"
}

/** Guest values are pasted into single-quoted shell and YAML, so they must not be able to close a quote. */
fun shellSafe(value: String) = value.isNotBlank() && value.none { it == '\'' || it == '"' || it == '\\' || it == '`' || it.isWhitespace() }

object LocalVm {
    const val ADMIN_USER = "admin"
    const val DIR = "$TERMUX_HOME/phoneport-vm"
    private const val DISK = "$DIR/disk.qcow2"
    private const val PARTIAL = "$DIR/disk.part"
    private const val SEED = "$DIR/seed.img"
    private const val USER_DATA = "$DIR/user-data"
    private const val META_DATA = "$DIR/meta-data"
    private const val PID = "$DIR/qemu.pid"
    private const val CONSOLE = "$DIR/console.log"
    private const val FIRMWARE = "$TERMUX_PREFIX/share/qemu/edk2-aarch64-code.fd"
    private const val PACKAGES = "qemu-system-aarch64-headless qemu-utils wget dosfstools mtools"

    /**
     * Downloads the guest image once and builds a cloud-init seed that installs Docker and starts
     * Portainer with [adminPassword]. The password only ever exists inside the seed image: the
     * plaintext cloud-config is deleted as soon as the seed is written.
     */
    fun provision(spec: VmSpec, adminPassword: String): String {
        spec.validate()
        require(shellSafe(adminPassword) && adminPassword.length >= 12) { "The Portainer admin password must be at least 12 characters without quotes or whitespace" }
        return """
            set -eu
            mkdir -p '$DIR'
            echo '> installing Termux packages'
            pkg install -y $PACKAGES
            if [ ! -f '$DISK' ]; then
              echo '> downloading Debian cloud image (once, several hundred MB)'
              wget -q --show-progress -O '$PARTIAL' '${spec.imageUrl}'
              qemu-img resize '$PARTIAL' '${spec.diskGb}G'
              mv '$PARTIAL' '$DISK'
            fi
            echo '> writing cloud-init seed'
            cat > '$USER_DATA' <<'PHONEPORT_CLOUD_CONFIG'
            #cloud-config
            hostname: phoneport
            package_update: true
            runcmd:
              - [ sh, -c, "curl -fsSL https://get.docker.com | sh" ]
              - [ sh, -c, "install -m 600 /dev/null /root/portainer-admin && printf '%s' '$adminPassword' > /root/portainer-admin" ]
              - [ sh, -c, "docker volume create portainer_data" ]
              - [ sh, -c, "docker run -d --name portainer --restart=always -p 9000:9000 -v /var/run/docker.sock:/var/run/docker.sock -v portainer_data:/data -v /root/portainer-admin:/run/portainer-admin:ro '${spec.portainerImage}' --admin-password-file /run/portainer-admin" ]
            PHONEPORT_CLOUD_CONFIG
            printf 'instance-id: phoneport\nlocal-hostname: phoneport\n' > '$META_DATA'
            rm -f '$SEED'
            dd if=/dev/zero of='$SEED' bs=1M count=2 status=none
            mkfs.vfat -n CIDATA '$SEED' > /dev/null
            mcopy -oi '$SEED' '$USER_DATA' ::user-data
            mcopy -oi '$SEED' '$META_DATA' ::meta-data
            rm -f '$USER_DATA'
            echo '> provisioned'
        """.trimIndent()
    }

    /**
     * Boots the guest headless and forwards the guest's Portainer port to loopback on the phone.
     * There is no KVM on Android, so QEMU runs in TCG and the first boot is slow.
     */
    fun start(spec: VmSpec): String {
        spec.validate()
        return """
            set -eu
            if xargs kill -0 < '$PID' 2> /dev/null; then echo '> already running'; exit 0; fi
            rm -f '$PID'
            test -f '$DISK' || { echo '> not provisioned'; exit 1; }
            echo '> starting QEMU (software emulation, first boot is slow)'
            qemu-system-aarch64 -M virt -accel tcg -cpu cortex-a72 \
              -smp '${spec.cpus}' -m '${spec.memoryMb}' -bios '$FIRMWARE' \
              -drive if=virtio,format=qcow2,file='$DISK' \
              -drive if=virtio,format=raw,file='$SEED' \
              -netdev user,id=net0,hostfwd=tcp:127.0.0.1:${spec.port}-:9000 \
              -device virtio-net-pci,netdev=net0 \
              -display none -serial file:'$CONSOLE' -pidfile '$PID' -daemonize
            echo '> started'
        """.trimIndent()
    }

    fun stop() = """
        set -eu
        xargs kill -TERM < '$PID' 2> /dev/null || echo '> not running'
        rm -f '$PID'
        echo '> stopped'
    """.trimIndent()

    /** Prints `running` or `stopped` so the app can recover state after a restart. */
    fun status() = """
        if xargs kill -0 < '$PID' 2> /dev/null; then echo running; else echo stopped; fi
        test -f '$DISK' && echo provisioned || echo unprovisioned
    """.trimIndent()

    /** Last lines of the guest serial console, the only diagnostic available for a headless boot. */
    fun console() = "tail -n 80 '$CONSOLE' 2> /dev/null || echo '> no console output yet'"
}
