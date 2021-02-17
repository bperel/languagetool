/* LanguageTool, a natural language style checker 
 * Copyright (C) 2013 Daniel Naber (http://www.danielnaber.de)
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301
 * USA
 */
package org.languagetool.dev.dumpcheck;

import org.apache.commons.cli.*;
import org.apache.commons.lang3.StringUtils;
import org.languagetool.JLanguageTool;
import org.languagetool.Language;
import org.languagetool.Languages;
import org.languagetool.MultiThreadedJLanguageTool;
import org.languagetool.rules.Category;
import org.languagetool.rules.CategoryId;
import org.languagetool.rules.Rule;
import org.languagetool.rules.patterns.AbstractPatternRule;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Checks texts from one or more {@link SentenceSource}s.
 * @since 2.4
 */
public class SentenceSourceChecker {

  private SentenceSourceChecker() {
    // no public constructor
  }

  public static void main(String[] args) throws IOException {
    SentenceSourceChecker prg = new SentenceSourceChecker();
    CommandLine commandLine = ensureCorrectUsageOrExit(args);
    File propFile = null;
    if (commandLine.hasOption('d')) {
      propFile = new File(commandLine.getOptionValue('d'));
      if (!propFile.exists() || propFile.isDirectory()) {
        throw new IOException("File not found or isn't a file: " + propFile.getAbsolutePath());
      }
    }
    String languageCode = commandLine.getOptionValue('l');
    Set<String> disabledRuleIds = new HashSet<>();
    if (commandLine.hasOption("rule-properties")) {
      File disabledRulesPropFile = new File(commandLine.getOptionValue("rule-properties"));
      if (!disabledRulesPropFile.exists() || disabledRulesPropFile.isDirectory()) {
        throw new IOException("File not found or isn't a file: " + disabledRulesPropFile.getAbsolutePath());
      }
      Properties disabledRules = new Properties();
      try (FileInputStream stream = new FileInputStream(disabledRulesPropFile)) {
        disabledRules.load(stream);
        addDisabledRules("all", disabledRuleIds, disabledRules);
        addDisabledRules(languageCode, disabledRuleIds, disabledRules);
      }
    }

    Set<String> disabledRuleCategoryIds = new HashSet<>();
    if (commandLine.hasOption("rule-category-properties")) {
      File disabledRuleCategoriesPropFile = new File(commandLine.getOptionValue("rule-category-properties"));
      if (!disabledRuleCategoriesPropFile.exists() || disabledRuleCategoriesPropFile.isDirectory()) {
        throw new IOException("File not found or isn't a file: " + disabledRuleCategoriesPropFile.getAbsolutePath());
      }
      Properties disabledRuleCategories = new Properties();
      try (FileInputStream stream = new FileInputStream(disabledRuleCategoriesPropFile)) {
        disabledRuleCategories.load(stream);
        addDisabledRuleCategories("all", disabledRuleIds, disabledRuleCategories);
        addDisabledRuleCategories(languageCode, disabledRuleIds, disabledRuleCategories);
      }
    }
    int maxArticles = Integer.parseInt(commandLine.getOptionValue("max-sentences", "0"));
    int maxErrors = Integer.parseInt(commandLine.getOptionValue("max-errors", "0"));
    int contextSize = Integer.parseInt(commandLine.getOptionValue("context-size", "50"));
    prg.run(propFile, disabledRuleIds, disabledRuleCategoryIds, languageCode, maxArticles,
      maxErrors, contextSize,
      commandLine);
  }

  private static void addDisabledRules(String languageCode, Set<String> disabledRuleIds, Properties disabledRules) {
    String disabledRulesString = disabledRules.getProperty(languageCode);
    if (disabledRulesString != null) {
      String[] ids = disabledRulesString.split(",");
      disabledRuleIds.addAll(Arrays.asList(ids));
    }
  }

