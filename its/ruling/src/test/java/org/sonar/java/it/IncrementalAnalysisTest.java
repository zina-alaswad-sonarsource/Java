package org.sonar.java.it;

import com.eclipsesource.json.Json;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonWriter;
import com.sonar.orchestrator.Orchestrator;
import com.sonar.orchestrator.build.MavenBuild;
import com.sonar.orchestrator.container.Server;
import com.sonar.orchestrator.locator.FileLocation;
import com.sonar.orchestrator.locator.MavenLocation;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import kotlin.Pair;
import org.apache.commons.lang.StringUtils;
import org.assertj.core.api.Fail;
import org.assertj.core.util.Lists;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonarqube.ws.Qualityprofiles;
import org.sonarqube.ws.client.HttpConnector;
import org.sonarqube.ws.client.WsClient;
import org.sonarqube.ws.client.WsClientFactories;
import org.sonarqube.ws.client.qualityprofiles.ActivateRuleRequest;
import org.sonarqube.ws.client.qualityprofiles.SearchRequest;
import org.sonarqube.ws.client.rules.CreateRequest;

import static org.assertj.core.api.Assertions.assertThat;

public class IncrementalAnalysisTest {
  private static final Logger LOG = LoggerFactory.getLogger(JavaRulingTest.class);

  private static final ImmutableSet<String> SUBSET_OF_ENABLED_RULES = ImmutableSet.copyOf(
    Splitter.on(',').trimResults().omitEmptyStrings().splitToList(
      System.getProperty("rules", "")));

  private static Path effectiveDumpOldFolder;

  @ClassRule
  public static Orchestrator orchestrator = Orchestrator.builderEnv()
    .setSonarVersion(System.getProperty("sonar.runtimeVersion", "LATEST_RELEASE[8.9]"))
    .addPlugin(FileLocation.byWildcardMavenFilename(new File("../../sonar-java-plugin/target"), "sonar-java-plugin-*.jar"))
    .addPlugin(MavenLocation.of("org.sonarsource.sonar-lits-plugin", "sonar-lits-plugin", "0.9.0.1682"))
    .build();

  @ClassRule
  public static TemporaryFolder TMP_DUMP_OLD_FOLDER = new TemporaryFolder();

  @BeforeClass
  public static void prepare_quality_profiles() throws Exception {
    ImmutableMap<String, ImmutableMap<String, String>> rulesParameters = ImmutableMap.<String, ImmutableMap<String, String>>builder()
      .put(
        "S1120",
        ImmutableMap.of("indentationLevel", "4"))
      .put(
        "S1451",
        ImmutableMap.of(
          "headerFormat",
          "\n/*\n" +
            " * Copyright (c) 1998, 2006, Oracle and/or its affiliates. All rights reserved.\n" +
            " * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms."))
      .put("S5961", ImmutableMap.of("MaximumAssertionNumber", "50"))
      .build();
    ImmutableSet<String> disabledRules = ImmutableSet.of(
      "S1874",
      "CycleBetweenPackages",
      // disable because it generates too many issues, performance reasons
      "S1106");
    Set<String> activatedRuleKeys = new HashSet<>();
    ProfileGenerator.generate(orchestrator, rulesParameters, disabledRules, SUBSET_OF_ENABLED_RULES, activatedRuleKeys);
    instantiateTemplateRule("S2253", "stringToCharArray", "className=\"java.lang.String\";methodName=\"toCharArray\"", activatedRuleKeys);
    instantiateTemplateRule("S4011", "longDate", "className=\"java.util.Date\";argumentTypes=\"long\"", activatedRuleKeys);
    instantiateTemplateRule("S124", "commentRegexTest", "regularExpression=\"(?i).*TODO\\(user\\).*\";message=\"bad user\"", activatedRuleKeys);
    instantiateTemplateRule("S3546", "InstancesOfNewControllerClosedWithDone",
      "factoryMethod=\"org.sonar.api.server.ws.WebService$Context#createController\";closingMethod=\"org.sonar.api.server.ws.WebService$NewController#done\"", activatedRuleKeys);
    instantiateTemplateRule("S3546", "JsonWriterNotClosed",
      "factoryMethod=\"org.sonar.api.server.ws.Response#newJsonWriter\";closingMethod=\"org.sonar.api.utils.text.JsonWriter#close\"", activatedRuleKeys);

    SUBSET_OF_ENABLED_RULES.stream()
      .filter(ruleKey -> !activatedRuleKeys.contains(ruleKey))
      .forEach(ruleKey -> Fail.fail("Specified rule does not exist: " + ruleKey));

    prepareDumpOldFolder();
  }

  private final String projectName = "guava";
  private final String projectKey = projectName;
  private final Path DATA_DIR = Path.of("./target/incremental_evaluation/").toAbsolutePath();
  private final Path projectWorkingDir = DATA_DIR.resolve(projectName);
  private final Path allIssuesDump = projectWorkingDir.resolve("all_files");
  private final int numberOfRandomFileSets = 10;
  private final int sizeOfRandomFileSetsInPercent = 10;

