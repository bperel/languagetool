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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Stack;

public class HtmlTools {
  public static class HtmlAnonymizer {
    public static final String DEFAULT_TAG = "tag";

    private String sourceUri;
    private String html;
    private String anonymizedHtml;

    private List<HtmlNode> htmlNodes = new ArrayList<>();
    private List<HtmlAttribute> htmlAttributes = new ArrayList<>();

    public HtmlAnonymizer(String sourceUri, String html) {
      this.sourceUri = sourceUri;
      this.html = html;
    }

    public void anonymize() throws ParserConfigurationException, IOException, SAXException {
      DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
      dbf.setValidating(false);
      DocumentBuilder db = dbf.newDocumentBuilder();

      Document doc = db.parse(new InputSource(new StringReader(html)));
      NodeList nodeList = doc.getElementsByTagName("*");
      for (int i = 0; i < nodeList.getLength(); i++) {
        Node node = nodeList.item(i);
        switch (node.getNodeType()) {
          case Node.ELEMENT_NODE:
            anonymizeNode(doc, node);
            break;
          case Node.COMMENT_NODE:
            doc.removeChild(node);
        }

        NamedNodeMap attributes = node.getAttributes();
        while (attributes.getLength() > 0) {
          removeAttribute(node, attributes.item(0));
        }
      }
      anonymizedHtml = getStringFromDocument(doc);
    }

    private void anonymizeNode(Document doc, Node node) {
      String nodeName = node.getNodeName();
      doc.renameNode(node, null, DEFAULT_TAG);
      htmlNodes.add(new HtmlNode(null, sourceUri, getFullXPath(node), nodeName));
    }

    private void removeAttribute(Node element, Node attribute) {
      String attributeName = attribute.getNodeName();
      String attributeValue = attribute.getNodeValue();

      htmlAttributes.add(new HtmlAttribute(null, sourceUri, getFullXPath(element), attributeName, attributeValue));
      element.getAttributes().removeNamedItem(attributeName);

    }

    public void deanonymize() {
      // TODO
    }

    public String getAnonymizedHtml() {
      return anonymizedHtml;
    }

    public List<HtmlNode> getHtmlNodes() {
      return anonymizedHtml == null ? null : htmlNodes;
    }

    public List<HtmlAttribute> getHtmlAttributes() {
      return anonymizedHtml == null ? null : htmlAttributes;
    }

    public static class HtmlNode {
      private Integer id;
      private String sourceUri;
      private String xpath;
      private String tagName;

      public HtmlNode(Integer id, String sourceUri, String xpath, String tagName) {
        this.id = id;
        this.sourceUri = sourceUri;
        this.xpath = xpath;
        this.tagName = tagName;
      }

      public String getSourceUri() {
        return sourceUri;
      }

      public String getXpath() {
        return xpath;
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
          Objects.equals(xpath, other.xpath) &&
          Objects.equals(tagName, other.tagName);
      }

      @Override
      public int hashCode() {
        return Objects.hash(id, sourceUri, xpath, tagName);
      }
    }

    public static class HtmlAttribute {
      private Integer id;
      private String sourceUri;
      private String xpath;
      private String name;
      private String value;

      public HtmlAttribute(Integer id, String sourceUri, String xpath, String name, String value) {
        this.id = id;
        this.sourceUri = sourceUri;
        this.xpath = xpath;
        this.name = name;
        this.value = value;
      }

      public String getSourceUri() {
        return sourceUri;
      }

      public String getXpath() {
        return xpath;
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
          xpath.equals(that.xpath) &&
          name.equals(that.name) &&
          value.equals(that.value);
      }

      @Override
      public int hashCode() {
        return Objects.hash(id, sourceUri, xpath, name, value);
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

  // https://lekkimworld.com/2007/06/19/building-xpath-expression-from-xml-node/
  public static String getFullXPath(Node n) {
    Node parent;
    Stack<Node> hierarchy = new Stack<>();
    StringBuilder buffer = new StringBuilder();

    hierarchy.push(n);

    switch (n.getNodeType()) {
      case Node.ATTRIBUTE_NODE:
        parent = ((Attr) n).getOwnerElement();
        break;
      case Node.ELEMENT_NODE:
      case Node.DOCUMENT_NODE:
        parent = n.getParentNode();
        break;
      default:
        throw new IllegalStateException("Unexpected Node type" + n.getNodeType());
    }

    while (null != parent && parent.getNodeType() != Node.DOCUMENT_NODE) {
      hierarchy.push(parent);
      parent = parent.getParentNode();
    }

    Node obj;
    while (!hierarchy.isEmpty() && null != (obj = hierarchy.pop())) {
      boolean handled = false;

      if (obj.getNodeType() == Node.ELEMENT_NODE) {
        Element e = (Element) obj;

        if (buffer.length() == 0) {
          buffer.append(obj.getNodeName());
        } else {
          buffer.append("/").append(obj.getNodeName());

          if (obj.hasAttributes()) {
            if (e.hasAttribute("id")) {
              buffer.append("[@id='").append(e.getAttribute("id")).append("']");
              handled = true;
            } else if (e.hasAttribute("name")) {
              buffer.append("[@name='").append(e.getAttribute("name")).append("']");
              handled = true;
            }
          }

          if (!handled) {
            int prev_siblings = 1;
            Node prev_sibling = obj.getPreviousSibling();
            while (null != prev_sibling) {
              if (prev_sibling.getNodeType() == obj.getNodeType()) {
                if (prev_sibling.getNodeName().equalsIgnoreCase(
                  obj.getNodeName())) {
                  prev_siblings++;
                }
              }
              prev_sibling = prev_sibling.getPreviousSibling();
            }
            buffer.append("[").append(prev_siblings).append("]");
          }
        }
      } else if (obj.getNodeType() == Node.ATTRIBUTE_NODE) {
        buffer.append("/@");
        buffer.append(obj.getNodeName());
      }
    }
    return buffer.toString();
  }
}
