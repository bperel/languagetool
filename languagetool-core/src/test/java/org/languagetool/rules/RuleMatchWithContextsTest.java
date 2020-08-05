/* LanguageTool, a natural language style checker
 * Copyright (C) 2019 Daniel Naber (http://www.danielnaber.de)
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
package org.languagetool.rules;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.languagetool.tools.HtmlTools.SuggestionNotApplicableException;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

public class RuleMatchWithContextsTest {

  @Rule
  public ExpectedException exceptionRule = ExpectedException.none();

  @Test
  public void testTextNotExcludedIfNoMatch() throws Exception {
    callMethod("fr", "<div>text</div>");
  }

  @Test
  public void testTextExcludedOnTextLangMatch() throws Exception {
    exceptionRule.expect(SuggestionNotApplicableException.class);
    exceptionRule.expectMessage("Match ignored because it matches the following path : parts[*].template.target[?(@.wt == 'Langue')]");
    callMethod("fr", "<div data-mw='{\"parts\":[{\"template\":{\"target\":{\"wt\":\"Langue\",\"href\":\"./Modèle:Langue\"}}}]}'>text</div>");
  }

  @Test
  public void testTextExcludedOnArticleLangMatch() throws Exception {
    exceptionRule.expect(SuggestionNotApplicableException.class);
    exceptionRule.expectMessage("Match ignored because it matches the following path : parts[*].template[?(@.target.wt == 'Article')][?(@.params.langue)]");
    callMethod("fr", "<div data-mw='{\"parts\":[{\"template\":{\"target\":{\"wt\":\"Article\",\"href\":\"./Modèle:Article\"},\"params\":{\"langue\":{\"wt\":\"en\"}}}}]}'>text</div>");
  }

  @Test
  public void testTextExcludedOnBookLangMatch() throws Exception {
    exceptionRule.expect(SuggestionNotApplicableException.class);
    exceptionRule.expectMessage("Match ignored because it matches the following path : parts[*].template[?(@.target.wt == 'Ouvrage')][?(@.params.langue)]");
    callMethod("fr", "<div data-mw='{\"parts\":[{\"template\":{\"target\":{\"wt\":\"Ouvrage\",\"href\":\"./Modèle:Ouvrage\"},\"params\":{\"langue\":{\"wt\":\"en\"}}}}]}'>text</div>");
  }

  @Test
  public void testTextExcludedInEditLinkMatch() throws Exception {
    exceptionRule.expect(SuggestionNotApplicableException.class);
    exceptionRule.expectMessage("Match ignored because it is part of an 'edit' link");
    callMethod("fr", "<a href='index.php?title=Wikipedia&amp;action=edit'>text</a>");
  }

  private void callMethod(String languageCode, String text) throws ParserConfigurationException, SuggestionNotApplicableException, IOException, SAXException {
    InputStream inputStream = new ByteArrayInputStream(text.getBytes());

    DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
    dbf.setValidating(false);
    Document document = dbf.newDocumentBuilder().parse(inputStream);
    Node node = document.getFirstChild();

    RuleMatchWithContexts.languageCode = languageCode;
    RuleMatchWithContexts.assertNodeNotExcluded(node);
  }

}
