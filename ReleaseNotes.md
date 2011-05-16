# Release Notes

Current release is 0.1.3.

* 0.1.3

- Add slime-disassemble to show bytecode for a frame
  When debugging it is sometimes useful to see the bytecode generated for a
  function. Pressing 'D' on a frame in the debugger opens a buffer with the
  bytecode.

- Propogate *compile-path* from lein through to debuggee
  *compile-path* is required for clojure.core/compile to work, and is
  depenedent on project setup. Ensure the lien plugin sets *compile-path*
  and forward this to the debuggee using the new-connection-hook, and a
  message with id 0, the reply to which is filtered and not returned to
  SLIME.

  Fixes #5

- Add slime-list-repl-forms
  It is useful to be able to list all forms entered at the repl.
  slime-list-repl-forms will show these in a new buffer.

- Remove the atom used for defslimefn
  defslimefn now just forwards to defn, adding some metadata to the
  function, and interning the function var into the
  swank-clj.swank.commands namespace.

- Fix for clojure-1.3.0-alpha7
  Missing import added

- Add autoload and keybinding for slime-line-breakpoint

* 0.1.2

- Fix nth-part and last for inspector

- Improve robustness of stepping

- Add breakpoint listing to the slime selector

- Update 1.2.0 source jar requirements

* 0.1.1

- Add support for clojure 1.2.1, and 1.3.0-master-SNAPSHOT.

* 0.1.0

Initial release.
