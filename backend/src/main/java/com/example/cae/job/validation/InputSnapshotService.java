package com.example.cae.job.validation;

import com.example.cae.common.FailureCode;
import com.example.cae.common.JsonCodec;
import com.example.cae.common.PlatformFailure;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Creates small synthetic input snapshots without ever buffering a solver result in the JVM. */
@Service
public class InputSnapshotService {
  private static final Pattern INCLUDE =
      Pattern.compile("(?im)^\\s*INCLUDE\\s+['\"]([^'\"]+)['\"]\\s*(?:\\$[^\\r\\n]*)?$");
  private final Path caseRoot;
  private final Path dataRoot;
  private final JsonCodec json;

  @Autowired
  public InputSnapshotService(
      @Value("${CASE_ROOT:../demo-data}") String caseRoot,
      @Value("${DATA_ROOT:../.data}") String dataRoot,
      JsonCodec json) {
    this(Path.of(caseRoot), Path.of(dataRoot), json);
  }

  public InputSnapshotService(Path caseRoot, Path dataRoot, JsonCodec json) {
    this.caseRoot = caseRoot.toAbsolutePath().normalize();
    this.dataRoot = dataRoot.toAbsolutePath().normalize();
    this.json = json;
  }

  public record Snapshot(
      String workspace, String checksum, String manifestUri, Map<String, String> files) {}

