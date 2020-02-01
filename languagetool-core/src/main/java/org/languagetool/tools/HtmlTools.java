package org.languagetool.tools;

import org.w3c.dom.*;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.*;
import java.util.stream.Collectors;

public class HtmlTools {
  public static class HtmlAnonymizer {
    public static final String DEFAULT_TAG = "tag";

    private String sourceUri;
    private String originalHtml;
    private String anonymizedHtml;

    private Set<HtmlNode> htmlNodes = new HashSet<>();
    private Set<HtmlAttribute> htmlAttributes = new HashSet<>();

    public HtmlAnonymizer() { }

    public static HtmlAnonymizer createFromAnonymized(String sourceUri, String anonymizedHtml, Set<HtmlNode> htmlNodes, Set<HtmlAttribute> htmlAttributes) {
      HtmlAnonymizer instance = new HtmlAnonymizer();
      instance.sourceUri = sourceUri;
      instance.anonymizedHtml = anonymizedHtml;
      instance.htmlNodes = htmlNodes;
      instance.htmlAttributes = htmlAttributes;

      return instance;
    }

    public static HtmlAnonymizer createFromHtml(String sourceUri, String html) {
      HtmlAnonymizer instance = new HtmlAnonymizer();
      instance.sourceUri = sourceUri;
      instance.originalHtml = html;

      return instance;
    }

    public void anonymize() throws ParserConfigurationException, IOException, SAXException {
      DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
      dbf.setValidating(false);
      DocumentBuilder db = dbf.newDocumentBuilder();

      Document doc = db.parse(new InputSource(new StringReader(originalHtml)));

      anonymizeNode(doc, doc.getDocumentElement(), null, 0);

      anonymizedHtml = getStringFromDocument(doc);
    }

    private void anonymizeNode(Document doc, Node node, Integer parentId, int childIndex) {
      switch (node.getNodeType()) {
        case Node.ELEMENT_NODE:
          String nodeName = node.getNodeName();
          if (!nodeName.equals(DEFAULT_TAG)) { // This node has not already been handled
            doc.renameNode(node, null, DEFAULT_TAG);
            htmlNodes.add(new HtmlNode(null, parentId, childIndex, sourceUri, nodeName));

            NamedNodeMap attributes = node.getAttributes();
            while (attributes.getLength() > 0) {
              removeAttribute(node, parentId, childIndex, attributes.item(0));
            }
          }

          int currentNodeId = (parentId == null ? 0 : parentId) + childIndex;
          NodeList childNodes = node.getChildNodes();
          for (int i = 0; i < childNodes.getLength(); i++) {
            anonymizeNode(doc, childNodes.item(i), currentNodeId, i);
          }
        break;
        case Node.COMMENT_NODE:
          node.getParentNode().removeChild(node);
        break;
      }
    }

    private void removeAttribute(Node element, Integer parentId, Integer childIndex, Node attribute) {
      String attributeName = attribute.getNodeName();
      String attributeValue = attribute.getNodeValue();

      htmlAttributes.add(new HtmlAttribute(null, sourceUri, parentId, childIndex, attributeName, attributeValue));
      element.getAttributes().removeNamedItem(attributeName);
    }

    private void deanonymizeNodeName(Node node, Integer parentId, int childIndex, HashMap<Node, String> replacements) {
      if (node.getNodeType() == Node.ELEMENT_NODE) {
        Optional<HtmlNode> currentHtmlNode = htmlNodes.stream().filter(htmlNode ->
          htmlNode.parentId.equals(parentId) && htmlNode.childIndex == childIndex
        ).findFirst();
        if (!currentHtmlNode.isPresent()) {
          throw new InputMismatchException("Couldn't find node with parent id " + parentId + " and child index " + childIndex);
        }
        replacements.put(node, currentHtmlNode.get().tagName);

        int currentNodeId = (parentId == null ? 0 : parentId) + childIndex;
        NodeList childNodes = node.getChildNodes();
        for (int i = 0; i < childNodes.getLength(); i++) {
          deanonymizeNodeName(childNodes.item(i), currentNodeId, i, replacements);
        }
      }
    }

