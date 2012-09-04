# lein-ritz

Leiningen plugin for launching a ritz server.

## Usage

For leiningen 2, add `[lein-ritz "0.3.2"]` to `:plugins` in `project.clj`.  Then
you should have access to the `ritz` task.

From version 1.7.0 on, Leiningen uses a separate `:plugins` list rather than
`:dev-dependencies`. If you are using Leiningen 1.6 or earlier, continue adding
the main `ritz` entry into your `:dev-dependencies`.

## License

Copyright © 2012 Hugo Duncan

Distributed under the Eclipse Public License.