  private static void addDisabledRuleCategories(String languageCode, Set<String> disabledRuleCategoryIds, Properties disabledRuleCategories) {
    String disabledRuleCategoriesString = disabledRuleCategories.getProperty(languageCode);
    if (disabledRuleCategoriesString != null) {
      String[] ids = disabledRuleCategoriesString.split(",");
      disabledRuleCategoryIds.addAll(Arrays.asList(ids));
    }
  }

  private static CommandLine ensureCorrectUsageOrExit(String[] args) {
    Options options = new Options();
    options.addOption(Option.builder("l").longOpt("language").argName("code").hasArg()
            .desc("language code like 'en' or 'de'").required().build());
    options.addOption(Option.builder("d").longOpt("db-properties").argName("file").hasArg()
            .desc("A file to set database access properties. If not set, the output will be written to STDOUT. " +
                  "The file needs to set the properties dbUrl ('jdbc:...'), dbUser, and dbPassword. " +
                  "It can optionally define the batchSize for insert statements, which defaults to 1.").build());
    options.addOption(Option.builder().longOpt("rule-properties").argName("file").hasArg()
            .desc("A file to set rules which should be disabled per language (e.g. en=RULE1,RULE2 or all=RULE3,RULE4)").build());
    options.addOption(Option.builder().longOpt("rule-category-properties").argName("file").hasArg()
            .desc("A file to set rule categories which should be disabled per language (e.g. en=RULE_CATEGORY1,RULE_CATEGORY2 or all=RULE_CATEGORY3,RULE_CATEGORY4)").build());
    options.addOption(Option.builder("r").longOpt("rule-ids").argName("id").hasArg()
            .desc("comma-separated list of rule-ids to activate").build());
    options.addOption(Option.builder().longOpt("also-enable-categories").argName("categories").hasArg()
            .desc("comma-separated list of categories to activate, additionally to rules activated anyway").build());
    options.addOption(Option.builder("f").longOpt("file").argName("file").hasArg()
            .desc("an unpacked Wikipedia XML dump; (must be named *.xml, dumps are available from http://dumps.wikimedia.org/backup-index.html) " +
                  "or a Tatoeba CSV file filtered to contain only one language (must be named tatoeba-*). You can specify this option more than once.")
            .required().build());
    options.addOption(Option.builder().longOpt("max-sentences").argName("number").hasArg()
            .desc("maximum number of sentences to check").build());
    options.addOption(Option.builder().longOpt("max-errors").argName("number").hasArg()
            .desc("maximum number of errors, stop when finding more").build());
    options.addOption(Option.builder().longOpt("context-size").argName("number").hasArg()
            .desc("context size per error, in characters").build());
    options.addOption(Option.builder().longOpt("languagemodel").argName("indexDir").hasArg()
            .desc("directory with a '3grams' sub directory that contains an ngram index").build());
    options.addOption(Option.builder().longOpt("neuralnetworkmodel").argName("baseDir").hasArg()
            .desc("base directory for saved neural network models (deprecated)").build());
    options.addOption(Option.builder().longOpt("remoterules").argName("configFile").hasArg()
            .desc("JSON file with configuration of remote rules").build());
    options.addOption(Option.builder().longOpt("filter").argName("regex").hasArg()
            .desc("Consider only sentences that contain this regular expression (for speed up)").build());
    options.addOption(Option.builder().longOpt("spelling")
            .desc("Don't skip spell checking rules").build());
    options.addOption(Option.builder().longOpt("rulesource").hasArg()
            .desc("Activate only rules from this XML file (e.g. 'grammar.xml')").build());
    options.addOption(Option.builder().longOpt("skip").hasArg()
            .desc("Skip this many sentences from input before actually checking sentences").build());
    try {
      CommandLineParser parser = new DefaultParser();
      return parser.parse(options, args);
    } catch (ParseException e) {
      System.err.println("Error: " + e.getMessage());
      HelpFormatter formatter = new HelpFormatter();
      formatter.setWidth(80);
      formatter.setSyntaxPrefix("Usage: ");
      formatter.printHelp(SentenceSourceChecker.class.getSimpleName() + " [OPTION]... --file <file> --language <code>", options);
      System.exit(1);
    }
    throw new IllegalStateException();
  }

