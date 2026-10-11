# Noether

**Jakarta Persistence 3.2** for the Vidocq ecosystem, with the provisional
**Jakarta Validation 3.1** provider used by its tests and local TCK harness.

The name honours **Emmy Noether (1882–1935)**, the German mathematician whose
work transformed abstract algebra and connected symmetries with conservation laws.

## Modules

| Directory | Maven coordinates | Role |
| --- | --- | --- |
| `.` | `io.vidocq.noether:noether-root` | Independent reactor, parent `io.vidocq:vidocq-parent:0.4.0-SNAPSHOT` |
| `noether-persistence` | `io.vidocq.noether:noether-jpa` | Persistence provider, dialects, APT, CDI/JTA integration and Java module integration tests |
| `noether-validation` | `io.vidocq.noether:noether-validation` | Local Validation stopgap, not a complete implementation and **not published** |

All implementation packages and Java modules use `io.vidocq.noether`.
The provider is `io.vidocq.noether.jpa.core.NoetherPersistenceProvider`.
Pool and transaction integration retain their external `io.vidocq.mansart`
dependencies. Jakarta Data remains in Mansart; no Data/JPA bridge is introduced.

## Build

Java 25 (Temurin) and Maven 3.9.16 are pinned in `.sdkmanrc`. From this repository:

```sh
sdk env
./mvnw -ntp test
./mvnw -ntp install
./mvnw -ntp -pl noether-persistence/noether-jpa-core -am test
```

Install the required Mansart pool/transaction and Vauban snapshots first when
working locally. `mansart.version` pins the independent Mansart dependency version.
The Vauban integration baseline uses the local snapshot carrying the pending
upstream injection fix. Production remains on the Java module path; the official
TCK uses its separate classpath harness.

## TCK and performance

The standalone runners are deliberately outside the reactor:

```sh
noether-persistence/noether-jpa-tck/run-official-tck-persistence-3.2.sh
noether-validation/noether-validation-tck/run-official-tck-validation-3.1.sh
noether-persistence/check-validation-neutrality.sh
```

The fixture tool retains the pinned official checksums and the documented
TCK-BUG-001 derived-jar patch. Official artifacts are never modified.
No formal Persistence certification is sought; the Web Profile is not a target.
Validation is a test/TCK stopgap, not a conformant Validation claim.

Current comparative harness and modular Leyden AOT smoke commands are:

```sh
cd noether-persistence/noether-jpa-bench
./run-bench.sh noether       # alternatives: hibernate, eclipselink
cd ../noether-jpa-processor-module-it
./run-aot-smoke.sh
```

The runtime consumer is `vidocq-runtime-noether-persistence-extension`,
with its `-codegen` companion and `vidocq-runtime-it-noether-persistence` vehicle.

## Extraction and evidence

On 2026-10-11 the tracked Persistence and Validation implementations were extracted
from Mansart (source branch `persistence-work`, revision `c91d72c`), preserving their
filtered Git history. Root build, licensing and CI infrastructure were adapted
from Mansart. The source repository is <https://codefloe.com/Vidocq/noether>.

The original `BENCH.md`, `TCK.md` and `BUG.md` under the extracted modules retain
their pre-extraction names, commands, source revisions and raw measurements,
explicitly labelled **historical**. They are not fresh measurements of Noether.
Use the commands above and the current runner/benchmark READMEs for the renamed
project. See [ROADMAP.md](ROADMAP.md), [Persistence roadmap](noether-persistence/ROADMAP.md)
and [Validation roadmap](noether-validation/ROADMAP.md) for scope and limitations.

The project uses the Vidocq disjunctive triple licence:
EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later. See `LICENSE`, `licenses/` and `NOTICE`;
the bundled Jakarta ORM schema retains its original upstream licence.

Noether has no independent published release yet. `0.4.0-SNAPSHOT` is the
current development version; older Mansart versions in historical evidence
are not Noether releases.
