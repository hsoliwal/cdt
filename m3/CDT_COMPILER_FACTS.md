# M3 CDT original build-configuration and scanner evidence

CDT owns its project configurations, language settings, built-in macros,
scanner discovery, source exclusions, build output directories and native
toolchain integration. Maven/Gradle must **not** infer these from
`.cproject` contents, a guessed `clang` command or a Maven lifecycle.

`org.eclipse.cdt.core.settings.model.M3CdtCompilerFacts` is an additive
read-only source-owner capture:

```java
var original = M3CdtCompilerFacts.capture(
    eclipseProject, selectedCdtConfigurationId,
    eclipseSelectedIBuildConfigurationName, originalInvocationUuid,
    monitor);
```

The exact CDT configuration ID is supplied by the original native CDT
build owner; it is **not** guessed from the active CDT configuration or
from Eclipse `IBuildConfiguration`. The capture obtains CDT's original
cached **read-only** project description via
`CCorePlugin.getDefault().getProjectDescription(project, false)`.

## Information actually captured

- Original project and invocation IDs, selected Eclipse configuration,
  selected CDT configuration ID/name, and independently active CDT ID
- Source-owned build-system ID, effective reference-info IDs
- Ordered resolved source paths, workspace-path flags and exclusions
- Each declared folder and file resource's language settings, language
  ID, source content types and extensions
- Source-owned declared **and resolved** include paths, include files,
  preprocessor macros, macro files, library paths, library files,
  flags, full paths, workspace ownership and exclusion information
- Build working directory, resolved output paths, error parser IDs and
  target binary parser IDs

The scanner/source/build digests are independently domain-separated
SHA-256 values computed from framed UTF-8 identities. Resource
descriptions are sorted by original project-relative path, while
the source owner's order within language settings and entry arrays is
preserved. Null settings are distinguished from empty settings.

All lists are bounded (8,192 resource descriptions, 64 languages per
resource, at most 262,144 scanner entries). Cancellation propagates
through the original Eclipse progress monitor; no partial hash becomes
an accepted snapshot after cancellation.

## Owner exchange

`Snapshot.toExchangeBytes()` emits strict versioned
`M3-CDT-COMPILER-FACTS-V1` UTF-8/LF bytes with the project,
invocation, distinct Eclipse/CDT/active identities, three SHA-256
digests and associated counts. The source owner retains the
Eclipse/CDT bundle and controls when to export this packet.

**Important:** The snapshot represents observed CDT metadata, not
an authenticated executable or exact compiler invocation. It does
not hash the compiler binary, complete link dependencies, include
file contents, generated outputs, toolchain/sysroot, environment,
CMake generator state or ABI, and it never authorizes a Maven goal,
Gradle task or CDT compiler build to be skipped.

The next adapter in the Maven/Gradle convergence must validate
original project/invocation/selected identities against the existing
`M3-ECLIPSE-BUILD-HANDOFF-V1` before admitting *any* advisory
source-owner evidence. Cross-toolchain cache admission still needs
separate complete compiler, ABI, output and dependency proofs.

## Qualification

`core/org.eclipse.cdt.core.tests/model/org/eclipse/cdt/core/settings/model/M3CdtCompilerFactsTest`
uses CDT's real `CProjectHelper.createNewStyleCProject` fixture to
test stable read-only digests, exact selected versus active CDT
configurations, foreign configuration refusal and cancellation. The
original CDT/Tycho suite must run before declaring qualification.
