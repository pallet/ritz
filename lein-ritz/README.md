# lein-ritz

Leiningen plugin for launching a ritz server.

## Usage

From version 1.7.0 on, Leiningen uses a separate list for plugins rather than
`:dev-dependencies`. If you are using Leiningen 1.6 or earlier, continue adding
the main `ritz` entry into your `:dev-dependencies`.

Add `[lein-ritz "0.3.0-SNAPSHOT"]` to `:plugins` in `project.clj`.  Then you should have
access to the `ritz` task.

## License

Copyright © 2012 Hugo Duncan

Distributed under the Eclipse Public License.
