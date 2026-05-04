# Installing Java

Stressar requires **Java 11 or later**. The latest Long-Term Support (LTS)
release is recommended for production benchmark runs; as of 2025 that is **Java 21**.

---

## Table of Contents

- [Ubuntu / Debian](#ubuntu--debian)
- [Red Hat / CentOS / Fedora](#red-hat--centos--fedora)
- [macOS](#macos)
- [Windows](#windows)
- [Manual installation (any OS)](#manual-installation-any-os)
- [Verify installation](#verify-installation)
- [Choosing a JVM distribution](#choosing-a-jvm-distribution)

---

## Ubuntu / Debian

```bash
# Refresh package index
sudo apt-get update

# Install the latest LTS JDK available in the default repository
sudo apt-get install -y default-jdk

# Or install a specific version (e.g. Java 21) via the official Adoptium PPA
sudo apt-get install -y wget apt-transport-https gpg
wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public \
    | gpg --dearmor | sudo tee /etc/apt/trusted.gpg.d/adoptium.gpg > /dev/null
echo "deb https://packages.adoptium.net/artifactory/deb $(. /etc/os-release; echo $VERSION_CODENAME) main" \
    | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt-get update
sudo apt-get install -y temurin-21-jdk
```

---

## Red Hat / CentOS / Fedora

```bash
# Fedora / CentOS Stream 9+
sudo dnf install -y java-21-openjdk-devel

# Older CentOS / RHEL 8
sudo yum install -y java-11-openjdk-devel
```

---

## macOS

**Option A — SDKMAN (recommended):**

```bash
# Install SDKMAN if not already installed
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"

# List available Java versions
sdk list java

# Install Eclipse Temurin 21
sdk install java 21.0.3-tem
sdk default java 21.0.3-tem
```

**Option B — Homebrew:**

```bash
brew install --cask temurin@21
```

---

## Windows

1. Download the Eclipse Temurin 21 installer from
   <https://adoptium.net/temurin/releases/?version=21>.
2. Run the `.msi` installer, selecting the option to set `JAVA_HOME` and add `java` to `PATH`.
3. Open a new Command Prompt and verify the installation (see below).

---

## Manual installation (any OS)

1. Download a JDK 21 tarball for your platform from
   <https://adoptium.net/temurin/releases/?version=21>.
2. Extract the archive to a permanent location, e.g.:

   ```bash
   sudo tar -xzf OpenJDK21*.tar.gz -C /usr/local/lib/jvm/
   ```

3. Set environment variables:

   ```bash
   export JAVA_HOME=/usr/local/lib/jvm/jdk-21
   export PATH=$JAVA_HOME/bin:$PATH
   ```

   Add these lines to `~/.bashrc` (or `~/.zshrc`) to make them permanent.

---

## Verify installation

```bash
java -version
```

Expected output (exact version may differ):

```
openjdk version "21.0.3" 2024-04-16
OpenJDK Runtime Environment Temurin-21.0.3+9 (build 21.0.3+9)
OpenJDK 64-Bit Server VM Temurin-21.0.3+9 (build 21.0.3+9, mixed mode)
```

The version reported must be **11 or higher**. The benchmark tool will refuse to start on
Java 8 or below.

```bash
# Also confirm JAVA_HOME is set correctly
echo $JAVA_HOME
```

---

## Choosing a JVM distribution

Any OpenJDK-compatible distribution works:

| Distribution | Website |
|---|---|
| Eclipse Temurin (recommended) | <https://adoptium.net> |
| Amazon Corretto | <https://aws.amazon.com/corretto/> |
| Microsoft Build of OpenJDK | <https://www.microsoft.com/openjdk> |
| Oracle JDK | <https://www.oracle.com/java/technologies/downloads/> |

For benchmarking, prefer a JDK with a HotSpot JVM (all of the above) rather than an
alternative VM such as GraalVM native-image, because the tool relies on JIT compilation
behaviour and JVM-level GC pause metrics.

---

## JVM tuning for benchmarking

The following flags improve benchmark predictability by bounding GC pauses and enabling
detailed GC logging:

```bash
export JAVA_OPTS="-Xmx4g -Xms4g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=20 \
  -XX:+PrintGCDetails \
  -XX:+PrintGCDateStamps \
  -Xloggc:gc.log"
```

Pass these via the `JAVA_OPTS` environment variable before running `./gradlew` or the
`bench` executable.

---

*Back to [RUNBOOK.md](../RUNBOOK.md) | [BENCHMARKING_GUIDE.md](../BENCHMARKING_GUIDE.md)*
