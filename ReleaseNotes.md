# Release Notes

Current release is 0.7.0.

## 0.7.0

### Features

- Enable line breakpoints
  Enables setting of line breakpoints with nrepl-ritz-line-breakpoint,
  bound to C-c C-x C-b.  Requires nrepl-ritz-break-on-exception to be on.

- Recreate breakpoints on load-file
  Breakpoints were not being recreated when code was recompiled for
  load-file.

  To do this, it adds a message reply hook, for notifying a function on the
  reply to a specific message.

- Add a load-file middleware
  The load-file operation adds optional disabling of local var clearing,
  and dead var removal.

- Update to nrepl.el 1.6

- Update to tools.nrepl 0.2.1

- Use lein 2 plugin loading for hooks
  Removes the need to add explicit hooks.

### Fixes
- Update to latest dynapath to resolve transitive dep issues.
  Dynapath renamed core -> util for 0.2.0, released to central for 0.2.1
  (requiring a group), and dynapath.util/classpath-urls now contains a
  check to verify the given CL is readable, so I removed that check.

- Make nrepl-ritz-jack-in use nrepl-jack-in

- Correct the regex for implementation fields
  The regex for matching implementation fields in stack frames was wrong.

- Calculate condition-info correctly
  The exception message information was blank for breakpoint and step
  events.

- Use the nrepl tooling session

- Removes ritz-javadoc support that migrated into nrepl
  Javadoc support moved into nrepl core, so supporting javadoc in ritz is
  no longer necessary.

- Use clojure.walk/macroexpand for macroexpand-all

- Update datomic transactor version

- Add slime support for clojure-mode
  Slime support was dropped in clojure-mode:
  https://github.com/technomancy/clojure-mode/pull/115

- Disable byte-compile-warnings (calls to runtime functions from the CL
  package), refs #60

- Ensure encoding related JVM options are passed to the controlling debugger
  vm, refs #62
  The swank connection reader should use the encoding specified in the 
  projects JVM options (-Dswank.encoding=...)

- Use ".codePointCount" to calculate the length of a rpc message string, refs
  #62
  Java Unicode surrogate pairs consists of two chars and thus results in an
  invalid length when ".length" is used to calculate the packet length.

- Fix codeq lookup of qualified symbols

- Namespace qualify symbol passed to codeq when unqualified

## 0.6.0

### Features

- Update nrepl-ritz.el to depend on nrepl 0.1.5

- Update to tools.nrepl 0.2.0-RC1

- Extract nrepl middleware into nrepl-middleware

- Add support for nREPL over HornetQ

- Add nREPL codeq middleware
  Provides a M-x nrepl-codeq-def command, to show all defs for a symbol.

- Add nREPL project middleware
  Middleware to allow setting the classpath for different projects.

- Use dynapath for reading the effective classpath from the class loader.
  This allows class-browse/classpath-urls and jdi-vm/current-classpath to
  work with non-URLClassLoaders. It doesn't deal with adding event
  exclusions for non-URLClassLoaders.

- Deprecate slime-javadoc-local-paths for slime-ritz-javadoc-local-paths
  This is now a var, and is passed on each call to slime-javadoc. This
  makes the call to javadoc stateless and avoids problems when using
  load-project.

- Use the stratum to identify clojure frames

### Fixes

- Fix handling of debug in tracking-eval

- Fix handling of debug options in compile-string-for-emacs

- Clear aborts on every command
  This is a sledgehammer to clear :abort-to-level flags in the debugger. A 
  more elegant solution would be appreciated.

- Add doc and release profiles to nrepl-project and -codeq

- Handle VMDisconnectEvent

- Add hard kill of vm on quit-lisp

- Fix handling of specified middleware in nrepl server

- Fix formatting of long line

- Update nrepl-eval-request for compatibilty with nrepl master

- Don't filter describe response in nrepl

- Support ritz-swank port and host specification in project or environment
  In line with current lein repl, allow specification of LEIN_REPL_PORT and 
  LEIN_REPL_HOST environment variables, and the specificaiton of :host and
  :port in the project :repl-options.

- Use pathSeparatorChar when building the launch classpath, refs #52

- use a init file insteads of passing the clojure form as command line
  parameter, refs #52
  There seems to be an issue when using nested single/double quotes in 
  arguments passed to the LaunchingConnector (Windows platform only).

- Fix port in ritz-form (now passed as integer)

- add lein-profile when started with "--no-debug"
  The dependency is required to start the swank server.

- fix slime-break-on-exception
  The prefix arg was always converted to a number. Numbers are always 
  logically true.  Also "swank:break-on-exception" was invoked using the 
  string value "true" or "false", which are always logically true.

- Use System/out for logging by preference

