#!/bin/bash

## Build an elpa package for slime-ritz.el
## Sets the version number based on the version in package.el

VERSION=$(head -1 project.clj | egrep -o -E "[0-9][0-9a-zA-Z.-]+")
echo "slime-ritz version $VERSION"

dest="slime-ritz-$VERSION"

sed -i .bak \
    -e "s/Version: .*/Version: $VERSION/" \
    src/main/elisp/slime-ritz.el \
&& rm src/main/elisp/slime-ritz.el.bak \
&& echo "src/main/elisp/slime-ritz.el ready for upload to marmalade if required"


