package org.languagetool.tools;

import org.languagetool.markup.AnnotatedText;
import org.languagetool.markup.AnnotatedTextBuilder;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.ls.DOMImplementationLS;
import org.w3c.dom.ls.LSSerializer;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.IOException;
import java.io.StringReader;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HtmlTools {
  public static class HtmlAnonymizer {
    public static final String DEFAULT_TAG = "tag";
    private static final XPath xPath = XPathFactory.newInstance().newXPath();

    private String title;
    private String wikiText;
    private String html;
    private String cssUrl;
    private String anonymizedHtml;

    public HtmlAnonymizer() { }

    public static HtmlAnonymizer createFromHtml(String title, String wikiText, String html) {
      HtmlAnonymizer instance = new HtmlAnonymizer();
      instance.title = title;
      instance.wikiText = wikiText;
      instance.html = html;

      return instance;
    }

    public void anonymize() throws ParserConfigurationException, IOException, SAXException {
      DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
      dbf.setValidating(false);
      DocumentBuilder db = dbf.newDocumentBuilder();
      Document doc = db.parse(new InputSource(new StringReader(html)));
      try {
        cssUrl = (String) xPath.compile("/html/head/link[@rel='stylesheet']/@href").evaluate(doc, XPathConstants.STRING);
      } catch (XPathExpressionException e) {
        System.err.println("No css stylesheet detected in " + title);
      }

      anonymizeNode(doc, doc.getDocumentElement());

      anonymizedHtml = getStringFromDocument(doc);
    }

    private boolean anonymizeNode(Document doc, Node node) {
      if (node.getNodeType() != Node.ELEMENT_NODE) {
        return false;
      }
      String nodeName = node.getNodeName();

      switch(nodeName) {
        case DEFAULT_TAG: // This node has already been handled
          break;
        case "head":
        case "style":
        case "pre":
          node.getParentNode().removeChild(node);
          return true;
        default:
          doc.renameNode(node, null, DEFAULT_TAG);

          NamedNodeMap attributes = node.getAttributes();
          if (attributes != null) {
            while (attributes.getLength() > 0) {
              removeAttribute(node, attributes.item(0));
            }
          }

          NodeList childNodes = node.getChildNodes();
          for (int i = 0; i < childNodes.getLength(); i++) {
            boolean nodeHasBeenRemoved = anonymizeNode(doc, childNodes.item(i));
            if (nodeHasBeenRemoved) {
              i--;
            }
          }
        break;
      }
      return false;
    }

    private void removeAttribute(Node element, Node attribute) {
      element.getAttributes().removeNamedItem(attribute.getNodeName());
    }

    public String getTitle() {
      return title;
    }

    public String getWikitext() {
      return wikiText;
    }

    public String getHtml() {
      return html;
    }

    public String getAnonymizedHtml() {
      return anonymizedHtml;
    }

    public String getCssUrl() {
      return cssUrl;
    }
  }

  public static class SuggestionNotApplicableException extends Exception {
    public SuggestionNotApplicableException(String s) {
      super(s);
    }
  }

  public static String getStringFromDocument(Document doc) {
    DOMImplementationLS domImplementation = (DOMImplementationLS) doc.getImplementation();
    LSSerializer lsSerializer = domImplementation.createLSSerializer();
    lsSerializer.getDomConfig().setParameter("xml-declaration", false);
    return lsSerializer.writeToString(doc).replaceFirst("<!DOCTYPE html>\n?", "");
  }

  public static String getErrorContextWithAppliedSuggestion(String articleTitle, String articleWikitext, String suggestionErrorContext, String suggestion) throws SuggestionNotApplicableException {
    String largestErrorContextWithoutHtmlTags = getLargestErrorContext(suggestionErrorContext);
    if (largestErrorContextWithoutHtmlTags.contains("</tag") || largestErrorContextWithoutHtmlTags.contains("<tag")) {
      throw new SuggestionNotApplicableException(String.format("Article %s : match skipped : Match string context '%s' can't be stripped off its HTML tags", articleTitle, largestErrorContextWithoutHtmlTags));
    }
    String stringToReplace = getStringToReplace(largestErrorContextWithoutHtmlTags);

    int errorStartPosition = largestErrorContextWithoutHtmlTags.indexOf("<err>");
    int errorEndPosition = largestErrorContextWithoutHtmlTags.indexOf("</err>") - "<err>".length();
    int articleTitleStartPosition = largestErrorContextWithoutHtmlTags.replaceAll("</?err>", "").indexOf(articleTitle);

    if ( articleTitleStartPosition > -1
      && errorStartPosition >= articleTitleStartPosition && (articleTitleStartPosition + articleTitle.length()) >= errorEndPosition
    ) {
      throw new SuggestionNotApplicableException(String.format("Article %s : match skipped : Match string '%s' is included in the article's title", articleTitle, stringToReplace));
    }

    boolean hasMatch = articleWikitext.contains(stringToReplace);
    boolean hasMultipleMatches = hasMatch && articleWikitext.indexOf(stringToReplace) != articleWikitext.lastIndexOf(stringToReplace);

    if (hasMatch) {
      if (hasMultipleMatches) {
        throw new SuggestionNotApplicableException(String.format("Article %s : match skipped : Match string '%s' is found multiple times in the wikitext of its article", articleTitle, stringToReplace));
      }
      else {
        System.out.println(String.format("Article %s : Match string '%s' found in the wikitext of its article", articleTitle, stringToReplace));
        try {
          return largestErrorContextWithoutHtmlTags.replaceAll("<err>.+?</err>", suggestion);
        }
        catch(RuntimeException e) {
          throw new SuggestionNotApplicableException(String.format("Article %s : match skipped : Match string '%s' can't be replaced with '%s'", articleTitle, stringToReplace, suggestion));
        }
      }
    }
    else {
      throw new SuggestionNotApplicableException(String.format("Article %s : match skipped : Match string '%s' can't be found in the wikitext of its article", articleTitle, stringToReplace));
    }
  }

  public static String getArticleWithAppliedSuggestion(String articleTitle, String articleWikitext, String suggestionErrorContext, String suggestion) throws SuggestionNotApplicableException {
    return articleWikitext.replace(
      getStringToReplace(getLargestErrorContext(suggestionErrorContext)),
      getErrorContextWithAppliedSuggestion(articleTitle, articleWikitext, suggestionErrorContext, suggestion)
    );
  }

  public static String getLargestErrorContext(String errorContext) {
    String LARGEST_ERROR_CONTEXT_REGEX = "^.*?([^>]*<err>(?:(?!</err>).)+</err>[^<]*)<?.*$";
    return errorContext.replaceAll(LARGEST_ERROR_CONTEXT_REGEX, "$1");
  }

  public static String getStringToReplace(String largestErrorContextWithoutHtmlTags) {
    return largestErrorContextWithoutHtmlTags.replaceAll("<err>(.+?)</err>", "$1");
  }

  public static AnnotatedText htmlToAnnotatedText(String html) {
    AnnotatedTextBuilder annotatedTextBuilder = new AnnotatedTextBuilder();
    Pattern regex = Pattern.compile("</?tag ?/?>");
    Matcher matcher = regex.matcher(html);
    int position = 0;
    while (matcher.find()) {
      MatchResult result = matcher.toMatchResult();
      String textBefore = html.substring(position, result.start());
      if (!textBefore.isEmpty()) {
        annotatedTextBuilder.addText(textBefore);
      }
      annotatedTextBuilder.addMarkup(result.group());
      position = result.end();
    }
    return annotatedTextBuilder.build();
  }

  public static class HTMLParser {
    public static final DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
    static {
      dbf.setValidating(false);
    }

    private static Long currentArticleId;
    private static Document currentArticleDocument;

    public static Document parseArticle(String articleHtml, Long articleId) {
      if (!articleId.equals(currentArticleId)) {
        DocumentBuilder docBuilder;
        try {
          docBuilder = dbf.newDocumentBuilder();
          currentArticleDocument = docBuilder.parse(new InputSource(new StringReader(articleHtml)));
          currentArticleId = articleId;
        } catch (ParserConfigurationException | SAXException | IOException e) {
          e.printStackTrace();
        }
      }
      return currentArticleDocument;
    }
  }
}