  public Snapshot create(Map<String, Object> job) {
    try {
      Path source = source(job);
      ManifestDocument document = readManifest(source.resolve("manifest.json"));
      Map<String, Object> manifest = document.value();
      requireEquals("analysisType", job.get("analysisType"), manifest.get("analysisType"));
      requireEquals("conditionCode", job.get("conditionCode"), manifest.get("conditionCode"));
      if (job.containsKey("modelRevision"))
        requireEquals("modelRevision", job.get("modelRevision"), manifest.get("modelRevision"));
      String entry = relativeString(manifest.get("entryDeck"), "entryDeck");
      checkRequestedInput(job, source, entry);
      Path entryPath = contained(source, entry, FailureCode.INPUT_FILE_MISSING);
      if (!Files.isRegularFile(entryPath, LinkOption.NOFOLLOW_LINKS))
        throw failure(
            FailureCode.INPUT_FILE_MISSING, "Entry deck is missing", Map.of("file", entry));
      Set<String> found = new LinkedHashSet<>();
      includeFiles(source, entryPath, found, new LinkedHashSet<>());
      Object includesValue = manifest.get("includes");
      if (!(includesValue instanceof List<?> declared))
        throw invalid("Manifest includes must be an array", Map.of());
      Set<String> expected = new LinkedHashSet<>();
      for (Object include : declared) {
        String name = relativeString(include, "include");
        if (!expected.add(name))
          throw invalid("Manifest contains a duplicate include", Map.of("file", name));
        contained(source, name, FailureCode.INPUT_INCLUDE_MISSING);
      }
      if (!expected.equals(found))
        throw invalid(
            "Manifest includes do not match the deck dependency graph",
            Map.of("declared", expected, "referenced", found));
      TreeMap<String, String> checksums = new TreeMap<>();
      List<String> files = new ArrayList<>(found);
      files.add(entry);
      files.add("manifest.json");
      if (Files.exists(source.resolve("metadata.json"), LinkOption.NOFOLLOW_LINKS))
        files.add("metadata.json");
      for (String name : files)
        checksums.put(name, sha(contained(source, name, FailureCode.INPUT_FILE_MISSING)));
      if (!checksums.get("manifest.json").equals(document.checksum()))
        throw invalid("Manifest changed during validation", Map.of("file", "manifest.json"));
      verifyDeclaredChecksums(manifest, checksums);
      String checksum = aggregate(checksums);
      String jobNo = job.get("jobNo").toString();
      if (!jobNo.matches("CAE-\\d{8}-\\d{6,}"))
        throw invalid("Job identifier is not safe for a workspace", Map.of());
      Path jobsRoot = dataRoot.resolve("jobs");
      ensureNoSymlinks(dataRoot, jobsRoot);
      Files.createDirectories(jobsRoot);
      ensureNoSymlinks(dataRoot, jobsRoot);
      Path workspace = jobsRoot.resolve(jobNo);
      if (Files.exists(workspace, LinkOption.NOFOLLOW_LINKS)) {
        Snapshot previous = verify(workspace, checksum);
        if (!previous.files().equals(checksums))
          throw invalid("Existing snapshot belongs to different input files", Map.of());
        return previous;
      }
      Path temporary = Files.createTempDirectory(jobsRoot, ".snapshot-");
      try {
        Path inputs = temporary.resolve("input");
        Files.createDirectory(inputs);
        for (String name : checksums.keySet()) {
          Path target = inputs.resolve(name).normalize();
          Files.createDirectories(target.getParent());
          Files.copy(
              contained(source, name, FailureCode.INPUT_FILE_MISSING),
              target,
              StandardCopyOption.COPY_ATTRIBUTES);
          if (!sha(target).equals(checksums.get(name)))
            throw invalid("Case changed while creating the snapshot", Map.of("file", name));
          readOnly(target);
        }
        validateCopiedContents(inputs, job);
        Path checksumFile = temporary.resolve("input-checksums.json");
        Files.writeString(
            checksumFile,
            json.write(Map.of("files", checksums, "checksum", checksum)),
            StandardCharsets.UTF_8);
        readOnly(checksumFile);
        try {
          Files.move(temporary, workspace, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
          Files.move(temporary, workspace);
        }
      } finally {
        if (Files.exists(temporary)) {
          try (var paths = Files.walk(temporary)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
              Files.deleteIfExists(path);
          }
        }
      }
      return verify(workspace, checksum);
    } catch (PlatformFailure failure) {
      throw failure;
    } catch (IOException error) {
      throw invalid(
          "Input storage could not be accessed",
          Map.of("reason", error.getClass().getSimpleName()));
    }
  }

  public Snapshot verifySnapshot(Map<String, Object> job) {
    Object workspace = job.get("workspace"), checksum = job.get("inputChecksum");
    if (!(workspace instanceof String path) || !(checksum instanceof String expected))
      throw invalid("Validated snapshot is missing", Map.of());
    try {
      return verify(Path.of(path), expected);
    } catch (IOException error) {
      throw invalid(
          "Input snapshot could not be verified",
          Map.of("reason", error.getClass().getSimpleName()));
    }
  }

  private Snapshot verify(Path workspace, String expected) throws IOException {
    workspace = workspace.toAbsolutePath().normalize();
    if (!workspace.startsWith(dataRoot.resolve("jobs")))
      throw invalid("Snapshot is outside the configured data root", Map.of());
    ensureNoSymlinks(dataRoot, workspace);
    Path checksumFile = contained(workspace, "input-checksums.json", FailureCode.INPUT_INVALID);
    Map<String, Object> record = manifest(checksumFile);
    if (!(record.get("files") instanceof Map<?, ?>))
      throw invalid("Snapshot checksum file list must be an object", Map.of());
    Map<String, Object> encoded = json.readMap(record.get("files"));
    TreeMap<String, String> checksums = new TreeMap<>();
    if (encoded.isEmpty()) throw invalid("Snapshot checksum record is empty", Map.of());
    for (var entry : encoded.entrySet()) {
      String relative = relativeString(entry.getKey(), "snapshot file");
      Path path = contained(workspace.resolve("input"), relative, FailureCode.INPUT_INVALID);
      String checksum = sha(path);
      if (!checksum.equals(entry.getValue()))
        throw invalid("Immutable snapshot checksum does not match", Map.of("file", relative));
      checksums.put(relative, checksum);
    }
    String actual = aggregate(checksums);
    if (!actual.equals(record.get("checksum")) || !actual.equals(expected))
      throw invalid("Immutable snapshot aggregate checksum does not match", Map.of());
    final Path inputs = workspace.resolve("input");
    try (var paths = Files.walk(inputs)) {
      Set<String> actualFiles =
          paths
              .filter(path -> !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
              .map(path -> inputs.relativize(path).toString().replace('\\', '/'))
              .collect(Collectors.toSet());
      if (!actualFiles.equals(checksums.keySet()))
        throw invalid("Snapshot contains unregistered files", Map.of());
    }
    return new Snapshot(
        workspace.toString(),
        actual,
        workspace.resolve("input/manifest.json").toString(),
        Map.copyOf(checksums));
  }

  private Path source(Map<String, Object> job) throws IOException {
    String casePath = relativeString(job.get("casePath"), "casePath");
    Path source = caseRoot.resolve(casePath).normalize();
    if (!source.startsWith(caseRoot)) throw invalid("Case is outside the case root", Map.of());
    ensureNoSymlinks(caseRoot, source);
    if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS))
      throw failure(
          FailureCode.INPUT_FILE_MISSING,
          "Case package directory is missing",
          Map.of("casePath", casePath));
    return source;
  }

