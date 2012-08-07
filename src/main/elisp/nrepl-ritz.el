;;; nrepl-ritz.el --- nrepl extensions for ritz
;;
;; Copyright 2012 Hugo Duncan
;;
;; Author: Hugo Duncan <hugo_duncan@yahoo.com>
;; Keywords: languages, lisp, nrepl
;; URL: https://github.com/pallet/ritz
;; Version: 0.3.2
;; License: EPL

(require 'nrepl-mode)

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
   "(ritz.nrepl.commands/threads)" "user"
   (nrepl-handler (current-buffer))))

(provide 'nrepl-ritz)
;;; nrepl-ritz.el ends here
