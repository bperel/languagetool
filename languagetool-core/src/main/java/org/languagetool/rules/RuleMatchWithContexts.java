package org.languagetool.rules;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.spi.json.JsonProvider;
import net.minidev.json.JSONArray;
import org.languagetool.AnalyzedSentence;
import org.languagetool.tools.ContextTools;
import org.w3c.dom.*;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.languagetool.tools.HtmlTools.*;

public class RuleMatchWithContexts extends RuleMatch {
  private static final String MARKER_START = "<err>";
  private static final String MARKER_END = "</err>";
  private static final int MAX_CONTEXT_LENGTH = 500;
  private static final int SMALL_CONTEXT_LENGTH = 40;  // do not modify - it would break lookup of errors marked as 'false alarm'

  public static String languageCode;
  public static String currentArticleCssUrl;
  public static Document currentArticleDocument;

  static final ContextTools contextTools;
  static final ContextTools smallContextTools;

  static {
    contextTools = new ContextTools();
    contextTools.setContextSize(MAX_CONTEXT_LENGTH);
    contextTools.setErrorMarkerStart(MARKER_START);
    contextTools.setErrorMarkerEnd(MARKER_END);
    contextTools.setEscapeHtml(false);
    smallContextTools = new ContextTools();
    smallContextTools.setContextSize(SMALL_CONTEXT_LENGTH);
    smallContextTools.setErrorMarkerStart(MARKER_START);
    smallContextTools.setErrorMarkerEnd(MARKER_END);
    smallContextTools.setEscapeHtml(false);
  }

  public static final DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();

  static {
    dbf.setValidating(false);
  }

  public static final XPath xPath = XPathFactory.newInstance().newXPath();

  public String smallTextContext;
  public String textContext;
  public String largeTextContext;
  public String htmlContext;

  private static final Map<String, Map<String, Set<String>>> excludedJsonPaths = new HashMap<String, Map<String, Set<String>>>() {
    {
      put("ca", Map.of("data-mw", Set.of("parts[*].template.target[?(@.wt == 'Lang')]")));
      put("de", Map.of("data-mw", Set.of("parts[*].template.target[?(@.wt == 'lang')]")));
      put("en", Map.of("data-mw", Set.of("parts[*].template.target[?(@.wt == 'Lang')]")));
      put("fr", Map.of("data-mw", Set.of(
        "parts[*].template.target[?(@.wt == 'Langue')]",
        "parts[*].template[?(@.target.wt == 'Article')][?(@.params.langue)]",
        "parts[*].template[?(@.target.wt == 'Ouvrage')][?(@.params.langue)]"
      )));
      put("nl", Map.of("data-mw", Set.of("parts[*].template.target[?(@.wt == 'Lang')]")));
      put("pl", Map.of("data-mw", Set.of("parts[*].template.target[?(@.wt == 'J')]")));
      put("pt", Map.of("data-mw", Set.of("parts[*].template.target[?(@.wt == 'Lang')]")));
      put("ru", Map.of("data-mw", Set.of("parts[*].template.target[?(@.wt == 'Lang')]")));
      put("uk", Map.of("data-mw", Set.of("parts[*].template.target[?(@.wt == 'Lang')]")));
    }
  };

  public RuleMatchWithContexts(Rule rule, AnalyzedSentence sentence, int fromPos, int toPos, String message, String shortMessage, List<String> suggestions) {
    super(rule, sentence, fromPos, toPos, message, shortMessage, suggestions);
  }