  @Test
  public void scan_entire_project() throws Exception {
    MavenBuild build = test_project(projectKey, projectName);

    build.setProperty("sonar.cpd.exclusions", "**/*")
      .setProperty("sonar.import_unknown_files", "true")
      .setProperty("sonar.skipPackageDesign", "true")
      .setProperty("dump.old", FileLocation.of(allIssuesDump.toString()).getFile().getAbsolutePath())
      .setProperty("dump.new", FileLocation.of(allIssuesDump.toString()).getFile().getAbsolutePath())
      .setProperty("lits.differences", FileLocation.of(DATA_DIR.resolve(projectName).resolve("diff").toString()).getFile().getAbsolutePath())
      .setProperty("sonar.internal.analysis.failFast", "true");

    orchestrator.executeBuild(build);
  }

  private static final Random random = new Random();

  @Test
  public void generateRandomFileSubsets() throws IOException {
    assertThat(allIssuesDump).isNotEmptyDirectory();
    var issues = Arrays.stream(allIssuesDump.toFile().listFiles())
      .filter(f -> f.isFile() && f.getName().startsWith("java-S"))
      .collect(Collectors.toMap(f -> f, f -> toJson(readFile(f))));

    var subsets = selectRandomFileSubsets(issues);
    writeSubsetFindingsToDisk(subsets, issues);
  }

  private Map<String, Set<String>> selectRandomFileSubsets(Map<File, JsonObject> issues) {
    var allFilesWithIssues = issues.values().stream()
      .flatMap(jsonObject -> jsonObject.keySet().stream())
      .collect(Collectors.toSet());

    var allFilesWithIssuesSorted = Lists.newArrayList(allFilesWithIssues);
    var numberOfFilesToPick = (allFilesWithIssues.size() * sizeOfRandomFileSetsInPercent) / 100;

    var res = new HashSet<Set<String>>();
    for (int i = 0; i < numberOfRandomFileSets; i++) {
      var randomFileIndices = new ArrayList<Integer>(numberOfFilesToPick);

      while (randomFileIndices.size() < numberOfFilesToPick) {
        var nextInt = random.nextInt(allFilesWithIssuesSorted.size());
        if (!randomFileIndices.contains(nextInt)) {
          randomFileIndices.add(nextInt);
        }
      }

      var randomFileSet = randomFileIndices.stream()
        .map(allFilesWithIssuesSorted::get)
        .collect(Collectors.toSet());

      res.add(randomFileSet);
    }

    return res.stream().collect(Collectors.toMap(s -> Integer.toHexString(s.hashCode()), s -> s));
  }

  private void writeSubsetFindingsToDisk(Map<String, Set<String>> subsets, Map<File, JsonObject> issues) throws IOException {
    for (Map.Entry<String, Set<String>> entry : subsets.entrySet()) {
      var hash = entry.getKey();
      var filesToScan = entry.getValue();
      var workingDir = projectWorkingDir.resolve(hash);
      Files.createDirectory(workingDir);

      for (Map.Entry<File, JsonObject> ruleIssues : issues.entrySet()) {
        var originalJson = ruleIssues.getValue();
        var jsonEntriesToWrite = new JsonObject();
        originalJson.keySet().stream()
          .filter(filesToScan::contains)
          .forEach(key -> jsonEntriesToWrite.add(key, originalJson.get(key)));

        if (!jsonEntriesToWrite.keySet().isEmpty()) {
          var originalFile = ruleIssues.getKey();
          var outputFile = workingDir.resolve(originalFile.getName());
          writeFile(outputFile.toFile(), toBadlyFormattedString(jsonEntriesToWrite));
        }

        // Record the file list in file
        var listOutputFile = projectWorkingDir.resolve("files_" + hash + ".json");
        var fileList = new JsonArray();
        filesToScan.forEach(fileList::add);
        writeFile(listOutputFile.toFile(), toBadlyFormattedString(fileList));
      }
    }
  }

  private JsonObject toJson(String rawText) {

    rawText = rawText.replace("\n", "")
      .replace("'", "\"")
      .replace(",]", "]")
      .replace(",}", "}");

    return JsonParser.parseString(rawText).getAsJsonObject();
  }

  private String toBadlyFormattedString(JsonElement jsonElement) {
    return jsonElement.toString()
      .replace("\"", "'")
      .replaceAll("([\\[\\{,])", "$1\n")
      .replaceAll("([]}])", "\n$1");
  }

  private void writeFile(File file, String data) throws IOException {
    file.createNewFile();
    try(FileWriter fw = new FileWriter(file)) {
      fw.write(data);
    }
  }

