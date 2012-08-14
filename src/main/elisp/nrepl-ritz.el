;;; nrepl-ritz.el --- nrepl extensions for ritz
;;
;; Copyright 2012 Hugo Duncan
;;
;; Author: Hugo Duncan <hugo_duncan@yahoo.com>
;; Keywords: languages, lisp, nrepl
;; URL: https://github.com/pallet/ritz
;; Version: 0.3.2
;; License: EPL

(require 'nrepl)

;;; overwrite nrepl.el functions to allow easy development of ritz.
;;; Maybe these could be put back into nrepl.el
(defvar nrepl-eval-op "eval"
  "nrepl op for eval of forms.")

(make-variable-buffer-local 'nrepl-eval-op)

(defun nrepl-send-string (input ns callback)
  (nrepl-send-request (list "op" nrepl-eval-op
                            "session" (nrepl-current-session)
                            "ns" ns
                            "code" input)
                      callback))

(defun flatten-alist (alist)
 (if (cdr alist)
     (append (list (caar alist) (cdar alist)) (flatten-alist (cdr alist)))
   (list (caar alist) (cdar alist))))

(defun nrepl-ritz-send-op (op callback attributes)
  (lexical-let ((request (append
                          (list "op" op "session" (nrepl-current-session))
                          (flatten-alist attributes))))

    (nrepl-send-request request callback)))

;;; send function for a jpda op
(defun nrepl-ritz-send-debug (input ns callback)
  (nrepl-send-request (list "op" "jpda"
                            "stdin" ""
                            "session" (nrepl-current-session)
                            "ns" ns
                            "code" input)
                      callback))

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

;;; javadoc browsing
(defun nrepl-ritz-javadoc-local-paths (local-paths)
  "Require JavaDoc namespace, adding a list of local paths."
  (nrepl-send-string
   (format
    "(ritz.repl-utils.doc/javadoc-local-paths '%S)" local-paths)
   "user"
   (nrepl-handler (current-buffer))))

(defun nrepl-ritz-javadoc-input-handler (symbol-name)
  "Browse javadoc on the Java class at point."
  (when (not symbol-name)
    (error "No symbol given"))
  (nrepl-send-string
   (format "(ritz.repl-utils.doc/javadoc-url \"%s\")" symbol-name)
   "user"
   (nrepl-make-response-handler
    (current-buffer)
    (lambda (buffer value)
      (lexical-let ((v (car (read-from-string value))))
        (lexical-let ((url (and (stringp v) v)))
          (if url
              (browse-url url)
            (error "No javadoc url for %s" symbol-name)))))
    nil nil nil)))

(defun nrepl-ritz-javadoc (query)
  "Browse javadoc on the Java class at point."
  (interactive "P")
  (nrepl-read-symbol-name
   "Javadoc for: " 'nrepl-ritz-javadoc-input-handler query))

(define-key nrepl-interaction-mode-map (kbd "C-c b") 'nrepl-ritz-javadoc)

;;; undefine symbol
(defun nrepl-ritz-undefine-symbol-handler (symbol-name)
  "Browse undefine on the Java class at point."
  (when (not symbol-name)
    (error "No symbol given"))
  (nrepl-send-string
   (format "(ns-unmap '%s '%s)" (nrepl-current-ns) symbol-name)
   (nrepl-current-ns)
   (nrepl-make-response-handler (current-buffer) nil nil nil nil)))

(defun nrepl-ritz-undefine-symbol (query)
  "Undefine the symbol at point."
  (interactive "P")
  (nrepl-read-symbol-name
   "Undefine: " 'nrepl-ritz-undefine-symbol-handler query))

(define-key
  nrepl-interaction-mode-map (kbd "C-c C-u") 'nrepl-ritz-undefine-symbol)

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
    (lambda (buffer value) (message value)) nil nil nil)
   `(("enable" . ,(if flag "true" "false")))))

(defun nrepl-ritz-continue (thread-id)
  (interactive "nthread-id: ")
  (nrepl-ritz-send-op
   "continue"
   (nrepl-make-response-handler (current-buffer) nil nil nil nil)
   `(("thread-id" . ,(prin1-to-string thread-id)))))

(defun nrepl-ritz-abort (thread-id)
  (interactive "nthread-id: ")
  (nrepl-ritz-send-op
   "abort-level"
   (nrepl-make-response-handler (current-buffer) nil nil nil nil)
   `(("thread-id" . ,(prin1-to-string thread-id)))))

(defun nrepl-ritz-quit-to-top-level (thread-id)
  (interactive "nthread-id: ")
  (nrepl-ritz-send-op
   "quit-to-top-level"
   (nrepl-make-response-handler (current-buffer) nil nil nil nil)
   `(("thread-id" . ,(prin1-to-string thread-id)))))

(provide 'nrepl-ritz)
;;; nrepl-ritz.el ends here
