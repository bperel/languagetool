/* LanguageTool, a natural language style checker 
 * Copyright (C) 2011 Daniel Naber (http://www.danielnaber.de)
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
package org.languagetool.dev.wikipedia;

import org.junit.Test;
import org.languagetool.language.GermanyGerman;
import org.languagetool.tools.HtmlTools;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.List;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.*;

public class WikipediaQuickCheckTest {

  // only for interactive use, as it accesses a remote API
  public void noTestCheckPage() throws IOException, PageNotFoundException {
    WikipediaQuickCheck check = new WikipediaQuickCheck();
    //String url = "http://de.wikipedia.org/wiki/Benutzer_Diskussion:Dnaber";
    //String url = "http://de.wikipedia.org/wiki/OpenThesaurus";
    //String url = "http://de.wikipedia.org/wiki/Gütersloh";
    //String url = "http://de.wikipedia.org/wiki/Bielefeld";
    String url = "https://de.wikipedia.org/wiki/Augsburg";
    MarkupAwareWikipediaResult result = check.checkPage(new URL(url));
    List<AppliedRuleMatch> appliedMatches = result.getAppliedRuleMatches();
    System.out.println("ruleApplications: " + appliedMatches.size());
    for (AppliedRuleMatch appliedMatch : appliedMatches) {
      System.out.println("=====");
      System.out.println("Rule     : " + appliedMatch.getRuleMatch().getRule().getDescription() + "\n");
      for (RuleMatchApplication ruleMatchApplication : appliedMatch.getRuleMatchApplications()) {
        System.out.println("Original : " + ruleMatchApplication.getOriginalErrorContext(10).replace("\n", " "));
        if (ruleMatchApplication.hasRealReplacement()) {
          System.out.println("Corrected: " + ruleMatchApplication.getCorrectedErrorContext(10).replace("\n", " "));
        }
        System.out.println();
      }
    }
  }

  @Test
  public void testCheckWikipediaMarkup() throws IOException {
    WikipediaQuickCheck check = new WikipediaQuickCheck();
    String markup = "== Beispiele ==\n\n" +
            "Eine kleine Auswahl von Fehlern.\n\n" +
            "Das Komma ist richtig, wegen dem Leerzeichen.";
    MediaWikiContent wikiContent = new MediaWikiContent(markup, "2012-11-11T20:00:00");
    ErrorMarker errorMarker = new ErrorMarker("<err>", "</err>");
    MarkupAwareWikipediaResult result = check.checkWikipediaMarkup(new URL("http://fake-url.org"), wikiContent, new GermanyGerman(), errorMarker);
    assertThat(result.getLastEditTimestamp(), is("2012-11-11T20:00:00"));
    List<AppliedRuleMatch> appliedMatches = result.getAppliedRuleMatches();
    // even though this error has no suggestion, there's a (pseudo) correction:
    assertThat(appliedMatches.size(), is(1));
    AppliedRuleMatch firstAppliedMatch = appliedMatches.get(0);
    assertThat(firstAppliedMatch.getRuleMatchApplications().size(), is(1));
    RuleMatchApplication ruleMatchApplication = firstAppliedMatch.getRuleMatchApplications().get(0);
    assertTrue("Got: " + ruleMatchApplication.getTextWithCorrection(),
            ruleMatchApplication.getTextWithCorrection().contains("<err>wegen dem</err> Leerzeichen."));
    assertThat(ruleMatchApplication.getOriginalErrorContext(12), is("st richtig, <err>wegen dem</err> Leerz"));
    assertThat(ruleMatchApplication.getCorrectedErrorContext(12), is("st richtig, <err>wegen dem</err> Leerz"));
  }

  @Test
  public void testGetPlainText() {
    WikipediaQuickCheck check = new WikipediaQuickCheck();
    String filteredContent = check.getPlainText(
            "<?xml version=\"1.0\"?><api><query><normalized><n from=\"Benutzer_Diskussion:Dnaber\" to=\"Benutzer Diskussion:Dnaber\" />" +
                    "</normalized><pages><page pageid=\"143424\" ns=\"3\" title=\"Benutzer Diskussion:Dnaber\"><revisions><rev xml:space=\"preserve\">\n" +
                    "Test [[Link]] Foo&amp;nbsp;bar.\n" +
                    "</rev></revisions></page></pages></query></api>");
    assertEquals("Test Link Foo\u00A0bar.", filteredContent);
  }

  @Test
  public void testGetPlainTextMapping() {
    WikipediaQuickCheck check = new WikipediaQuickCheck();
    String text = "Test [[Link]] und [[AnotherLink|noch einer]] und [http://test.org external link] Foo&amp;nbsp;bar.\n";
    PlainTextMapping filteredContent = check.getPlainTextMapping(
            "<?xml version=\"1.0\"?><api><query><normalized><n from=\"Benutzer_Diskussion:Dnaber\" to=\"Benutzer Diskussion:Dnaber\" />" +
                    "</normalized><pages><page pageid=\"143424\" ns=\"3\" title=\"Benutzer Diskussion:Dnaber\"><revisions><rev xml:space=\"preserve\">" +
                    text +
                    "</rev></revisions></page></pages></query></api>");

    assertEquals("Test Link und noch einer und external link Foo\u00A0bar.", filteredContent.getPlainText());
    assertEquals(1, filteredContent.getOriginalTextPositionFor(1).line);
    assertEquals(1, filteredContent.getOriginalTextPositionFor(1).column);
    assertEquals(filteredContent.getPlainText().charAt(0), text.charAt(0));

    assertEquals('u', text.charAt(14));  // note that these are zero-based, the others are not
    assertEquals('u', filteredContent.getPlainText().charAt(10));
    assertEquals(1, filteredContent.getOriginalTextPositionFor(11).line);
    assertEquals(15, filteredContent.getOriginalTextPositionFor(11).column);
  }

  @Test
  public void testGetPlainTextMappingMultiLine1() {
    WikipediaQuickCheck check = new WikipediaQuickCheck();
    String text = "Test [[Link]] und [[AnotherLink|noch einer]].\nUnd [[NextLink]] Foobar.\n";
    PlainTextMapping filteredContent = check.getPlainTextMapping(
            "<?xml version=\"1.0\"?><api><query><normalized><n from=\"Benutzer_Diskussion:Dnaber\" to=\"Benutzer Diskussion:Dnaber\" />" +
                    "</normalized><pages><page pageid=\"143424\" ns=\"3\" title=\"Benutzer Diskussion:Dnaber\"><revisions><rev xml:space=\"preserve\">" +
                    text +
                    "</rev></revisions></page></pages></query></api>");
    assertEquals("Test Link und noch einer. Und NextLink Foobar.", filteredContent.getPlainText());
    assertEquals(1, filteredContent.getOriginalTextPositionFor(1).line);
    assertEquals(1, filteredContent.getOriginalTextPositionFor(1).column);
    assertEquals(filteredContent.getPlainText().charAt(0), text.charAt(0));

    assertEquals('U', text.charAt(46));  // note that these are zero-based, the others are not
    assertEquals(' ', filteredContent.getPlainText().charAt(25));
    assertEquals('U', filteredContent.getPlainText().charAt(26));
    assertEquals(2, filteredContent.getOriginalTextPositionFor(27).line);

    assertEquals(45, filteredContent.getOriginalTextPositionFor(25).column);
    assertEquals(1, filteredContent.getOriginalTextPositionFor(26).column);
    assertEquals(2, filteredContent.getOriginalTextPositionFor(27).column);
  }

  @Test
  public void testGetPlainTextMappingMultiLine2() {
    WikipediaQuickCheck check = new WikipediaQuickCheck();
    String text = "Test [[Link]] und [[AnotherLink|noch einer]].\n\nUnd [[NextLink]] Foobar.\n";
    PlainTextMapping filteredContent = check.getPlainTextMapping(
            "<?xml version=\"1.0\"?><api><query><normalized><n from=\"Benutzer_Diskussion:Dnaber\" to=\"Benutzer Diskussion:Dnaber\" />" +
                    "</normalized><pages><page pageid=\"143424\" ns=\"3\" title=\"Benutzer Diskussion:Dnaber\"><revisions><rev xml:space=\"preserve\">" +
                    text +
                    "</rev></revisions></page></pages></query></api>");
    assertEquals("Test Link und noch einer.\n\nUnd NextLink Foobar.", filteredContent.getPlainText());
    assertEquals(1, filteredContent.getOriginalTextPositionFor(1).line);
    assertEquals(1, filteredContent.getOriginalTextPositionFor(1).column);
    assertEquals(filteredContent.getPlainText().charAt(0), text.charAt(0));

    assertEquals('U', text.charAt(47));  // note that these are zero-based, the others are not
    assertEquals('U', filteredContent.getPlainText().charAt(27));
    assertEquals(3, filteredContent.getOriginalTextPositionFor(28).line);
    assertEquals(45, filteredContent.getOriginalTextPositionFor(25).column);
    assertEquals(46, filteredContent.getOriginalTextPositionFor(26).column);
    assertEquals(47, filteredContent.getOriginalTextPositionFor(27).column);
    assertEquals(1, filteredContent.getOriginalTextPositionFor(28).column);
  }

  @Test
  public void testRemoveInterLanguageLinks() {
    WikipediaQuickCheck check = new WikipediaQuickCheck();
    assertEquals("foo  bar", check.removeWikipediaLinks("foo [[pt:Some Article]] bar"));
    assertEquals("foo [[some link]] bar", check.removeWikipediaLinks("foo [[some link]] bar"));
    assertEquals("foo [[Some Link]] bar ", check.removeWikipediaLinks("foo [[Some Link]] bar [[pt:Some Article]]"));
    assertEquals("foo [[zh-min-nan:Linux]] bar", check.removeWikipediaLinks("foo [[zh-min-nan:Linux]] bar"));  // known limitation
    assertEquals("[[Scultura bronzea di Gaudí mentre osserva il suo ''[[Il Capriccio|Capriccio]]'']]", check.removeWikipediaLinks("[[File:Gaudì-capriccio.JPG|thumb|left|Scultura bronzea di Gaudí mentre osserva il suo ''[[Il Capriccio|Capriccio]]'']]"));
    assertEquals("[[[[Palau de la Música Catalana]], entrada]]", check.removeWikipediaLinks("[[Fitxer:Palau_de_musica_2.JPG|thumb|[[Palau de la Música Catalana]], entrada]]"));
    assertEquals("foo  bar", check.removeWikipediaLinks("foo [[Kategorie:Kurgebäude]] bar"));
    assertEquals("foo [[''Kursaal Palace'' in San Sebastián]] bar", check.removeWikipediaLinks("foo [[Datei:FestivalSS.jpg|miniatur|''Kursaal Palace'' in San Sebastián]] bar"));
    assertEquals("[[Yupana, emprat pels [[Inques]].]]", check.removeWikipediaLinks("[[Fitxer:Yupana 1.GIF|thumb|Yupana, emprat pels [[Inques]].]]"));
  }

  @Test
  public void testExpandTemplates() throws IOException, ParserConfigurationException, SAXException {

    URL page = new URL("http://localhost:8024/wikipedia_fr/v3/page/html/Utilisateur:Nonoxb/101156998");
    BufferedReader in = new BufferedReader(
      new InputStreamReader(page.openStream()));

    String inputLine;
    StringBuilder content = new StringBuilder();
    while ((inputLine = in.readLine()) != null)
      content.append(inputLine);
    in.close();

    String html = content.toString();
//    String html = "<!DOCTYPE html><html prefix=\"dc: http://purl.org/dc/terms/ mw: http://mediawiki.org/rdf/\" about=\"http://fr.wikipedia.org/wiki/Special:Redirect/revision/101156998\"><head prefix=\"mwr: http://fr.wikipedia.org/wiki/Special:Redirect/\"><meta charset=\"utf-8\"/><meta property=\"mw:pageNamespace\" content=\"2\"/><meta property=\"mw:pageId\" content=\"4956291\"/><link rel=\"dc:replaces\" resource=\"mwr:revision/101156970\"/><meta property=\"dc:modified\" content=\"2014-02-11T09:22:41.000Z\"/><meta property=\"mw:revisionSHA1\" content=\"95d9700289bdeb2456d2e61b2fab0b83b66fd8e0\"/><meta property=\"mw:html:version\" content=\"2.0.0\"/><link rel=\"dc:isVersionOf\" href=\"//fr.wikipedia.org/wiki/Utilisateur%3ANonoxb\"/><title>Utilisateur:Nonoxb</title><base href=\"//fr.wikipedia.org/wiki/\"/><link rel=\"stylesheet\" href=\"//fr.wikipedia.org/w/load.php?modules=mediawiki.legacy.commonPrint%2Cshared%7Cmediawiki.skinning.content.parsoid%7Cmediawiki.skinning.interface%7Cskins.vector.styles%7Csite.styles%7Cext.cite.style%7Cext.cite.styles%7Cmediawiki.page.gallery.styles&amp;only=styles&amp;skin=vector\"/><!--[if lt IE 9]><script src=\"//fr.wikipedia.org/w/load.php?modules=html5shiv&amp;only=scripts&amp;skin=vector&amp;sync=1\"></script><script>html5.addElements('figure-inline');</script><![endif]--><meta http-equiv=\"content-language\" content=\"fr\"/><meta http-equiv=\"vary\" content=\"Accept\"/></head><body data-parsoid='{\"dsr\":[0,2904,0,0]}' lang=\"fr\" class=\"mw-content-ltr sitedir-ltr ltr mw-body-content parsoid-body mediawiki mw-parser-output\" dir=\"ltr\"><section data-mw-section-id=\"0\" data-parsoid=\"{}\"><p data-parsoid='{\"dsr\":[0,17,0,0]}'>Bien le bonjour !</p></section><section data-mw-section-id=\"1\" data-parsoid=\"{}\"><h2 id=\"Qui_je_suis\" data-parsoid='{\"dsr\":[18,35,2,2]}'>Qui je suis</h2><span about=\"#mwt1\" typeof=\"mw:Transclusion\" data-parsoid='{\"dsr\":[36,70,null,null],\"pi\":[[{\"k\":\"texte\",\"named\":true}]]}' data-mw='{\"parts\":[{\"template\":{\"target\":{\"wt\":\"BUdébut\",\"href\":\"./Modèle:BUdébut\"},\"params\":{\"texte\":{\"wt\":\"&apos;&apos;&apos;Brunoperel&apos;&apos;&apos;\"}},\"i\":0}}]}'></span><span about=\"#mwt2\" typeof=\"mw:Transclusion\" data-parsoid='{\"dsr\":[71,100,null,null],\"pi\":[[]]}' data-mw='{\"parts\":[{\"template\":{\"target\":{\"wt\":\"Utilisateur habite France\",\"href\":\"./Modèle:Utilisateur_habite_France\"},\"params\":{},\"i\":0}}]}'></span>  <span about=\"#mwt3\" typeof=\"mw:Transclusion\" data-parsoid='{\"dsr\":[103,121,null,null],\"pi\":[[]]}' data-mw='{\"parts\":[{\"template\":{\"target\":{\"wt\":\"Utilisateur fr\",\"href\":\"./Modèle:Utilisateur_fr\"},\"params\":{},\"i\":0}}]}'></span><span about=\"#mwt4\" typeof=\"mw:Transclusion\" data-parsoid='{\"dsr\":[122,142,null,null],\"pi\":[[]]}' data-mw='{\"parts\":[{\"template\":{\"target\":{\"wt\":\"Utilisateur en-3\",\"href\":\"./Modèle:Utilisateur_en-3\"},\"params\":{},\"i\":0}}]}'></span><span about=\"#mwt5\" typeof=\"mw:Transclusion\" data-parsoid='{\"dsr\":[143,176,null,null],\"pi\":[[]]}' data-mw='{\"parts\":[{\"template\":{\"target\":{\"wt\":\"Utilisateur ni Dieu ni Maitre\",\"href\":\"./Modèle:Utilisateur_ni_Dieu_ni_Maitre\"},\"params\":{},\"i\":0}}]}'></span><span about=\"#mwt6\" typeof=\"mw:Transclusion\" data-parsoid='{\"dsr\":[177,200,null,null],\"pi\":[[]]}' data-mw='{\"parts\":[{\"template\":{\"target\":{\"wt\":\"Utilisateur artiste\",\"href\":\"./Modèle:Utilisateur_artiste\"},\"params\":{},\"i\":0}}]}'></span><span about=\"#mwt7\" typeof=\"mw:Transclusion\" data-parsoid='{\"dsr\":[201,221,null,null],\"pi\":[[]]}' data-mw='{\"parts\":[{\"template\":{\"target\":{\"wt\":\"Utilisateur chat\",\"href\":\"./Modèle:Utilisateur_chat\"},\"params\":{},\"i\":0}}]}'></span><span about=\"#mwt8\" typeof=\"mw:Transclusion\" data-parsoid='{\"dsr\":[222,247,null,null],\"pi\":[[]]}' data-mw='{\"parts\":[{\"template\":{\"target\":{\"wt\":\"Utilisateur WikiGnome\",\"href\":\"./Modèle:Utilisateur_WikiGnome\"},\"params\":{},\"i\":0}}]}'></span><span about=\"#mwt9\" typeof=\"mw:Transclusion\" data-parsoid='{\"dsr\":[248,269,null,null],\"pi\":[[]]}' data-mw='{\"parts\":[{\"template\":{\"target\":{\"wt\":\"Utilisateur BOINC\",\"href\":\"./Modèle:Utilisateur_BOINC\"},\"params\":{},\"i\":0}}]}'></span><span about=\"#mwt10\" typeof=\"mw:Transclusion\" data-parsoid='{\"dsr\":[270,289,null,null],\"pi\":[[]]}' data-mw='{\"parts\":[{\"template\":{\"target\":{\"wt\":\"Utilisateur VLC\",\"href\":\"./Modèle:Utilisateur_VLC\"},\"params\":{},\"i\":0}}]}'></span><span about=\"#mwt11\" typeof=\"mw:Transclusion\" data-parsoid='{\"dsr\":[290,316,null,null],\"pi\":[[]]}' data-mw='{\"parts\":[{\"template\":{\"target\":{\"wt\":\"Utilisateur JavaScript\",\"href\":\"./Modèle:Utilisateur_JavaScript\"},\"params\":{},\"i\":0}}]}'></span><span about=\"#mwt12\" typeof=\"mw:Transclusion\" data-parsoid='{\"dsr\":[317,336,null,null],\"pi\":[[]]}' data-mw='{\"parts\":[{\"template\":{\"target\":{\"wt\":\"Utilisateur PHP\",\"href\":\"./Modèle:Utilisateur_PHP\"},\"params\":{},\"i\":0}}]}'></span><span about=\"#mwt13\" typeof=\"mw:Transclusion\" data-parsoid='{\"dsr\":[337,357,null,null],\"pi\":[[]]}' data-mw='{\"parts\":[{\"template\":{\"target\":{\"wt\":\"Utilisateur Java\",\"href\":\"./Modèle:Utilisateur_Java\"},\"params\":{},\"i\":0}}]}'></span><span about=\"#mwt14\" typeof=\"mw:Transclusion\" data-parsoid='{\"dsr\":[358,413,null,null],\"pi\":[[{\"k\":\"année\",\"named\":true},{\"k\":\"mois\",\"named\":true},{\"k\":\"jour\",\"named\":true}]]}' data-mw='{\"parts\":[{\"template\":{\"target\":{\"wt\":\"Utilisateur Wikipédia:Date\",\"href\":\"./Modèle:Utilisateur_Wikipédia:Date\"},\"params\":{\"année\":{\"wt\":\"2010\"},\"mois\":{\"wt\":\"1\"},\"jour\":{\"wt\":\"7\"}},\"i\":0}}]}'></span><span about=\"#mwt15\" typeof=\"mw:Transclusion\" data-parsoid='{\"dsr\":[414,447,null,null],\"pi\":[[{\"k\":\"1\"}]]}' data-mw='{\"parts\":[{\"template\":{\"target\":{\"wt\":\"Utilisateur Contributions\",\"href\":\"./Modèle:Utilisateur_Contributions\"},\"params\":{\"1\":{\"wt\":\"100\"}},\"i\":0}}]}'></span><span about=\"#mwt16\" typeof=\"mw:Transclusion\" data-parsoid='{\"dsr\":[448,671,null,null],\"pi\":[[{\"k\":\"couleur\",\"named\":true,\"spc\":[\"\",\"\",\"\",\"\n  \"]},{\"k\":\"img\",\"named\":true,\"spc\":[\"\",\"\",\"\",\"\n  \"]},{\"k\":\"img-taille\",\"named\":true,\"spc\":[\"\",\"\",\"\",\"\n  \"]},{\"k\":\"titre\",\"named\":true,\"spc\":[\"\",\"\",\"\",\"\n  \"]},{\"k\":\"texte\",\"named\":true,\"spc\":[\"\",\"\",\"\",\"\n\"]}]]}' data-mw='{\"parts\":[{\"template\":{\"target\":{\"wt\":\"BUtilisateur\n  \",\"href\":\"./Modèle:BUtilisateur\"},\"params\":{\"couleur\":{\"wt\":\"#D0E9FF\"},\"img\":{\"wt\":\"Wikisource-logo.svg\"},\"img-taille\":{\"wt\":\"33px\"},\"titre\":{\"wt\":\"Wikisource\"},\"texte\":{\"wt\":\"&apos;&apos;&apos;[[:s:User:Nonox|Je]]&apos;&apos;&apos; [[:s:Special:Contributions/Nonox|contribue]] également sur &apos;&apos;&apos;[[:s:|Wikisource]]&apos;&apos;&apos;.\"}},\"i\":0}}]}'></span><span about=\"#mwt17\" typeof=\"mw:Transclusion\" data-parsoid='{\"dsr\":[672,954,null,null],\"pi\":[[{\"k\":\"couleur\",\"named\":true,\"spc\":[\"\",\"\",\"\",\"\n  \"]},{\"k\":\"img-taille\",\"named\":true,\"spc\":[\"\",\"\",\"\",\"\n  \"]},{\"k\":\"titre\",\"named\":true,\"spc\":[\"\",\"\",\"\",\"\n  \"]},{\"k\":\"texte\",\"named\":true,\"spc\":[\"\",\"\",\"\",\"\n\"]}]]}' data-mw='{\"parts\":[{\"template\":{\"target\":{\"wt\":\"BUtilisateur\n  \",\"href\":\"./Modèle:BUtilisateur\"},\"params\":{\"couleur\":{\"wt\":\"#D0E9FF\"},\"img-taille\":{\"wt\":\"33px\"},\"titre\":{\"wt\":\"Translate Wiki\"},\"texte\":{\"wt\":\"&apos;&apos;&apos;[http://translatewiki.net/wiki/User:Brunoperel Je]&apos;&apos;&apos; [http://translatewiki.net/wiki/Special:Contributions/Brunoperel contribue] également sur &apos;&apos;&apos;[http://translatewiki.net TranslateWiki]&apos;&apos;&apos;.\"}},\"i\":0}}]}'></span><span about=\"#mwt18\" typeof=\"mw:Transclusion\" data-parsoid='{\"dsr\":[955,964,null,null],\"pi\":[[]]}' data-mw='{\"parts\":[{\"template\":{\"target\":{\"wt\":\"BUfin\",\"href\":\"./Modèle:BUfin\"},\"params\":{},\"i\":0}}]}'></span><ul data-parsoid='{\"dsr\":[965,1247,0,0]}'><li data-parsoid='{\"dsr\":[965,1006,1,0]}'>Ingénieur en développement informatique</li><li data-parsoid='{\"dsr\":[1007,1077,1,0]}'>Principalement <a rel=\"mw:WikiLink\" href=\"./Wikipédia:WikiGnome\" title=\"Wikipédia:WikiGnome\" data-parsoid='{\"stx\":\"piped\",\"a\":{\"href\":\"./Wikipédia:WikiGnome\"},\"sa\":{\"href\":\"Wikipédia:WikiGnome\"},\"dsr\":[1024,1057,22,2]}'>WikiGnome</a> sur l'encyclopédie.</li><li data-parsoid='{\"dsr\":[1078,1134,1,0]}'>Je m'occupe aussi de l'accueil des nouveaux arrivants.</li><li data-parsoid='{\"dsr\":[1135,1247,1,0]}'>Je participe plus à <a rel=\"mw:ExtLink\" class=\"external text\" href=\"http://fr.wikisource.org\" data-parsoid='{\"targetOff\":1183,\"contentOffsets\":[1183,1193],\"dsr\":[1157,1194,26,1]}'>WikiSource</a> qu'à Wikipedia, mais il m'arrive de passer par ici !</li></ul><p data-parsoid='{\"dsr\":[1248,1254,0,0]}'><br data-parsoid='{\"stx\":\"html\",\"selfClose\":true,\"dsr\":[1248,1254,6,0]}'/></p><ul data-parsoid='{\"dsr\":[1255,1844,0,0]}'><li data-parsoid='{\"dsr\":[1255,1694,1,0]}'>Passionné par l'information et la programmation, j'ai réalisé plusieurs sites Web en <a rel=\"mw:WikiLink\" href=\"./PHP\" title=\"PHP\" data-parsoid='{\"stx\":\"simple\",\"a\":{\"href\":\"./PHP\"},\"sa\":{\"href\":\"PHP\"},\"dsr\":[1342,1349,2,2]}'>PHP</a>/<a rel=\"mw:WikiLink\" href=\"./JavaScript\" title=\"JavaScript\" data-parsoid='{\"stx\":\"simple\",\"a\":{\"href\":\"./JavaScript\"},\"sa\":{\"href\":\"JavaScript\"},\"dsr\":[1350,1364,2,2]}'>JavaScript</a>. Je suis un grand fan d'<a rel=\"mw:WikiLink\" href=\"./AJAX\" title=\"AJAX\" data-parsoid='{\"stx\":\"simple\",\"a\":{\"href\":\"./AJAX\"},\"sa\":{\"href\":\"AJAX\"},\"dsr\":[1389,1397,2,2]}'>AJAX</a> et des <a rel=\"mw:WikiLink\" href=\"./Expressions_régulières\" title=\"Expressions régulières\" data-parsoid='{\"stx\":\"simple\",\"a\":{\"href\":\"./Expressions_régulières\"},\"sa\":{\"href\":\"expressions régulières\"},\"dsr\":[1405,1431,2,2]}'>expressions régulières</a>. Je travaille actuellement sur un site Internet permettant de gérer une collection de magazines Disney, en utilisant la base mondiale <a rel=\"mw:WikiLink\" href=\"./INDUCKS\" title=\"INDUCKS\" data-parsoid='{\"stx\":\"simple\",\"a\":{\"href\":\"./INDUCKS\"},\"sa\":{\"href\":\"INDUCKS\"},\"dsr\":[1566,1577,2,2]}'>INDUCKS</a> et en ajoutant des fonctionnalités sympathiques. Je travaille sur différents autres projets hébergés sur <a rel=\"mw:WikiLink\" href=\"./GitHub\" title=\"GitHub\" data-parsoid='{\"stx\":\"simple\",\"a\":{\"href\":\"./GitHub\"},\"sa\":{\"href\":\"GitHub\"},\"dsr\":[1683,1693,2,2]}'>GitHub</a>.</li><li data-parsoid='{\"dsr\":[1695,1844,1,0]}'>Je supporte, plus ou moins activement, différents logiciels que je trouve novateurs, bien pensés ou encore idéologiquement sympas ^^, par exemple<span typeof=\"mw:DisplaySpace mw:Placeholder\" data-parsoid='{\"src\":\" \",\"isDisplayHack\":true,\"dsr\":[1842,1843,null,0]}'> </span>:</li></ul><dl data-parsoid='{\"dsr\":[1845,2622,0,0]}'><dd data-parsoid='{\"dsr\":[1845,2622,1,0]}'><ul data-parsoid='{\"dsr\":[1846,2622,0,0]}'><li data-parsoid='{\"dsr\":[1846,2024,1,0]}'>Les <a rel=\"mw:WikiLink\" href=\"./Freeware\" title=\"Freeware\" data-parsoid='{\"stx\":\"piped\",\"a\":{\"href\":\"./Freeware\"},\"sa\":{\"href\":\"Freeware\"},\"dsr\":[1852,1874,11,2]}'>freewares</a> de <a rel=\"mw:WikiLink\" href=\"./Piriform\" title=\"Piriform\" data-parsoid='{\"stx\":\"simple\",\"a\":{\"href\":\"./Piriform\"},\"sa\":{\"href\":\"Piriform\"},\"dsr\":[1878,1890,2,2]}'>Piriform</a><span typeof=\"mw:DisplaySpace mw:Placeholder\" data-parsoid='{\"src\":\" \",\"isDisplayHack\":true,\"dsr\":[1890,1891,null,0]}'> </span>: <a rel=\"mw:WikiLink\" href=\"./CCleaner\" title=\"CCleaner\" data-parsoid='{\"stx\":\"simple\",\"a\":{\"href\":\"./CCleaner\"},\"sa\":{\"href\":\"CCleaner\"},\"dsr\":[1893,1905,2,2]}'>CCleaner</a>, <a rel=\"mw:WikiLink\" href=\"./Defraggler\" title=\"Defraggler\" data-parsoid='{\"stx\":\"simple\",\"a\":{\"href\":\"./Defraggler\"},\"sa\":{\"href\":\"Defraggler\"},\"dsr\":[1907,1921,2,2]}'>Defraggler</a> et maintenant <a rel=\"mw:WikiLink\" href=\"./Speccy\" title=\"Speccy\" data-parsoid='{\"stx\":\"simple\",\"a\":{\"href\":\"./Speccy\"},\"sa\":{\"href\":\"Speccy\"},\"dsr\":[1936,1946,2,2]}'>Speccy</a>, c'est pas open-source mais c'est simple et ça fait ce que c'est censé faire.</li><li data-parsoid='{\"dsr\":[2025,2186,2,0]}'><a rel=\"mw:WikiLink\" href=\"./Picasa\" title=\"Picasa\" data-parsoid='{\"stx\":\"simple\",\"a\":{\"href\":\"./Picasa\"},\"sa\":{\"href\":\"Picasa\"},\"dsr\":[2028,2038,2,2]}'>Picasa</a> de Google. Je ne suis pas toujours fan des produits Google, mais en l'occurrence celui-là est riche et complet. Ah, la reconnaissance des visages !</li><li data-parsoid='{\"dsr\":[2187,2399,2,0]}'><a rel=\"mw:WikiLink\" href=\"./Firefox\" title=\"Firefox\" data-parsoid='{\"stx\":\"simple\",\"a\":{\"href\":\"./Firefox\"},\"sa\":{\"href\":\"Firefox\"},\"dsr\":[2190,2201,2,2]}'>Firefox</a> et <a rel=\"mw:WikiLink\" href=\"./Google_Chrome\" title=\"Google Chrome\" data-parsoid='{\"stx\":\"simple\",\"a\":{\"href\":\"./Google_Chrome\"},\"sa\":{\"href\":\"Google Chrome\"},\"dsr\":[2205,2222,2,2]}'>Google Chrome</a>. Je préfère le processus de développement du premier, mais le second est incontestablement un concurrent de taille concernant les technologies Web et la fluidité de navigation.</li><li data-parsoid='{\"dsr\":[2400,2525,2,0]}'><a rel=\"mw:WikiLink\" href=\"./VirtualBox\" title=\"VirtualBox\" data-parsoid='{\"stx\":\"simple\",\"a\":{\"href\":\"./VirtualBox\"},\"sa\":{\"href\":\"VirtualBox\"},\"dsr\":[2403,2417,2,2]}'>VirtualBox</a> d'Oracle. C'est toujours rigolo d'avoir plein d'<a rel=\"mw:WikiLink\" href=\"./Système_d'exploitation\" title=\"Système d'exploitation\" data-parsoid='{\"stx\":\"piped\",\"a\":{\"href\":\"./Système_d&apos;exploitation\"},\"sa\":{\"href\":\"Système d&apos;exploitation\"},\"dsr\":[2466,2495,25,2]}'>OS</a> qui tournent en même temps...</li><li data-parsoid='{\"dsr\":[2526,2622,2,0]}'>DownThemAll, JDownloader et HeidiSQL<span typeof=\"mw:DisplaySpace mw:Placeholder\" data-parsoid='{\"src\":\" \",\"isDisplayHack\":true,\"dsr\":[2565,2566,null,0]}'> </span>: des logiciels propres et constamment en développement.</li></ul></dd></dl><p data-parsoid='{\"dsr\":[2623,2739,0,0]}'><br data-parsoid='{\"stx\":\"html\",\"selfClose\":true,\"dsr\":[2623,2629,6,0]}'/>... Bon, et puis je ne vais pas vous raconter toutes mes passions, je suis pas ici pour parler de moi (si ?).</p></section><section data-mw-section-id=\"2\" data-parsoid=\"{}\"><h2 id=\"Ce_que_je_viens_faire_ici\" data-parsoid='{\"dsr\":[2741,2772,2,2]}'>Ce que je viens faire ici</h2><ul data-parsoid='{\"dsr\":[2773,2904,0,0]}'><li data-parsoid='{\"dsr\":[2773,2904,1,0]}'>Oh, diverses choses... Quelques corrections, quelques ajouts par-ci par-là. Une modeste contribution à ce géant qu'est Wikipedia.</li></ul></section></body></html>\"";

    System.out.println(html);

    String sourceUri = "https://fr.wikipedia.org/wiki/HTML";
    HtmlTools.HtmlAnonymizer htmlAnonymizer = new HtmlTools.HtmlAnonymizer(sourceUri, html);
    htmlAnonymizer.anonymize();


    System.out.println(htmlAnonymizer.getAnonymizedHtml());
  }

}