  public static Function<RuleMatchWithContexts, RuleMatchWithContexts> mapRuleAddHtmlContext() {
    return match -> {
      try {
        String largestErrorContextWithoutHtmlTags = getLargestErrorContext(match.getLargeTextContext());
        String stringToReplace = getStringToReplace(largestErrorContextWithoutHtmlTags);
        NodeList nodeList = (NodeList) xPath.compile(getXpathExpression(stringToReplace)).evaluate(currentArticleDocument, XPathConstants.NODESET);
        switch (nodeList.getLength()) {
          case 0:
            System.out.printf(" No HTML match for '%s'%n", stringToReplace);
            break;
          case 1:
            System.out.printf(" Found an HTML match for '%s'%n", stringToReplace);
            String htmlContext = getSimplifiedHtmlContext(dbf.newDocumentBuilder().newDocument(), nodeList.item(0), null);
            match.setHtmlContext(htmlContext);
            return match;
          default:
            System.out.printf(" Found more than 1 HTML match (%d) for '%s'%n", nodeList.getLength(), stringToReplace);
        }
      } catch (XPathExpressionException | ParserConfigurationException e) {
        e.printStackTrace();
        return null;
      } catch (SuggestionNotApplicableException e) {
        System.out.println(e.getMessage());
        return null;
      }

      return null;
    };
  }

  private static String getSimplifiedHtmlContext(Document doc, Node node, Node childElement) throws SuggestionNotApplicableException {
    if (node instanceof Text) {
      Node surroundingElement = doc.importNode(node.getParentNode(), true);
      assertNodeNotExcluded(surroundingElement);
      return getSimplifiedHtmlContext(doc, node.getParentNode().getParentNode(), surroundingElement);
    } else {
      Element simpleElement;
      if (node.getParentNode() == null) {
        doc.appendChild(childElement);
        return getStringFromDocument(doc);
      } else {
        assertNodeNotExcluded(node);
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

  static void assertNodeNotExcluded(Node node) throws SuggestionNotApplicableException {
    NamedNodeMap attributes = node.getAttributes();
    Map<String, Set<String>> excludedJsonPathsForLanguage = excludedJsonPaths.get(languageCode);
    JsonProvider jsonProvider = Configuration.defaultConfiguration().jsonProvider();

    for (String attributeName : excludedJsonPathsForLanguage.keySet()) {
      Node attribute = attributes.getNamedItem(attributeName);
      if (attribute != null) {
        Object attributeContent = jsonProvider.parse(attribute.getNodeValue());
        for (String jsonPath : excludedJsonPathsForLanguage.get(attributeName)) {
          JSONArray match = JsonPath.read(attributeContent, jsonPath);
          if (match != null && !match.isEmpty()) {
            throw new SuggestionNotApplicableException(" Match ignored because it matches the following path : " + jsonPath);
          }
        }
      }
    }
  }

  public static Function<RuleMatch, RuleMatchWithContexts> addTextContext(String finalWikitext, String sentenceTitle, String sentenceText) {
    return match -> {
      String context = contextTools.getContext(match.getFromPos(), match.getToPos(), sentenceText);
      if (context.length() > MAX_CONTEXT_LENGTH) {
        return null;
      }
      String smallContext = smallContextTools.getContext(match.getFromPos(), match.getToPos(), sentenceText);

      List<String> suggestions = match.getSuggestedReplacements();
      if (suggestions.isEmpty()
        || suggestions.get(0).matches("^\\(.+\\)$") // This kind of suggestions is expecting user input
      ) {
        return null;
      }
      try {
        getErrorContextWithAppliedSuggestion(sentenceTitle, finalWikitext, context, suggestions.get(0));

        String largestErrorContextWithoutHtmlTags = getLargestErrorContext(context);

        RuleMatchWithContexts matchWithHtmlContext = new RuleMatchWithContexts(
          match.getRule(), match.getSentence(), match.getFromPos(), match.getToPos(), match.getMessage(), match.getShortMessage(), match.getSuggestedReplacements()
        );
        matchWithHtmlContext.setSmallTextContext(smallContext);
        matchWithHtmlContext.setTextContext(context);
        matchWithHtmlContext.setLargeTextContext(getStringToReplace(largestErrorContextWithoutHtmlTags));
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

  private static String getXpathExpression(String value) {
    String textExpression;
    if (!value.contains("'")) {
      textExpression = String.format("'%s'", value);
    } else if (!value.contains("\"")) {
      textExpression = String.format("\"%s\"", value);
    } else {
      textExpression = String.format("concat('%s')", value.replace("'", "',\"'\",'"));
    }
    return String.format("//text()[contains(.,%s)]", textExpression);
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
