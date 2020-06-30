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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class HtmlToolsTest {

  @Test
  public void testGetArticleWithAppliedSuggestion() throws HtmlTools.SuggestionNotApplicableException {
    String wikiText = "avec la ''monarchie de Habsbourg'' et la ''Confédération germanique''.";
    String errorContext = "la <tag>monarchie <err>de Habsbourg</err></tag> et la <tag>Confédération germanique</tag>.";
    String suggestion = "d'Habsbourg";
    String replacedText = HtmlTools.getArticleWithAppliedSuggestion("", wikiText, errorContext, suggestion);

    assertEquals("avec la ''monarchie d'Habsbourg'' et la ''Confédération germanique''.", replacedText);
  }

  @Test
  public void testGetArticleWithAppliedSuggestionWithTagBeforeErrorContext() throws HtmlTools.SuggestionNotApplicableException {
    String wikiText = "avec la ''monarchie'' de Habsbourg et la Confédération germanique.";
    String errorContext = "la <tag>monarchie</tag><err> de Habsbourg</err> et la Confédération germanique.";
    String suggestion = " d'Habsbourg";
    String replacedText = HtmlTools.getArticleWithAppliedSuggestion("", wikiText, errorContext, suggestion);

    assertEquals("avec la ''monarchie'' d'Habsbourg et la Confédération germanique.", replacedText);
  }

  @Test
  public void testGetArticleWithAppliedSuggestionThrowIfArticleTitleInMatch() {
    String errorContext = "Le <tag>programme</tag> Hello <err>World</err>.";
    String suggestion = "Word";

    try {
      HtmlTools.getArticleWithAppliedSuggestion("Hello World", "", errorContext, suggestion);
      fail("Should have thrown SuggestionNotApplicableException");
    }
    catch (HtmlTools.SuggestionNotApplicableException e) {
      assertEquals("Article Hello World : match skipped : Match string ' Hello World.' is included in the article's title", e.getMessage());
    }
  }

  @Test
  public void testGetArticleWithAppliedSuggestionThrowIfArticleTitleIsMatch() {
    String errorContext = "Le <tag>programme</tag> <err>Hello World</err>.";
    String suggestion = "Salut Word";

    try {
      HtmlTools.getArticleWithAppliedSuggestion("Hello World", "", errorContext, suggestion);
      fail("Should have thrown SuggestionNotApplicableException");
    }
    catch (HtmlTools.SuggestionNotApplicableException e) {
      assertEquals("Article Hello World : match skipped : Match string ' Hello World.' is included in the article's title", e.getMessage());
    }
  }
}
