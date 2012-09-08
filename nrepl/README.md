# ritz-nrepl

`ritz-nrepl` comprises an nREPL server and middleware. The server uses JPDA to
provide debugger middleware. The library also provides general purpose
middleware, which can be used with any nREPL client, independently of the
debugger.

Alpha.

## nREPL debugger server usage

Add `lein-ritz` to the `:plugins` key of your `~/.lein/profiles` file (requires
lein version 2).

```clj
{:user {:plugins [[lein-ritz "0.4.1"]]}}
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

## nREPL general middleware usage

Add `ritz-nrepl` to your `:dev` `:dependencies` vector, and add the middleware
to `:nrepl-middleware` under `:repl-options` in `project.clj.

```clj
:dependencies [[ritz/ritz-nrepl "0.4.1"]]
:repl-options {:nrepl-middleware [ritz.nrepl.middleware.javadoc/wrap-javadoc]}
```

## Provided nREPL ops

["javadoc"](http://palletops.com/ritz/0.4/nrepl/api/ritz.nrepl.middleware.javadoc.html)
: Returns a url of the javadoc for the specified symbol

["apropos"](http://palletops.com/ritz/0.4/nrepl/api/ritz.nrepl.middleware.apropos.html)
: Returns a description of each function matching a partial symbol


["doc"](http://palletops.com/ritz/0.4/nrepl/api/ritz.nrepl.middleware.doc.html)
: Returns the doc string for the specified symbol

["describe-symbol"](http://palletops.com/ritz/0.4/nrepl/api/ritz.nrepl.middleware.describe-symbol.html)
: Returns a description of the specified symbol

["complete"](http://palletops.com/ritz/0.4/nrepl/api/ritz.nrepl.middleware.simple-complete.html)
: Simple completion

["eval"](http://palletops.com/ritz/0.4/nrepl/api/ritz.nrepl.middleware.tracking-eval.html)
: eval with source form tracking

[API Docs](http://palletops.com/ritz/0.4/nrepl/api/)

## License

Copyright © 2012 Hugo Duncan

Distributed under the Eclipse Public License.