  private void run(File propFile, Set<String> disabledRules, Set<String> disabledRuleCategoryIds, String langCode,
                   int maxSentences, int maxErrors, int contextSize,
                   CommandLine options) throws IOException {
    long startTime = System.currentTimeMillis();
    String[] ruleIds = options.hasOption('r') ? options.getOptionValue('r').split(",") : null;
    String[] additionalCategoryIds = options.hasOption("also-enable-categories") ? options.getOptionValue("also-enable-categories").split(",") : null;
    String[] fileNames = options.getOptionValues('f');
    File languageModelDir = options.hasOption("languagemodel") ? new File(options.getOptionValue("languagemodel")) : null;
    File word2vecModelDir = options.hasOption("word2vecmodel") ? new File(options.getOptionValue("word2vecmodel")) : null;
    File neuralNetworkModelDir = options.hasOption("neuralnetworkmodel") ? new File(options.getOptionValue("neuralnetworkmodel")) : null;
    File remoteRules = options.hasOption("remoterules") ? new File(options.getOptionValue("remoterules")) : null;
    Pattern filter = options.hasOption("filter") ? Pattern.compile(options.getOptionValue("filter")) : null;
    String ruleSource = options.hasOption("rulesource") ? options.getOptionValue("rulesource") : null;
    int sentencesToSkip = options.hasOption("skip") ? Integer.parseInt(options.getOptionValue("skip")) : 0;
    Language lang = Languages.getLanguageForShortCode(langCode);
    MultiThreadedJLanguageTool lt = new MultiThreadedJLanguageTool(lang);
    lt.setCleanOverlappingMatches(false);
    if (languageModelDir != null) {
      lt.activateLanguageModelRules(languageModelDir);
    }
    if (word2vecModelDir != null) {
      lt.activateWord2VecModelRules(word2vecModelDir);
    }
    if (neuralNetworkModelDir != null) {
      lt.activateNeuralNetworkRules(neuralNetworkModelDir);
    }
    int activatedBySource = 0;
    for (Rule rule : lt.getAllRules()) {
      if (rule.isDefaultTempOff()) {
        System.out.println("Activating " + rule.getFullId() + ", which is default='temp_off'");
        lt.enableRule(rule.getId());
      }
      if (ruleSource != null) {
        boolean enable = false;
        if (rule instanceof AbstractPatternRule) {
          String sourceFile = ((AbstractPatternRule) rule).getSourceFile();
          if (sourceFile != null && sourceFile.endsWith("/" + ruleSource) && !rule.isDefaultOff()) {
            enable = true;
            activatedBySource++;
          }
        }
        if (enable) {
          lt.enableRule(rule.getId());
        } else {
          lt.disableRule(rule.getId());
        }
      }
    }
    lt.activateRemoteRules(remoteRules);
    if (ruleSource == null) {
      if (ruleIds != null) {
        enableOnlySpecifiedRules(ruleIds, lt);
      } else {
        applyRuleDeactivation(lt, disabledRules, disabledRuleCategoryIds);
      }
    } else {
      System.out.println("Activated " + activatedBySource + " rules from " + ruleSource);
    }
    if (filter != null) {
      System.out.println("*** NOTE: only sentences that match regular expression '" + filter + "' will be checked");
    }
    activateAdditionalCategories(additionalCategoryIds, lt);
    if (options.hasOption("spelling")) {
      System.out.println("Spelling rules active: yes (only if you're using a language code like en-US which comes with spelling)");
    } else if (ruleIds == null) {
      disableSpellingRules(lt);
      System.out.println("Spelling rules active: no");
    }
    System.out.println("Working on: " + StringUtils.join(fileNames, ", "));
    System.out.println("Sentence limit: " + (maxSentences > 0 ? maxSentences : "no limit"));
    System.out.println("Context size: " + contextSize);
    System.out.println("Error limit: " + (maxErrors > 0 ? maxErrors : "no limit"));
    System.out.println("Skip: " + sentencesToSkip);
    //System.out.println("Version: " + JLanguageTool.VERSION + " (" + JLanguageTool.BUILD_DATE + ")");

    CorpusMatchDatabaseHandler databaseHandler = new CorpusMatchDatabaseHandler(propFile, lang.getShortCode(), maxSentences, maxErrors);
    FileInputStream inStream = new FileInputStream(propFile);
    Properties properties = new Properties();
    properties.load(inStream);
    String parsoidUrl = getProperty(properties, "parsoidUrl");

    MixingSentenceSource.lt = lt;
    MixingSentenceSource.create(Arrays.asList(fileNames), lang, filter, parsoidUrl, databaseHandler);
    MixingSentenceSource.lt.shutdown();
  }

