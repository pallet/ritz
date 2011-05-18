;;; slime-clj.el --- slime extensions for swank-clj
;;
;; Copyright 2011 Hugo Duncan
;;
;; Author: Hugo Duncan <hugo_duncan@yahoo.com>
;; Keywords: languages, lisp, slime
;; URL: https://github.com/hugoduncan/swank-clj
;; Version: 0.1.5
;; License: GNU GPL (same license as Emacs)

(define-slime-contrib slime-clj
  "Integration with swank-clj features"
  (:authors "Hugo Duncan <hugo_duncan@yahoo.com>")
  (:license "EPL")
  (:on-load
   (define-key slime-mode-map "\C-c\C-x\C-b" 'slime-line-breakpoint)))

(defun slime-line-breakpoint ()
  "Set a breakpoint at the current line"
  (interactive)
  (slime-eval-with-transcript
   `(swank:line-breakpoint
     ,(slime-current-package) ,(buffer-name) ,(line-number-at-pos))))

;;;; Breakpoints
(defvar slime-breakpoints-buffer-name (slime-buffer-name :breakpoints))

(defun slime-list-breakpoints ()
  "Display a list of breakpoints."
  (interactive)
  (let ((name slime-breakpoints-buffer-name))
    (slime-with-popup-buffer (name :connection t
                                   :mode 'slime-breakpoint-control-mode)
      (slime-update-breakpoints-buffer)
      (goto-char (point-min))
      (setq slime-popup-buffer-quit-function 'slime-quit-breakpoints-buffer))))

(defvar slime-breakpoint-index-to-id nil)

(defun slime-quit-breakpoints-buffer (&optional _)
  (slime-popup-buffer-quit t)
  (setq slime-breakpoint-index-to-id nil)
  (slime-eval-async `(swank:quit-breakpoint-browser)))

(defun slime-update-breakpoints-buffer ()
  (interactive)
  (with-current-buffer slime-breakpoints-buffer-name
    (slime-eval-async '(swank:list-breakpoints)
      'slime-display-breakpoints)))

(defun slime-display-breakpoints (breakpoints)
  (with-current-buffer slime-breakpoints-buffer-name
    (let* ((inhibit-read-only t)
           (index (get-text-property (point) 'breakpoint-id))
           (old-breakpoint-id (and (numberp index)
                               (elt slime-breakpoint-index-to-id index)))
           (old-line (line-number-at-pos))
           (old-column (current-column)))
      (setq slime-breakpoint-index-to-id (mapcar 'car (cdr breakpoints)))
      (erase-buffer)
      (slime-insert-breakpoints breakpoints)
      (let ((new-position (position old-breakpoint-id breakpoints :key 'car)))
        (goto-char (point-min))
        (forward-line (1- (or new-position old-line)))
        (move-to-column old-column)
        (slime-move-point (point))))))

(defvar *slime-breakpoints-table-properties*
  '(nil (face bold)))

(defun slime-format-breakpoints-labels (breakpoints)
  (let ((labels (mapcar (lambda (x)
                          (capitalize (substring (symbol-name x) 1)))
                        (car breakpoints))))
    (cons labels (cdr breakpoints))))

(defun slime-insert-breakpoint (breakpoint longest-lines)
  (unless (bolp) (insert "\n"))
  (loop for i from 0
        for align in longest-lines
        for element in breakpoint
        for string = (prin1-to-string element t)
        for property = (nth i *slime-breakpoints-table-properties*)
        do
        (if property
            (slime-insert-propertized property string)
            (insert string))
        (insert-char ?\  (- align (length string) -3))))

(defun slime-insert-breakpoints (breakpoints)
  (let* ((breakpoints (slime-format-breakpoints-labels breakpoints))
         (longest-lines (slime-longest-lines breakpoints))
         (labels (let (*slime-breakpoints-table-properties*)
                   (with-temp-buffer
                     (slime-insert-breakpoint (car breakpoints) longest-lines)
                     (buffer-string)))))
    (if (boundp 'header-line-format)
        (setq header-line-format
              (concat (propertize " " 'display '((space :align-to 0)))
                      labels))
        (insert labels))
    (loop for index from 0
          for breakpoint in (cdr breakpoints)
          do
          (slime-propertize-region `(breakpoint-id ,index)
            (slime-insert-breakpoint breakpoint longest-lines)))))

;;;;; Major mode

(define-derived-mode slime-breakpoint-control-mode fundamental-mode
  "Breakpoints"
  "SLIME Breakpoint Control Panel Mode.

\\{slime-breakpoint-control-mode-map}
\\{slime-popup-buffer-mode-map}"
  (when slime-truncate-lines
    (set (make-local-variable 'truncate-lines) t))
  (setq buffer-undo-list t))

(slime-define-keys slime-breakpoint-control-mode-map
  ("d" 'slime-breakpoint-disable)
  ("e" 'slime-breakpoint-enable)
  ("g" 'slime-update-breakpoints-buffer)
  ("k" 'slime-breakpoint-kill)
  ("v" 'slime-breakpoint-view))

(defun slime-breakpoint-kill ()
  (interactive)
  (slime-eval `(swank:breakpoint-kill
                ,@(slime-get-properties 'breakpoint-id)))
  (call-interactively 'slime-update-breakpoints-buffer))

(defun slime-get-region-properties (prop start end)
  (loop for position = (if (get-text-property start prop)
                           start
                           (next-single-property-change start prop))
        then (next-single-property-change position prop)
        while (<= position end)
        collect (get-text-property position prop)))

(defun slime-get-properties (prop)
  (if (use-region-p)
      (slime-get-region-properties prop
                                   (region-beginning)
                                   (region-end))
      (let ((value (get-text-property (point) prop)))
        (when value
          (list value)))))

(defun slime-breakpoint-disable ()
  (interactive)
  (let ((id (get-text-property (point) 'breakpoint-id)))
    (slime-eval-async `(swank:breakpoint-disable ,id)))
  (call-interactively 'slime-update-breakpoints-buffer))

(defun slime-breakpoint-enable ()
  (interactive)
  (let ((id (get-text-property (point) 'breakpoint-id)))
    (slime-eval-async `(swank:breakpoint-enable ,id)))
  (call-interactively 'slime-update-breakpoints-buffer))

(defun slime-breakpoint-view ()
  (interactive)
  (let ((id (get-text-property (point) 'breakpoint-id)))
    (slime-eval-async
        `(swank:breakpoint-location ,id)
      #'slime-show-source-location)))

(def-slime-selector-method ?b
  "SLIME Breakpoints buffer"
  (slime-list-breakpoints)
  slime-breakpoints-buffer-name)

;;; repl forms
(defun slime-list-repl-forms ()
  "List the source forms"
  (interactive)
  (slime-eval-async `(swank:list-repl-source-forms)
    (lambda (result)
      (slime-show-description result nil))))

;;; swank development helpers
(defun slime-toggle-swank-logging ()
  "Toggle logging in swank"
  (interactive)
  (slime-eval-with-transcript
   `(swank:toggle-swank-logging)))

(defun slime-resume-vm ()
  "Resume a suspended vm"
  (interactive)
  (slime-eval-with-transcript
   `(swank:resume-vm)))

;;; javadoc browsing
(defun slime-javadoc-local-paths (local-paths)
  "Require JavaDoc namespace, adding a list of local paths."
  (slime-eval-async `(swank:javadoc-local-paths ,@local-paths)))

(defun slime-javadoc (symbol-name)
  "Browse javadoc on the Java class at point."
  (interactive (list (slime-read-symbol-name "Javadoc for: ")))
  (when (not symbol-name)
    (error "No symbol given"))
  (set-buffer (slime-output-buffer))
  (unless (eq (current-buffer) (window-buffer))
    (pop-to-buffer (current-buffer) t))
  (goto-char (point-max))
  (slime-eval-async
      `(swank:javadoc-url ,symbol-name)
    (lambda (url)
      (if url
          (browse-url url)
        (error "No javadoc url for %S" url)))))

;;; Initialization
(defcustom slime-clj-connected-hook nil
  "List of functions to call when SLIME connects to clojure."
  :type 'hook
  :group 'slime-lisp)

(defcustom slime-clj-repl-mode-hook nil
  "List of functions to call when a SLIME clojure repl starts."
  :type 'hook
  :group 'slime-lisp)

(defun slime-connection-is-clojure-p ()
  (compare-strings "clojure" 0 7 (slime-connection-name) 0 7))

(defun slime-clj-init ()
  "Initialise slime-clj.  Creates clojure specific slime hooks."
  (add-hook
   'slime-connected-hook
   (lambda ()
     (slime-clj-bind-keys)
     (when (slime-connection-is-clojure-p)
       (run-hooks 'slime-clj-connected-hook))))
  (add-hook
   'slime-repl-mode-hook
   (lambda ()
     (when (slime-connection-is-clojure-p)
       (run-hooks 'slime-clj-repl-mode-hook)))))

(add-hook 'slime-clj-connected-hook 'slime-clojure-connection-setup)
(add-hook 'slime-clj-repl-mode-hook 'slime-clojure-repl-setup)

(defun slime-clojure-connection-setup ()
  (slime-clj-bind-keys))

(defun slime-clojure-repl-setup ()
  (slime-clj-bind-repl-keys))

(defun slime-clj-bind-keys ()
  (define-key slime-mode-map "\C-c\C-x\C-b" 'slime-line-breakpoint)
  (define-key slime-mode-map (kbd "C-c b") 'slime-javadoc))

(defun slime-clj-bind-repl-keys ()
  (define-key slime-repl-mode-map (kbd "C-c b") 'slime-javadoc))

;;;###autoload
(add-hook 'slime-load-hook
          (lambda ()
            (require 'slime-clj)
            (slime-clj-init)))

(provide 'slime-clj)
;;; slime-clj.el ends here