  private Map<String, Object> manifest(Path path) throws IOException {
    return readManifest(path).value();
  }

  private record ManifestDocument(Map<String, Object> value, String checksum) {}

  private ManifestDocument readManifest(Path path) throws IOException {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
      throw failure(
          FailureCode.INPUT_FILE_MISSING,
          "Manifest or checksum record is missing",
          Map.of("file", path.getFileName().toString()));
    if (Files.isSymbolicLink(path))
      throw invalid(
          "Symbolic links are not accepted as input",
          Map.of("file", path.getFileName().toString()));
    try {
      String text = Files.readString(path, StandardCharsets.UTF_8);
      MessageDigest checksum = digest();
      checksum.update(text.getBytes(StandardCharsets.UTF_8));
      Map<String, Object> result = json.readMap(text);
      if (result == null)
        throw invalid(
            "Manifest JSON must be an object", Map.of("file", path.getFileName().toString()));
      return new ManifestDocument(result, HexFormat.of().formatHex(checksum.digest()));
    } catch (IllegalArgumentException error) {
      throw invalid("Manifest JSON is invalid", Map.of("file", path.getFileName().toString()));
    }
  }

  private void validateCopiedContents(Path input, Map<String, Object> job) throws IOException {
    Map<String, Object> copied = manifest(input.resolve("manifest.json"));
    requireEquals("analysisType", job.get("analysisType"), copied.get("analysisType"));
    requireEquals("conditionCode", job.get("conditionCode"), copied.get("conditionCode"));
    if (job.containsKey("modelRevision"))
      requireEquals("modelRevision", job.get("modelRevision"), copied.get("modelRevision"));
    String entry = relativeString(copied.get("entryDeck"), "entryDeck");
    Set<String> found = new LinkedHashSet<>();
    includeFiles(
        input,
        contained(input, entry, FailureCode.INPUT_FILE_MISSING),
        found,
        new LinkedHashSet<>());
    if (!(copied.get("includes") instanceof List<?> declared))
      throw invalid("Snapshot manifest includes must be an array", Map.of());
    Set<String> expected = new LinkedHashSet<>();
    for (Object include : declared) expected.add(relativeString(include, "include"));
    if (!expected.equals(found))
      throw invalid("Snapshot INCLUDE dependencies changed during validation", Map.of());
  }

  private void checkRequestedInput(Map<String, Object> job, Path source, String entry) {
    Map<String, Object> input = json.readMap(job.get("input"));
    for (String key : List.of("entryDeck", "manifest")) {
      if (!input.containsKey(key)) continue;
      String requested = relativeString(input.get(key), key);
      if (requested.startsWith("demo-data/"))
        requested = requested.substring("demo-data/".length());
      String wanted = key.equals("entryDeck") ? entry : "manifest.json";
      String packageRelative = caseRoot.relativize(source).toString().replace('\\', '/');
      if (!requested.equals(wanted) && !requested.equals(packageRelative + "/" + wanted))
        throw invalid("Requested input does not match the chosen package", Map.of("field", key));
    }
  }

  private void includeFiles(Path root, Path deck, Set<String> found, Set<Path> visiting)
      throws IOException {
    if (!visiting.add(deck))
      throw invalid(
          "INCLUDE cycle is not allowed", Map.of("file", root.relativize(deck).toString()));
    if (visiting.size() > 64 || found.size() > 4096)
      throw invalid("INCLUDE dependency graph exceeds the supported limit", Map.of());
    try (var reader = Files.newBufferedReader(deck, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (!line.stripLeading().matches("(?i)INCLUDE\\b.*")) continue;
        var matcher = INCLUDE.matcher(line);
        if (!matcher.matches()) {
          throw invalid(
              "INCLUDE must use the supported quoted relative path syntax",
              Map.of("file", root.relativize(deck).toString()));
        }
        String include = relativeString(matcher.group(1), "INCLUDE");
        Path included = deck.getParent().resolve(include).normalize();
        String relative = root.relativize(included).toString().replace('\\', '/');
        Path safe = contained(root, relative, FailureCode.INPUT_INCLUDE_MISSING);
        if (visiting.contains(safe))
          throw invalid("INCLUDE cycle is not allowed", Map.of("file", relative));
        if (found.add(relative)) includeFiles(root, safe, found, visiting);
      }
    }
    visiting.remove(deck);
  }

