# ritz-nrepl-middleware

Middleware for [nREPL](https://github.com/clojure/tools.nrepl).

## Usage

Add `ritz-nrepl-middleware` to your `:dev` `:dependencies` vector, and add the
middleware to `:nrepl-middleware` under `:repl-options`. You can do this in your
`project.clj` file, or in the `:user` profile in `~/.lein/profiles.clj`.

```clj
:dependencies [[ritz/ritz-nrepl-middleware "0.7.0"]]
:repl-options {:nrepl-middleware
                [ritz.nrepl.middleware.javadoc/wrap-javadoc
                 ritz.nrepl.middleware.simple-complete/wrap-simple-complete]}
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

["complete"](http://palletops.com/ritz/0.4/nrepl/api/ritz.nrepl.middleware.fuzzy-complete.html)
: Fuzzy completion

["eval"](http://palletops.com/ritz/0.4/nrepl/api/ritz.nrepl.middleware.tracking-eval.html)
: eval with source form tracking

["load-file"](http://palletops.com/ritz/0.4/nrepl/api/ritz.nrepl.middleware.load-file.html)
: load file with optional locals clearing and dead var removal

[API Docs](http://palletops.com/ritz/0.4/nrepl-middleware/api/)


## License

Copyright © 2012, 2013 Hugo Duncan

Distributed under the Eclipse Public License.
