# ritz-nrepl

`ritz-nrepl` is an nREPL server with a debugger. The server uses JPDA to provide
debugger middleware.

## nREPL debugger server usage

Add `lein-ritz` to the `:plugins` key of your `~/.lein/profiles` file (requires
lein version 2).

```clj
{:user {:plugins [[lein-ritz "0.6.0"]]}}
```

Install the nrepl-ritz.el contrib from
[marmalade](http://marmalade-repo.org/). If you are using a SNAPSHOT version of
ritz-nrepl, you probably will need to install nrepl-ritz.el from
[melpa](http://melpa.milkbox.net/packages/) instead.

Note that on Emacs 23 you will need to
[install](http://tromey.com/elpa/install.html) package.el.

Once installed, run the server with:

```
lein2 ritz-nrepl
```

Then in emacs, `M-x nrepl` and enter the port printed by the previous command.

## nREPL Ritz Emacs Commands

* **C-c C-b**: display javadoc for class at point
* **C-c C-u**: undefine symbol at point
* **C-c C-c**: compile top-level expression at point
* nrepl-ritz-break-on-exception : enable debugger on exceptions
* nrepl-ritz-reload-project : re-read classpath from project.clj
* nrepl-ritz-load-project: Use the project.clj for the current buffer
* nrepl-ritz-lein: Run a lein task on the current project

See [SLDB](http://common-lisp.net/project/slime/doc/html/Debugger.html) for help
on using the debugger.

[API Docs](http://palletops.com/ritz/0.4/nrepl/api/)

## License

Copyright © 2012 Hugo Duncan

Distributed under the Eclipse Public License.
