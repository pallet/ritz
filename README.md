# swank-clj

Refactored swank-clojure, with jpda debugging support.

This is alpha quality.

- Breaks on uncaught exceptions and breakpoints.
- Allows stepping from breakpoints
- Allows evaluation of expressions in the context of a stack frame

## Install

Add `[swank-clj "0.1.0-SNAPSHOT"]` to your project.clj `:dev-dependencies`.

A compatible slime.el is in slime/slime.el. It is available as a `package.el`
package file you can
[download](https://github.com/downloads/hugoduncan/swank-clj/slime-20101113.tar)
and install with `M-x package-install-file`.  Note that you will need to remove
this package to use
[swank-clojure](https://github.com/technomancy/swank-clojure) again.

## Usage

To run with jpda:

    lein swank-clj

To run without jpda:

    lein swank-clj 4005 localhost :server-ns swank-clj.repl

### Breakpoints

To set a breakpoint, eval `swank-clj.el` from src/main/elisp, put the cursor
on the line where you want a breakpoint, and `M-x (slime-line-breakpoint)`.

Note that breakpoints disappear on recompilation at the moment.

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
