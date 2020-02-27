package org.languagetool.tools;

import org.w3c.dom.*;
import org.w3c.dom.ls.DOMImplementationLS;
import org.w3c.dom.ls.LSSerializer;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;
import java.util.stream.Collectors;

public class HtmlTools {
  public static class HtmlAnonymizer {
    public static final String DEFAULT_TAG = "tag";

    private long articleId;
    private String title;
    private String wikiText;
    private String originalHtml;
    private String anonymizedHtml;

    private List<HtmlNode> htmlNodes = new ArrayList<>();
    private List<HtmlAttribute> htmlAttributes = new ArrayList<>();

    private Integer lastNodeId;

    public HtmlAnonymizer() { }

    public static HtmlAnonymizer createFromAnonymized(String anonymizedHtml, List<HtmlNode> htmlNodes, List<HtmlAttribute> htmlAttributes) {
      HtmlAnonymizer instance = new HtmlAnonymizer();
      instance.anonymizedHtml = anonymizedHtml;
      instance.htmlNodes = htmlNodes;
      instance.htmlAttributes = htmlAttributes;

      return instance;
    }

    public static HtmlAnonymizer createFromHtml(String title, String wikiText,String html) {
      HtmlAnonymizer instance = new HtmlAnonymizer();
      instance.title = title;
      instance.wikiText = wikiText;
      instance.originalHtml = html;

      return instance;
    }

    public void anonymize() throws ParserConfigurationException, IOException, SAXException {
      DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
      dbf.setValidating(false);
      DocumentBuilder db = dbf.newDocumentBuilder();

      Document doc = db.parse(new InputSource(new StringReader(originalHtml)));

      lastNodeId = null;
      anonymizeNode(doc, doc.getDocumentElement(), null, 0);

      anonymizedHtml = getStringFromDocument(doc);
    }

    private void anonymizeNode(Document doc, Node node, Integer parentId, int childIndex) {
      if (node.getNodeType() != Node.ELEMENT_NODE) {
        return;
      }
      String nodeName = node.getNodeName();
      if (!nodeName.equals(DEFAULT_TAG)) { // This node has not already been handled
        doc.renameNode(node, null, DEFAULT_TAG);
        htmlNodes.add(new HtmlNode(null, null, parentId, childIndex, nodeName));

        NamedNodeMap attributes = node.getAttributes();
        if (attributes != null) {
          while (attributes.getLength() > 0) {
            removeAttribute(node, parentId, childIndex, attributes.item(0));
          }
        }
      }

      lastNodeId = (lastNodeId == null ? 0 : lastNodeId + 1);
      Integer currentNodeId = lastNodeId;

      NodeList childNodes = node.getChildNodes();
      for (int i = 0; i < childNodes.getLength(); i++) {
        anonymizeNode(doc, childNodes.item(i), currentNodeId, i);
      }
    }

    private void removeAttribute(Node element, Integer parentId, Integer childIndex, Node attribute) {
      String attributeName = attribute.getNodeName();
      String attributeValue = attribute.getNodeValue();

      htmlAttributes.add(new HtmlAttribute(null, null, parentId, childIndex, attributeName, attributeValue));
      element.getAttributes().removeNamedItem(attributeName);
    }

    private void deanonymizeNodeName(Node node, Integer parentId, int childIndex, HashMap<Node, String> replacements) {
      if (node.getNodeType() == Node.ELEMENT_NODE) {
        Optional<HtmlNode> currentHtmlNode = htmlNodes.stream().filter(htmlNode ->
          Objects.equals(htmlNode.parentId, parentId) && Objects.equals(htmlNode.childIndex, childIndex)
        ).findFirst();
        if (!currentHtmlNode.isPresent()) {
          throw new InputMismatchException("Couldn't find node with parent id " + parentId + " and child index " + childIndex);
        }
        replacements.put(node, currentHtmlNode.get().tagName);

        lastNodeId = (lastNodeId == null ? 0 : lastNodeId + 1);
        Integer currentNodeId = lastNodeId;

        NodeList childNodes = node.getChildNodes();
        for (int i = 0; i < childNodes.getLength(); i++) {
          deanonymizeNodeName(childNodes.item(i), currentNodeId, i, replacements);
        }
      }
    }

