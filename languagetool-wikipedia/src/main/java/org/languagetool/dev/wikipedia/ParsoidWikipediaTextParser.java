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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.languagetool.tools.HtmlTools;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.zip.GZIPInputStream;

/**
 * Convert Wikipedia syntax to HTML (or the other way around) using Parsoid.
 */
public class ParsoidWikipediaTextParser {

  public ParsoidWikipediaTextParser() {
  }

  public HtmlTools.HtmlAnonymizer convertWikitextToHtml(String title, String wikiText) {
    try {
      String html = callParsoid("wikitext", "html", wikiText);
      if (html == null) {
        return null;
      }
      HtmlTools.HtmlAnonymizer htmlAnonymizer = HtmlTools.HtmlAnonymizer.createFromHtml(title, wikiText, html);
      htmlAnonymizer.anonymize();

      return htmlAnonymizer;
    } catch (IOException | ParserConfigurationException | SAXException e) {
      e.printStackTrace();
      return null;
    }
  }

  public String convertHtmlToWikitext(String html) {
    String wikitext = callParsoid("html", "wikitext", html);
    if (wikitext != null) {
      wikitext = wikitext.replaceFirst("(?s)^.+?</title>", "");
    }
    return wikitext;
  }

  private String callParsoid(String fromFormat, String toFormat, String inputText) {
    URL url;
    try {
      url = new URL("http://localhost:8026/wikipedia_fr/v3/transform/"+fromFormat+"/to/"+toFormat);
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();

      HashMap<String, String> params = new HashMap<>();
      params.put(fromFormat, inputText);
      String requestBody = new ObjectMapper().writeValueAsString(params);

      conn.setDoInput(true);
      conn.setDoOutput(true);
      conn.setRequestMethod("POST");
      conn.setUseCaches(false);
      conn.setRequestProperty("Accept-Encoding", "gzip");
      conn.setRequestProperty("Content-Type", "application/json");

      PrintWriter out = new PrintWriter(new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8));
      out.print(requestBody);
      out.close();

      conn.connect();

      InputStreamReader reader;
      if ("gzip".equals(conn.getContentEncoding())) {
        reader = new InputStreamReader(new GZIPInputStream(conn.getInputStream()));
      }
      else {
        reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8);
      }

      BufferedReader bufferedReader = new BufferedReader(reader);
      StringBuilder sb = new StringBuilder();
      String line;
      while ((line = bufferedReader.readLine()) != null) {
        sb.append(line).append("\n");
      }
      bufferedReader.close();

      return sb.toString();
    } catch (IOException e) {
      e.printStackTrace();
      return null;
    }
  }
}
