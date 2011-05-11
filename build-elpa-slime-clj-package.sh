#!/bin/bash

## Build an elpa package for slime-clj.el
## Sets the version number based on the version in package.el

VERSION=$(head -1 project.clj | egrep -o -E "[0-9][0-9a-zA-Z.-]+")
echo "slime-clj version $VERSION"

dest="slime-clj-$VERSION"

rm -rf "marmalade/$dest" "marmalade/slime"
mkdir marmalade/$dest
cp slime/contrib/slime-clj.el marmalade/$dest

sed -i .bak \
    -e "s/Version: .*/Version: $VERSION/" \
    marmalade/$dest/slime-clj.el

rm marmalade/$dest/slime-clj.el.bak

echo "marmalade/$dest/slime-clj.el"
ls -l marmalade/$dest | grep -v total
