#!/bin/bash

# start the release

if [[ $# -lt 3 ]]; then
  echo "usage: $(basename $0) previous-version new-version next-version" >&2
  exit 1
fi

previous_version=$1
version=$2
next_version=$3

echo ""
echo "Start release of $version, previous version is $previous_version"
echo ""
echo ""

lein do clean, test && \
git flow release start $version || exit 1

lein set-version ${version} :previous-version ${previous_version} \
  || { echo "set version failed" >2 ; exit 1; } || exit 1

lein set-sub-version ${version} :previous-version ${previous_version} \
  || { echo "set sub-version failed" >2 ; exit 1; }

echo ""
echo ""
echo "Changes since $previous_version"
git --no-pager log --pretty=changelog $previous_version..
echo ""
echo ""
echo "Now edit project.clj, ReleaseNotes and README"

$EDITOR project.clj

$EDITOR lein-ritz/project.clj
$EDITOR lein-ritz/src/leiningen/ritz.clj
$EDITOR lein-ritz/src/leiningen/ritz_nrepl.clj
$EDITOR lein-ritz/README.md

$EDITOR repl-utils/project.clj
$EDITOR repl-utils/README.md

$EDITOR debugger/project.clj
$EDITOR debugger/README.md

$EDITOR nrepl/project.clj
$EDITOR nrepl/elisp/nrepl-ritz.el
$EDITOR nrepl/README.md

$EDITOR swank/project.clj
$EDITOR swank/elisp/slime-ritz.el
$EDITOR swank/README.md

$EDITOR ReleaseNotes.md
$EDITOR README.md

MARMALADE="http://marmalade-repo.org"

echo -n "commiting project.clj, release notes and readme.  enter to continue:" \
&& git status \
&& read x \
&& git add -u \
&& git commit -m "Updated versions for $version" \
&& echo -n "Peform release.  enter to continue:" && read x \
&& lein do clean, install, test, deploy clojars \
&& git flow release finish $version \
&& curl -X POST -F "name=${MARMALADE_USER}" -F "token=${MARMALADE_TOKEN}" -F 'package=@swank/elisp/slime-ritz.el' ${MARMALADE}/v1/packages \
&& curl -X POST -F "name=${MARMALADE_USER}" -F "token=${MARMALADE_TOKEN}" -F 'package=@nrepl/elisp/nrepl-ritz.el' ${MARMALADE}/v1/packages \
&& echo "Setting project to ${next_version} for next cycle. enter to continue:" \
&& read x \
&& lein set-version ${next_version} :previous-version ${version} \
&& lein set-sub-version ${next_version} :previous-version ${version} \
&& git add -u \
&& git st \
&& git commit -m "Updated version for next release cycle" \
&& echo "Now push to github (don't forget the tags!)"
