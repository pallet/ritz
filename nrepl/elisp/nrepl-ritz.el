;;; nrepl-ritz.el --- nrepl extensions for ritz
;;
;; Copyright 2012 Hugo Duncan
;;
;; Author: Hugo Duncan <hugo_duncan@yahoo.com>
;; Keywords: languages, lisp, nrepl
;; URL: https://github.com/pallet/ritz
;; Version: 0.6.0
;; Package-Requires: ((nrepl "0.1.5"))
;; License: EPL

(require 'nrepl)

(defcustom nrepl-ritz-server-command
  (if (or (locate-file nrepl-lein-command exec-path)
          (locate-file (format "%s.bat" nrepl-lein-command) exec-path))
      (format "%s ritz-nrepl" nrepl-lein-command)
    (format "echo \"%s ritz-nrepl\" | $SHELL -l" nrepl-lein-command))
  "The command used to start the nREPL via nrepl-ritz-jack-in.
For a remote nREPL server lein must be in your PATH.  The remote
proc is launched via sh rather than bash, so it might be necessary
to specific the full path to it. Localhost is assumed."
  :type 'string
  :group 'nrepl-mode)

;;;###autoload
(defun nrepl-ritz-jack-in (prompt-project)
  (interactive "P")
  (let* ((cmd (if prompt-project
                  (format "cd %s && %s" (ido-read-directory-name "Project: ")
                          nrepl-ritz-server-command)
                  nrepl-ritz-server-command))
         (process (start-process-shell-command
                   "nrepl-ritz-server" "*nrepl-server*" cmd)))
    (set-process-filter process 'nrepl-server-filter)
    (set-process-sentinel process 'nrepl-server-sentinel)
    (set-process-coding-system process 'utf-8-unix 'utf-8-unix)
    (message "Starting nREPL ritz server...")))

;;; overwrite nrepl.el functions to allow easy development of ritz.
;;; Maybe these could be put back into nrepl.el
(defvar nrepl-eval-op "eval"
  "nrepl op for eval of forms.")
(make-variable-buffer-local 'nrepl-eval-op)

(defun nrepl-eval-request (input &optional ns session)
  (append (if ns (list "ns" ns))
          (list
           "op" nrepl-eval-op
           "session" (or session (nrepl-current-session))
           "code" input)))

;;; # General helpers
(defun flatten-alist (alist)
  "Convert the ALIST into a list with a sequence of key and value."
  (if (cdr alist)
     (append (list (caar alist) (cdar alist)) (flatten-alist (cdr alist)))
   (list (caar alist) (cdar alist))))

(defun alist-from-list (l)
  "Convert the list L containing a sequence key and value into an alist."
  (if l
      (append (list (cons (car l) (cadr l))) (alist-from-list (cddr l)))))

(defun nrepl-alist-get (alist &rest keys)
  "Lookup the specified KEYS in ALIST, returning a list of their values.
    (nrepl-alist-get '((\"a\" . 1)(\"b\" . 1)(\"c\" . 3)) \"c\" \"a\")
       => (3 1)"
  (mapcar (lambda (key) (cdr (assoc key alist))) keys))

(defun nrepl-keywordise (alist)
  "Takes a list, and turns every other value into a keyword. This is useful
   for passing lists to destructuring-bind with a &key pattern."
  (when (car alist)
    (append
     (list (intern (concat ":" (car alist))) (cadr alist))
     (nrepl-keywordise (cddr alist)))))

(defun nrepl-length= (seq n)
  "Return (= (length SEQ) N)."
  (etypecase seq
    (list
     (cond ((zerop n) (null seq))
           ((let ((tail (nthcdr (1- n) seq)))
              (and tail (null (cdr tail)))))))
    (sequence
     (= (length seq) n))))

(defun nrepl-ritz-flash-region (start end &optional timeout)
  "Temporarily highlight region from START to END."
  (let ((overlay (make-overlay start end)))
    (overlay-put overlay 'face 'secondary-selection)
    (run-with-timer (or timeout 0.2) nil 'delete-overlay overlay)))

;;; # Requests
(defun nrepl-ritz-send-op (op callback attributes)
  (lexical-let ((request (append
                          (list "op" op "session" (nrepl-current-session))
                          (mapcar 'prin1-to-string attributes))))
    (nrepl-send-request request callback)))

(defun nrepl-ritz-send-op-strings (op callback attributes)
  (lexical-let ((request (append
                          (list "op" op "session" (nrepl-current-session))
                          attributes)))
    (nrepl-send-request request callback)))

(defun nrepl-ritz-send-dbg-op (op on-value &rest attributes)
  "If ON-VALUE is supplied, then it is called when a value is
returned otherwise an on-done callback is created to close the
current buffer.

Debug ops are expected to return a list containing a stream of
name value pairs. The names are converted to keywords before
passing to the ON-VALUE callback."
  (lexical-let ((request
                 (append
                  (list "op" op
                        "session" (nrepl-current-session)
                        "thread-id" (prin1-to-string nrepl-dbg-thread-id))
                  (mapcar 'prin1-to-string attributes)))
                (f on-value))
    (nrepl-send-request
     request
     (nrepl-make-response-handler
      (current-buffer)
      (when on-value
        (lambda (buffer value)
          (when value
            (apply f (nrepl-keywordise value)))))
      nil nil
      (unless on-value
        (lambda (buffer) (kill-buffer buffer)))))))

;;; send function for a jpda op
(defun nrepl-ritz-send-debug (input ns callback)
  (nrepl-send-request (list "op" "jpda"
                            "stdin" ""
                            "session" (nrepl-current-session)
                            "ns" ns
                            "code" input)
                      callback))

;;; # Helpers
(defmacro nrepl-ritz-define-keys (keymap &rest key-command)
  "Define keys in KEYMAP. Each KEY-COMMAND is a list of (KEY COMMAND)."
  `(progn . ,(mapcar (lambda (k-c) `(define-key ,keymap . ,k-c))
                     key-command)))

(put 'nrepl-ritz-define-keys 'lisp-indent-function 1)


(defun nrepl-ritz-filter-buffers (predicate &optional buffers)
  "Return a list of buffers where PREDICATE returns
true. PREDICATE is executed in the buffer to test."
  (lexical-let ((buffers (or buffers (buffer-list))))
    (remove-if-not (lambda (%buffer)
                   (with-current-buffer %buffer
                     (funcall predicate)))
                   buffers)))

(defun nrepl-ritz-add-face (face string)
  (add-text-properties 0 (length string) (list 'face face) string)
  string)

(put 'nrepl-ritz-add-face 'lisp-indent-function 1)

(defmacro nrepl-ritz-with-rigid-indentation (level &rest body)
  "Execute BODY and then rigidly indent its text insertions.
Assumes all insertions are made at point."
  (let ((start (gensym)) (l (gensym)))
    `(let ((,start (point)) (,l ,(or level '(current-column))))
       (prog1 (progn ,@body)
         (nrepl-ritz-indent-rigidly ,start (point) ,l)))))

(put 'nrepl-ritz-with-rigid-indentation 'lisp-indent-function 1)

(defun nrepl-ritz-indent-rigidly (start end column)
  "indent-rigidly, without inheriting text properties."
  (let ((indent (make-string column ?\ )))
    (save-excursion
      (goto-char end)
      (beginning-of-line)
      (while (and (<= start (point))
                  (progn
                    (insert-before-markers indent)
                    (zerop (forward-line -1))))))))

(defun nrepl-ritz-insert-indented (&rest strings)
  "Insert all STRINGS rigidly indented."
  (nrepl-ritz-with-rigid-indentation nil
    (apply #'insert strings)))

(defsubst nrepl-ritz-insert-propertized (props &rest args)
  "Insert all ARGS and then add text-PROPS to the inserted text."
  (nrepl-propertize-region props (apply #'insert args)))

(defun nrepl-ritz-property-bounds (prop)
  "Return the positions of the previous and next changes to PROP.
PROP is the name of a text property."
  (assert (get-text-property (point) prop))
  (let ((end (next-single-char-property-change (point) prop)))
    (list (previous-single-char-property-change end prop) end)))

;;; # Goto source locations
(defun nrepl-ritz-show-buffer-position (position &optional recenter)
  "Ensure sure that the POSITION in the current buffer is visible."
  (let ((window (display-buffer (current-buffer) t)))
    (save-selected-window
      (select-window window)
      (goto-char position)
      (ecase recenter
        (top    (recenter 0))
        (center (recenter))
        ((nil)
         (unless (pos-visible-in-window-p)
           (cond ((= (current-column) 0) (recenter 1))
                 (t (recenter)))))))))

(defun nrepl-ritz-goto-location-buffer (zip file source-form)
  (cond
    (file
     (let ((filename file))
       (set-buffer (or (get-file-buffer filename)
                       (let ((find-file-suppress-same-file-warnings t))
                         (find-file-noselect filename))))))
    (zip
     (destructuring-bind (file entry) zip
       (require 'arc-mode)
       (set-buffer (find-file-noselect file t))
       (goto-char (point-min))
       (re-search-forward (concat "  " entry "$"))
       (let ((buffer (save-window-excursion
                       (archive-extract)
                       (current-buffer))))
         (set-buffer buffer)
         (goto-char (point-min)))))
    (source-form
     (set-buffer (get-buffer-create "*nREPL source*"))
     (erase-buffer)
     (clojure-mode)
     (insert source-form)
     (goto-char (point-min)))))

(defun nrepl-ritz-goto-location-position (line)
  (cond
    (line
     (goto-char (point-min))
     (beginning-of-line line)
     (skip-chars-forward " \t"))))

(defun nrepl-ritz-location-offset (line)
  "Return the position, as character number, of LOCATION."
  (save-restriction
    (widen)
    (nrepl-ritz-goto-location-position line)
    (point)))

(defun nrepl-ritz-goto-source-location (zip file line &optional noerror)
  "Move to the source location LOCATION.  Several kinds of locations
are supported:

<location> ::= (:location <buffer> <position> <hints>)
             | (:error <message>)

<buffer>   ::= (:file <filename>)
             | (:buffer <buffername>)
             | (:buffer-and-file <buffername> <filename>)
             | (:source-form <string>)
             | (:zip <file> <entry>)

<position> ::= (:position <fixnum>) ; 1 based (for files)
             | (:offset <start> <offset>) ; start+offset (for C-c C-c)
             | (:line <line> [<column>])
             | (:function-name <string>)
             | (:source-path <list> <start-position>)
             | (:method <name string> <specializers> . <qualifiers>)"
  (nrepl-ritz-goto-location-buffer zip file source-form)
  (let ((pos (nrepl-ritz-location-offset line)))
    (cond ((and (<= (point-min) pos) (<= pos (point-max))))
          (widen-automatically (widen))
          (t
           (error "Location is outside accessible part of buffer")))
    (goto-char pos)))

(defun nrepl-ritz-show-source-location
  (zip file line source-form &optional no-highlight-p)
  "Show the source location, but don't hijack focus."
  (save-selected-window
    (nrepl-ritz-goto-source-location zip file line source-form)
    (unless no-highlight-p (nrepl-ritz-highlight-sexp))
    (nrepl-ritz-show-buffer-position (point))))

(defun nrepl-ritz-highlight-sexp (&optional start end)
  "Highlight the first sexp after point."
  (let ((start (or start (point)))
        (end (or end (save-excursion (ignore-errors (forward-sexp)) (point)))))
    (nrepl-ritz-flash-region start end)))

(defun nrepl-ritz-highlight-line (&optional timeout)
  (nrepl-ritz-flash-region (+ (line-beginning-position) (current-indentation))
                      (line-end-position)
                      timeout))

;;; logging
(defun nrepl-ritz-toggle-nrepl-logging ()
  "Describe symbol."
  (interactive)
  (nrepl-send-string
   "(require 'ritz.logging)"
   (nrepl-make-response-handler (current-buffer) nil nil nil nil)
   nrepl-buffer-ns)
  (nrepl-send-string
   "(ritz.logging/toggle-level :trace)"
   (nrepl-make-response-handler (current-buffer) nil nil nil nil)
   nrepl-buffer-ns))


;;; jpda commands
(defun nrepl-ritz-threads ()
  (interactive)
  (nrepl-ritz-send-debug
   "(ritz.nrepl.debug/threads)"
   "user"
   (nrepl-make-response-handler
    (nrepl-popup-buffer "*nREPL threads*" t)
    nil
    'nrepl-emit-into-popup-buffer nil nil)))

;;; describe
(defun nrepl-ritz-describe-symbol-input-handler (symbol-name)
  "Describe symbol."
  (when (not symbol-name)
    (error "No symbol given"))
  (nrepl-ritz-send-op-strings
   "describe-symbol"
   (nrepl-make-response-handler
    (current-buffer)
    (lambda (buffer description)
      (with-current-buffer (nrepl-popup-buffer "*nREPL apropos*" t)
        (let ((inhibit-read-only t)
              (buffer-undo-list t)
              (standard-output (current-buffer)))
          (destructuring-bind (&key symbol-name type arglists doc)
              (nrepl-keywordise description)
            (nrepl-ritz-print-apropos
             :symbol-name symbol-name
             :type type
             :arglists arglists
             :doc (concat "\n\n" doc))))))
    nil nil nil)
   `("symbol" ,symbol-name "ns" ,nrepl-buffer-ns)))

(defun nrepl-ritz-describe-symbol (query)
  "Browse describe-symbol on the Java class at point."
  (interactive "P")
  (nrepl-read-symbol-name
   "Describe symbol: " 'nrepl-ritz-describe-symbol-input-handler query))

;;; compeletion
(defun nrepl-completion-complete-op-fn (str)
  "Return a list of completions using the nREPL \"complete\" op."
  (lexical-let ((strlst (plist-get
                         (nrepl-send-request-sync
                          (list "op" "complete"
                                "session" (nrepl-current-session)
                                "ns" nrepl-buffer-ns
                                "symbol" str))
                         :value)))
    (when strlst
      (car strlst))))

;; If the version of nrepl.el has nrepl-completion-fn, enable this using:
(setq nrepl-completion-fn 'nrepl-completion-complete-op-fn)

;;; apropos
(defun nrepl-ritz-call-describe (arg)
  (let* ((pos (if (markerp arg) arg (point)))
         (item (get-text-property pos 'item)))
    (nrepl-ritz-describe-symbol item)))

(defvar nrepl-ritz-apropos-label-properties
  (progn
    (require 'apropos)
    (cond ((and (boundp 'apropos-label-properties)
                (symbol-value 'apropos-label-properties)))
          ((boundp 'apropos-label-face)
           (etypecase (symbol-value 'apropos-label-face)
             (symbol `(face ,(or (symbol-value 'apropos-label-face)
                                 'italic)
                            mouse-face highlight))
             (list (symbol-value 'apropos-label-face)))))))

(defun nrepl-ritz-print-apropos-property (symbol-name property value label)
  (let ((start (point)))
    (princ "  ")
    (nrepl-ritz-insert-propertized nrepl-ritz-apropos-label-properties label)
    (princ ": ")
    (princ (etypecase value
             (string value)
             ((member :not-documented) "(not documented)")))
    (add-text-properties
     start (point)
     (list 'type property
           'action 'nrepl-ritz-call-describe
           'button t
           'apropos-label label
           'item symbol-name
           'help-echo (format "Describe %s" symbol-name)))
    (terpri)))

(defun nrepl-ritz-print-apropos (&rest args)
  (destructuring-bind (&key symbol-name type arglists doc) args
    (assert symbol-name)
    (nrepl-ritz-insert-propertized `(face ,apropos-symbol-face) symbol-name)
    (terpri)
    (let ((apropos-label-properties slime-apropos-label-properties))
      (cond
       ((string= type "variable")
        (nrepl-ritz-print-apropos-property symbol-name type doc "Variable"))
       ((string= type "function")
        (nrepl-ritz-print-apropos-property
         symbol-name type (concat (format "%s" arglists) "  " doc) "Function"))
       ((string= type "macro")
        (nrepl-ritz-print-apropos-property
         symbol-name type (concat (format "%s" arglists) "  " doc) "Macro"))))))

(defun nrepl-ritz-apropos-handler (args)
  "Send apropos request and show response"
  (nrepl-ritz-send-op-strings
   "apropos"
   (nrepl-make-response-handler
    (current-buffer)
    (lambda (buffer apropos)
      (with-current-buffer (nrepl-popup-buffer "*nREPL apropos*" t)
        (let ((inhibit-read-only t)
              (buffer-undo-list t)
              (standard-output (current-buffer)))
          (set (make-local-variable 'truncate-lines) t)
          (dolist (a apropos)
            (apply 'nrepl-ritz-print-apropos (nrepl-keywordise a))))))
    nil nil nil)
   args))

(defun nrepl-ritz-apropos (symbol-name &optional public-only-p ns
                                       case-sensitive-p)
  "Show apropos at point."
  (interactive
   (if current-prefix-arg
       (list (read-string "Clojure apropos: ")
             (y-or-n-p "Public symbols only? ")
             (let ((ns (read-string "Namespace: ")))
               (if (string= ns "") nil ns))
             (y-or-n-p "Case-sensitive? "))
     (list (read-string "Clojure apropos: ") t nil nil)))
  (nrepl-ritz-apropos-handler
   `("symbol" ,symbol-name
     ,@(when ns `("ns" ,ns))
     "prefer-ns" ,nrepl-buffer-ns
     ,@(when public-only-p `("public-only?" "true"))
     ,@(when case-sensitive-p `("case-sensitive?" "true")))))

(defun nrepl-ritz-apropos-all (symbol-name)
  "Show apropos at point."
  (interactive)
  (nrepl-ritz-apropos (read-string "Clojure apropos: ") nil nil))

;;; javadoc browsing
(defvar nrepl-ritz-javadoc-local-paths nil)

(defun nrepl-ritz-javadoc-input-handler (symbol-name)
  "Browse javadoc on the Java class at point."
  (when (not symbol-name)
    (error "No symbol given"))
  (nrepl-ritz-send-op-strings
   "javadoc"
   (nrepl-make-response-handler
    (current-buffer)
    (lambda (buffer url)
      (if url
          (browse-url url)
        (error "No javadoc url for %s" symbol-name)))
    nil nil nil)
   `("symbol" ,symbol-name "ns" ,nrepl-buffer-ns
     "local-paths" ,(mapconcat #'identity nrepl-ritz-javadoc-local-paths " "))))

(defun nrepl-ritz-javadoc (query)
  "Browse javadoc on the Java class at point."
  (interactive "P")
  (nrepl-read-symbol-name
   "Javadoc for: " 'nrepl-ritz-javadoc-input-handler query))

(define-key nrepl-interaction-mode-map (kbd "C-c b") 'nrepl-ritz-javadoc)
(define-key nrepl-mode-map (kbd "C-c b") 'nrepl-ritz-javadoc)

;;; codeq def browsing
(defvar nrepl-codeq-url "datomic:free://localhost:4334/git")

(defun nrepl--codeq-def-insert-def (def)
  (destructuring-bind (def datetime) def
    (insert "        " datetime "\n"
            def "\n\n\n")))

(defun nrepl-codeq-def-handler (symbol-name)
  "Display codeq defs for symbol-name."
  (when (not symbol-name)
    (error "No symbol given"))
  (nrepl-ritz-send-op-strings
   "codeq-def"
   (nrepl-make-response-handler
    (current-buffer)
    (lambda (buffer value)
      (if value
          (with-current-buffer (nrepl-popup-buffer "*nREPL codeq*" t)
            (let ((inhibit-read-only t))
              (mapc 'nrepl--codeq-def-insert-def value)))
        (error "No codeq def for %s" symbol-name)))
    nil nil nil)
   `("symbol" ,symbol-name "ns" ,nrepl-buffer-ns
     "datomic-url" ,nrepl-codeq-url)))

(defun nrepl-codeq-def (query)
  "Display codeq defs for symbol at point"
  (interactive "P")
  (nrepl-read-symbol-name
   "Codeq defs for: " 'nrepl-codeq-def-handler query))

;;; undefine symbol
(defun nrepl-ritz-undefine-symbol-handler (symbol-name)
  "Undefine on the symbol at point."
  (when (not symbol-name)
    (error "No symbol given"))
  (nrepl-send-string
   (format "(ns-unmap '%s '%s)" nrepl-buffer-ns symbol-name)
   (nrepl-make-response-handler (current-buffer) nil nil nil nil)
   nrepl-buffer-ns))

(defun nrepl-ritz-undefine-symbol (query)
  "Undefine the symbol at point."
  (interactive "P")
  (nrepl-read-symbol-name
   "Undefine: " 'nrepl-ritz-undefine-symbol-handler query))

(define-key
  nrepl-interaction-mode-map (kbd "C-c C-u") 'nrepl-ritz-undefine-symbol)
(define-key
  nrepl-mode-map (kbd "C-c C-u") 'nrepl-ritz-undefine-symbol)

(defun nrepl-ritz-compile-expression (&optional prefix)
  "Compile the current toplevel form."
  (interactive "P")
  (apply
   #'nrepl-ritz-compile-region
   prefix (nrepl-region-for-expression-at-point)))

(defun nrepl-ritz-compile-region (prefix start end)
  "Compile the current toplevel form."
  (interactive "Pr")
  (nrepl-ritz-flash-region start end)
  (let ((form (buffer-substring-no-properties start end)))
    (nrepl-ritz-send-op-strings
     "eval"
     (nrepl-make-response-handler
      (current-buffer)
      (lambda (buffer description)
        (message description))
      (lambda (buffer out) (message out))
      (lambda (buffer err) (message err))
      nil)
     `("code" ,form
       "debug" ,(if prefix "true" "false")
       "ns" ,nrepl-buffer-ns))))

(define-key
  nrepl-interaction-mode-map (kbd "C-c C-c") 'nrepl-ritz-compile-expression)

;;; Lein
(defun nrepl-ritz-lein (arg-string)
  "Run leiningen."
  (interactive "slein ")
  (nrepl-ritz-send-op
   "lein"
   (nrepl-make-response-handler
    (nrepl-popup-buffer "*nREPL lein*" t)
    (lambda (buffer description)
      (message description))
    'nrepl-emit-into-popup-buffer
    'nrepl-emit-into-popup-buffer
    (lambda (buffer) (message "lein done")))
   `(args ,(split-string arg-string " "))))


;;; Reset repl
(defun nrepl-ritz-reset-repl ()
  "Reload project.clj."
  (interactive)
  (nrepl-ritz-send-op-strings
   "reset-repl"
   (nrepl-make-response-handler
    (current-buffer)
    (lambda (buffer description)
      (message description))
    (lambda (buffer out) (message out))
    (lambda (buffer err) (message err))
    nil)
   `()))


;;; Reload project.clj
(defun nrepl-ritz-recreate-session-handler ()
  (lambda (response)
    (message "Requesting new session completed")
    (nrepl-dbind-response response (id new-session)
      (cond (new-session
             (message "Loaded project.")
             (setq nrepl-session new-session))))))

(defun nrepl-ritz-recreate-session ()
  (message "Requesting new session")
  (nrepl-create-client-session (nrepl-ritz-recreate-session-handler)))

(defun nrepl-ritz-reload-project ()
  "Reload project.clj."
  (interactive)
  (nrepl-ritz-send-op-strings
   "reload-project"
   (nrepl-make-response-handler
    (current-buffer)
    (lambda (buffer description)
      (message description))
    (lambda (buffer out) (message out))
    (lambda (buffer err) (message err))
    (lambda (buffer) (with-current-buffer buffer
                       (nrepl-ritz-recreate-session))))
   `()))


(defun nrepl-ritz-load-project (prompt-project)
  "Reload project.clj."
  (interactive "P")
  (let* ((dir (if prompt-project
                 (ido-read-directory-name "Project: ")
               (expand-file-name
                (locate-dominating-file buffer-file-name "src/"))))
         (file (concat dir "project.clj")))
    (message "Loading %s..." file)
    (nrepl-ritz-send-op-strings
     "load-project"
     (nrepl-make-response-handler
      (current-buffer)
      (lambda (buffer description)
        (message description))
      (lambda (buffer out) (message out))
      (lambda (buffer err) (message err))
      (lambda (buffer) (with-current-buffer buffer
                         (nrepl-ritz-recreate-session))))
     `("project-file" ,file))))

;;; # Minibuffer
(defvar nrepl-ritz-minibuffer-map
  (let ((map (make-sparse-keymap)))
    (set-keymap-parent map minibuffer-local-map)
    (define-key map "\t" 'nrepl-complete)
    (define-key map "\M-\t" 'nrepl-complete)
    map)
  "Minibuffer keymap with nREPL completion and clojure syntax table.")

(defvar nrepl-ritz-minibuffer-history '()
  "History list of expressions read from the minibuffer.")

(defun nrepl-ritz-minibuffer-setup-hook ()
  (cons (lexical-let ((namespace (nrepl-current-ns))
                      (session (nrepl-current-session)))
          (lambda ()
            (setq nrepl-buffer-ns namespace)
            (setq nrepl-session session)
            (set-syntax-table clojure-mode-syntax-table)))
        minibuffer-setup-hook))

(defun nrepl-ritz-read-from-minibuffer
  (prompt &optional default-value history)
  "Read a string from the minibuffer, prompting with string PROMPT.
If DEFAULT-VALUE is non-nil, it is inserted into the minibuffer before
reading input.  The result is a string (\"\" if no input was given)."
  (let ((minibuffer-setup-hook (nrepl-ritz-minibuffer-setup-hook)))
    (read-from-minibuffer
     prompt nil nrepl-ritz-minibuffer-map nil
     (or history 'nrepl-ritz-minibuffer-history) default-value)))


;;; # Debugger
;; Based on SLDB

;;; ## Customisation and vars
(defgroup nrepl-debugger nil
  "Stacktrace options and fontification."
  :prefix "nrepl-dbg-"
  :group 'nrepl)

(defcustom nrepl-dbg-initial-restart-limit 6
  "Maximum number of restarts to display initially."
  :group 'nrepl-debugger
  :type 'integer)

(nrepl-make-variables-buffer-local
 (defvar nrepl-dbg-exception nil
   "A list (DESCRIPTION TYPE) describing the exception being debugged.")

 (defvar nrepl-dbg-restarts nil
   "List of (NAME DESCRIPTION) for each available restart.")

 (defvar nrepl-dbg-level nil
   "Current debug level (recursion depth) displayed in buffer.")

 (defvar nrepl-dbg-stacktrace-start-marker nil
   "Marker placed at the first frame of the stacktrace.")

 (defvar nrepl-dbg-restart-list-start-marker nil
  "Marker placed at the first restart in the restart list.")

 (defvar nrepl-dbg-continuations nil
   "List of ids for pending continuation.")

 (defvar nrepl-dbg-thread-id nil
  "Thread associated with a buffer"))

(defvar nrepl-dbg-show-java-frames t
  "Whether to show java frames or not")

(defmacro define-nrepl-dbg-faces (&rest faces)
  "Define the set of faces used in the debugger.
Each face specifiation is (NAME DESCRIPTION &optional PROPERTIES).
NAME is a symbol; the face will be called sldb-NAME-face.
DESCRIPTION is a one-liner for the customization buffer.
PROPERTIES specifies any default face properties."
  `(progn ,@(loop for face in faces
                  collect `(define-nrepl-dbg-face ,@face))))

(defmacro define-nrepl-dbg-face (name description &optional default)
  (let ((facename (intern (format "nrepl-dbg-%s-face" (symbol-name name)))))
    `(defface ,facename
       (list (list t ,default))
      ,(format "Face for %s." description)
      :group 'nrepl-debugger)))

(define-nrepl-dbg-faces
  (topline        "the top line describing the error")
  (exception      "the exception class")
  (section        "the labels of major sections in the debugger buffer")
  (frame-label    "stacktrace frame numbers")
  (restart-number "restart numbers (correspond to keystrokes to invoke)"
                  '(:bold t))
  (restart-type   "restart names")
  (restart        "restart descriptions")
  (frame-line     "function names and arguments in the stacktrace")
  (detailed-frame-line
   "function names and arguments in a detailed (expanded) frame")
  (local-name     "local variable names")
  (local-value    "local variable values"))

(defmacro nrepl-dbg-in-face (name string)
  "Return STRING with a face property of nrepl-dbg-NAME-face."
  `(propertize
    ,string 'face ',(intern (format "nrepl-dbg-%s-face" (symbol-name name)))))

(put 'nrepl-dbg-in-face 'lisp-indent-function 1)

;;; ## nrepl-dbg mode
(defvar nrepl-dbg-mode-syntax-table
  (let ((table (copy-syntax-table clojure-mode-syntax-table)))
    ;; We give < and > parenthesis syntax, so that #< ... > is treated
    ;; as a balanced expression.  This enables autodoc-mode to match
    ;; #<unreadable> actual arguments in the stacktraces with formal
    ;; arguments of the function.  (For clojure mode, this is not
    ;; desirable, since we do not wish to get a mismatched paren
    ;; highlighted everytime we type < or >.)
    (modify-syntax-entry ?< "(" table)
    (modify-syntax-entry ?> ")" table)
    table)
  "Syntax table for NREPL-DBG mode.")

(define-derived-mode nrepl-dbg-mode fundamental-mode "nrepl-dbg"
  "nREPL debugger mode.
In addition to ordinary nREPL commands, the following are
available:\\<nrepl-dbg-mode-map>

Commands to examine the selected frame:
   \\[nrepl-dbg-toggle-details]   - toggle details (locals)
   \\[nrepl-dbg-show-source]   - view source for the frame
   \\[nrepl-dbg-eval-in-frame]   - eval in frame
   \\[nrepl-dbg-pprint-eval-in-frame]   - eval in frame, pretty-print result
   \\[nrepl-dbg-disassemble]   - disassemble
   \\[nrepl-dbg-inspect-in-frame]   - inspect

Commands to invoke restarts:
   \\[nrepl-dbg-quit]   - quit
   \\[nrepl-dbg-abort]   - abort
   \\[nrepl-dbg-continue]   - continue
   \\[nrepl-dbg-invoke-restart-0]-\\[nrepl-dbg-invoke-restart-9] - restart shortcuts
   \\[nrepl-dbg-invoke-named-restart]   - invoke restart by name

Commands to navigate frames:
   \\[nrepl-dbg-frame-down]   - down
   \\[nrepl-dbg-frame-up]   - up
   \\[nrepl-dbg-details-down] - down, with details
   \\[nrepl-dbg-details-up] - up, with details
   \\[nrepl-dbg-cycle] - cycle between restarts & stacktrace
   \\[nrepl-dbg-beginning-of-stacktrace]   - beginning of stacktrace
   \\[nrepl-dbg-end-of-stacktrace]   - end of stacktrace

Miscellaneous commands:
   \\[nrepl-dbg-step-into]   - step
   \\[nrepl-interactive-eval]   - eval
   \\[nrepl-dbg-inspect-exception]   - inspect thrown exception

Full list of commands:

\\{nrepl-dbg-mode-map}"
  (erase-buffer)
  (set-syntax-table nrepl-dbg-mode-syntax-table)
  (set (make-local-variable 'truncate-lines) t)
  (setq nrepl-session (nrepl-current-session)))

(set-keymap-parent nrepl-dbg-mode-map nrepl-mode-map)

(nrepl-ritz-define-keys nrepl-dbg-mode-map
  ((kbd "RET") 'nrepl-dbg-default-action)
  ("\C-m"      'nrepl-dbg-default-action)
  ([return] 'nrepl-dbg-default-action)
  ([mouse-2]  'nrepl-dbg-default-mouse-action)
  ([follow-link] 'mouse-face)
  ("\C-i" 'nrepl-dbg-cycle)
  ("h"    'describe-mode)
  ("v"    'nrepl-dbg-show-source)
  ("e"    'nrepl-dbg-eval-in-frame)
  ("d"    'nrepl-dbg-pprint-eval-in-frame)
  ("D"    'nrepl-dbg-disassemble)
  ("i"    'nrepl-dbg-inspect-in-frame)
  ("n"    'nrepl-dbg-frame-down)
  ("p"    'nrepl-dbg-frame-up)
  ("\M-n" 'nrepl-dbg-details-down)
  ("\M-p" 'nrepl-dbg-details-up)
  ("<"    'nrepl-dbg-beginning-of-stacktrace)
  (">"    'nrepl-dbg-end-of-stacktrace)
  ("t"    'nrepl-dbg-toggle-details)
  ("j"    'nrepl-dbg-toggle-java-frames)
  ("I"    'nrepl-dbg-invoke-named-restart)
  ("c"    'nrepl-dbg-continue)
  ("s"    'nrepl-dbg-step-into)
  ("x"    'nrepl-dbg-step-over)
  ("o"    'nrepl-dbg-step-out)
  ("a"    'nrepl-dbg-abort)
  ("q"    'nrepl-dbg-quit)
  ("P"    'nrepl-dbg-print-exception)
  ("C"    'nrepl-dbg-inspect-exception)
  (":"    'nrepl-interactive-eval))

;; Keys 0-9 are shortcuts to invoke particular restarts.
(dotimes (number 10)
  (lexical-let
      ((fname (intern (format "nrepl-dbg-invoke-restart-%S" number)))
       (docstring (format "Invoke restart number %S." number)))
    (eval `(defun ,fname ()
             ,docstring
             (interactive)
             (nrepl-dbg-invoke-restart ,number)))
    (define-key nrepl-dbg-mode-map (number-to-string number) fname)))

;;; ## Buffer creation and update
(defun nrepl-dbg-buffers (&optional session)
  "Return a list of all nrepl-dbg buffers belonging to SESSION."
  (lexical-let ((pred (if session
                          (lambda ()
                            (and (eq nrepl-session session)
                                 (eq major-mode 'nrepl-dbg-mode)))
                        (lambda () (eq major-mode 'nrepl-dbg-mode)))))
    (nrepl-ritz-filter-buffers pred)))

(defun nrepl-dbg-find-buffer (thread &optional session)
  (lexical-let ((session (or session (nrepl-current-session))))
    (car
     (nrepl-ritz-filter-buffers
      (lambda () (eq nrepl-dbg-thread-id thread))
      (nrepl-dbg-buffers session)))))

(defun nrepl-dbg-get-buffer (thread &optional session)
  "Find or create a nrepl-dbg-buffer for THREAD."
  (lexical-let ((session (or session (nrepl-current-session))))
    (or (nrepl-dbg-find-buffer thread session)
        (let ((name (format "*nrepl-dbg %s*" thread)))
          (with-current-buffer (generate-new-buffer name)
            (setq nrepl-session session
                  nrepl-dbg-thread-id thread)
            (current-buffer))))))


(defun nrepl-dbg-setup (thread level exception restarts frames &optional force)
  "Setup a new NREPL-DBG buffer.
EXCEPTION is a string describing the exception being debugged.
RESTARTS is a list of strings (NAME DESCRIPTION) for each available restart.
FRAMES is a list (NUMBER DESCRIPTION &optional PLIST) describing the initial
portion of the stacktrace. Frames are numbered from 0."
  (with-current-buffer (nrepl-dbg-get-buffer thread)
    (unless (and (equal nrepl-dbg-level level) (not force))
      (let ((inhibit-read-only t))
        (nrepl-dbg-mode)
        (setq nrepl-dbg-thread-id thread)
        (setq nrepl-dbg-level level)
        (setq mode-name (format "nrepl-dbg[%d]" nrepl-dbg-level))
        (setq nrepl-dbg-exception exception)
        (setq nrepl-dbg-restarts restarts)
        (nrepl-dbg-insert-exception exception)
        (insert "\n\n" (nrepl-dbg-in-face section "Restarts:") "\n")
        (setq nrepl-dbg-restart-list-start-marker (point-marker))
        (nrepl-dbg-insert-restarts restarts 0 nrepl-dbg-initial-restart-limit)
        (insert "\n" (nrepl-dbg-in-face section "Stacktrace:") "\n")
        (setq nrepl-dbg-stacktrace-start-marker (point-marker))
        (save-excursion
          (if frames
              (nrepl-dbg-insert-frames
               (nrepl-dbg-prune-initial-frames frames) t)
            (insert "[No stacktrace]")))
        (run-hooks 'nrepl-dbg-hook)
        (set-syntax-table lisp-mode-syntax-table))
      (setq buffer-read-only t))
    (pop-to-buffer (current-buffer))))

(defun nrepl-dbg-activate (thread level select)
  "Display the debugger buffer for THREAD.
If LEVEL isn't the same as in the buffer reinitialize the buffer."
  (or (lexical-let ((buffer (nrepl-dbg-find-buffer thread)))
        (when buffer
          (with-current-buffer buffer
            (when (equal nrepl-dbg-level level)
              (when select (pop-to-buffer (current-buffer)))
              t))))
      (nrepl-dbg-reinitialize thread level)))

(defun nrepl-dbg-reinitialize (thread level)
  (nrepl-ritz-send-op
   "debugger-info"
   (nrepl-make-response-handler
    (current-buffer)
    (lambda (buffer value)
      (lexical-let ((v (nrepl-keywordise value)))
        (destructuring-bind (&key thread-id level exception restarts frames) v
          (nrepl-dbg-setup thread-id level exception restarts frames t))))
    nil nil nil)
   `(thread-id ,thread level ,level frame-min  0 frame-max 10)))

(defun nrepl-dbg-exit (thread _level &optional stepping)
  "Exit from the debug level LEVEL."
  (when-let (nrepl-dbg (nrepl-dbg-find-buffer thread))
    (with-current-buffer nrepl-dbg
      (kill-buffer))))

;;; ## Insertion
(defun nrepl-dbg-insert-exception (exception)
  "Insert the text for EXCEPTION.
EXCEPTION should be a list (MESSAGE TYPE).
EXTRAS is currently used for the stepper."
  (destructuring-bind (message type) exception
    (nrepl-ritz-insert-propertized
     '(nrepl-dbg-default-action nrepl-dbg-inspect-exception)
     (nrepl-dbg-in-face topline message)
     "\n"
     (nrepl-dbg-in-face exception type))))

(defun nrepl-dbg-insert-restarts (restarts start count)
  "Insert RESTARTS and add the needed text props
RESTARTS should be a list ((NAME DESCRIPTION) ...)."
  (let* ((len (length restarts))
         (end (if count (min (+ start count) len) len)))
    (loop for (name string) in (subseq restarts start end)
          for number from start
          do (nrepl-ritz-insert-propertized
              `(,@nil restart ,number
                      nrepl-dbg-default-action nrepl-dbg-invoke-restart
                      mouse-face highlight)
              " " (nrepl-dbg-in-face restart-number (number-to-string number))
              ": ["  (nrepl-dbg-in-face restart-type name) "] "
              (nrepl-dbg-in-face restart string))
          (insert "\n"))
    (when (< end len)
      (let ((pos (point)))
        (nrepl-ritz-insert-propertized
         (list 'nrepl-dbg-default-action
               (lambda ()
                 (nrepl-dbg-insert-more-restarts restarts pos end)))
         " --more--\n")))))

(defun nrepl-dbg-insert-more-restarts (restarts position start)
  (goto-char position)
  (let ((inhibit-read-only t))
    (delete-region position (1+ (line-end-position)))
    (nrepl-dbg-insert-restarts restarts start nil)))

(defun nrepl-dbg-frame.string (frame)
  (destructuring-bind (_ str &optional _) frame str))

(defun nrepl-dbg-frame.number (frame)
  (destructuring-bind (n _ &optional _) frame n))

(defun nrepl-dbg-frame.plist (frame)
  (destructuring-bind (_ _ &optional plist) frame plist))

(defun nrepl-dbg-prune-initial-frames (frames)
  "Return the prefix of FRAMES to initially present to the user.
Regexp heuristics are used to avoid showing ritz frames."
  (let* ((case-fold-search t)
         (rx "^\\([() ]\\|lambda\\)*\\(ritz|nrepl\\)\\>"))
    (or (loop for frame in frames
              until (string-match rx (nrepl-dbg-frame.string frame))
              collect frame)
        frames)))

(defun nrepl-dbg-insert-frames (frames more)
  "Insert FRAMES into buffer.
If MORE is non-nil, more frames are on the Lisp stack."
  (mapc #'nrepl-dbg-insert-frame frames)
  (when more
    (nrepl-ritz-insert-propertized
     `(,@nil nrepl-dbg-default-action nrepl-dbg-fetch-more-frames
             nrepl-dbg-previous-frame-number
             ,(nrepl-dbg-frame.number (first (last frames)))
             point-entered nrepl-dbg-fetch-more-frames
             start-open t
             face nrepl-dbg-section-face
             mouse-face highlight)
     " --more--")
    (insert "\n")))

(defun nrepl-dbg-insert-frame (frame &optional face)
  "Insert FRAME with FACE at point.
If FACE is nil, `nrepl-dbg-frame-line-face' is used."
  (setq face (or face 'nrepl-dbg-frame-line-face))
  (let ((number (nrepl-dbg-frame.number frame))
        (string (nrepl-dbg-frame.string frame))
        (stratum (lax-plist-get (nrepl-dbg-frame.plist frame) "stratum")))
    (when (or nrepl-dbg-show-java-frames
              (not (equal stratum "Java")))
      (nrepl-propertize-region
          `(frame ,frame nrepl-dbg-default-action nrepl-dbg-toggle-details)
        (nrepl-propertize-region '(mouse-face highlight)
          (insert
           " " (nrepl-dbg-in-face frame-label (format "%2d:" number)) " ")
          (nrepl-ritz-insert-indented
           (nrepl-ritz-add-face face string)))
        (insert "\n")))))

(defun nrepl-dbg-fetch-frames (thread-id from to)
  (nrepl-ritz-send-op
   "stacktrace"
   (nrepl-make-response-handler
    (current-buffer)
    (lambda (buffer value)
           (lexical-let ((frames value)
                         (more (nrepl-length= frames count))
                         (pos (point))))
           (delete-region (line-beginning-position) (point-max))
           (nrepl-dbg-insert-frames frames more)
           (goto-char pos))
    nil nil nil)
   `("thread-id" ,thread-id "from" ,from "to" ,to)))

(defun nrepl-dbg-fetch-more-frames (&rest _)
  "Fetch more stacktrace frames.
Called on the `point-entered' text-property hook."
  (let ((inhibit-point-motion-hooks t)
        (inhibit-read-only t)
        (prev (get-text-property (point) 'nrepl-dbg-previous-frame-number)))
    ;; we may be called twice, PREV is nil the second time
    (when prev
      (let* ((count 40)
             (from (1+ prev))
             (to (+ from count)))
        (nrepl-dbg-fetch-frames nrepl-dbg-thread-id from to)))))

;;; ## Property queries
(defun nrepl-dbg-restart-at-point ()
  (or (get-text-property (point) 'restart)
      (error "No restart at point")))

(defun nrepl-dbg-frame-number-at-point ()
  (let ((frame (get-text-property (point) 'frame)))
    (cond (frame (car frame))
          (t (error "No frame at point")))))

(defun nrepl-dbg-var-number-at-point ()
  (let ((var (get-text-property (point) 'var)))
    (cond (var var)
          (t (error "No variable at point")))))

(defun nrepl-dbg-previous-frame-number ()
  (save-excursion
    (nrepl-dbg-frame-back)
    (nrepl-dbg-frame-number-at-point)))

(defun nrepl-dbg-frame-details-visible-p ()
  (and (get-text-property (point) 'frame)
       (get-text-property (point) 'details-visible-p)))

(defun nrepl-dbg-frame-region ()
  (nrepl-ritz-property-bounds 'frame))

(defun nrepl-dbg-frame-forward ()
  (goto-char (next-single-char-property-change (point) 'frame)))

(defun nrepl-dbg-frame-back ()
  (when (> (point) nrepl-dbg-stacktrace-start-marker)
    (goto-char (previous-single-char-property-change
                (if (get-text-property (point) 'frame)
                    (car (nrepl-dbg-frame-region))
                    (point))
                'frame
                nil nrepl-dbg-stacktrace-start-marker))))

(defun nrepl-dbg-goto-last-frame ()
  (goto-char (point-max))
  (while (not (get-text-property (point) 'frame))
    (goto-char (previous-single-property-change (point) 'frame))
    ;; Recenter to bottom of the window; -2 to account for the
    ;; empty last line displayed in nrepl-dbg buffers.
    (recenter -2)))

(defun nrepl-dbg-beginning-of-stacktrace ()
  "Goto the first frame."
  (interactive)
  (goto-char nrepl-dbg-stacktrace-start-marker))

;;; ## Recenter & Redisplay
(defmacro nrepl-ritz-save-coordinates (origin &rest body)
  "Restore line and column relative to ORIGIN, after executing BODY.

This is useful if BODY deletes and inserts some text but we want to
preserve the current row and column as closely as possible."
  (let ((base (make-symbol "base"))
        (goal (make-symbol "goal"))
        (mark (make-symbol "mark")))
    `(let* ((,base ,origin)
            (,goal (nrepl-ritz-coordinates ,base))
            (,mark (point-marker)))
       (set-marker-insertion-type ,mark t)
       (prog1 (save-excursion ,@body)
         (nrepl-ritz-restore-coordinate ,base ,goal ,mark)))))

(put 'nrepl-ritz-save-coordinates 'lisp-indent-function 1)

(defun nrepl-ritz-coordinates (origin)
  ;; Return a pair (X . Y) for the column and line distance to ORIGIN.
  (let ((y (nrepl-ritz-count-lines origin (point)))
        (x (save-excursion
             (- (current-column)
                (progn (goto-char origin) (current-column))))))
    (cons x y)))

(defun nrepl-ritz-restore-coordinate (base goal limit)
  ;; Move point to GOAL. Coordinates are relative to BASE.
  ;; Don't move beyond LIMIT.
  (save-restriction
    (narrow-to-region base limit)
    (goto-char (point-min))
    (let ((col (current-column)))
      (forward-line (cdr goal))
      (when (and (eobp) (bolp) (not (bobp)))
        (backward-char))
      (move-to-column (+ col (car goal))))))

(defun nrepl-ritz-count-lines (start end)
  "Return the number of lines between START and END.
This is 0 if START and END at the same line."
  (- (count-lines start end)
     (if (save-excursion (goto-char end) (bolp)) 0 1)))

;;; ## Commands
(defun nrepl-dbg-default-action ()
  "Invoke the action at point."
  (interactive)
  (let ((fn (get-text-property (point) 'nrepl-dbg-default-action)))
    (if fn (funcall fn))))

(defun nrepl-dbg-default-mouse-action (event)
  "Invoke the action pointed at by the mouse."
  (interactive "e")
  (destructuring-bind (_mouse-1 (_w pos &rest _)) event
    (save-excursion
      (goto-char pos)
      (nrepl-dbg-default-action))))

(defun nrepl-dbg-cycle ()
  "Cycle between restart list and stacktrace."
  (interactive)
  (let ((pt (point)))
    (cond ((< pt nrepl-dbg-restart-list-start-marker)
           (goto-char nrepl-dbg-restart-list-start-marker))
          ((< pt nrepl-dbg-stacktrace-start-marker)
           (goto-char nrepl-dbg-stacktrace-start-marker))
          (t
           (goto-char nrepl-dbg-restart-list-start-marker)))))

(defun nrepl-dbg-end-of-stacktrace ()
  "Fetch the entire stacktrace and go to the last frame."
  (interactive)
  (nrepl-dbg-fetch-all-frames)
  (nrepl-dbg-goto-last-frame))

(defun nrepl-dbg-fetch-all-frames ()
  (let ((inhibit-read-only t)
        (inhibit-point-motion-hooks t))
    (nrepl-dbg-goto-last-frame)
    (let ((last (nrepl-dbg-frame-number-at-point)))
      (goto-char (next-single-char-property-change (point) 'frame))
      (delete-region (point) (point-max))
      (nrepl-dbg-fetch-frames nrepl-dbg-thread-id (1+ last) nil))))

(defun nrepl-dbg-show-source ()
  "Highlight the frame at point's expression in a source code buffer."
  (interactive)
  (nrepl-dbg-show-frame-source (nrepl-dbg-frame-number-at-point)))

(defun nrepl-dbg-show-frame-source (frame-number)
  (nrepl-ritz-send-dbg-op
   "frame-source"
   (lambda (&rest args)
     (destructuring-bind (&key zip file line error source-form) args
       (if error
           (progn
             (message "%s" error)
             (ding))
         (nrepl-ritz-show-source-location zip file line source-form))))
   'frame-number frame-number))

;;; ## Toggle frame details
(defun nrepl-dbg-toggle-details (&optional on)
  "Toggle display of locals for the current frame."
  (interactive)
  (assert (nrepl-dbg-frame-number-at-point))
  (let ((inhibit-read-only t)
        (inhibit-point-motion-hooks t))
    (if (or on (not (nrepl-dbg-frame-details-visible-p)))
        (nrepl-dbg-show-frame-details)
      (nrepl-dbg-hide-frame-details))))

(defun nrepl-dbg-show-frame-details ()
  (lexical-let* ((frame (get-text-property (point) 'frame))
                 (num (car frame)))
    (destructuring-bind (start end) (nrepl-dbg-frame-region)
      (lexical-let ((start start) (end end) (buffer (current-buffer)))
        (nrepl-ritz-send-dbg-op
         "frame-locals"
         (lambda (&key locals)
           (with-current-buffer buffer
             (nrepl-dbg-insert-frame-details start end frame locals)))
         'frame-number num)))))

(defun nrepl-dbg-insert-frame-details (start end frame locals)
  (let ((inhibit-read-only t))
    (nrepl-ritz-save-coordinates start
      (delete-region start end)
      (nrepl-propertize-region `(frame ,frame details-visible-p t)
        (nrepl-dbg-insert-frame frame 'nrepl-dbg-detailed-frame-line-face)
        (insert
         "      "
         (nrepl-dbg-in-face section
           (if locals "Locals:" "[No Locals]")) "\n")
        (nrepl-dbg-insert-locals locals "        " frame)
        (setq end (point)))))
  (nrepl-dbg-recenter-region start end))

(defvar nrepl-dbg-insert-frame-variable-value-function
  'nrepl-dbg-insert-frame-variable-value)

(defun nrepl-dbg-insert-locals (vars prefix frame)
  "Insert VARS and add PREFIX at the beginning of each inserted line.
VAR should be a plist with the keys :name, :id, and :value."
  (loop for i from 0
        for var in vars do
        (destructuring-bind (&key name id value) (nrepl-keywordise var)
          (nrepl-propertize-region
              (list 'nrepl-dbg-default-action 'nrepl-dbg-inspect-var 'var i)
            (insert prefix
                    (nrepl-dbg-in-face
                     local-name
                     (concat name (if (zerop id) "" (format "#%d" id))))
                    " = ")
            (funcall nrepl-dbg-insert-frame-variable-value-function
                     value frame i)
            (insert "\n")))))

(defun nrepl-dbg-insert-frame-variable-value (value _frame _index)
  (insert (nrepl-dbg-in-face local-value value)))

(defun nrepl-dbg-hide-frame-details ()
  "Delete the display of locals."
  (destructuring-bind (start end) (nrepl-dbg-frame-region)
    (let ((frame (get-text-property (point) 'frame)))
      (nrepl-ritz-save-coordinates start
        (delete-region start end)
        (nrepl-propertize-region '(details-visible-p nil)
          (nrepl-dbg-insert-frame frame))))))


(defun nrepl-dbg-toggle-java-frames ()
  "Show or hide java frames"
  (interactive)
  (setq nrepl-dbg-show-java-frames (not nrepl-dbg-show-java-frames))
  (nrepl-dbg-reinitialize nrepl-dbg-thread-id nrepl-dbg-level))

(defun nrepl-dbg-disassemble ()
  "Disassemble the code for the current frame."
  (interactive)
  (let ((frame (nrepl-dbg-frame-number-at-point)))
    (nrepl-ritz-send-dbg-op
     "disassemble-frame"
     (lambda (&key result)
       (with-current-buffer (nrepl-popup-buffer "*nREPL disassembly*" t)
       (let ((inhibit-read-only t))
         (insert result))))
     'frame-number frame)))

;;; ## eval and inspect
(defun nrepl-dbg-eval-in-frame (frame string)
  "Prompt for an expression and evaluate it in the selected frame."
  (interactive (nrepl-dbg-read-form-for-frame "Eval in frame> "))
  (nrepl-ritz-send-dbg-op
   "frame-eval"
   (lambda (&key result)
     (message "%s" result))
   'frame-number frame
   'code string))

(defun nrepl-dbg-pprint-eval-in-frame (frame string)
  "Prompt for an expression, evaluate in selected frame, pretty-print result."
  (interactive (nrepl-dbg-read-form-for-frame "Eval in frame> "))
  (nrepl-ritz-send-dbg-op
   "frame-eval"
   (lambda (&key result)
     (with-current-buffer (nrepl-popup-buffer "*nREPL description*" t)
       (let ((inhibit-read-only t))
         (insert result))))
   'frame-number frame
   'code string
   'pprint t))

(defun nrepl-dbg-read-form-for-frame (prompt)
  (lexical-let ((frame (nrepl-dbg-frame-number-at-point)))
    (list frame (nrepl-ritz-read-from-minibuffer prompt))))

(defun nrepl-dbg-inspect-in-frame (string)
  "Prompt for an expression and inspect it in the selected frame."
  (interactive (list (nrepl-ritz-read-from-minibuffer
                      "Inspect in frame (evaluated): "
                      (sexp-at-point))))
  (let ((number (nrepl-dbg-frame-number-at-point)))
    (nrepl-ritz-send-dbg-op
     "inspect-in-frame"
     (lambda (&key result) (nrepl-ritz-open-inspector result nil))
     'frame-number number
     'code string)))

(defun nrepl-dbg-inspect-var ()
  (let ((frame (nrepl-dbg-frame-number-at-point))
        (var (nrepl-dbg-var-number-at-point)))
    (nrepl-ritz-send-dbg-op
     "inspect-frame-var"
     (lambda (&key result) (nrepl-ritz-open-inspector result nil))
     'frame-number number
     'var-number var)))

(defun nrepl-dbg-inspect-exception ()
  "Inspect the current exception."
  (interactive)
  (nrepl-ritz-send-dbg-op
     "inspect-current-exception"
     (lambda (&key result) (nrepl-ritz-open-inspector result nil))))

(defun nrepl-dbg-print-exception ()
  (interactive)
  (nrepl-ritz-send-dbg-op
     "print-current-exception"
     (lambda (&key result) (message result))))

;;; ## Movement across frames
(defun nrepl-dbg-frame-down ()
  "Move to next frame."
  (interactive)
  (nrepl-dbg-frame-forward))

(defun nrepl-dbg-frame-up ()
  "Move to previous frame."
  (interactive)
  (nrepl-dbg-frame-back)
  (when (= (point) nrepl-dbg-stacktrace-start-marker)
    (recenter (1+ (count-lines (point-min) (point))))))

(defun nrepl-dbg-move-with-details (move-fn)
  "Adds toggling of frame details and display of source to a
frame move command."
  (let ((inhibit-read-only t))
    (when (nrepl-dbg-frame-details-visible-p) (nrepl-dbg-hide-frame-details))
    (funcall move-fn)
    (nrepl-dbg-show-source)
    (nrepl-dbg-toggle-details t)))

(defun nrepl-dbg-details-up ()
  "Move to previous frame and show details."
  (interactive)
  (nrepl-dbg-move-with-details 'nrepl-dbg-up))

(defun nrepl-dbg-details-down ()
  "Move to next frame and show details."
  (interactive)
  (nrepl-dbg-move-with-details 'nrepl-dbg-frame-down))

;;; ## Restarts
(defun nrepl-dbg-invoke-restart (&optional number)
  "Invoke a restart NUMBER, and defaulting to the restart at point."
  (interactive)
  (let ((restart (or number (nrepl-dbg-restart-at-point))))
    (nrepl-ritz-send-dbg-op "invoke-restart" nil 'restart-number restart)))

(defun nrepl-dbg-invoke-named-restart (restart-name)
  "Invoke the RESTART-NAME restart."
  (interactive
   (list (let ((completion-ignore-case t))
           (completing-read "Restart: " nrepl-dbg-restarts nil t
                            ""
                            'nrepl-dbg-invoke-named-restart))))
  (nrepl-ritz-send-dbg-op "invoke-restart" nil 'restart-name restart-name))

(defun nrepl-dbg-continue ()
  "Continue the execution of the thread."
  (interactive)
  (assert nrepl-dbg-restarts ()
          "nrepl-dbg-continue called outside of nrepl-dbg buffer")
  (nrepl-ritz-send-dbg-op "invoke-restart" nil 'restart-name "continue"))

(defun nrepl-dbg-quit ()
  "Abort all execution of the thread."
  (interactive)
  (assert nrepl-dbg-restarts ()
          "nrepl-dbg-quit called outside of nrepl-dbg buffer")
  (nrepl-ritz-send-dbg-op "invoke-restart" nil 'restart-name "quit"))

(defun nrepl-dbg-abort ()
  "Abort the execution of the current level of the thread"
  (interactive)
  (nrepl-ritz-send-dbg-op "invoke-restart" nil 'restart-name "abort"))

(defun nrepl-dbg-step-into ()
  "Step to into next call."
  (interactive)
  (nrepl-ritz-send-dbg-op "invoke-restart" nil 'restart-name "step-into"))

(defun nrepl-dbg-step-over ()
  "Step over next call."
  (interactive)
  (nrepl-ritz-send-dbg-op "invoke-restart" nil 'restart-name "step-over"))

(defun nrepl-dbg-step-out ()
  "Step out of the current function."
  (interactive)
  (nrepl-ritz-send-dbg-op "invoke-restart" nil 'restart-name "step-out"))






;;; breakpoints
(defun nrepl-ritz-break-breakpoint-list ()
  (interactive)
  (nrepl-send-debug
   "(ritz.nrepl.debug/breakpoint-list)"
   (nrepl-current-ns)
   (nrepl-make-response-handler (current-buffer) nil nil nil nil)))

(defun nrepl-ritz-break-on-exception (flag)
  (interactive "p")
  (nrepl-ritz-send-op
   "break-on-exception"
   (nrepl-make-response-handler
    (current-buffer)
    (lambda (buffer value)
      (lexical-let ((v (nrepl-keywordise value)))
        (destructuring-bind (&key thread-id level exception restarts frames) v
          (message "b-o-e calling %s %s" thread-id level)
          (nrepl-dbg-setup thread-id level exception restarts frames))))
    nil nil nil)
   `("enable" ,(if flag "true" "false"))))

(provide 'nrepl-ritz)
;;; nrepl-ritz.el ends here
