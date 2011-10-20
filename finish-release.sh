#!/bin/bash

# finish the release after updating release notes

if [[ $# -lt 1 ]]; then
  echo "usage: $(basename $0) new-version" >&2
  exit 1
fi

version=$1

echo "finish release of $version"

echo -n "commiting project.clj, release notes and readme.  enter to continue:" \
&& read x \
&& git add project.clj ReleaseNotes.md README.md src/main/elisp/slime-ritz.el \
&& git commit -m "Updated project.clj, release notes and readme for $version" \
&& echo -n "Peform release.  enter to continue:" && read x \
&& lein test \
&& lein jar, pom \
&& git flow release finish $version \
&& scp ritz-${version}.jar pom.xml clojars: \
&& bash build-elpa-package.sh \
&& echo "Now push and upload to github and marmalade"