    private void deanonymizeNodeAttributes(Node node, Integer parentId, int childIndex) {
      if (node.getNodeType() == Node.ELEMENT_NODE) {
        List<HtmlAttribute> currentHtmlAttributes = htmlAttributes.stream().filter(htmlAttribute ->
          Objects.equals(htmlAttribute.parentId, parentId) && Objects.equals(htmlAttribute.childIndex, childIndex)
        ).collect(Collectors.toList());

        for (HtmlAttribute htmlAttribute : currentHtmlAttributes) {
          ((Element) node).setAttribute(htmlAttribute.getAttributeName(), htmlAttribute.getAttributeValue());
        }

        lastNodeId = (lastNodeId == null ? 0 : lastNodeId + 1);
        Integer currentNodeId = lastNodeId;

        NodeList childNodes = node.getChildNodes();
        for (int i = 0; i < childNodes.getLength(); i++) {
          deanonymizeNodeAttributes(childNodes.item(i), currentNodeId, i);
        }
      }
    }

    public void deanonymize() throws ParserConfigurationException, IOException, SAXException {
      DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
      dbf.setValidating(false);
      DocumentBuilder db = dbf.newDocumentBuilder();

      Document doc = db.parse(new InputSource(new StringReader(anonymizedHtml)));

      lastNodeId = null;
      deanonymizeNodeAttributes(doc.getDocumentElement(), null, 0);

      HashMap<Node, String> nodeNameReplacements = new HashMap<>();
      lastNodeId = null;
      deanonymizeNodeName(doc.getDocumentElement(), null, 0, nodeNameReplacements);

      for (Node node : nodeNameReplacements.keySet()) {
        doc.renameNode(node, null, nodeNameReplacements.get(node));
      }

      originalHtml = getStringFromDocument(doc);
    }

    public String getTitle() {
      return title;
    }

    public long getArticleId() {
      return articleId;
    }

    public void setArticleId(long articleId) {
      this.articleId = articleId;
    }

    public String getWikitext() {
      return wikiText;
    }

    public String getAnonymizedHtml() {
      return anonymizedHtml;
    }

    public String getOriginalHtml() {
      return originalHtml;
    }

    public List<HtmlNode> getHtmlNodes() {
      return anonymizedHtml == null ? null : htmlNodes;
    }

    public List<HtmlAttribute> getHtmlAttributes() {
      return anonymizedHtml == null ? null : htmlAttributes;
    }

    public static class HtmlNode {
      private Integer id;
      private Integer articleId;
      private Integer parentId;
      private Integer childIndex;
      private String tagName;

      public HtmlNode(Integer id, Integer articleId, Integer parentNodeId, Integer childIndex, String tagName) {
        this.id = id;
        this.articleId = articleId;
        this.parentId = parentNodeId;
        this.childIndex = childIndex;
        this.tagName = tagName;
      }

      public Integer getParentId() {
        return parentId;
      }

      public Integer getChildIndex() {
        return childIndex;
      }

      public String getTagName() {
        return tagName;
      }

      @Override
      public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final HtmlNode other = (HtmlNode) o;
        return Objects.equals(id, other.id) &&
          Objects.equals(parentId, other.parentId) &&
          Objects.equals(childIndex, other.childIndex) &&
          Objects.equals(tagName, other.tagName);
      }

      @Override
      public int hashCode() {
        return Objects.hash(id, parentId, childIndex, tagName);
      }
    }

    public static class HtmlAttribute {
      private Integer id;
      private Integer articleId;
      private Integer parentId;
      private Integer childIndex;
      private String attributeName;
      private String attributeValue;

      public HtmlAttribute(Integer id, Integer articleId, Integer parentId, Integer childIndex, String attributeName, String attributeValue) {
        this.id = id;
        this.articleId = articleId;
        this.parentId = parentId;
        this.childIndex = childIndex;
        this.attributeName = attributeName;
        this.attributeValue = attributeValue;
      }

      public Integer getParentId() {
        return parentId;
      }

      public Integer getChildIndex() {
        return childIndex;
      }

      public String getAttributeName() {
        return attributeName;
      }

      public String getAttributeValue() {
        return attributeValue;
      }

      @Override
      public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HtmlAttribute that = (HtmlAttribute) o;
        return Objects.equals(id, that.id) &&
          parentId.equals(that.parentId) &&
          attributeName.equals(that.attributeName) &&
          attributeValue.equals(that.attributeValue);
      }

      @Override
      public int hashCode() {
        return Objects.hash(id, parentId, attributeName, attributeValue);
      }
    }
  }

  private static String getStringFromDocument(Document doc) {
    DOMImplementationLS domImplementation = (DOMImplementationLS) doc.getImplementation();
    LSSerializer lsSerializer = domImplementation.createLSSerializer();
    return lsSerializer.writeToString(doc);
  }
}
