package sfdc.sfdx.scanner.pmd;

import java.io.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import org.w3c.dom.*;
import sfdc.sfdx.scanner.pmd.catalog.PmdCatalogCategory;
import sfdc.sfdx.scanner.pmd.catalog.PmdCatalogJson;
import sfdc.sfdx.scanner.pmd.catalog.PmdCatalogRule;
import sfdc.sfdx.scanner.pmd.catalog.PmdCatalogRuleset;
import sfdc.sfdx.scanner.xml.XmlReader;
import sfdc.sfdx.scanner.ExitCode;

class PmdRuleCataloger {
  private String pmdVersion;
  private String pmdPath;
  private List<String> languages;
  // These two maps are how we know which files we need to scan for each language.
  private Map<String,List<String>> categoryPathsByLanguage = new HashMap<>();
  private Map<String,List<String>> rulesetPathsByLanguage = new HashMap<>();

  // These maps are going to help us store intermediate objects in an easy-to-reference way.
  private Map<String,List<PmdCatalogRule>> rulesByLanguage = new HashMap<>();
  private Map<String,List<PmdCatalogRuleset>> rulesetsByLanguage = new HashMap<>();

  // These lists are going to be the master lists that we ultimately use to build our JSON at the end.
  private List<PmdCatalogCategory> masterCategoryList = new ArrayList<>();
  private List<PmdCatalogRule> masterRuleList = new ArrayList<>();
  private List<PmdCatalogRuleset> masterRulesetList = new ArrayList<>();


  /**
   *
   * @param pmdVersion   - The version of PMD being used, e.g. 6.20.0.
   * @param pmdPath      - The path to PMD's lib folder.
   * @param languages    - The languages whose rules should be catalogued.
   */
  PmdRuleCataloger(String pmdVersion, String pmdPath, List<String> languages) {
    this.pmdVersion = pmdVersion;
    this.pmdPath = pmdPath;
    this.languages = languages;
  }


  /**
   * Builds a catalog describing all of the rules, rulesets, and categories defined by PMD for the used languages, and
   * stores them in a JSON file.
   */
  void catalogRules() {
    // STEP 1: Identify all of the ruleset and category files for each language we're looking at.
    for (String language : this.languages) {
      this.mapFilePathsByLanguage(language);
    }

    // STEP 2: Process the category files to derive category and rule representations.
    for (String language : this.categoryPathsByLanguage.keySet()) {
      List<String> categoryPaths = this.categoryPathsByLanguage.get(language);
      for (String categoryPath : categoryPaths) {
        processCategoryFile(language, categoryPath);
      }
    }

    // STEP 3: Process the ruleset files.
    for (String language : this.rulesetPathsByLanguage.keySet()) {
      List<String> rulesetPaths = this.rulesetPathsByLanguage.get(language);
      // STEP 3A: For each ruleset, generate a representation.
      for (String rulesetPath : rulesetPaths) {
        generateRulesetRepresentation(language, rulesetPath);
      }
      // STEP 3B: Create links between dependent rulesets.
      linkDependentRulesets(rulesetsByLanguage.get(language));
    }

    // STEP 4: Link rules to the rulesets that reference them.
    for (String language : rulesetsByLanguage.keySet()) {
      List<PmdCatalogRuleset> rulesets = rulesetsByLanguage.get(language);
      List<PmdCatalogRule> rules = rulesByLanguage.get(language);
      linkRulesToRulesets(rules, rulesets);
    }

    // STEP 5: Build a JSON using all of our objects.
    PmdCatalogJson json = new PmdCatalogJson(masterRuleList, masterCategoryList, masterRulesetList);

    // STEP 6: Write the JSON to a file.
    writeJsonToFile(json);
  }


  /**
   * Returns the name of the JAR in which PMD stores rule definitions for the specified language.
   * @param language - The language for which we should find the corresponding PMD JAR.
   * @return         - The name of the corresponding JAR.
   */
  private String deriveJarNameForLanguage(String language) {
    return "pmd-" + language + "-" + this.pmdVersion + ".jar";
  }


