# Google Play upload signing

Google Play uses two deliberately separate signing identities for this app:

- The **installed-app signing key** signs APKs delivered to users. Its public
  lineage is pinned in `release/production-signing-lineage.json` and must be
  preserved when Play App Signing is enrolled.
- The **Play upload key** signs the AAB sent to Play Console. Google verifies
  this signature, removes it, and signs delivered APKs with the installed-app
  signing key.

The scripts below reject an upload certificate that matches the installed-app
certificate or either known development certificate. They never need access to
the installed-app private key.

## 1. Create the independent upload key once

Create a dedicated directory outside the repository. Disable inherited NTFS
permissions and allow only the signing account, `SYSTEM`, and local
Administrators. Then run, using the same JDK 21 installation used by the release
toolchain:

```powershell
.\scripts\new-play-upload-keystore.ps1 `
    -KeystorePath C:\protected\nanokvm-play-upload.p12 `
    -JavaPath "C:\Program Files\Android\Android Studio\jbr\bin\java.exe"
```

The script prompts in the console for a strong password. It has no password
parameter, rejects a repository-local keystore, refuses to overwrite either
output, and exports the public upload certificate as PEM. Back up the private
keystore and its password in separately controlled encrypted locations. Record
the printed SHA-256 fingerprint independently before registering the public
certificate in Play Console.

## 2. Produce reviewed evidence for the exact Play bundle

From a clean checkout at the exact annotated release tag, run the strict
evidence workflow with the Play extension:

```powershell
.\scripts\write-unsigned-release-evidence.ps1 `
    -SourceTag v<VERSION> `
    -JavaPath "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" `
    -BuildToolsPath "C:\Android\Sdk\build-tools\<VERSION>" `
    -IncludePlayBundle
```

The normal `releaseBundle` record and direct-release checks are unchanged.
`-IncludePlayBundle` additionally runs and retains the Play unit tests, lint,
unsigned APK, AAB, baseline-profile verification, and the Play variant's own
R8 mapping, seeds, usage, configuration, and resource-shrinker output. The
resulting evidence has distinct `playBundle`, `playUnsignedApk`, `playVariant`,
and `playR8` records; it does not reuse the direct-release R8 evidence. Review
the evidence and retained artifacts, then preserve the printed evidence
SHA-256 through an independent channel.

The optional `-PlayBundle` and `-PlayUnsignedApk` parameters may only spell the
canonical outputs of `:app:bundlePlay` and `:app:assemblePlay`; they cannot
redirect evidence to unrelated AAB/APK files.

Lint remains blocking except for five exact dependency-update advisories at
`gradle/libs.versions.toml`. The evidence retains each advisory's exact ID,
message, path, line, and column, and the signer recomputes that assessment from
the retained XML report. Absolute build-checkout prefixes in lint XML are
canonicalized to that repository-relative path, so reviewed evidence remains
valid when moved to an offline signing checkout. The reviewed advisories are:

- `com.android.application` 9.3.0 to 9.3.1;
- `com.android.library` 9.3.0 to 9.3.1;
- `com.android.test` 9.3.0 to 9.3.1;
- `androidx.baselineprofile` 1.5.0-alpha07 to 1.5.0-beta01; and
- `androidx.baselineprofile.producer` 1.5.0-alpha07 to 1.5.0-beta01.

Any changed message, different location, different severity, duplicated
advisory, or additional lint issue blocks evidence generation and upload
signing.

## 3. Close the 16 KB page-size gate

Before signing, run the retained evidence manifest's exact `playBundle` through
the reviewed-evidence command in `PLAY_PAGE_SIZE.md`. Retain the immutable JSON
result and its SHA-256 with the release record. The result must say
`pageSizeReleaseGateClosed: true`; a dirty-worktree preflight, generic release
bundle, or separately generated APK does not close this gate.

Do not continue to upload signing if the exact evidence-bound AAB fails its
bundle configuration, ELF `PT_LOAD`, generated universal-APK, or `zipalign -P
16` check.

## 4. Sign only the reviewed Play bundle

```powershell
.\scripts\sign-play-upload-aab.ps1 `
    -SourceTag v<VERSION> `
    -UnsignedEvidencePath dist\<UNSIGNED-EVIDENCE>.json `
    -ExpectedEvidenceSha256 <REVIEWED-EVIDENCE-SHA256> `
    -JavaPath "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" `
    -KeystorePath C:\protected\nanokvm-play-upload.p12 `
    -KeyAlias nanokvm-play-upload `
    -ExpectedUploadCertificateSha256 <RECORDED-UPLOAD-CERTIFICATE-SHA256>
```

The signer requires the evidence manifest's `playBundle` record. It copies
those exact reviewed bytes to a staging directory, prompts interactively for
the keystore/key password, and signs with the JDK 21 `jarsigner`. It then:

- verifies the complete JAR signature and rejects unsigned payload entries;
- proves every non-signature AAB entry still matches the reviewed bundle;
- rejects Play evidence targeting an API below 36;
- resolves and hash-verifies all five nonempty Play-specific R8 records;
- extracts and independently verifies the public upload certificate;
- confirms the actual certificate equals the expected upload fingerprint and
  differs from the installed-app and development identities; and
- records source tag/commit, package, version, evidence hash, unsigned and
  signed AAB hashes, certificate identity, verification hash, and tool hashes.

The output set is:

- the signed `*-play-upload.aab`;
- `*-SHA256SUMS.txt`;
- `*-upload-certificate-sha256.txt`;
- `*-upload-certificate.pem`;
- `*-jarsigner-verify.txt`; and
- `*-play-upload-metadata.json`.

No output is overwritten. A failed operation removes its staging files and any
partially published output set. The release bundle, evidence file, and keystore
are never modified.

The signer accepts only `playBundle`, the policy-specific build with Play-only
feature gates. The generic direct-release bundle cannot enter this upload lane.
