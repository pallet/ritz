# lein-ritz

Leiningen plugin for launching a ritz server.

## Install

For leiningen 2, add `[lein-ritz "0.7.0"]` to `:plugins` in `project.clj`.  Then
you should have access to the `ritz` task.

From version 1.7.0 on, Leiningen uses a separate `:plugins` list rather than
`:dev-dependencies`. If you are using Leiningen 1.6 or earlier, continue adding
the main `ritz` entry into your `:dev-dependencies`.

## ritz-swank usage

To start a server on an arbitrary port:

    lein ritz

You can specify an optional port and bind address:

    lein ritz 4005 localhost

You can also specify that the debugger should not be used:

    lein ritz --no-debug

## License

Copyright © 2012 Hugo Duncan

Distributed under the Eclipse Public License.
