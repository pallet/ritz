# ritz

Ritz is a swank server for running [clojure](http://clojure.org) in
[slime](http://common-lisp.net/project/slime).

Ritz was originally based on
[swank-clojure](http://github.com/technomancy/swank-clojure) and was
originally called swank-clj.  The last swank-clj release is 0.1.6.

This is alpha quality.

## Features

- Break on exceptions and breakpoints.
- Allows stepping from breakpoints
- Allows evaluation of expressions in the context of a stack frame
- Inspection of locals in any stack frame
- Disassembly of functions from symbol or stack frame

Should work with clojure 1.2.0, 1.2.1 and 1.3.0-alpha7.

## Install

### SLIME

Install the slime-ritz.el contrib from [marmalade](http://marmalade-repo.org/).

A compatible slime.el is in slime/slime.el. It is available as a `package.el`
package file you can
[download](https://github.com/downloads/pallet/ritz/slime-20101113.1.tar)
and install with `M-x package-install-file`.  Note that you may need to remove
this package to use
[swank-clojure](https://github.com/technomancy/swank-clojure) again.

### Lein/Cake Project

Add `[ritz "0.2.0"]` to your project.clj `:dev-dependencies`.

### Lein Plugin

Run `lein plugin install ritz 0.2.0`.

### Maven Plugin

See [zi](https://github.com/pallet/zi).

### Sun/Oracle JDK and OpenJDK

To use the Sun/Oracle JDK, and possibly OpenJDK, you
[need to add](http://download.oracle.com/javase/1.5.0/docs/tooldocs/findingclasses.html)
`tools.jar` from your JDK install to your classpath. This is not required on OS
X, where `tools.jar` does not exist.

If you are using maven then there are
[instructions in the FAQ](http://maven.apache.org/general.html#tools-jar-dependency).

For cake, add the following (with the correct jdk path), to
`PROJECT_ROOT/.cake/config`:
    project.classpath = /usr/lib/jvm/java-6-sun/lib/tools.jar

For lein, add the tools.jar to the dev-resources-path:

    :dev-resources-path "/usr/lib/jvm/java-6-sun/lib/tools.jar"

If you are missing tools.jar from the classpath, you will see an exception like `java.lang.ClassNotFoundException: com.sun.jdi.VirtualMachine`.

### Source Browsing

If you would like to browse into the clojure java sources then add the following
to your `:dev-dependencies`, with the appropriate clojure version.

    [org.clojure/clojure "1.2.1" :classifier "sources"]

For clojure 1.2.0, you will need the following instead:

    [clojure-source "1.2.0"]

To be able to see Java sources when using openjdk, add the `src.zip` to you
classpath. e.g. for lein:

    :dev-resources-path "/usr/lib/jvm/java-6-openjdk/src.zip"

## Usage

To run ritz with debugging capabilities (notice that it will need to spawn an
extra JVM process):

    lein ritz

To run ritz with no debugging capabilities:

    lein ritz 4005 localhost :server-ns ritz.repl

To run with a maven project:

    mvn zi:ritz

### Breakpoints

To set a breakpoint, put the cursor on the line where you want a breakpoint, and
`M-x slime-line-breakpoint`.

Note that breakpoints disappear on recompilation at the moment.

To list breakpoints, use `M-x slime-list-breakpoints` or press `b` in the
`slime-selector`.  In the listing you can use the following keys

 - e enable
 - d disable
 - g refresh list
 - k remove breakpoint
 - v view source location

### Exception filtering

To filter which exceptions break into the debugger, there is an `IGNORE`
restart, that will ignore an exception type.

To list breakpoints, use `M-x slime-list-exception-filters` or press `f` in the
`slime-selector`.  In the listing you can use the following keys

 - e enable
 - d disable
 - g refresh list
 - k remove exception filter

### Javadoc

Specify the location of local javadoc using `slime-javadoc-local-paths` in
your `.emacs` file. Note that this requires a connection, so should be in
your `slime-connected-hook` or `ritz-connected-hook`. e.g.

    (defun my-javadoc-setup ()
      (slime-javadoc-local-paths
        (list (concat (expand-file-name "~") "/lisp/docs/java"))))

    (add-hook 'slime-connected-hook 'my-javadoc-setup)

The command `slime-javadoc`, bound to `C-c b` by default, will open javadoc in
the browser you have set up in emacs.

### SLIME configuration

If you use slime with multiple lisps, you can isolate clojure specific
setup by using `ritz-connected-hook` and `ritz-repl-mode-hook`.

## Open Problems

Recompilation of clojure code creates new classes, with the same location as the
code they replace.  Recompilation therefore looses breakpoints, which are set on
the old code. Setting breakpoints by line number finds all the old code too.

## Roadmap

Allow customisations of which exceptions are trapped by ritz.

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
