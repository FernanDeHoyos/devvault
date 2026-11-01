# DevVault third-party notices (review draft)

This distribution contains third-party software. DevVault's Apache-2.0
`LICENSE` applies to DevVault's own code; it does not replace the licenses of
the bundled components. This document and the accompanying reports are a
technical inventory for review, not a legal opinion or a declaration that all
redistribution conditions have been satisfied.

## Component inventories

The package's `third-party-notices/` directory contains:

- `java-dependencies.csv` and `java-dependencies.html`: the resolved Gradle
  `runtimeClasspath`, including transitive JARs shipped in `devvault.jar`,
  with project and license metadata from Maven POMs.
- `java-license-file-status.txt`: records which embedded Java JARs supplied
  their own license or notice texts and which must be reviewed from upstream.
- `java-packaged-jars.txt`: exact JAR files included in the executable
  application. Spring Boot can add packaging tools not present in the Gradle
  `runtimeClasspath` report; use this list alongside the report.
- `ui-sbom.cdx.json`: a CycloneDX SBOM for production npm dependencies shipped
  in the compiled UI. Development-only npm tools are excluded.
- `ui/`: license and notice files copied from installed production npm
  dependencies when those files are present.
- `java/`: license and notice files copied from the nested runtime JARs when
  those files are present.
- `ui-license-file-status.txt`: identifies production UI dependencies that did
  not provide a top-level license/notice text file.

The bundled Java runtime's own legal files are retained under
`runtime/legal/`. The corresponding license and notice files for Java
dependencies that do not embed a copy are identified by their Maven metadata
in the CSV/HTML report and must be obtained from the upstream project.

## Items for specialist review

The resolved Java runtime includes components whose published metadata
identifies licenses other than Apache-2.0, including LGPL, EPL, EDL and GPL
with the Classpath Exception. The UI production dependencies also have their
own licenses. Review the generated reports and all accompanying license and
notice texts against the exact versions in the package.

The current Java report specifically flags these components for review:

| Coordinates | Reported license |
|---|---|
| `ch.qos.logback:logback-classic:1.5.12`, `ch.qos.logback:logback-core:1.5.12` | GNU LGPL |
| `net.java.dev.jna:jna:5.13.0`, `net.java.dev.jna:jna-jpms:5.19.1`, `net.java.dev.jna:jna-platform-jpms:5.19.1` | LGPL-2.1-or-later |
| `org.hibernate.orm:hibernate-core:6.6.2.Final` | GNU LGPL 2.1 or later |
| `org.apache.tomcat.embed:tomcat-embed-core:10.1.33`, `org.aspectj:aspectjweaver:1.9.22.1` | Eclipse Public License 2.0 |
| `com.sun.istack:istack-commons-runtime:4.1.2`, Jakarta Activation/Persistence/Validation/XML Bind APIs `2.1.3`/`3.1.0`/`3.0.2`/`4.0.2`, Jakarta Annotation/Transaction APIs `2.1.1`/`2.0.1`, Angus Activation `2.0.2`, JAXB Core/Runtime `4.0.5`, and `org.glassfish.jaxb:txw2:4.0.5` | GPL-2.0 with GNU Classpath Exception, per the selected Maven metadata |
| `org.bouncycastle:bcpkix-jdk18on:1.76`, `bcprov-jdk18on:1.76`, `bcutil-jdk18on:1.76` | Bouncy Castle License |
| `org.antlr:antlr4-runtime:4.13.0`; `org.postgresql:postgresql:42.7.4` | BSD-3-Clause; BSD-2-Clause |

This table highlights results from the automated metadata report; it is not a
complete legal classification. Some upstream projects publish multiple license
options or terms in addition to the one selected by Maven metadata. Verify the
upstream license and notice documents for each exact component/version,
including the Java runtime and all files listed by `java-packaged-jars.txt`.

In particular, determine whether the distribution method, modifications,
linking/combination, source availability, relinking, attribution, and notice
requirements are satisfied for each applicable license. Do not treat this
inventory as legal clearance. Resolve every `Unknown`, missing license file,
or conflicting license expression before publishing binary releases.

## Regenerating the inventory

The platform packagers regenerate the Java report and production UI SBOM.
For a manual review from the repository root:

```powershell
.\gradlew.bat extractRuntimeDependencyLicenses
Set-Location ui
npm ci
npm sbom --omit=dev --sbom-format=cyclonedx
```

On Linux, use `./gradlew extractRuntimeDependencyLicenses` for the Gradle
command. `scripts/collect-ui-license-files.mjs` generates the production SBOM
and copies the corresponding npm license texts.
Compare reports against the exact JAR and UI included in each candidate
package; a report from another commit is not evidence for that package.
