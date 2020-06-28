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
import org.languagetool.*;
import org.languagetool.rules.CategoryId;
import org.languagetool.rules.Rule;
import org.languagetool.rules.RuleMatch;
import org.languagetool.rules.patterns.AbstractPatternRule;
import org.languagetool.tools.HtmlTools;
import org.w3c.dom.*;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.languagetool.dev.dumpcheck.CorpusMatchDatabaseHandler.MAX_CONTEXT_LENGTH;
import static org.languagetool.tools.HtmlTools.SuggestionNotApplicableException;
import static org.languagetool.tools.HtmlTools.getErrorContextWithAppliedSuggestion;

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
    int maxArticles = Integer.parseInt(commandLine.getOptionValue("max-sentences", "0"));
    int maxErrors = Integer.parseInt(commandLine.getOptionValue("max-errors", "0"));
    int contextSize = Integer.parseInt(commandLine.getOptionValue("context-size", "50"));
    prg.run(propFile, disabledRuleIds, languageCode, maxArticles,
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

  private void run(File propFile, Set<String> disabledRules, String langCode,
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
        applyRuleDeactivation(lt, disabledRules);
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

    CorpusMatchDatabaseHandler databaseHandler = new CorpusMatchDatabaseHandler(propFile, maxSentences, maxErrors);
    FileInputStream inStream = new FileInputStream(propFile);
    Properties properties = new Properties();
    properties.load(inStream);
    String parsoidUrl = getProperty(properties, "parsoidUrl");

    RuleMatchWithHtmlContexts.lt = lt;
    MixingSentenceSource.create(Arrays.asList(fileNames), lang, filter, parsoidUrl, databaseHandler);
    RuleMatchWithHtmlContexts.lt.shutdown();
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

  private void applyRuleDeactivation(JLanguageTool lt, Set<String> disabledRules) {
    // disabled via config file, usually to avoid too many false alarms:
    for (String disabledRuleId : disabledRules) {
      lt.disableRule(disabledRuleId);
    }
    System.out.println("These rules are disabled: " + lt.getDisabledRules());
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
      throw new RuntimeException("Required key '" + key + "' not found in properties");
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

  static class RuleMatchWithHtmlContexts extends RuleMatch {
    public static MultiThreadedJLanguageTool lt = null;

    public static final DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
    static {
      dbf.setValidating(false);
    }

    public static final XPath xPath = XPathFactory.newInstance().newXPath();

    private static Long currentArticleId;
    private static String currentArticleCssUrl;
    private static Document currentArticleDocument;

    public String smallTextContext;
    public String textContext;
    public String largeTextContext;
    public String htmlContext;

    public RuleMatchWithHtmlContexts(Rule rule, AnalyzedSentence sentence, int fromPos, int toPos, String message, String shortMessage, List<String> suggestions) {
      super(rule, sentence, fromPos, toPos, message, shortMessage, suggestions);
    }

    public static List<RuleMatchWithHtmlContexts> getMatches(Sentence sentence, String articleWikitext, String articleHtml, String articleCssUrl) throws IOException {
      List<RuleMatchWithHtmlContexts> matches = lt.check(sentence.getText()).stream()
        .map(mapRuleAddTextContext(sentence, articleWikitext))
        .filter(Objects::nonNull).collect(Collectors.toList());

      if (!matches.isEmpty() && articleHtml != null) {
        try {
          if (!sentence.getArticleId().equals(currentArticleId)) {
            DocumentBuilder docBuilder = dbf.newDocumentBuilder();
            currentArticleId = sentence.getArticleId();
            currentArticleCssUrl = articleCssUrl;
            currentArticleDocument = docBuilder.parse(new InputSource(new StringReader(articleHtml)));
          }
          return matches.stream().map(mapRuleAddHtmlContext())
            .filter(Objects::nonNull).collect(Collectors.toList());
        } catch (SAXException | IOException | ParserConfigurationException e) {
          e.printStackTrace();
        }
      }
      return matches;
    }

    private static Function<RuleMatchWithHtmlContexts, RuleMatchWithHtmlContexts> mapRuleAddHtmlContext() {
      return match -> {
        try {
          String largestErrorContextWithoutHtmlTags = HtmlTools.getLargestErrorContext(match.getLargeTextContext());
          String stringToReplace = HtmlTools.getStringToReplace(largestErrorContextWithoutHtmlTags);
          NodeList nodeList = (NodeList) xPath.compile(getXpathExpression(stringToReplace)).evaluate(currentArticleDocument, XPathConstants.NODESET);
          switch (nodeList.getLength()) {
            case 0:
              System.out.println("No HTML match for '" + stringToReplace + "'");
              break;
            case 1:
              System.out.println("Found an HTML match for '" + stringToReplace +"'");
              match.setHtmlContext(getSimplifiedHtmlContext(dbf.newDocumentBuilder().newDocument(), nodeList.item(0), null));
              return match;
            default:
              System.out.println("Found more than 1 HTML match (" + nodeList.getLength()+ ") for '" + stringToReplace +"'");
          }
        } catch (XPathExpressionException | ParserConfigurationException e) {
          e.printStackTrace();
        }

        return null;
      };
    }

    private static String getSimplifiedHtmlContext(Document doc, Node node, Node childElement) {
      if (node instanceof Text) {
        Node surroundingElement = doc.importNode(node.getParentNode(), true);
        return getSimplifiedHtmlContext(doc, node.getParentNode().getParentNode(), surroundingElement);
      }
      else {
        Element simpleElement;
        if (node.getParentNode() == null) {
          doc.appendChild(childElement);
          return HtmlTools.getStringFromDocument(doc);
        }
        else {
          simpleElement = doc.createElement(node.getNodeName());

          if (node.getNodeName().equals("html")) {
            Element headElement = doc.createElement("head");
            Element cssElement = doc.createElement("link");
            cssElement.setAttribute("rel", "stylesheet");
            cssElement.setAttribute("href", currentArticleCssUrl);
            headElement.appendChild(cssElement);
            simpleElement.appendChild(headElement);
          }

          simpleElement.appendChild(childElement);
          copyAttributes(node, simpleElement);

          return getSimplifiedHtmlContext(doc, node.getParentNode(), simpleElement);
        }
      }
    }

    private static Function<RuleMatch, RuleMatchWithHtmlContexts> mapRuleAddTextContext(Sentence sentence, String finalWikitext) {
      return match -> {
        String context = CorpusMatchDatabaseHandler.contextTools.getContext(match.getFromPos(), match.getToPos(), sentence.getText());
        if (context.length() > MAX_CONTEXT_LENGTH) {
          return null;
        }
        String smallContext = CorpusMatchDatabaseHandler.smallContextTools.getContext(match.getFromPos(), match.getToPos(), sentence.getText());

        List<String> suggestions = match.getSuggestedReplacements();
        if (suggestions.isEmpty()
          || suggestions.get(0).matches("^\\(.+\\)$") // This kind of suggestions are expecting user input
        ) {
          return null;
        }
        try {
          getErrorContextWithAppliedSuggestion(sentence.getTitle(), finalWikitext, context, suggestions.get(0));

          String largestErrorContextWithoutHtmlTags = HtmlTools.getLargestErrorContext(context);

          RuleMatchWithHtmlContexts matchWithHtmlContext = new RuleMatchWithHtmlContexts(
            match.getRule(), match.getSentence(), match.getFromPos(), match.getToPos(), match.getMessage(), match.getShortMessage(), match.getSuggestedReplacements()
          );
          matchWithHtmlContext.setSmallTextContext(smallContext);
          matchWithHtmlContext.setTextContext(context);
          matchWithHtmlContext.setLargeTextContext(HtmlTools.getStringToReplace(largestErrorContextWithoutHtmlTags));
          return matchWithHtmlContext;
        } catch (SuggestionNotApplicableException e) {
          System.out.println(e.getMessage());
          return null;
        }
      };
    }

    private static void copyAttributes(Node from, Element to) {
      NamedNodeMap attributes = from.getAttributes();
      for (int i = 0; i < attributes.getLength(); i++) {
        to.setAttribute(attributes.item(i).getNodeName(), attributes.item(i).getTextContent());
      }
    }

    private static String getXpathExpression(String value)
    {
      String textExpression;
      if (!value.contains("'")) {
        textExpression = '\'' + value + '\'';
      }
      else if (!value.contains("\"")) {
        textExpression = '"' + value + '"';
      }
      else {
        textExpression = "concat('" + value.replace("'", "',\"'\",'") + "')";
      }
      return "//text()[contains(.,"+textExpression+")]";
    }

    public String getSmallTextContext() {
      return smallTextContext;
    }

    public void setSmallTextContext(String smallTextContext) {
      this.smallTextContext = smallTextContext;
    }

    public String getTextContext() {
      return textContext;
    }

    public void setTextContext(String textContext) {
      this.textContext = textContext;
    }

    public String getLargeTextContext() {
      return largeTextContext;
    }

    public void setLargeTextContext(String largeTextContext) {
      this.largeTextContext = largeTextContext;
    }

    public String getHtmlContext() {
      return htmlContext;
    }

    public void setHtmlContext(String htmlContext) {
      this.htmlContext = htmlContext;
    }
  }

}
