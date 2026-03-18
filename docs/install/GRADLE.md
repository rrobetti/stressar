# Installing Gradle

The OJP Performance Tester Tool ships with a **Gradle Wrapper** (`./gradlew` / `gradlew.bat`),
which automatically downloads the correct Gradle version on first use.  
**You do not need to install Gradle separately** to build or run the tool.

This guide explains how to install a standalone Gradle installation if you need one (e.g. for IDE
integration, CI environments that do not allow network access, or running Gradle commands outside
the wrapper).

The current stable release is **Gradle 8** (8.x). Gradle 7+ is the minimum version required by
this project.

---

## Table of Contents

- [Using the Gradle Wrapper (recommended)](#using-the-gradle-wrapper-recommended)
- [Ubuntu / Debian](#ubuntu--debian)
- [Red Hat / CentOS / Fedora](#red-hat--centos--fedora)
- [macOS](#macos)
- [Windows](#windows)
- [Manual installation (any OS)](#manual-installation-any-os)
- [SDKMAN (all platforms)](#sdkman-all-platforms)
- [Verify installation](#verify-installation)

---

## Using the Gradle Wrapper (recommended)

The repository already contains a `gradlew` script and a `gradle/wrapper/gradle-wrapper.jar`.
These are all you need:

```bash
# On Linux / macOS
./gradlew build

# On Windows
gradlew.bat build
```

The wrapper downloads the exact Gradle version pinned in
`gradle/wrapper/gradle-wrapper.properties` on first run and caches it under
`~/.gradle/wrapper/dists/`.

**Prerequisite:** Java 11+ must be installed (see [JAVA.md](JAVA.md)).

---

## Ubuntu / Debian

Ubuntu's default repositories may include an older Gradle version. Install via SDKMAN (see
below) or manually to get the latest release.

```bash
# Via APT (may not be the latest version)
sudo apt-get update
sudo apt-get install -y gradle

# Verify
gradle --version
```

---

## Red Hat / CentOS / Fedora

```bash
# Gradle is not in the default RHEL/CentOS repos; use SDKMAN or manual installation
# (see sections below)
```

---

## macOS

```bash
# Via Homebrew
brew install gradle

# Verify
gradle --version
```

---

## Windows

**Option A — Scoop:**

```powershell
scoop bucket add main
scoop install gradle
gradle --version
```

**Option B — Chocolatey:**

```powershell
choco install gradle
gradle --version
```

**Option C — Manual (see below).**

---

## SDKMAN (all platforms)

SDKMAN is the easiest way to install and manage multiple Gradle versions on Linux, macOS, and
Windows (via Git Bash or WSL):

```bash
# Install SDKMAN if not already installed
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"

# List available Gradle versions
sdk list gradle

# Install the latest stable release
sdk install gradle

# Or install a specific version
sdk install gradle 8.13
sdk default gradle 8.13
```

---

## Manual installation (any OS)

1. Download the latest binary distribution from
   <https://gradle.org/releases/>.

2. Extract the archive:

   ```bash
   # Linux / macOS
   sudo mkdir -p /usr/local/gradle
   sudo unzip gradle-8.13-bin.zip -d /usr/local/gradle
   ```

3. Add Gradle to your `PATH`:

   ```bash
   export GRADLE_HOME=/usr/local/gradle/gradle-8.13
   export PATH=$GRADLE_HOME/bin:$PATH
   ```

   Add these lines to `~/.bashrc` (or `~/.zshrc`) to make them permanent:

   ```bash
   echo 'export GRADLE_HOME=/usr/local/gradle/gradle-8.13' >> ~/.bashrc
   echo 'export PATH=$GRADLE_HOME/bin:$PATH' >> ~/.bashrc
   source ~/.bashrc
   ```

   On Windows, add `C:\gradle\gradle-8.13\bin` to your system `Path` environment variable.

---

## Verify installation

```bash
gradle --version
```

Expected output:

```
------------------------------------------------------------
Gradle 8.13
------------------------------------------------------------

Build time:   2025-03-10 12:34:56 UTC
Revision:     <hash>

Kotlin:       1.9.24
Groovy:       3.0.21
Ant:          Apache Ant(TM) version 1.10.14 compiled on August 16 2023
JVM:          21.0.3 (Eclipse Adoptium 21.0.3+9)
OS:           Linux 5.15.0 amd64
```

The version reported must be **7.0 or higher**.

---

*Back to [RUNBOOK.md](../RUNBOOK.md) | [BENCHMARKING_GUIDE.md](../BENCHMARKING_GUIDE.md)*
