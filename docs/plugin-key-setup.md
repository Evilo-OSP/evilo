# Plugin signing keys

How evilo's app build gets the Ed25519 public key it verifies plugin manifests
with, and how to rotate it during development.

The app embeds **only a public key**. Private seeds never belong in this
repository, its build environment, or its CI secrets.

## What the app needs

| Input | Required | Notes |
| --- | --- | --- |
| `EVILO_PUBLIC_KEY_HEX` environment variable | one of the two | Raw 32-byte public key, 64 lowercase hex characters. Takes precedence. |
| `<keydir>/public.key` | one of the two | Same value on one line. `<keydir>` defaults to `~/.evilo/signing`, override with `-Pevilo.signingKeyDirectory=/abs/dir`. |

`:evilo-app:generateEviloAppConfiguration` runs on every compile and fails with
`Supply EVILO_PUBLIC_KEY_HEX or an existing external public.key` when neither is
present. Only the normalized public key and the key ID `evilo-root-v1` are
embedded into `GeneratedEviloConfiguration`; the private key is never read during
compilation.

For a local build:

```sh
EVILO_PUBLIC_KEY_HEX=<64 hex chars> ./gradlew :evilo-android:assembleDebug
```

For the manual CI build (`.github/workflows/android-development.yml`), store the
same value as a repository Secret, because that workflow reads
`secrets.EVILO_PUBLIC_KEY_HEX`. A Secret keeps it out of a source literal, not out
of the APK — the public key is extractable from any built APK, and the published
plugin catalog carries it in clear text anyway:

```sh
gh secret set EVILO_PUBLIC_KEY_HEX --repo crowforkotlin/evilo --body "$(cat ~/.evilo/signing/public.key)"
gh workflow run android-development.yml --repo crowforkotlin/evilo --ref main
```

## Creating a pair

`generatePluginSigningKeys` creates or validates an external pair. It is never a
compilation dependency and never overwrites existing files.

```sh
./gradlew :evilo-app:generatePluginSigningKeys \
  -Pevilo.signingKeyDirectory="$HOME/.evilo/signing"
```

It writes `private.key`, `public.key`, and `authority.properties` with the key ID
`evilo-root-v1`, then verifies the pair. Running it again against an existing
directory re-verifies instead of regenerating. The directory must stay outside
the repository, and the private file is created `0600`.

Without Gradle:

```sh
mkdir -p ~/.evilo/signing && chmod 700 ~/.evilo/signing
openssl genpkey -algorithm ED25519 -out ~/.evilo/signing/private.pem
openssl pkey -in ~/.evilo/signing/private.pem -outform DER | tail -c 32 \
  | od -An -tx1 -v | tr -d ' \n' > ~/.evilo/signing/private.key
openssl pkey -in ~/.evilo/signing/private.pem -pubout -outform DER | tail -c 32 \
  | od -An -tx1 -v | tr -d ' \n' > ~/.evilo/signing/public.key
printf 'keyId=evilo-root-v1\npublicKeyHex=%s\n' "$(cat ~/.evilo/signing/public.key)" \
  > ~/.evilo/signing/authority.properties
rm ~/.evilo/signing/private.pem
```

## Rotating during development

Plugin verification matches the catalog-declared key against the key compiled
into the app. Rotate both sides together:

1. Create the new pair in a fresh directory; keep the old one until no build
   trusts it.
2. Rebuild the APK with the new `EVILO_PUBLIC_KEY_HEX` and reinstall.
3. Give the public key to the plugin repository that signs releases
   (`docs/publishing.md` there) and replace its private-key Secret.
4. Publish the plugin under a new version and a larger `versionCode`.

Nothing migrates because development installs are disposable. Releases signed by
the old key stop verifying in the new app; releases signed by the new key fail in
the old app. The app also pins the approved manifest digest per version, so a
re-signed manifest must arrive as a new version — never as new bytes for an
existing one.

Installed plugins, their approval records, and publisher-scoped storage are
keyed by publisher identity, so an app that changes its trusted key reports
`A different publisher already owns this installed plugin ID` until the old copy
is uninstalled.
