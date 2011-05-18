# swank-clj

Refactored swank-clojure, with jpda debugging support.

This is alpha quality.

- Breaks on uncaught exceptions and breakpoints.
- Allows stepping from breakpoints
- Allows evaluation of expressions in the context of a stack frame
- Inspection of locals in any stack frame

## Install

Add `[swank-clj "0.1.5"]` to your project.clj `:dev-dependencies`.

Install the slime-clj.el contrib from [marmalade](http://marmalade-repo.org/).

A compatible slime.el is in slime/slime.el. It is available as a `package.el`
package file you can
[download](https://github.com/downloads/hugoduncan/swank-clj/slime-20101113.1.tar)
and install with `M-x package-install-file`.  Note that you may need to remove
this package to use
[swank-clojure](https://github.com/technomancy/swank-clojure) again.

If you would like to browse into the clojure java sources then add the following
to your `:dev-dependencies`, with the appropriate clojure version.

    [org.clojure/clojure "1.2.1" :classifier "sources"]

For clojure 1.2.0, you will need the following instead:

    [clojure-source "1.2.0"]

## Usage

To run with jpda:

    lein swank-clj

To run without jpda:

    lein swank-clj 4005 localhost :server-ns swank-clj.repl

### Breakpoints

To set a breakpoint, eval `swank-clj.el` from src/main/elisp, put the cursor
on the line where you want a breakpoint, and `M-x slime-line-breakpoint`.

Note that breakpoints disappear on recompilation at the moment.

To list breakpoints, use `M-x slime-list-breakpoints` or press `b` in the
`slime-selector`.  In the listing you can use the following keys

 - e enable
 - d disable
 - g refresh list
 - k remove breakpoint
 - v view source location

### Javadoc

Specify the location of local javadoc using `slime-javadoc-local-paths` in
your `.emacs` file.

`slime-javadoc`, bound to `C-c b` by default, will open javadoc in the
browser you have set up in emacs.

### SLIME configuration

If you use slime with multiple lisps, you can isolate clojure specific
setup by using `slime-clj-connected-hook` and `slime-clj-repl-mode-hook`.

## Open Problems

Recompilation of clojure code creates new classes, with the same location as the
code they replace.  Recompilation therefore looses breakpoints, which are set on
the old code. Setting breakpoints by line number finds all the old code too.

## Roadmap

A pure JDI backend, that doesn't require swank in the target VM is certainly a
possibility.

A slime-eval-symbol-at-point would be useful (requires determining the frame
in the current sldb stacktrace using file and line number).

Add watchpoints with logging of locals to an emacs buffer or file.

## Use Cases

### Development

Run swank server and JDI debugger in the same process to have a single JVM and keep
memory usage down

### Debug

Run swank and debugger in a seperate JVM process. Attach to any -Xdebug enabled
JVM process.

### Production server

Run swank server in process and attach slime as required. This requires the
debugger to run in process.

## License

Copyright (C) 2010, 2011 Hugo Duncan

Distributed under the Eclipse Public License.