- Fix add-all-connections-fn! calls

- Add exclusions to reduce number of unused transitive dependencies

- Pass string explicitly in thread startup form, rather than a symbol

- Update doc strings

- Add a function to return a sequence of field namv/values pairs
  Useful when exposing state without making remote method invocations.

- Use pathSeparatorChar when building the launch classpath
  Should address issues when starting on windows.

- Ensure tools.jar is on the classpath with lein ritz-nrepl
  Fixes #53

- Add connection for eval-string-in-frame tests

- Force use of clojure 1.4 in the controlling vm

- Fix recursive exception handling

- Fix named stepping restarts in ritz-swank

- Fix eval-in-frame for new classloader usage
  The eval-in-frame code was using the wrong clojure runtime.

- Add nrepl-ritz-propery-bounds

- Make exception handling more robust
  Ignore exceptions from the context control threads.


## 0.5.0

### Features

- Add slime commands for lein, load-project, and reload-project
  These functions work with the lein project.clj file. slime-ritz-lein lets
  you run lein in the debug vm. slime-ritz-load-project will switch to
  using the project for the current buffer. slime-ritz-reload-project will
  reload the current projects dependencies.

- Add nrepl-ritz-load-project
  Adds the ability to switch between projects.

- Add nrepl-ritz-lein
  Allow running lein tasks in the debug vm.

- Add nrepl-ritz-reset-repl
  Command to wipe out user namespaces.

- Add nrepl-ritz-undefine-symbol to nrepl repl mode map
  Bound to C-c C-u.

### Fixes

- Use nrepl completion op by default

- Fix nrepl-ritz-server-command
  Hadn't been updated to new cli flags in lein task

- Remove :jpda profile from lein ritz-nrepl

- Ensure :jvm-opts are passed to the debug vm
  The controlling debugger vm is now also started with fixed :jvm-opts

- Use JDI to talk to debug vm
  Removes the swank server in the debug vm.

- Fix ritz-in task for new arg processing in ritz task
  Fixes #41

## 0.4.2

### Fixes

- Fix some type declarations

- Fix quoting in ritz.jpda/jdi/launch

- Fix swank-fuzzy-test and add timeout-test

- Update ritz-nrepl to depend on published leiningen preview10

- Try and handled expired JPDA references

- Fix project :nrepl-middleware
  Removes the need for quoting, and adds the standard ritz-nrepl
  middleware.

- Ensure defonce and defmulti add :defonce metadata
  Fixes #33

### Features

- Add nrepl-ritz-reload-project
  This command will reload the project.clj file, compute a new classpath, 
  and reset the REPL to run with the new classpath. Creates a new nrepl
  session, and clears all user namespaces.

- Add slime-reset-repl
  Adds slime-reset-repl command, to reset a repl by unloading all user
  defined namespaces.

  Fixes #36

- Add ritz.repl-utils.namespaces/ununse to remove a use'd namespace

- Add a complete middleware using fuzzy-completion

- Factor out with-timeout into ritz.repl-utils.timeout

- Add debug option to tracking-eval

- Factor out source form tracking into r.repl-utils.source-forms

- Add tracking-eval middleware to ritz-nrepl README

- Update lein-ritz ritz task to use tools.cli
  The --no-debug option now allows starting the server without the
  debugger.

- Add nrepl-ritz-compile-expression
  Compiles the top level expression. Bound to C-c C-c.

- Add tracking-eval nREPL middleware
  Adds a eval middleware that tracks source forms.

- Add a defprotocol that caches it's Interface
  Provides a defprotocol that does not regenerate it's interface unless a
  chnge in protocol signature is detected.

  Closes #34

- Move with-compiler-options to ritz.ritz-utils.compile

- Add feature-cond to ritz.ritz-utils.clojure
  This allows feature based conditional compilation.

## 0.4.1

### Fixes
- Fix display of condition for breakpoints
  No longer tries to interpret an exception for the breakpoint event. 
  Fixes #26

- Fix symlink to slime-ritz.el in ritz jack-in payloads
  Fixes #31

- send back well formed events to slime

- Fix connection/set-namespace (called for side effects, but without an atom
  the namespace was being forgotten and namespace could not be changed)
  Fixes #29

- Add guard to all-connections function evaluation. Fixes an exception on
  closing a swank session.

### Features

- Add functions to return dependencies between namespaces
  dependent-on returns the sequence of namespaces that are dependent on a
  namespace. dependencies returns the sequence of namespaces that a
  namespace depends on.


## 0.4.0

This is a major refactoring into individual sub-projects, with the introduction
of nrepl support.

### features

- Add nrepl-ritz-jack-in command

- Add nrepl-ritz-toggle-nrepl-logging
  Toggles :trace level logging in the nrepl server.

