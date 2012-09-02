# nrepl

An nREPL server and middleware.

Alpha.

# nREPL usage

You will need lein-ritz 0.4.0-SNAPSHOT. You will need to install nrepl-ritz.el.

```
lein2 ritz-nrepl :headless
```

Then in emacs, `M-x nrepl` and enter the port printed by the previous command.

# Provided nREPL ops

"javadoc" Returns a url of the javadoc for the specified symbol

"apropos" Returns a description of each function matching a partial symbol

"doc" Returns the doc string for the specified symbol

"describe-symbol" Returns a description of the specified symbol

## License

Copyright © 2012 Hugo Duncan

Distributed under the Eclipse Public License.