  private Path contained(Path root, String relative, FailureCode missingCode) throws IOException {
    relative = relativeString(relative, "file");
    Path result = root.resolve(relative).normalize();
    if (!result.startsWith(root))
      throw invalid("Input path escapes its root", Map.of("file", relative));
    ensureNoSymlinks(root, result);
    if (!Files.isRegularFile(result, LinkOption.NOFOLLOW_LINKS))
      throw failure(missingCode, "Required input file is missing", Map.of("file", relative));
    return result;
  }

  private void ensureNoSymlinks(Path root, Path candidate) throws IOException {
    Path current = root;
    if (Files.isSymbolicLink(current))
      throw invalid("Symbolic links are not accepted as input or workspace roots", Map.of());
    for (Path part : root.relativize(candidate)) {
      current = current.resolve(part);
      if (Files.isSymbolicLink(current))
        throw invalid(
            "Symbolic links are not accepted as input",
            Map.of("file", root.relativize(current).toString()));
    }
    if (Files.exists(root)
        && Files.exists(candidate)
        && !candidate.toRealPath().startsWith(root.toRealPath()))
      throw invalid("Input path escapes its real root", Map.of());
  }

  private String relativeString(Object value, String field) {
    if (!(value instanceof String path)
        || path.isBlank()
        || path.startsWith("/")
        || path.startsWith("\\")
        || path.contains("\\")
        || path.contains(":")
        || path.indexOf('\u0000') >= 0
        || List.of(path.split("/", -1)).contains("..")
        || List.of(path.split("/", -1)).contains("."))
      throw invalid(
          "Input paths must be nonempty relative paths without traversal", Map.of("field", field));
    return path;
  }

  private void requireEquals(String field, Object actual, Object expected) {
    if (actual == null || !actual.equals(expected))
      throw invalid("Manifest " + field + " does not match the Job", Map.of("field", field));
  }

  private void verifyDeclaredChecksums(Map<String, Object> manifest, Map<String, String> actual) {
    Object encoded = manifest.get("checksums");
    if (encoded == null) return;
    if (!(encoded instanceof Map<?, ?> expected))
      throw invalid("Manifest checksums must be an object", Map.of());
    expected.forEach(
        (key, value) -> {
          String file = relativeString(key, "checksum file");
          if (!actual.containsKey(file) || !actual.get(file).equals(value))
            throw invalid("Declared input checksum does not match", Map.of("file", file));
        });
  }

  private String sha(Path path) throws IOException {
    MessageDigest digest = digest();
    try (var input = Files.newInputStream(path)) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private String aggregate(Map<String, String> files) {
    MessageDigest digest = digest();
    new TreeMap<>(files)
        .forEach(
            (name, checksum) ->
                digest.update((name + ":" + checksum + "\n").getBytes(StandardCharsets.UTF_8)));
    return HexFormat.of().formatHex(digest.digest());
  }

  private MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by Java", e);
    }
  }

  private void readOnly(Path path) throws IOException {
    if (Files.getFileStore(path).supportsFileAttributeView("posix"))
      Files.setPosixFilePermissions(
          path,
          Set.of(
              PosixFilePermission.OWNER_READ,
              PosixFilePermission.GROUP_READ,
              PosixFilePermission.OTHERS_READ));
    else if (!path.toFile().setWritable(false, false))
      throw new IOException("Could not protect immutable input file");
  }

  private PlatformFailure invalid(String message, Map<String, Object> details) {
    return failure(FailureCode.INPUT_INVALID, message, details);
  }

  private PlatformFailure failure(FailureCode code, String message, Map<String, Object> details) {
    return new PlatformFailure(code, "VALIDATION", message, details);
  }
}
