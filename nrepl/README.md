# rtiz-nrepl

`ritz-nrepl` comprises an nREPL server and middleware. The server uses JPDA to
provide debugger middleware. The library also provides general purpose
middleware, which can be used with any nREPL client, independently of the
debugger.

Alpha.

## nREPL debugger server usage

You will need lein-ritz 0.4.0-SNAPSHOT. You will need to install nrepl-ritz.el.

```
lein2 ritz-nrepl
```

Then in emacs, `M-x nrepl` and enter the port printed by the previous command.

## nREPL general middleware usage

Add `ritz-nrepl` to your `:dev` `:dependencies` vector, and add the middleware
to `:nrepl-middleware` under `:repl-options` in `project.clj.

```clj
:dependencies [[ritz/ritz-nrepl "0.4.0-SNAPSHOT"]]
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

[API Docs](http://palletops.com/ritz/0.4/nrepl/api/)

## License

Copyright © 2012 Hugo Duncan

Distributed under the Eclipse Public License.