- Add nREPL complete middleware implementation using simple-complete

- Add apropos, doc, and describe-symbol nrepl middleware

- Add javadoc middleware

- Add a test nrepl transport

- Add resolve of Java symbols in javadoc using ns-interns

- Factor out projects for repl-utils, debugger, swank and nrepl

- Update to nrepl 0.2.0-beta9 and add op metadata

- Make nrepl-ritz depend on the nrepl elisp package

- Add port of sldb to nrepl-ritz.el
  The port isn't completely functional yet, but will allow examination of
  stacktraces.

- Disable break on exception by default
  Adds bindings for C-c C-x b to slime-break-on-exception. With a prefix it
  will disable break on exception

- Add nrepl-ritz-undefine-symbol

- Add nrepl-ritz-javadoc
  Bound to C-x b, displays javadoc for the symbol at point

- Refactor thread listing
  Removes the associng of the thread list onto the context from the 
  ritz.jpda.debug/thread-list function, and pushes it into the swank code.

## 0.3.2

- Fix autoloads

- Fix hooks for lein1

## 0.3.1

- use `eval-after-load` instead of a load-hook
  This way slime-ritz will initialize properly if slime was already
  loaded..

- Remove cake support (no longer depends on useful)

- Fix markdown formatting

## 0.3.0

### Features

- Update arglist display to recognise partial

- Automatically add tools.jar to the classpath

- Make undefine-function work on namespace refers

- Make slime-load-file remove old vars

- Allow C-c C-l to be used in interactive development to clear old
  definitions on file load

- Add slime-undefine-function
  This will unmap any var

- Initial support for swell restart selection

- Only block user threads on exceptions, and breakpoints
  In order to allow code navigation and compilation while at a breakpoint,
  only suspend user threads.

- Enable setting of line breakpoints in java code

- Disable locals clearing when compiling with debug policy

- Allow for alternative announce message

- Add ignore restarts for exception throw and catch locations
  When a stack trace is presented in sldb, it now has restarts to allow
  ignoring throw and catch locations


### Fixes

- Make restarts robust to missing locations in exceptions

- Fix logic for disable-locals-clearing in 1.4.0

- Fix default exception filters so they actually work

- Filter the symbols used for arglist lookup

- Update default exception filters

- Fix ritz slime-mode hooks

- Process field when looking javadoc for ClassName/field

- Update for clojure-1.4 *compiler-options*

- Handle case with no catch location in r.j.debug/break-for?

- Add :source-path to location-data, and recognise SOURCE_FORM as clojure

- Make eval-region mimic clojure.lang.Compiler/load

- Add hook to put -sources jars on classpath
  To use this, add :hooks [ritz.add-sources]

- Fix eval of strings containing ns forms

- Add namespace tracking to eval requests
  Fixes #10

- Move to lein2 and clojure 1.3.0

- Remove cake :tasks from project.clj

- Add imported symbols to fuzzy completion

- Fix issue with simple class completion

- Pick up :jvm-opts from project map

- Switch to separate lein-ritz plugin, with ritz-in functionality

- Disable exception event requests when retrieving exception messages

- Ensure *e is set when an exception is thrown

## 0.2.1

- Tweak remote-swank-port to try and reduce hangs on startup

- Add display of data in data carrying exceptions

- Prevent exception when find-source-path is called with nil source-path

- Add a shortcut for resuming suspended threads

- Add update-indentation-information slime function

- Enable filtering of exceptions on exception message

- Enable saving and project specific initialisation of exception filters

- Enable slime-presentations and slime-media

- Correct instructions about breakpoint setting

- Fix compilation result message
  The slime compilation buffer should now work correctly

- Add guard for incorrect level count

- Add processing of :repl-init and :repl-init-script to lein task


## 0.2.0

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

## 0.1.7

- Add missing require for clojure.main

- Rename swank-clj to ritz

- Update cake task to use flatland/useful, since cake removed its cake.utils
  namespace.

- Improve the condition message to show keys for contrib.Condition
  Extra information is often attached to a Condition, and it is useful to
  be able to see this in the sldb trace.

- Ensure autodoc doesn't error on invalid symbols

- Implement slime autodoc


## 0.1.6

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

## 0.1.5

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

## 0.1.4

- Fix broken lein swank plugin

## 0.1.3

### Known Issues

- Broken lein swank plugin

### Changes

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

## 0.1.2

- Fix nth-part and last for inspector

- Improve robustness of stepping

- Add breakpoint listing to the slime selector

- Update 1.2.0 source jar requirements

## 0.1.1

- Add support for clojure 1.2.1, and 1.3.0-master-SNAPSHOT.

## 0.1.0

Initial release.