  private void enableOnlySpecifiedRules(String[] ruleIds, JLanguageTool lt) {
    for (Rule rule : lt.getAllRules()) {
      lt.disableRule(rule.getId());
    }
    for (String ruleId : ruleIds) {
      lt.enableRule(ruleId);
    }
    warnOnNonExistingRuleIds(ruleIds, lt);
    System.out.println("Only these rules are enabled: " + Arrays.toString(ruleIds));
  }

  private void warnOnNonExistingRuleIds(String[] ruleIds, JLanguageTool lt) {
    for (String ruleId : ruleIds) {
      boolean found = false;
      for (Rule rule : lt.getAllRules()) {
        if (rule.getId().equals(ruleId)) {
          found = true;
          break;
        }
      }
      if (!found) {
        System.out.println("WARNING: Could not find rule '" + ruleId + "'");
      }
    }
  }

  private void applyRuleDeactivation(JLanguageTool lt, Set<String> disabledRules, Set<String> disabledRuleCategories) {
    // disabled via config file, usually to avoid too many false alarms:
    for (String disabledRuleId : disabledRules) {
      lt.disableRule(disabledRuleId);
    }
    System.out.println("These rules are disabled: " + lt.getDisabledRules());

    Map<CategoryId, Category> categories = lt.getCategories();
    for (String disabledRuleCategoryId : disabledRuleCategories) {
      boolean found = false;
      for (CategoryId categoryId : categories.keySet()) {
        if (categoryId.toString().equals(disabledRuleCategoryId)) {
          found = true;
          lt.disableCategory(categoryId);
        }
      }
      if (!found) {
        System.err.println("Disabled category not found : " + disabledRuleCategoryId);
      }
    }
  }

  private void activateAdditionalCategories(String[] additionalCategoryIds, JLanguageTool lt) {
    if (additionalCategoryIds != null) {
      for (String categoryId : additionalCategoryIds) {
        for (Rule rule : lt.getAllRules()) {
          CategoryId id = rule.getCategory().getId();
          if (id != null && id.toString().equals(categoryId)) {
            System.out.println("Activating " + rule.getId() + " in category " + categoryId);
            lt.enableRule(rule.getId());
          }
        }
      }
    }
  }

  private static String getProperty(Properties prop, String key) {
    String value = prop.getProperty(key);
    if (value == null) {
      throw new RuntimeException(String.format("Required key '%s' not found in properties", key));
    }
    return value;
  }

  private void disableSpellingRules(JLanguageTool lt) {
    List<Rule> allActiveRules = lt.getAllActiveRules();
    for (Rule rule : allActiveRules) {
      if (rule.isDictionaryBasedSpellingRule()) {
        lt.disableRule(rule.getId());
      }
    }
  }
}
