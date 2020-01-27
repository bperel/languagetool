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
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class HtmlToolsTest {
  
  @Test
  public void testRemoveAttributes() throws IOException, ParserConfigurationException, SAXException {
    String html = "<div a='b'>text</div>";

    String sourceUri = "https://fr.wikipedia.org/wiki/HTML";
    HtmlTools.HtmlAnonymizer htmlAnonymizer = new HtmlTools.HtmlAnonymizer(sourceUri, html);
    htmlAnonymizer.anonymize();

    assertEquals(htmlAnonymizer.getAnonymizedHtml(), "<tag>text</tag>");
    assertEquals(htmlAnonymizer.getHtmlAttributes(), Collections.singletonList(new HtmlTools.HtmlAnonymizer.HtmlAttribute(null, 1, "a", "b")));
    assertEquals(htmlAnonymizer.getHtmlNodes(), Collections.singletonList(new HtmlTools.HtmlAnonymizer.HtmlNode(null, sourceUri, "tag", "div")));
  }

//  @Test
//  public void testRemoveComment() throws IOException, ParserConfigurationException, SAXException {
//    String html = "<div><!--[if lt IE 9]><script src=\"script.js\"></script><![endif]-->text</div>";
//
//    String sourceUri = "https://fr.wikipedia.org/wiki/HTML";
//    HtmlTools.HtmlAnonymizer htmlAnonymizer = new HtmlTools.HtmlAnonymizer(sourceUri, html);
//    htmlAnonymizer.anonymize();
//
//    assertEquals(htmlAnonymizer.getAnonymizedHtml(), "<tag>text</tag>");
//    assertEquals(htmlAnonymizer.getHtmlAttributes(), Collections.emptyList());
//    assertEquals(htmlAnonymizer.getHtmlNodes(), Collections.singletonList(new HtmlTools.HtmlAnonymizer.HtmlNode(null, sourceUri, "tag", "div")));
//  }
}
