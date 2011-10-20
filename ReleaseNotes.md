# Release Notes

Current release is 0.1.8.

* 0.1.8

- Update readme with section on exception filtering

- Remove java reflection in logging code

- Add exception filters
  The filters can be set by the IGNORE restart, and can be edited in the
  slime selector using the 'f key.

- Log issue with inability to send command to connection
  This occurs on init script processing (not fixed yet)

- Update to recent pallet versions

- Add stone excepetion display

- Put all clojure source dependencies into lein-multi config

- Display pprint of Stone or Condition exceptions

- Update readme to explicitly state the OS X doesn't have tools.jar

- Use clojure.main/with-bindings and flush output

- Factor out repl-utils/io

- Update logback versions and use :local-repo-classpath

- Changes to break-for-exception? and fix swank-clj references

- Normailse function formatting

- Update readme to mention openjdk in tools.jar setup, and list the exception
  that is raised if tools.jar is missing

- Add note to readme about tools.jar and maven

- Add pallet script to set up a dev environment for ritz
  Basic tmux, emacs, git install, with clone of ritz repo

* 0.1.7

- Add missing require for clojure.main

- Rename swank-clj to ritz

- Update cake task to use flatland/useful, since cake removed its cake.utils
  namespace.

- Improve the condition message to show keys for contrib.Condition
  Extra information is often attached to a Condition, and it is useful to
  be able to see this in the sldb trace.

- Ensure autodoc doesn't error on invalid symbols

- Implement slime autodoc


* 0.1.6

- Add debug/pprint-eval-string-in-frame
  This moves the pretty printing into the debugee

- Unmangle clojure names for local variables

- Fix pprint-eval-string-in-frame to correctly output a string

- Fix generation of nested exceptions on eval-in-frame

- Fix fuzzy completion of explicitly namespaced symbols

- Improve filtering of unimplimented arities in disassembly listings

- Try harder to maintain relative source paths when compiling

- Stop focussing repl on slime-javadoc

- Add support for invokePrim
  Clojure 1.3 introduces a new function invokePrim to handle primitive
  arguments.

- disable AOT
  In order for swank-clj to work across clojure versions, remove the aot.

- Fix return value of fuzzy-completions when no completions found

* 0.1.5

- Add slime-javadoc-local-paths and slime-javadoc
  slime-javadoc-local-paths can be used to set paths to local javadoc.

  slime-javadoc opens the javadoc for the symbol at point in a browser. It
  is bound to C-c b by default.

- Make compile-string-for-emacs more robust
  Do a better job of finding the namespace for compilation, and don't
  complain if the namespace is not found (use the repl's current namespace)

- Add slime-clj-connected-hook and slime-clj-repl-mode-hook
  When using slime with multiple lists, allow easy segregation of clojure
  specific setup

- Implement disassemble-form for slime-disassemble-symbol
  Enables disassembly of a function given its symbol

* 0.1.4

- Fix broken lein swank plugin

* 0.1.3

## Known Issues

- Broken lein swank plugin

## Changes

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
