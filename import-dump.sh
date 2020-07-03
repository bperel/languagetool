wikis="ca de en fr nl pl pt ru uk"

cd /home/dumps || exit 1

while true; do
  for wiki in $wikis; do
    echo "Processing wiki $wiki"
    curl -s https://dumps.wikimedia.org/${wiki}wiki/ \
      | grep -Po '(?<=href=")[0-9]+(?!")' \
      | tac \
      | while read -r dump; do

      echo "Dump : $dump"
      echo "Retrieving sha1sums..."
      curl -s "https://dumps.wikimedia.org/${wiki}wiki/$dump/${wiki}wiki-$dump-sha1sums.txt" > sha1sums.txt
      echo "Done."

      curl -s "https://dumps.wikimedia.org/${wiki}wiki/$dump/dumpstatus.json" \
      | jq '.jobs.articlesmultistreamdump.files | keys[]' \
      | grep -Po '(?<=").+xml[^"]+' \
      | while read -r file; do
        echo "File : $file"
        if [ -f "$file.done" ]; then
          echo "File already downloaded and processed, skipping"
        else
          if grep `sha1sum "$file"` sha1sums.txt; then
            echo "File already downloaded but not processed, reprocessing"
          else
            url="https://dumps.wikimedia.org/${wiki}wiki/$dump/$file"
            echo "File to download : $url"
            curl -O "$url"
          fi
          if [ -d "/home/nwords/$wiki" ]; then
            nwordsargument='--languagemodel nwords'
          else
            nwordsargument=
          fi
          java -jar /srv/languagetool-wikipedia/*/languagetool-wikipedia.jar check-data $nwordsargument -f "`pwd`/$file" -l "$wiki" -d /home/server.properties --rule-properties=/home/disabled_rules.properties \
            && touch "$file.done" && rm -f "$file" && break
        fi
      done
    done
  done

  sleep 3600
done
