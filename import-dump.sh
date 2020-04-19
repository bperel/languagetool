wikis="ca de en fr nl pl pt ru uk"

cd /home/dumps

for wiki in $wikis; do
  echo "Processing wiki $wiki"
  latestdump=`curl -s https://dumps.wikimedia.org/${wiki}wiki/latest/${wiki}wiki-latest-pages-articles-multistream-index.txt.bz2-rss.xml \
  | xpath -q -e '//description//text()' \
  | grep -Po "(?<=${wiki}wiki/)([^/]+)"`

  echo "Latest dump : $latestdump"
  curl -s "https://dumps.wikimedia.org/${wiki}wiki/$latestdump/dumpstatus.json" \
  | jq '.jobs.articlesmultistreamdump.files | keys[]' \
  | grep -Po '(?<=").+xml[^"]+' \
  | while read -r file; do
    echo "File : $file"
    if [ -f "$file.done" ]; then
      echo "File already downloaded and processed, skipping"
    else
      if bzip2 -t "$file"; then
        echo "File already downloaded but not processed, reprocessing"
      else
        url="https://dumps.wikimedia.org/${wiki}wiki/$latestdump/$file"
        echo "File to download : $url"
        curl -O $url
      fi
      if [ -d /home/nwords/$wiki ]; then
        nwordsargument='--languagemodel nwords'
      else
        nwordsargument=
      fi
      java -jar /srv/languagetool-wikipedia/*/languagetool-wikipedia.jar check-data $nwordsargument -f `pwd`/$file -l $wiki -d /home/server.properties \
        && touch "$file.done" && rm -f $file

      continue # For now, only process one file per language
    fi
  done
done
