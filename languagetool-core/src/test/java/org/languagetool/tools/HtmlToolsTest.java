/* LanguageTool, a natural language style checker 
 * Copyright (C) 2015 Daniel Naber (http://www.danielnaber.de)
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
package org.languagetool.tools;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.languagetool.tools.HtmlTools.HtmlAnonymizer.HtmlAttribute;
import org.languagetool.tools.HtmlTools.HtmlAnonymizer.HtmlNode;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.ls.DOMImplementationLS;
import org.w3c.dom.ls.LSSerializer;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.*;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;

import static org.junit.Assert.assertEquals;

@RunWith(Parameterized.class)
public class HtmlToolsTest {
  private static String sourceUri = "https://fr.wikipedia.org/wiki/HTML";

  private String originalHtml;
  private String anonymizedHtml;
  private String deanonymizedHtml;
  private List<HtmlAttribute> anonymizedHtmlAttributes;
  private List<HtmlNode> anonymizedHtmlNodes;

  public HtmlToolsTest(String originalHtml, String anonymizedHtml, String deanonymizedHtml, List<HtmlAttribute> anonymizedHtmlAttributes, List<HtmlNode> anonymizedHtmlNodes) {
    this.originalHtml = originalHtml;
    this.anonymizedHtml = anonymizedHtml;
    this.deanonymizedHtml = deanonymizedHtml;
    this.anonymizedHtmlAttributes = anonymizedHtmlAttributes;
    this.anonymizedHtmlNodes = anonymizedHtmlNodes;
  }

  @Parameterized.Parameters
  public static Collection<Object[]> data() {
    return Arrays.asList(
      new Object[][]{
        {
          "<div a=\"b\">text</div>",
          "<tag>text</tag>",
          "<div a=\"b\">text</div>",
          Collections.singletonList(new HtmlAttribute(null, null, null, 0, "a", "b")),
          Collections.singletonList(new HtmlNode(null, null, null, 0, "div"))
        },
        {
          "<a><b1>text 1</b1><b2>text 2</b2></a>",
          "<tag><tag>text 1</tag><tag>text 2</tag></tag>",
          "<a><b1>text 1</b1><b2>text 2</b2></a>",
          Collections.emptyList(),
          Arrays.asList(
            new HtmlNode(null, null, null, 0, "a"),
            new HtmlNode(null, null, 0, 0, "b1"),
            new HtmlNode(null, null, 0, 1, "b2")
          )
        },
        {
          "<div><!--[if lt IE 9]><script src=\"script.js\"></script><![endif]-->text</div>",
          "<tag>text</tag>",
          "<div>text</div>",
          Collections.emptyList(),
          Collections.singletonList(new HtmlNode(null, null, null, 0, "div"))
        }
      }
    );
  }

  @Test
  public void testAnonymizeRemoveAttributes() throws IOException, ParserConfigurationException, SAXException {
    HtmlTools.HtmlAnonymizer htmlAnonymizer = HtmlTools.HtmlAnonymizer.createFromHtml("title", "", originalHtml);
    htmlAnonymizer.anonymize();

    assertEquals(anonymizedHtml, htmlAnonymizer.getAnonymizedHtml());
    assertEquals(anonymizedHtmlAttributes, htmlAnonymizer.getHtmlAttributes());
    assertEquals(anonymizedHtmlNodes, htmlAnonymizer.getHtmlNodes());
  }

  @Test
  public void testDeanonymizeAddAttributes() throws IOException, SAXException, ParserConfigurationException {
    HtmlTools.HtmlAnonymizer htmlAnonymizer = HtmlTools.HtmlAnonymizer.createFromAnonymized(anonymizedHtml, anonymizedHtmlNodes, anonymizedHtmlAttributes);
    htmlAnonymizer.deanonymize();
    assertEquals(deanonymizedHtml, htmlAnonymizer.getOriginalHtml());
  }
}