  private String readFile(File file) {
    try {
      var fileReader = new FileReader(file);
      char[] chars = new char[(int) file.length()];
      assertThat(fileReader.read(chars)).isEqualTo(file.length());
      return String.valueOf(chars);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  public void scan_subset_of_project_files() {

  }

  private static MavenBuild test_project(String projectKey, @Nullable String path, String projectName) throws IOException {
    String pomLocation = "../sources/" + (path != null ? path + "/" : "") + projectName + "/pom.xml";
    File pomFile = FileLocation.of(pomLocation).getFile().getCanonicalFile();
    prepareProject(projectKey, projectName);
    MavenBuild mavenBuild = MavenBuild.create().setPom(pomFile).setCleanPackageSonarGoals().addArgument("-DskipTests");
    mavenBuild.setProperty("sonar.projectKey", projectKey);
    return mavenBuild;
  }

  private static MavenBuild test_project(String projectKey, String projectName) throws IOException {
    return test_project(projectKey, null, projectName);
  }

  private static void prepareProject(String projectKey, String projectName) {
    orchestrator.getServer().provisionProject(projectKey, projectName);
    orchestrator.getServer().associateProjectToQualityProfile(projectKey, "java", "rules");
  }

  private static void instantiateTemplateRule(String ruleTemplateKey, String instantiationKey, String params, Set<String> activatedRuleKeys) {
    if (!SUBSET_OF_ENABLED_RULES.isEmpty() && !SUBSET_OF_ENABLED_RULES.contains(instantiationKey)) {
      return;
    }
    activatedRuleKeys.add(instantiationKey);
    newAdminWsClient(orchestrator)
      .rules()
      .create(new CreateRequest()
        .setName(instantiationKey)
        .setMarkdownDescription(instantiationKey)
        .setSeverity("INFO")
        .setStatus("READY")
        .setTemplateKey("java:" + ruleTemplateKey)
        .setCustomKey(instantiationKey)
        .setPreventReactivation("true")
        .setParams(Arrays.asList(("name=\"" + instantiationKey + "\";key=\"" + instantiationKey + "\";" +
          "markdown_description=\"" + instantiationKey + "\";" + params).split(";", 0))));

    String profileKey = newAdminWsClient(orchestrator).qualityprofiles()
      .search(new SearchRequest())
      .getProfilesList().stream()
      .filter(qualityProfile -> "rules".equals(qualityProfile.getName()))
      .map(Qualityprofiles.SearchWsResponse.QualityProfile::getKey)
      .findFirst()
      .orElse(null);

    if (StringUtils.isEmpty(profileKey)) {
      LOG.error("Could not retrieve profile key : Template rule " + ruleTemplateKey + " has not been activated");
    } else {
      String ruleKey = "java:" + instantiationKey;
      newAdminWsClient(orchestrator).qualityprofiles()
        .activateRule(new ActivateRuleRequest()
          .setKey(profileKey)
          .setRule(ruleKey)
          .setSeverity("INFO")
          .setParams(Collections.emptyList()));
      LOG.info(String.format("Successfully activated template rule '%s'", ruleKey));
    }
  }

  private static void prepareDumpOldFolder() throws Exception {
    Path allRulesFolder = Paths.get("src/test/resources");
    if (SUBSET_OF_ENABLED_RULES.isEmpty()) {
      effectiveDumpOldFolder = allRulesFolder.toAbsolutePath();
    } else {
      effectiveDumpOldFolder = TMP_DUMP_OLD_FOLDER.getRoot().toPath().toAbsolutePath();
      Files.list(allRulesFolder)
        .filter(p -> p.toFile().isDirectory())
        .forEach(srcProjectDir -> copyDumpSubset(srcProjectDir, effectiveDumpOldFolder.resolve(srcProjectDir.getFileName())));
    }
  }

  static WsClient newAdminWsClient(Orchestrator orchestrator) {
    return WsClientFactories.getDefault().newClient(HttpConnector.newBuilder()
      .credentials(Server.ADMIN_LOGIN, Server.ADMIN_PASSWORD)
      .url(orchestrator.getServer().getUrl())
      .build());
  }

  private static void copyDumpSubset(Path srcProjectDir, Path dstProjectDir) {
    try {
      Files.createDirectory(dstProjectDir);
    } catch (IOException e) {
      throw new IllegalStateException("Unable to create directory: " + dstProjectDir.toString());
    }
    SUBSET_OF_ENABLED_RULES.stream()
      .map(ruleKey -> srcProjectDir.resolve("java-" + ruleKey + ".json"))
      .filter(p -> p.toFile().exists())
      .forEach(srcJsonFile -> copyFile(srcJsonFile, dstProjectDir));
  }

  private static void copyFile(Path source, Path targetDir) {
    try {
      Files.copy(source, targetDir.resolve(source.getFileName()), StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new IllegalStateException("Unable to copy file: " + source.toString());
    }
  }

}
