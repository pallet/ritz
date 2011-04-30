# swank-clj

Refactored swank-clojure, with jpda debugging support.

This is alpha quality.

- Breaks on uncaught exceptions and breakpoints.
- Allows stepping from breakpoints

## Install

Add `[swank-clj "0.1.0-SNAPSHOT"]` to your project.clj `:dev-dependencies`.

## Usage

To run without jpda:

    lein swank-clj 4005 localhost :server-ns swank-clj.repl

To run with jpda:

    lein swank-clj

### Breakpoints

To set a breakpoint, eval `swank-clj.el` from src/main/elisp, put the cursor
on the line where you want a breakpoint, and `M-x (slime-line-breakpoint)`.

Note that breakpoints disappear on recompilation at the moment.

## License

Copyright (C) 2010, 2011 Hugo Duncan

Distributed under the Eclipse Public License.
