# PhonePort

Native Android client for browsing Portainer app catalogs and deploying to a selected Portainer environment. Portainer is the only container-management backend; the client never talks to a Docker socket directly. It can either connect to a Portainer you already run, or create and run one on the phone itself (see **Run Portainer** below).

## Build and sideload

Requirements: JDK 21, Android SDK platform 36 and build tools 35.0.0. Set `JAVA_HOME` and `ANDROID_HOME`, or put `sdk.dir=/path/to/android-sdk` in an untracked `local.properties` file.

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The default APK targets arm64-v8a and Android 10+ (minSdk 29). On your phone, allow APK installation from the app used to open the file. This is a debug-signed sideload build, not a Play Store release.

On first launch, enter the Portainer URL and API key (or username/password). Enable the self-signed toggle only if required. Connect, then select a Docker environment; a single environment is selected automatically. Later launches open **Your apps**, with installed/running app tiles, state, image, uptime/status, port links, and a large **Add app** button. Container and Compose stack tiles are listed separately, including containers belonging to stacks.

**Add app → template tile → Deploy** is the catalog/deploy flow. Fill required environment fields before deployment. Notes, volumes, port mappings, template presets, dropdown defaults, and privileged-container requests are shown in the detail screen. Pull and deployment progress appears in a cancellable sheet. Tap an installed tile for start, stop, restart, remove confirmation, and container logs.

Mapped ports open on the Portainer host when the binding is wildcard/loopback. For a VM-hosted Portainer, those guest ports also need host forwarding; apart from Portainer's own port, this client does not create VM forwards. TCP services that are not HTTP will not render in a web browser.

## Run Portainer

