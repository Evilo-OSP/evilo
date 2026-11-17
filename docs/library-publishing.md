# Publishing the evilo library SDK

`io.github.crowforkotlin.evilo:evilo-library` is the Kotlin Multiplatform module
under `evilo-library/`. Plugin repositories implement `MangaSourceService` and
exchange `Manga`/`Chapter`/`Page`, so they must compile against this module from
a repository they can reach. Once it is on Maven Central they declare one
dependency and need no checkout of this repository.

Artifacts published per release: the multiplatform root, `-jvm`, `-android`,
`-iosarm64`, `-iossimulatorarm64`, `-js`, `-wasm-js`, and `-wasm-wasi`. Each
carries a sources JAR and a javadoc JAR, which Maven Central requires.

Those javadoc JARs are **placeholders**. Central explicitly allows placeholder
javadoc JARs, and generating Dokka HTML for all eight targets produced ~1 MiB
each — 8 MiB of duplicate documentation for a 432-line module, 88% of the whole
publication. The real KDoc stays in this repository. Together with sources, the
whole release is ~1.1 MiB / 43 files, or ~4.5 MiB / 172 files once Central adds
`.asc`, `.md5`, and `.sha1` sidecars.

## Versioning

The version comes from one place: `evilo-plugin-sdk` in
`gradle/libs.versions.toml`. `evilo-library/build.gradle.kts` reads it and the
release workflow refuses to publish when the git tag disagrees with it.

Bump it for every published release; never republish an existing version. This
coordinate is a public API commitment — the plugin protocol and the model classes
are consumed by third-party repositories. Additive changes may take a minor bump
before `1.0.0`; a breaking change to `MangaSourceService`, `EviloPluginService`,
or the serialized envelopes needs an explicit decision and, after `1.0.0`, a
major bump.

Current version: `0.0.1`.

## Local verification

```sh
./gradlew :evilo-library:publishToMavenLocal
```

That writes every variant under
`~/.m2/repository/io/github/crowforkotlin/evilo/` (the groupId is
`io.github.crowforkotlin.evilo`) with sources and javadoc JARs. Local publishing
never needs a GPG key: signing is applied only when the build receives signing
inputs.

A plugin repository can then resolve the coordinate without any `-Pevilo.sdkPath`
override, because its `settings.gradle.kts` already maps `mavenLocal` for this
groupId.

## Publishing to Maven Central

Prerequisites, all one-time:

| Item | Where |
| --- | --- |
| Verified namespace `io.github.crowforkotlin` | Central Portal → Namespaces. `io.github.crowforkotlin.evilo` inherits from it. |
| Portal user token | Central Portal → Account → Generate User Token |
| GPG key pair, public half uploaded to a keyserver | `keyserver.ubuntu.com` or `keys.openpgp.org` |
| Repository secrets | `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `MAVEN_CENTRAL_SIGNING_KEY` (ASCII-armored private key), `MAVEN_CENTRAL_SIGNING_KEY_PASSWORD` |

Publish:

```sh
git switch main
# bump evilo-plugin-sdk in gradle/libs.versions.toml, then commit
git tag library-v0.0.1
git push origin library-v0.0.1
```

`.github/workflows/publish-library.yml` runs on `library-v*` tags:

1. `verify` resolves the catalog version, fails when the tag disagrees, fails when
   that version already exists on Maven Central, runs `:evilo-library:jvmTest`,
   stages the publication into `~/.m2`, rejects the release when it exceeds the
   footprint gate, and asserts that each POM carries license/SCM metadata and
   that sources and javadoc JARs exist.
2. `publish` uploads with `:evilo-library:publishToMavenCentral`. The plugin is
   configured with `automaticRelease = true`, so a validated deployment is
   released without a manual portal step.

Run it without publishing to rehearse:

```sh
gh workflow run publish-library.yml --repo crowforkotlin/evilo --ref main -f dry_run=true
```

## After publishing

Central processing is asynchronous. Check the coordinates before depending on
them:

```sh
curl -sI https://repo1.maven.org/maven2/io/github/crowforkotlin/evilo/evilo-library/0.0.1/evilo-library-0.0.1.pom | head -1
```

Then, in every plugin repository:

1. Set `evilo-plugin-sdk` in `gradle/libs.versions.toml` to the published version.
2. Drop `-Pevilo.sdkPath=../evilo` from local commands.
3. Delete the `EVILO_SDK_REPOSITORY` and `EVILO_SDK_REVISION` repository variables.
   They exist only to check this repository out while the artifact is missing
   from Central; with the artifact published they are dead weight and a second,
   silent version pin that drifts from the catalog.

The plugin release workflow in this template no longer references those
variables, so nothing else needs changing.

## Failure modes

| Symptom | Cause |
| --- | --- |
| `Tag library-vX.Y.Z does not match library-vA.B.C` | Tag and catalog version disagree. |
| `evilo-library X.Y.Z is already on Maven Central` | Version reuse; bump instead. |
| `Configure MAVEN_CENTRAL_*` | Missing repository secret. |
| `Missing sources for …` / `Missing javadoc for …` | Publication configuration was changed away from `JavadocJar.Empty`/`SourcesJar.Sources`. |
| `Missing license metadata` / `Missing scm metadata` | `pom { }` block edited or removed; Central rejects incomplete POMs. |
| Deployment stays `VALIDATING` then fails | Unsigned or incorrectly signed artifacts; confirm the GPG public key is on a keyserver. |
| `Publication exceeds 8 MiB` / `exceeds 60 files` | A target or artifact was added. Check the quota below before raising the gate. |

## Central's free-tier quota

Central meters the **whole organisation**, monthly: deployments, file count, and
bytes. `io.github.crowforkotlin` currently shows one `wasmline` deployment at
577.77 MB and 1320 files against an 80 MB / 1000 file allowance, with a grace
period ending 2026-12-06. `evilo-library` is not what exhausted it: at ~4.5 MiB
and ~172 files it is roughly 0.8% of the waste, and `wasmline` publishes ten
native targets per release.

Practical consequences:

- `evilo-library` publishing is fine on its own; the `verify` job fails early if
  this module ever grows past the gate instead of discovering the limit at upload.
- A `wasmline` release and an `evilo-library` release in the same calendar month
  compete for the same allowance. Publish at most one of them per month, or
  reduce `wasmline`'s target list.
- Moving `evilo-library` to another namespace does not help; the limit is per
  organisation, not per namespace.