    private void deanonymizeNodeAttributes(Node node, Integer parentId, int childIndex) {
      if (node.getNodeType() == Node.ELEMENT_NODE) {
        List<HtmlAttribute> currentHtmlAttributes = htmlAttributes.stream().filter(htmlAttribute ->
          htmlAttribute.parentId.equals(parentId) && htmlAttribute.childIndex == childIndex
        ).collect(Collectors.toList());

        for (HtmlAttribute htmlAttribute : currentHtmlAttributes) {
          ((Element) node).setAttribute(htmlAttribute.getName(), htmlAttribute.getValue());
        }

        int currentNodeId = (parentId == null ? 0 : parentId) + childIndex;
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

      deanonymizeNodeAttributes(doc.getDocumentElement(), null, 0);

      HashMap<Node, String> nodeNameReplacements = new HashMap<>();
      deanonymizeNodeName(doc.getDocumentElement(), null, 0, nodeNameReplacements);
      for (Node node : nodeNameReplacements.keySet()) {
        doc.renameNode(node, null, nodeNameReplacements.get(node));
      }

      originalHtml = getStringFromDocument(doc);
    }

    public String getAnonymizedHtml() {
      return anonymizedHtml;
    }

    public String getOriginalHtml() {
      return originalHtml;
    }

    public Set<HtmlNode> getHtmlNodes() {
      return anonymizedHtml == null ? null : htmlNodes;
    }

    public Set<HtmlAttribute> getHtmlAttributes() {
      return anonymizedHtml == null ? null : htmlAttributes;
    }

    public static class HtmlNode {
      private Integer id;
      private String sourceUri;
      private Integer parentId;
      private Integer childIndex;
      private String tagName;

      public HtmlNode(Integer id, Integer parentNodeId, Integer childIndex, String sourceUri, String tagName) {
        this.id = id;
        this.sourceUri = sourceUri;
        this.parentId = parentNodeId;
        this.childIndex = childIndex;
        this.tagName = tagName;
      }

      public String getSourceUri() {
        return sourceUri;
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
          Objects.equals(sourceUri, other.sourceUri) &&
          Objects.equals(parentId, other.parentId) &&
          Objects.equals(childIndex, other.childIndex) &&
          Objects.equals(tagName, other.tagName);
      }

      @Override
      public int hashCode() {
        return Objects.hash(id, sourceUri, parentId, childIndex, tagName);
      }
    }

    public static class HtmlAttribute {
      private Integer id;
      private String sourceUri;
      private Integer parentId;
      private Integer childIndex;
      private String name;
      private String value;

      public HtmlAttribute(Integer id, String sourceUri, Integer parentId, Integer childIndex, String name, String value) {
        this.id = id;
        this.sourceUri = sourceUri;
        this.parentId = parentId;
        this.childIndex = childIndex;
        this.name = name;
        this.value = value;
      }

      public String getSourceUri() {
        return sourceUri;
      }

      public Integer getParentId() {
        return parentId;
      }

      public Integer getChildIndex() {
        return childIndex;
      }

      public String getName() {
        return name;
      }

      public String getValue() {
        return value;
      }

      @Override
      public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HtmlAttribute that = (HtmlAttribute) o;
        return Objects.equals(id, that.id) &&
          sourceUri.equals(that.sourceUri) &&
          parentId.equals(that.parentId) &&
          name.equals(that.name) &&
          value.equals(that.value);
      }

      @Override
      public int hashCode() {
        return Objects.hash(id, sourceUri, parentId, name, value);
      }
    }
  }

  private static String getStringFromDocument(Document doc)
  {
    try
    {
      DOMSource domSource = new DOMSource(doc);
      StringWriter writer = new StringWriter();
      StreamResult result = new StreamResult(writer);
      TransformerFactory tf = TransformerFactory.newInstance();
      Transformer transformer = tf.newTransformer();
      transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
      transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
      transformer.transform(domSource, result);
      return writer.toString();
    }
    catch(TransformerException ex)
    {
      ex.printStackTrace();
      return null;
    }
  }
}
