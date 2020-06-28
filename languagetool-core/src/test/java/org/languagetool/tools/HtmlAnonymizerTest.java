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
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;

import static org.junit.Assert.assertEquals;

@RunWith(Parameterized.class)
public class HtmlAnonymizerTest {
  private final String originalHtml;
  private final String anonymizedHtml;

  public HtmlAnonymizerTest(String originalHtml, String anonymizedHtml) {
    this.originalHtml = originalHtml;
    this.anonymizedHtml = anonymizedHtml;
  }

  @Parameterized.Parameters
  public static Collection<Object[]> data() {
    return Arrays.asList(
      new Object[][]{
        {
          "<div a=\"b\">text</div>",
          "<tag>text</tag>"
        },
        {
          "<a><b1>text 1</b1><b2>text 2</b2></a>",
          "<tag><tag>text 1</tag><tag>text 2</tag></tag>"
        }
      }
    );
  }

  @Test
  public void testAnonymizeRemoveAttributes() throws IOException, ParserConfigurationException, SAXException {
    HtmlTools.HtmlAnonymizer htmlAnonymizer = HtmlTools.HtmlAnonymizer.createFromHtml("title", "", originalHtml);
    htmlAnonymizer.anonymize();

    assertEquals(anonymizedHtml, htmlAnonymizer.getAnonymizedHtml());
  }
}
