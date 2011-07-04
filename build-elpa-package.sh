#!/bin/bash

if [[ $# -eq 1 ]]; then
    RELEASE=".$1"
fi

## Build an elpa package for slime
VERSION=$(grep -E -o "[0-9]{4,4}-[0-9]{1,2}-[0-9]{1,2}" slime/ChangeLog | head -1 | sed -e "s/-//g" )
VERSION=${VERSION}${RELEASE}
echo "SLIME version $VERSION"

dest="slime-$VERSION"

rm -rf "marmalade/$dest" "marmalade/slime"
find slime | cpio -pd marmalade

# remove the ritz contrib
rm -f marmalade/slime/contrib/ritz.el

# add an elpa style header
sed -i .bak \
    -e "/For a detailed/ i \\
;; Authors: Eric Marsden, Luke Gorrie, Helmut Eller, Tobias C. Rittweiler" \
    -e "/For a detailed/ i \\
;; URL: http://common-lisp.net/project/slime/" \
    -e "/For a detailed/ i \\
;; Keywords: languages, lisp, slime" \
    -e "/For a detailed/ i \\
;; Version: $VERSION" \
    -e "/For a detailed/ i \\
;; Adapted-by: Hugo Duncan" \
    -e "/For a detailed/ i \\
;;" \
    marmalade/slime/slime.el

rm marmalade/slime/slime.el.bak

# create a package descriptor
cat > marmalade/slime/slime-pkg.el <<EOF
(define-package "slime" "$VERSION"
                "Superior Lisp Interaction Mode for Emacs")
EOF

mv marmalade/slime marmalade/$dest
( cd marmalade; tar cvf ../$dest.tar $dest )
ls -l $dest.tar