  /**
   * Identifies the JAR associated with the given language, and puts all category and ruleset definition files into the
   * corresponding Map field.
   * @param language - The language that should be processed.
   */
  private void mapFilePathsByLanguage(String language) {
    // First, define our lists.
    List<String> categoryPaths = new ArrayList<>();
    List<String> rulesetPaths = new ArrayList<>();

    // Next, we need to determine the path to the JAR we want to inspect, and then do that inspection.
    String jarPath = this.pmdPath + File.separator + this.deriveJarNameForLanguage(language);
    try {
      // Read in the JAR file.
      JarInputStream jstream = new JarInputStream(new FileInputStream(jarPath));
      JarEntry entry;

      // Iterate over every entry in the stream. These are the names of files/directories in that JAR.
      while ((entry = jstream.getNextJarEntry()) != null) {
        String fName = entry.getName();
        // We only care about .xml files, since those are how rulesets and categories are defined.
        if (fName.endsWith(".xml")) {
          // Rulesets all live in "rulesets/**/*.xml", and Categories live in "category/**/*.xml". It's frustrating that
          // one's plural and the other isn't, but sometimes life involves compromises.
          if (fName.startsWith("category")) {
            categoryPaths.add(fName);
          } else if (fName.startsWith("rulesets")) {
            rulesetPaths.add(fName);
          }
        }
      }
    } catch (FileNotFoundException fnf) {
      System.err.println("No PMD JAR found for language " + language + ". Please check the classpath.");
      System.exit(ExitCode.PMD_NO_SUCH_JAR.getCode());
    } catch (IOException io) {
      System.err.println("Failed to read PMD JAR for language " + language + ".");
      System.exit(ExitCode.PMD_JAR_READ_FAILED.getCode());
    }

    // Finally, map the files we found by the language name.
    if (!categoryPaths.isEmpty()) {
      this.categoryPathsByLanguage.put(language, categoryPaths);
    }
    if (!rulesetPaths.isEmpty()) {
      this.rulesetPathsByLanguage.put(language, rulesetPaths);
    }
  }

  private void processCategoryFile(String language, String path) {
    // STEP 1: Turn the category file's XML into a Document object with a Root Element that we can actually use.
    Document doc = XmlReader.getInstance().getDocumentFromPath(path);
    Element root = doc.getDocumentElement();

    // STEP 2: Use the root element to derive a Category representation, and put it in the master list.
    String categoryName = root.getAttribute("name");
    PmdCatalogCategory category = new PmdCatalogCategory(categoryName, path);
    this.masterCategoryList.add(category);

    // STEP 3: Get the "rule"-type nodes and use them to create Rule representations, which we should map to the target
    // language and also put in the master list.
    NodeList ruleNodes = root.getElementsByTagName("rule");
    List<PmdCatalogRule> rules = new ArrayList<>();
    int ruleCount = ruleNodes.getLength();
    for (int i = 0; i < ruleCount; i++) {
      Element ruleNode = (Element) ruleNodes.item(i);
      PmdCatalogRule rule = new PmdCatalogRule(ruleNode, category, language);
      rules.add(rule);
    }
    if (!this.rulesByLanguage.containsKey(language)) {
      this.rulesByLanguage.put(language, new ArrayList<>());
    }
    this.rulesByLanguage.get(language).addAll(rules);
    this.masterRuleList.addAll(rules);
  }

  private void generateRulesetRepresentation(String language, String path) {
    // STEP 1: Turn the ruleset file's XML into a Document object with a Root Element that we can actually use.
    Document doc = XmlReader.getInstance().getDocumentFromPath(path);
    Element root = doc.getDocumentElement();

    // STEP 2: Use the root element to derive a Ruleset representation, which we should map to the target language and
    // also put in the master list.
    PmdCatalogRuleset ruleset = new PmdCatalogRuleset(root, path);
    if (!this.rulesetsByLanguage.containsKey(language)) {
      this.rulesetsByLanguage.put(language, new ArrayList<>());
    }
    this.rulesetsByLanguage.get(language).add(ruleset);
    masterRulesetList.add(ruleset);
  }

  private void linkDependentRulesets(List<PmdCatalogRuleset> rulesets) {
    // Map the rulesets by their path.
    Map<String,PmdCatalogRuleset> rulesetsByPath = new HashMap<>();
    for (PmdCatalogRuleset ruleset : rulesets) {
      rulesetsByPath.put(ruleset.getPath(), ruleset);
    }

    // Next, feed the map into each ruleset to see which ones it depends on.
    for (PmdCatalogRuleset ruleset : rulesets) {
      ruleset.processDependencies(rulesetsByPath);
    }
  }

  private void linkRulesToRulesets(List<PmdCatalogRule> rules, List<PmdCatalogRuleset> rulesets) {
    for (PmdCatalogRule rule : rules) {
      for (PmdCatalogRuleset ruleset : rulesets) {
        ruleset.processRule(rule);
      }
    }
  }

  private void writeJsonToFile(PmdCatalogJson json) {
    File catalogDirectory = new File("./catalogs");
    catalogDirectory.mkdir();
    try (
      FileWriter file = new FileWriter("./catalogs/PmdCatalog.json")
    ) {
      file.write(json.constructJson().toString());
    } catch (IOException ioe) {
      System.err.println("Failed to write JSON to file: " + ioe.getMessage());
      System.exit(ExitCode.JSON_WRITE_EXCEPTION.getCode());
    }
  }
}
