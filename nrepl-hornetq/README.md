# ritz-nrepl-hornetq

An nREPL server implemented with HornetQ

## Usage

### Server

To start an nREPL server over HornetQ:

    lein ritz-hornetq

By default this connects to a HornetQ server on `localhost`, using the standard
5445 port. You can specify port and host as arguments.

    lein ritz-hornetq --user "me" --password "letmein" 55445 somehost

You can also ask for an embedded HornetQ server to be started.

    lein ritz-hornetq --hornetq-server 5445

### Client

Add `ritz-nrepl-hornetq` to your `project.clj` or `:user` profile plugins:

    [ritz/ritz-nrepl-hornetq "0.5.1"]

You can then use lein to start a repl against a running HornetQ server.

    lein repl :connect hornetq://localhost:5445

### Embedding

Add `ritz/ritz-nrepl-hornet` to your projects dependencies. You start the server

```clj
(ns my-app
  (:use [ritz.nrepl-hornetq :only [start-server]]))

(start-server {:transport :netty :host "somehost" :port 5445})
```

## License

Copyright © 2012 Hugo Duncan

Distributed under the Eclipse Public License.
