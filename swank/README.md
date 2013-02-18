# ritz-swank

Ritz-swank is a swank server for running [clojure](http://clojure.org) in
[slime](http://common-lisp.net/project/slime).

## Features

- Break on exceptions and breakpoints.
- Allows stepping from breakpoints
- Allows evaluation of expressions in the context of a stack frame
- Inspection of locals in any stack frame
- Disassembly of functions from symbol or stack frame

Should work with any version of clojure from 1.2.0.

## Install

The install has two parts. The first is to install the slime components into
emacs (if you are not using jack-in), and the second is to enable
[Leiningen](http://github.com/technomancy/leiningen) to use ritz.

To try Ritz without destroying your swank-clojure install, you may wish to back
up your .emacs file and .emacs.d directory. Ritz uses a different version of
slime than [swank-clojure](https://github.com/technomancy/swank-clojure).

### SLIME

The easiest way to install a compatible version of slime is to use the emacs
packaging system `package.el` - download the
[package file](https://github.com/downloads/pallet/ritz/slime-20101113.1.tar)
and install with `M-x package-install-file` (pass the path to the downloaded
package file, no un-tar required).

(Alternatively, the same version of slime is in the ritz source tree at
slime/slime.el.)

Install the slime-ritz.el contrib from
[marmalade](http://marmalade-repo.org/). If you are using a SNAPSHOT version of
Ritz, you probably will need to install slime-ritz.el from
[melpa](http://melpa.milkbox.net/packages/) instead.

Note that on Emacs 23 you will need to
[install](http://tromey.com/elpa/install.html) package.el.

To add the repositories to the emacs package system, you will need the following
in your `.emacs` file, and eval it or restart emacs.

```elisp
(require 'package)
(add-to-list 'package-archives
  '("marmalade" . "http://marmalade-repo.org/packages/") t)
(add-to-list 'package-archives
  '("melpa" . "http://melpa.milkbox.net/packages/") t)
(package-initialize)
```

### Lein 2

To make ritz available in all your projects, add the lein-ritz plugin to your
`:user` profile in `~/.lein/profiles.clj`. This is the preferred over enabling
ritz on a per project basis.

```clj
{:user {:plugins [[lein-ritz "0.7.0"]]}}
```

To enable ritz on a per project basis, add it to your `project.clj`'s :dev
profile.

```clj
{:dev {:plugins [[lein-ritz "0.7.0"]]}}
```

In either case, start a swank server with `lein ritz` inside your project
directory, and then use `M-x slime-connect` in emacs to connect to it.

### Lein 1

To make ritz available in all your projects, install the lein-ritz plugin.

```
lein plugin install lein-ritz "0.7.0"
```

Add `[lein-ritz "0.7.0"]` to your project.clj `:dev-dependencies`.


Start a swank server with `lein ritz` inside your project directory,
and then use `M-x slime-connect` in emacs to connect to it.

### Experimental 'jack-in' support

There is experimental support to "jack in" from an existing project
using [Leiningen](http://github.com/technomancy/leiningen):

For "jack-in" to work, you can not have SLIME installed.

* Install `clojure-mode` either from
  [Marmalade](http://marmalade-repo.org) or from
  [git](http://github.com/technomancy/clojure-mode).
* lein plugin install lein-ritz "0.7.0"
* in your .emacs file, add the following and evalulate it (or restart emacs)

    ```lisp
    (setq clojure-swank-command
      (if (or (locate-file "lein" exec-path) (locate-file "lein.bat" exec-path))
        "lein ritz-in %s"
        "echo \"lein ritz-in %s\" | $SHELL -l"))
    ```
* From an Emacs buffer inside a project, invoke `M-x clojure-jack-in`

## Maven Plugin

See [zi](https://github.com/pallet/zi).

## Source Browsing

If you would like to browse into java sources then add the source jars
to your `:dev-dependencies`, with the appropriate versions.

    [org.clojure/clojure "1.2.1" :classifier "sources"]

For clojure 1.2.0, you will need the following instead:

    [clojure-source "1.2.0"]

To be able to see Java sources when using openjdk, add the `src.zip` to you
classpath. e.g. for lein:

    :dev-resources-path "/usr/lib/jvm/java-6-openjdk/src.zip"

### lein 2

In lein 2 this is simplified. You can add a hook to your user profile to have
source jars automatically put on the classpath.

    :hooks [ritz.add-sources]

To obtain source jars for your project you can use

    lein pom; mvn dependency:sources;

## USAGE

To run ritz with debugging capabilities (notice that it will need to spawn an
extra JVM process):

    lein ritz

To run ritz with no debugging capabilities:

    lein ritz 4005 localhost :server-ns ritz.repl

To run with a maven project:

    mvn zi:ritz

### SLIME Ritz Emacs Commands

* **C-c C-b**: display javadoc for class at point
* **C-c C-u**: undefine symbol at point
* **C-c C-c**: compile top-level expression at point
* **C-c C-x b**: break on exception (turn it off with a prefix)
* **C-c C-x C-b**: set breakpoint at line
* slime-break-on-exception: break on exception
* slime-ritz-reload-project: re-read classpath from project.clj
* slime-ritz-load-project: Use the project.clj for the current buffer
* slime-ritz-lein: Run a lein task on the current project

See [SLDB](http://common-lisp.net/project/slime/doc/html/Debugger.html) for help
on using the debugger.

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
 - s save the exception filters

Exception filters are saved to .ritz-exception-filters, which is read by ritz on
startup.

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


## Embedding

You can embed Ritz in your project, start the server from within your own code,
and connect via Emacs to that instance:

```clj
(ns my-app
  (:use [ritz.swank.socket-server :only [start]]))

(start {:server-ns 'ritz.swank.repl})
 ;; optionally takes :host/:port keyword args
```

The `:server-ns` keyword is used to select the server without the built in
debugger (which starts an extra VM, and probably shouldn't be used embedded).

To make this work in production, ritz-swank needs to be in :dependencies in
project.clj in addition to being installed as a user-level plugin.

## Open Problems

Recompilation of clojure code creates new classes, with the same location as the
code they replace.  Recompilation therefore looses breakpoints, which are set on
the old code. Setting breakpoints by line number finds all the old code too.

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

## History

Ritz was originally based on
[swank-clojure](http://github.com/technomancy/swank-clojure) and was
originally called swank-clj.  The last swank-clj release is 0.1.6.

## License

Copyright (C) 2010, 2011, 2012 Hugo Duncan

Distributed under the Eclipse Public License.
