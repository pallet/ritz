# ritz-nrepl

`ritz-nrepl` is an nREPL server with a debugger. The server uses JPDA to provide
debugger middleware.

## nREPL debugger installation

Add this to your `~/.lein/profiles.clj` file (requires lein version 2):

```clj
{:user
 {:plugins [[lein-ritz "0.7.0"]]
  :dependencies [[ritz/ritz-nrepl-middleware "0.7.0"]]
  :repl-options {:nrepl-middleware
                 [ritz.nrepl.middleware.javadoc/wrap-javadoc
                  ritz.nrepl.middleware.simple-complete/wrap-simple-complete]}}}
```

Add this to your `~/.emacs.d/init.el` file, in order to install the emacs
packages:

```cl
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

Add this to your `~/.emacs.d/init.el` file, in order to enable nrepl-ritz
functionality in your nrepl connected clojure buffers:

```cl
(add-hook 'nrepl-interaction-mode-hook 'my-nrepl-mode-setup)
(defun my-nrepl-mode-setup ()
  (require 'nrepl-ritz))
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

## Possible Issues

### Classpath Length Issue on Windows

If you see something like:

```
Cannot run program "java" (in directory "M:\libs\myproject"):
  CreateProcess error=206, The file name or extension is too long
```

then you may have hit a limit in the length of the classpath computed by
leiningen.  Try adding `:eval-in :classloader` to your project.

### hostname Issue

If ritz-nrepl complains:

```
ERROR: transport error 202: connect failed: Connection refused
ERROR: JDWP Transport dt_socket failed to initialize, TRANSPORT_INIT(510)
```

then

 - please check that your hostname resolves (`host $(hostname)`).
 - check that if you are using drip, that you have 0.1.8 or later.

## Additional Resources

* [Debuggers for Clojure](https://github.com/hugoduncan/ritz-conj) - High level overview of Ritz & nrepl with a [live
demo of ritz-nrepl](http://www.youtube.com/watch?v=sA5zOLCa3Xw&t=21m45s).


## License

Copyright © 2012 Hugo Duncan

Distributed under the Eclipse Public License.
