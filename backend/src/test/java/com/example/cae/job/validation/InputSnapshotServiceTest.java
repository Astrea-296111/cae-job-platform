package com.example.cae.job.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.cae.common.FailureCode;
import com.example.cae.common.JsonCodec;
import com.example.cae.common.PlatformFailure;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class InputSnapshotServiceTest {
  @TempDir Path temporary;
  private final JsonCodec json = new JsonCodec(new ObjectMapper());

  @Test
  void snapshotsRecursiveDependenciesAndProtectsCopiedFiles() throws Exception {
    Path source = packageDirectory();
    write(source, "model.fem", "INCLUDE 'parts/assembly.inc'\n");
    write(source, "parts/assembly.inc", "INCLUDE \"materials.inc\" $ synthetic material\n");
    write(source, "parts/materials.inc", "MAT1,1,210000\n");
    writeManifest(source, manifest(List.of("parts/assembly.inc", "parts/materials.inc")));
    Map<String, Object> job = job();
    job.put(
        "input",
        Map.of(
            "entryDeck",
            "demo-data/structure/case-good/model.fem",
            "manifest",
            "structure/case-good/manifest.json"));

    InputSnapshotService.Snapshot snapshot = service().create(job);

    assertThat(snapshot.checksum()).matches("[0-9a-f]{64}");
    assertThat(snapshot.files())
        .containsOnlyKeys(
            "model.fem", "manifest.json", "parts/assembly.inc", "parts/materials.inc");
    Path copied = Path.of(snapshot.workspace()).resolve("input/parts/materials.inc");
    assertThat(Files.readString(copied))
        .isEqualTo(Files.readString(source.resolve("parts/materials.inc")));
    assertThat(Files.getPosixFilePermissions(copied))
        .doesNotContain(
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.OTHERS_WRITE);
    assertThat(service().verifySnapshot(snapshotJob(snapshot)).checksum())
        .isEqualTo(snapshot.checksum());
  }

  @Test
  void originalCaseEditsCannotChangePreviouslyValidatedSnapshot() throws Exception {
    Path source = goodPackage();
    InputSnapshotService.Snapshot snapshot = service().create(job());
    String frozen = Files.readString(Path.of(snapshot.workspace()).resolve("input/model.fem"));

    Files.writeString(source.resolve("model.fem"), "Changed after READY\n");
    Files.writeString(source.resolve("materials.inc"), "Different material\n");

    assertThat(service().verifySnapshot(snapshotJob(snapshot)).checksum())
        .isEqualTo(snapshot.checksum());
    assertThat(Files.readString(Path.of(snapshot.workspace()).resolve("input/model.fem")))
        .isEqualTo(frozen);
  }

  @Test
  void manifestChangedBetweenParsingAndChecksummingIsRejected() throws Exception {
    Path source = goodPackage();
    JsonCodec concurrentlyModifiedManifest =
        new JsonCodec(new ObjectMapper()) {
          private boolean modified;

          @Override
          public Map<String, Object> readMap(Object value) {
            Map<String, Object> parsed = super.readMap(value);
            if (!modified) {
              modified = true;
              Map<String, Object> replacement = new LinkedHashMap<>(parsed);
              replacement.put("conditionCode", "A_DIFFERENT_CONDITION");
              try {
                Files.writeString(source.resolve("manifest.json"), write(replacement));
              } catch (IOException error) {
                throw new IllegalStateException(error);
              }
            }
            return parsed;
          }
        };

    invalid(
        () ->
            new InputSnapshotService(caseRoot(), dataRoot(), concurrentlyModifiedManifest)
                .create(job()));
    assertThat(Files.exists(dataRoot().resolve("jobs/CAE-20261010-000007"))).isFalse();
  }

  @Test
  void dependencyGraphChangedAfterScanningCannotBeCopiedAsValidatedInput() throws Exception {
    Path source = goodPackage();
    write(source, "rogue.inc", "MATERIAL introduced concurrently\n");
    JsonCodec concurrentlyModifiedDeck =
        new JsonCodec(new ObjectMapper()) {
          private boolean modified;

          @Override
          public Map<String, Object> readMap(Object value) {
            Map<String, Object> parsed = super.readMap(value);
            if (modified) return parsed;
            modified = true;
            return new LinkedHashMap<>(parsed) {
              @Override
              public Object get(Object key) {
                if ("includes".equals(key)) {
                  try {
                    Files.writeString(
                        source.resolve("model.fem"),
                        "INCLUDE 'materials.inc'\nINCLUDE 'rogue.inc'\n");
                  } catch (IOException error) {
                    throw new IllegalStateException(error);
                  }
                }
                return super.get(key);
              }
            };
          }
        };

    assertThrows(
        PlatformFailure.class,
        () ->
            new InputSnapshotService(caseRoot(), dataRoot(), concurrentlyModifiedDeck)
                .create(job()));
    assertThat(Files.exists(dataRoot().resolve("jobs/CAE-20261010-000007"))).isFalse();
  }

  @Test
  void repeatedCreationOfUnchangedInputReturnsSameSnapshot() throws Exception {
    goodPackage();
    InputSnapshotService.Snapshot first = service().create(job());
    InputSnapshotService.Snapshot duplicate = service().create(job());
    assertThat(duplicate).isEqualTo(first);
  }

  @Test
  void editedSnapshotIsRejectedBeforeExecution() throws Exception {
    goodPackage();
    InputSnapshotService.Snapshot snapshot = service().create(job());
    Path deck = Path.of(snapshot.workspace()).resolve("input/model.fem");
    overwriteProtected(deck, "INCLUDE 'materials.inc'\n$ unauthorized edit\n");

    PlatformFailure failure = invalid(() -> service().verifySnapshot(snapshotJob(snapshot)));
    assertThat(failure.details()).containsEntry("file", "model.fem");
  }

  @Test
  void extraUnregisteredSnapshotFilesAreRejected() throws Exception {
    goodPackage();
    InputSnapshotService.Snapshot snapshot = service().create(job());
    write(Path.of(snapshot.workspace()).resolve("input"), "unregistered.inc", "unregistered\n");
    invalid(() -> service().verifySnapshot(snapshotJob(snapshot)));
  }

  @Test
  void missingIncludeHasSpecificNonRetryableFailure() throws Exception {
    Path source = packageDirectory();
    write(source, "model.fem", "INCLUDE 'missing_material.inc'\n");
    writeManifest(source, manifest(List.of("missing_material.inc")));
    PlatformFailure failure = assertThrows(PlatformFailure.class, () -> service().create(job()));
    assertThat(failure.code()).isEqualTo(FailureCode.INPUT_INCLUDE_MISSING);
    assertThat(failure.stage()).isEqualTo("VALIDATION");
    assertThat(failure.code().retryable()).isFalse();
    assertThat(failure.details()).containsEntry("file", "missing_material.inc");
  }

  @Test
  void missingEntryDeckDoesNotCreateWorkspace() throws Exception {
    Path source = packageDirectory();
    writeManifest(source, manifest(List.of()));
    PlatformFailure failure = assertThrows(PlatformFailure.class, () -> service().create(job()));
    assertThat(failure.code()).isEqualTo(FailureCode.INPUT_FILE_MISSING);
    assertThat(Files.exists(dataRoot().resolve("jobs"))).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "../outside",
        "/tmp/absolute",
        "C:/desktop/case",
        "structure/../case-good",
        "structure\\case-good"
      })
  void absoluteAndTraversalCasePathsAreRejected(String casePath) throws Exception {
    goodPackage();
    Map<String, Object> job = job();
    job.put("casePath", casePath);
    invalid(() -> service().create(job));
  }

  @ParameterizedTest
  @ValueSource(strings = {"../outside.inc", "/tmp/outside.inc", "C:\\desktop\\material.inc"})
  void includesCannotEscapePackageEvenWhenDeclaredInManifest(String include) throws Exception {
    Path source = packageDirectory();
    write(source, "model.fem", "INCLUDE '" + include + "'\n");
    writeManifest(source, manifest(List.of(include)));
    invalid(() -> service().create(job()));
  }

  @Test
  void symlinkedDeckCannotReadOutsideCasePackage() throws Exception {
    Path source = goodPackage();
    Path outside = temporary.resolve("outside.fem");
    Files.writeString(outside, "INCLUDE 'materials.inc'\n");
    Files.delete(source.resolve("model.fem"));
    Files.createSymbolicLink(source.resolve("model.fem"), outside);
    invalid(() -> service().create(job()));
  }

  @Test
  void symlinkedCaseDirectoryAndWorkspaceRootAreRejected() throws Exception {
    goodPackage();
    Files.createSymbolicLink(caseRoot().resolve("alias"), packageDirectory());
    Map<String, Object> alias = job();
    alias.put("casePath", "alias");
    invalid(() -> service().create(alias));

    Files.createDirectories(dataRoot());
    Path outside = temporary.resolve("outside-workspaces");
    Files.createDirectory(outside);
    Files.createSymbolicLink(dataRoot().resolve("jobs"), outside);
    invalid(() -> service().create(job()));
    try (var contents = Files.list(outside)) {
      assertThat(contents.findAny()).isEmpty();
    }
  }

  @ParameterizedTest
  @CsvSource({"analysisType, CFD", "conditionCode, FRONTAL_CRASH"})
  void manifestAnalysisAndConditionMustMatchJob(String field, String value) throws Exception {
    Path source = goodPackage();
    Map<String, Object> manifest = manifest(List.of("materials.inc"));
    manifest.put(field, value);
    writeManifest(source, manifest);
    invalid(() -> service().create(job()));
  }

  @Test
  void manifestMustDeclareExactlyTheReferencedDependencyGraph() throws Exception {
    Path source = goodPackage();
    writeManifest(source, manifest(List.of()));
    invalid(() -> service().create(job()));

    write(source, "unused.inc", "unused\n");
    writeManifest(source, manifest(List.of("materials.inc", "unused.inc")));
    invalid(() -> service().create(job()));
  }

  @Test
  void requestedDeckCannotDisagreeWithSelectedPackageManifest() throws Exception {
    goodPackage();
    Map<String, Object> job = job();
    job.put("input", Map.of("entryDeck", "structure/case-good/other.fem"));
    invalid(() -> service().create(job));
  }

  @Test
  void optionalDeclaredChecksumsAreVerifiedAgainstActualBytes() throws Exception {
    Path source = goodPackage();
    Map<String, Object> manifest = manifest(List.of("materials.inc"));
    String deckHash =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(source.resolve("model.fem"))));
    manifest.put("checksums", Map.of("model.fem", deckHash));
    writeManifest(source, manifest);
    assertThat(service().create(job()).files()).containsEntry("model.fem", deckHash);

    manifest.put("checksums", Map.of("model.fem", "0".repeat(64)));
    writeManifest(source, manifest);
    invalid(() -> service().create(job()));
  }

  @Test
  void cyclicIncludesAreRejectedWithoutRecursingForever() throws Exception {
    Path source = packageDirectory();
    write(source, "model.fem", "INCLUDE 'assembly.inc'\n");
    write(source, "assembly.inc", "INCLUDE 'model.fem'\n");
    writeManifest(source, manifest(List.of("assembly.inc", "model.fem")));
    invalid(() -> service().create(job()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "{not-json", "[]"})
  void malformedOrNonObjectManifestIsDomainValidationFailure(String contents) throws Exception {
    Path source = goodPackage();
    Files.writeString(source.resolve("manifest.json"), contents);
    invalid(() -> service().create(job()));
  }

  @Test
  void unsupportedIncludeSyntaxCannotSilentlyPassValidation() throws Exception {
    Path source = packageDirectory();
    write(source, "model.fem", "INCLUDE missing_material.inc\n");
    writeManifest(source, manifest(List.of()));
    invalid(() -> service().create(job()));
  }

  @Test
  void checksumRecordTamperingCannotReplaceDatabaseChecksum() throws Exception {
    goodPackage();
    InputSnapshotService.Snapshot snapshot = service().create(job());
    Path record = Path.of(snapshot.workspace()).resolve("input-checksums.json");
    overwriteProtected(
        record, json.write(Map.of("files", snapshot.files(), "checksum", "0".repeat(64))));
    invalid(() -> service().verifySnapshot(snapshotJob(snapshot)));
  }

  private Path caseRoot() {
    return temporary.resolve("cases");
  }

  private Path dataRoot() {
    return temporary.resolve("data");
  }

  private Path packageDirectory() throws IOException {
    Path directory = caseRoot().resolve("structure/case-good");
    Files.createDirectories(directory);
    return directory;
  }

  private Path goodPackage() throws IOException {
    Path source = packageDirectory();
    write(source, "model.fem", "INCLUDE 'materials.inc'\n");
    write(source, "materials.inc", "MAT1,1,210000\n");
    writeManifest(source, manifest(List.of("materials.inc")));
    return source;
  }

  private Map<String, Object> manifest(List<String> includes) {
    return new LinkedHashMap<>(
        Map.of(
            "caseId",
            "TEST-TORSION",
            "entryDeck",
            "model.fem",
            "includes",
            includes,
            "analysisType",
            "STRUCTURE",
            "conditionCode",
            "BIW_TORSION",
            "modelRevision",
            "V37",
            "parameters",
            Map.of("seed", 42)));
  }

  private Map<String, Object> job() {
    return new LinkedHashMap<>(
        Map.of(
            "jobNo",
            "CAE-20261010-000007",
            "casePath",
            "structure/case-good",
            "analysisType",
            "STRUCTURE",
            "conditionCode",
            "BIW_TORSION",
            "input",
            Map.of()));
  }

  private Map<String, Object> snapshotJob(InputSnapshotService.Snapshot snapshot) {
    return Map.of("workspace", snapshot.workspace(), "inputChecksum", snapshot.checksum());
  }

  private InputSnapshotService service() {
    return new InputSnapshotService(caseRoot(), dataRoot(), json);
  }

  private void writeManifest(Path source, Map<String, Object> manifest) throws IOException {
    write(source, "manifest.json", json.write(manifest));
  }

  private void write(Path root, String relative, String value) throws IOException {
    Path path = root.resolve(relative);
    Files.createDirectories(path.getParent());
    Files.writeString(path, value, StandardCharsets.UTF_8);
  }

  private void overwriteProtected(Path path, String contents) throws IOException {
    Files.setPosixFilePermissions(
        path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    Files.writeString(path, contents, StandardCharsets.UTF_8);
  }

  private PlatformFailure invalid(Runnable operation) {
    PlatformFailure failure = assertThrows(PlatformFailure.class, operation::run);
    assertThat(failure.code()).isEqualTo(FailureCode.INPUT_INVALID);
    return failure;
  }
}