**Settings → Run Portainer** creates and boots a local Portainer without any other machine. It requires [Termux](https://f-droid.org/packages/com.termux/), which hosts the virtual machine; PhonePort itself only sends commands to it.

Termux setup, once:

```sh
# in Termux
mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties
# then fully restart Termux
```

Grant the Termux `RUN_COMMAND` permission when PhonePort asks. The first run then, inside Termux:

1. installs `qemu-system-aarch64-headless`, `qemu-utils`, `wget`, `dosfstools` and `mtools`;
2. downloads a Debian 12 arm64 cloud image (several hundred MB) and grows it to 12 GB;
3. builds a cloud-init seed that installs Docker and starts `portainer-ce`;
4. boots the guest headless, forwarding guest port 9000 to `127.0.0.1:9000` on the phone;
5. waits for Portainer to answer, then signs in as `admin` with a generated 24-character password kept in the app's encrypted credential store.

Everything lives in `~/phoneport-vm` inside Termux. Later runs skip provisioning and just boot the existing disk. **Guest console** prints the tail of the guest's serial log, which is the only diagnostic for a headless boot.

Android phones expose no KVM, so QEMU runs in TCG software emulation. The VM works but is slow, and the first boot — which also installs Docker inside the guest — can take a long time. Budget storage (image plus disk) and battery accordingly.

## Tests

```sh
./gradlew :core:test :app:testDebugUnitTest
./gradlew -PtargetAbi=x86_64 :app:connectedDebugAndroidTest
```

For an arm64 emulator/device, omit `-PtargetAbi=x86_64`. The property changes only the verification APK's native ABI; the normal build remains arm64. JVM tests use local JSON fixtures and MockWebServer and require no Portainer or internet. Build dependencies download on the first run; subsequent JVM runs support `--offline` once dependencies are cached. Reports are under each module's `build/reports/tests` directory. Compose tests cover connection input, installed tiles/actions, removal confirmation, catalog selection, and required-field deployment gating.

For a local emulator smoke test using the included **mock**, not a real Docker backend:

```sh
python3 tools/mock_portainer.py --port 19000
adb reverse tcp:9000 tcp:19000
```

Connect the emulator app to `http://127.0.0.1:9000` using `demo-key`. The mock serves sample installed apps and simulates pulls, creation, actions, and framed logs. It does not start real containers. See [verification notes](docs/VERIFICATION.md) for executed checks and limitations.

## Model and parser

See [Template.kt](core/src/main/kotlin/dev/phoneport/core/Template.kt) and [TemplateParserTest.kt](core/src/test/kotlin/dev/phoneport/core/TemplateParserTest.kt).

`Template` unifies container (1) and Compose stack (3) entries. It retains image, repository, source URLs, environment field metadata, ports, volumes, restart policy, command, network, labels, and privilege/interactive flags. Swarm entries (2) are hidden. Missing titles, images for containers, or repository information for stacks cause individual entries to be dropped. Inconsistent scalar types and malformed optional subentries are tolerated. Invalid whole documents produce explicit errors and retain the last disk cache.

Environment defaults are strings, including numeric/boolean catalog defaults. Dropdown option defaults are honored when an explicit field default is absent. Presets cannot be overridden by the request builder. Fields are required unless the catalog explicitly marks them optional or not required.

Deduplication uses the exact `(title, image)` pair and unions source URLs and categories. As requested, Compose entries with the same title and an empty image also deduplicate under that rule.

## Real fixtures

Snapshots downloaded on 2026-09-06 are under [core/src/test/resources/catalogs](core/src/test/resources/catalogs). [SOURCES.json](core/src/test/resources/catalogs/SOURCES.json) records their original URLs. They remain upstream catalog data, provided here for reproducible parser tests.

| Fixture | Schema | Raw entries |
| --- | --- | --- |
| Official | 3 | 74 |
| Lissy93 | 3 | 684 |
| Pi-Hosted arm64 | 2 | 230 |
| LinuxServer.io requested URL | 2 | 1 |

The supplied LinuxServer.io `templates-2.0.json` URL currently serves a migration notice without an image, so it correctly yields zero deployable entries. That notice points to `https://raw.githubusercontent.com/technorabilia/portainer-templates/main/lsio/templates/templates.json`. The original requested default is preserved.

## Prepare Portainer access

1. Ensure Portainer is reachable from Android, for example via your existing VM port forwarding at `http://127.0.0.1:9000` or via its LAN address.
2. In Portainer, select your username, then **My account**.
3. Under **Access tokens**, choose **Add access token**, enter a description, and confirm with your password.
4. Copy the generated token while it is displayed. Portainer will not display it again.

The token uses the `X-API-Key` header and has your Portainer user's permissions. See [Portainer's API access documentation](https://docs.portainer.io/2.27/api/access).

## Self-signed certificates and HTTP

TLS verification is enabled by default. The network factory accepts an explicit opt-in to disable certificate and hostname validation for the configured Portainer origin only. This allows self-signed servers but also removes protection against impersonation; prefer a trusted certificate whenever possible. Registry/catalog clients use normal certificate validation and never receive Portainer credentials. Redirect following is disabled to avoid leaking credentials or bypassing the origin restriction.

The URL validator permits HTTP only for localhost, IPv4 loopback, RFC1918 IPv4 addresses, and IPv6 loopback. Android network-security XML cannot express CIDR ranges, so the Android layer uses a matching application-level restriction; see [Android's network security configuration documentation](https://developer.android.com/privacy-and-security/security-config).

Connection, read, and write timeouts are 60 seconds. API mutation requests are not automatically retried after transport failures. JWT authentication retries once on 401 after renewal. Docker pull stream errors and Portainer's `message` field are preserved verbatim.

## Storage and network behavior

Connection settings, the selected environment, and catalog source URLs use DataStore. API keys, JWTs, and username/password credentials use EncryptedSharedPreferences backed by Android Keystore. Username/password credentials are retained only to renew expired JWT sessions. App backup is disabled. Switching connections validates new credentials before replacing the saved connection.

Catalogs are parsed into disk snapshots before browsing, revalidated with ETag/If-None-Match on refresh, and retained on failures. Already cached catalogs are browsable offline after restarting the app. Sources can be added or removed in Settings. Deduplicated cards retain all source labels. Search is debounced by 180 ms and matches all entered terms across title, description, image and categories.

Architecture checks are lazy, limited to three queued workers, and only query Docker Hub token/manifest APIs. Confirmed results are cached for seven days; unknown results for one hour. Other registries and single-image manifests without a platform index return unknown. **Hide non-arm64** hides confirmed amd64-only entries; unknown entries stay visible and deployable. Compose stacks have unknown architecture because their services may use multiple images.

The app contains no analytics or crash reporting. Catalog and registry clients are separate from the authenticated Portainer client. Remote logo/stackfile access is restricted to HTTPS origins hosting your configured catalogs; other hosts produce placeholders or an explicit stackfile error. SVG logos use a placeholder because no extra SVG decoder dependency was added. GitHub repository stackfiles are converted to raw URLs using their specified reference or `HEAD` (the default branch), without calling GitHub's API. Stackfile retrieval uses normal TLS verification.

Cancel stops the client's in-flight requests and closes streams. It cannot roll back work Portainer has already accepted. Refresh installed apps before retrying after cancellation, a timeout, or a connection loss. If container creation succeeds but starting fails, the created container is retained and its ID appears in progress. Container removal requires the container to be stopped and retains volumes. Compose restart performs stop then start; a failure between those operations leaves the stack stopped.

Plain TTY logs and Docker's framed stdout/stderr stream are supported. The viewer retains the latest 150,000 characters; an idle stream can time out after 60 seconds and can be reopened. Compose stacks expose lifecycle actions; logs are available on their individual container tiles. Stack tiles show stack state; container tiles show Docker's uptime/status and port mappings.
