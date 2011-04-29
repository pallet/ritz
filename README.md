# swank-clj

Refactored swank-clojure. Added jpda connection.

This is alpha quality.

## Install

Add `[swank-clj "0.1.0-SNAPSHOT"]` to your project.clj `:dev-dependencies`.

## Usage

To run without jpda:

    lein swank-clj 4005 localhost :server-ns swank-clj.repl

To run with jpda:

    lein swank-clj

To set a breakpoint, eval `swank-clj.el` from src/main/elisp, put the cursor
on the line where you want a breakpoint, and `M-x (slime-line-breakpoint)`.

## License

Copyright (C) 2010, 2011 Hugo Duncan

Distributed under the Eclipse Public License.
