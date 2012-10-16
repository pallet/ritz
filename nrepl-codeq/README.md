# nrepl-codeq

An nREPL middleware for use with datomic's codeq. `elisp` for nrepl.el is
provided to use the middleware.

## Usage

You will need an nREPL srever running the codeq middleware, and to have the
`nrepl-ritz.el` emacs packgae installed.

[Clone codeq](https://github.com/Datomic/codeq), and run `lein install`.

Add nrepl-codeq to your `:dev` (in `project.clj`) or `:user` (in
`~/.lein/profiles.clj`) dependencies.

```clj
:dependencies [[ritz/ritz-nrepl-middleware "0.5.0"]]
```

Add wrap-codeq-def to your nREPL middleware (in `project.clj` or your `:user`
profile).

```clj
:repl-options {:nrepl-middleware
                [ritz.nrepl.middleware.codeq-def/wrap-codeq-def]}
```

To view the definitions of a symbol within an nrepl.el session, use

    M-x nrepl-codeq-def

## Middleware details

The middleware provides the "codeq-def" op, and requires "symbol" and
"datomic-url" arguments. It returns list, where each element is a list
containing a source element and a date element.

## Known Issues

The middleware introduces a dependency on datomic and codeq in the user's
project classpath. These transitively depend on clojure 1.5.0 alpha's.

## License

Copyright © 2012 Hugo Duncan

Distributed under the Eclipse Public License.
