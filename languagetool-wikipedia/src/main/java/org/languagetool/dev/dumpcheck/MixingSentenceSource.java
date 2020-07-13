/* LanguageTool, a natural language style checker
 * Copyright (C) 2013 Daniel Naber (http://www.danielnaber.de)
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
package org.languagetool.dev.dumpcheck;

import org.apache.commons.compress.compressors.CompressorInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.lang3.StringUtils;
import org.languagetool.Language;
import org.languagetool.MultiThreadedJLanguageTool;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Alternately returns sentences from different sentence sources.
 * @since 2.4
 */
public class MixingSentenceSource extends SentenceSource {
  public static MultiThreadedJLanguageTool lt = null;

  private final List<SentenceSource> sources;
  private final Map<String, Integer> sourceDistribution = new HashMap<>();
  
  private int count;

  public static MixingSentenceSource create(List<String> dumpFileNames, Language language) throws IOException {
    return create(dumpFileNames, language, null, null, null);
  }

  public static MixingSentenceSource create(List<String> dumpFileNames, Language language, Pattern filter, String parsoidUrl, CorpusMatchDatabaseHandler resultHandler) throws IOException {
    List<SentenceSource> sources = new ArrayList<>();
    for (String dumpFileName : dumpFileNames) {
      File file = new File(dumpFileName);
      if (file.getName().endsWith(".bz2") || file.getName().endsWith(".xml")) {
        if (parsoidUrl == null || resultHandler == null) {
          throw new RuntimeException("You need to specify a Parsoid URL and a DB handler to parse XML files");
        }
        InputStream is = new FileInputStream(dumpFileName);
        if (file.getName().endsWith(".bz2")) {
          is = new MultiStreamBZip2InputStream(is);
        }
        sources.add(new WikipediaSentenceSource(is, language, filter, parsoidUrl, resultHandler));
      } else if (file.getName().startsWith("tatoeba-")) {
        sources.add(new TatoebaSentenceSource(new FileInputStream(dumpFileName), language, filter));
      } else if (file.getName().endsWith(".txt")) {
        sources.add(new PlainTextSentenceSource(new FileInputStream(dumpFileName), language, filter));
      } else if (file.getName().endsWith(".xz")) {
        sources.add(new CommonCrawlSentenceSource(new FileInputStream(dumpFileName), language, filter));
      } else {
        throw new RuntimeException("Could not find a source handler for " + dumpFileName +
                " - Wikipedia files must be named '*.xml' or '*.bz2', Tatoeba files must be named 'tatoeba-*', CommonCrawl files '*.xz', plain text files '*.txt'");
      }
    }
    return new MixingSentenceSource(sources, language);
  }

  private MixingSentenceSource(List<SentenceSource> sources, Language language) {
    super(language);
    this.sources = sources;
  }

  Map<String, Integer> getSourceDistribution() {
    return sourceDistribution;
  }

  private static String getProperty(Properties prop, String key) {
    String value = prop.getProperty(key);
    if (value == null) {
      throw new RuntimeException("Required key '" + key + "' not found in properties");
    }
    return value;
  }

  @Override
  public boolean hasNext() {
    for (SentenceSource source : sources) {
      if (source.hasNext()) {
        return true;
      }
    }
    return false;
  }

  @Override
  public Sentence next() {
    SentenceSource sentenceSource = sources.get(count % sources.size());
    while (!sentenceSource.hasNext()) {
      sources.remove(sentenceSource);
      if (sources.isEmpty()) {
        throw new NoSuchElementException();
      }
      count++;
      sentenceSource = sources.get(count % sources.size());
    }
    count++;
    Sentence next = sentenceSource.next();
    updateDistributionMap(next);
    return next;
  }

  private void updateDistributionMap(Sentence next) {
    Integer prevCount = sourceDistribution.get(next.getSource());
    if (prevCount != null) {
      sourceDistribution.put(next.getSource(), prevCount + 1);
    } else {
      sourceDistribution.put(next.getSource(), 1);
    }
  }

  @Override
  public String getSource() {
    return StringUtils.join(sources, ", ");
  }

  // https://chaosinmotion.blog/2011/07/29/and-another-curiosity-multi-stream-bzip2-files/
  public static class MultiStreamBZip2InputStream extends CompressorInputStream
  {
    private InputStream fInputStream;
    private BZip2CompressorInputStream fBZip2;

    public MultiStreamBZip2InputStream(InputStream in) throws IOException
    {
      fInputStream = in;
      fBZip2 = new BZip2CompressorInputStream(in);
    }

    @Override
    public int read() throws IOException
    {
      int ch = fBZip2.read();
      if (ch == -1) {
        /*
         * If this is a multistream file, there will be more data that
         * follows that is a valid compressor input stream. Restart the
         * decompressor engine on the new segment of the data.
         */
        if (fInputStream.available() > 0) {
          // Make use of the fact that if we hit EOF, the data for
          // the old compressor was deleted already, so we don't need
          // to close.
          fBZip2 = new BZip2CompressorInputStream(fInputStream);
          ch = fBZip2.read();
        }
      }
      return ch;
    }

    /**
     * Read the data from read(). This makes sure we funnel through read so
     * we can do our multistream magic.
     */
    public int read(byte[] dest, int off, int len) throws IOException
    {
      if ((off < 0) || (len < 0) || (off + len > dest.length)) {
        throw new IndexOutOfBoundsException();
      }

      int i = 1;
      int c = read();
      if (c == -1) return -1;
      dest[off++] = (byte)c;
      while (i < len) {
        c = read();
        if (c == -1) break;
        dest[off++] = (byte)c;
        ++i;
      }
      return i;
    }

    public void close() throws IOException
    {
      fBZip2.close();
      fInputStream.close();
    }
  }

}
