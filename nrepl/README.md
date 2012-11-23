# ritz-nrepl

`ritz-nrepl` is an nREPL server with a debugger. The server uses JPDA to provide
debugger middleware.

## nREPL debugger installation

Add this to your `~/.lein/profiles` file (requires lein version 2):

```clj
{:user
 {:plugins [[lein-ritz "0.6.0"]]
  :dependencies [[ritz/ritz-nrepl-middleware "0.6.0"]
                 [ritz/ritz-debugger "0.6.0"]
                 [ritz/ritz-repl-utils "0.6.0"]]
  :repl-options {:nrepl-middleware
                 [ritz.nrepl.middleware.javadoc/wrap-javadoc
                  ritz.nrepl.middleware.simple-complete/wrap-simple-complete]}}
 :hooks
 [ritz.add-sources]}
```

Add this to your `~/.emacs.d/init.el` file:

```clj
(require 'package)
(add-to-list 'package-archives
       '("marmalade" . "http://marmalade-repo.org/packages/"))
(package-initialize)
(when (not package-archive-contents)
  (package-refresh-contents))
(defvar my-packages '(clojure-mode
                      nrepl
                      nrepl-ritz))
(dolist (p my-packages)
  (when (not (package-installed-p p))
    (package-install p)))
```

If you are using a SNAPSHOT version of
ritz-nrepl, you probably will need to install nrepl-ritz.el from
[melpa](http://melpa.milkbox.net/packages/) instead.

Note that on Emacs 23 you will need to
[install](http://tromey.com/elpa/install.html) package.el.

## Using the debugger in your project

Retrieve the Clojure-sources for your project (optional):

```
cd ~/your-project
lein pom; mvn dependency:sources;
```

Then in emacs, `M-x nrepl-ritz-jack-in` to start a nrepl session with debugger capabilities.

## nREPL Ritz Emacs Commands

* **C-c C-c**: compile top-level expression at point
* **C-u C-c C-c**: compile top-level expression at point to run without locals clearing
* **C-c C-u**: undefine symbol at point
* nrepl-ritz-break-on-exception : enable debugger on exceptions
* nrepl-ritz-reload-project : re-read classpath from project.clj
* nrepl-ritz-load-project: Use the project.clj for the current buffer
* nrepl-ritz-lein: Run a lein task on the current project

## SLDB commands for frame examination

* t (or enter) toggle display of local variables
* v show source for current frame
* e eval expression in frame
* d pprint result of eval in frame
* D disassemble frame

See [SLDB](http://common-lisp.net/project/slime/doc/html/Debugger.html) for help
on using the debugger.

[API Docs](http://palletops.com/ritz/0.4/nrepl/api/)

## License

Copyright © 2012 Hugo Duncan

Distributed under the Eclipse Public License.
