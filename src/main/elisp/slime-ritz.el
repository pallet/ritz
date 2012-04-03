;;; slime-ritz.el --- slime extensions for ritz
;;
;; Copyright 2011, 2012 Hugo Duncan
;;
;; Author: Hugo Duncan <hugo_duncan@yahoo.com>
;; Keywords: languages, lisp, slime
;; URL: https://github.com/pallet/ritz
;; Version: 0.3.0-SNAPSHOT
;; License: EPL

(define-slime-contrib slime-ritz
  "Integration with ritz features"
  (:authors "Hugo Duncan <hugo_duncan@yahoo.com>")
  (:license "EPL")
  (:on-load
   (define-key slime-mode-map "\C-c\C-x\C-b" 'slime-line-breakpoint)
   (define-key java-mode-map "\C-c\C-x\C-b" 'slime-java-line-breakpoint)))

(defun slime-line-breakpoint ()
  "Set a breakpoint at the current line"
  (interactive)
  (slime-eval-with-transcript
   `(swank:line-breakpoint
     ,(slime-current-package) ,(buffer-name) ,(line-number-at-pos))))

(defun slime-java-line-breakpoint ()
  "Set a breakpoint at the current line in java"
  (interactive)
  (slime-eval-with-transcript
   `(swank:line-breakpoint nil ,(buffer-name) ,(line-number-at-pos))))

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

;;;;; Exception Filters
(defvar slime-exception-filters-buffer-name (slime-buffer-name :exception-filters))

(defun slime-list-exception-filters ()
  "Display a list of exception filterss."
  (interactive)
  (let ((name slime-exception-filters-buffer-name))
    (slime-with-popup-buffer (name :connection t
                                   :mode 'slime-exception-filter-control-mode)
      (slime-update-exception-filters-buffer)
      (goto-char (point-min))
      (setq slime-popup-buffer-quit-function 'slime-quit-exception-filters-buffer))))

(defvar slime-exception-filter-index-to-id nil)

(defun slime-quit-exception-filters-buffer (&optional _)
  (slime-popup-buffer-quit t)
  (setq slime-exception-filter-index-to-id nil)
  (slime-eval-async `(swank:quit-exception-filter-browser)))

(defun slime-update-exception-filters-buffer ()
  (interactive)
  (with-current-buffer slime-exception-filters-buffer-name
    (slime-eval-async '(swank:list-exception-filters)
      'slime-display-exception-filters)))

(defun slime-display-exception-filters (filters)
  (with-current-buffer slime-exception-filters-buffer-name
    (let* ((inhibit-read-only t)
           (index (get-text-property (point) 'exception-filter-id))
           (old-exception-filter-id (and (numberp index)
                               (elt slime-exception-filter-index-to-id index)))
           (old-line (line-number-at-pos))
           (old-column (current-column)))
      (setq slime-exception-filter-index-to-id (mapcar 'car (cdr filters)))
      (erase-buffer)
      (slime-insert-exception-filters filters)
      (let ((new-position (position old-exception-filter-id filters :key 'car)))
        (goto-char (point-min))
        (forward-line (1- (or new-position old-line)))
        (move-to-column old-column)
        (slime-move-point (point))))))

(defvar *slime-exception-filters-table-properties*
  '(nil (face bold)))

(defun slime-format-exception-filters-labels (exceptions)
  (let ((labels (mapcar (lambda (x)
                          (capitalize (substring (symbol-name x) 1)))
                        (car exceptions))))
    (cons labels (cdr exceptions))))

(defun slime-insert-exception-filter (exception-filter longest-lines)
  (unless (bolp) (insert "\n"))
  (loop for i from 0
        for align in longest-lines
        for element in exception-filter
        for string = (prin1-to-string element t)
        for property = (nth i *slime-exception-filters-table-properties*)
        do
        (if property
            (slime-insert-propertized property string)
            (insert string))
        (insert-char ?\  (- align (length string) -3))))

(defun slime-insert-exception-filters (exception-filters)
  (let* ((exception-filters (slime-format-exception-filters-labels exception-filters))
         (longest-lines (slime-longest-lines exception-filters))
         (labels (let (*slime-exception-filters-table-properties*)
                   (with-temp-buffer
                     (slime-insert-exception-filter (car exception-filters) longest-lines)
                     (buffer-string)))))
    (if (boundp 'header-line-format)
        (setq header-line-format
              (concat (propertize " " 'display '((space :align-to 0)))
                      labels))
        (insert labels))
    (loop for index from 0
          for exception-filter in (cdr exception-filters)
          do
          (slime-propertize-region `(exception-filter-id ,index)
            (slime-insert-exception-filter exception-filter longest-lines)))))

;;;;; Major mode
(define-derived-mode slime-exception-filter-control-mode fundamental-mode
  "ExceptionFilters"
  "SLIME Exception Filter Control Panel Mode.

\\{slime-exception-filter-control-mode-map}
\\{slime-popup-buffer-mode-map}"
  (when slime-truncate-lines
    (set (make-local-variable 'truncate-lines) t))
  (setq buffer-undo-list t))

(slime-define-keys slime-exception-filter-control-mode-map
  ("d" 'slime-exception-filter-disable)
  ("e" 'slime-exception-filter-enable)
  ("g" 'slime-update-exception-filters-buffer)
  ("k" 'slime-exception-filter-kill)
  ("s" 'slime-save-exception-filters))

(defun slime-exception-filter-kill ()
  (interactive)
  (slime-eval `(swank:exception-filter-kill
                ,@(slime-get-properties 'exception-filter-id)))
  (call-interactively 'slime-update-exception-filters-buffer))

(defun slime-exception-filter-disable ()
  (interactive)
  (let ((id (get-text-property (point) 'exception-filter-id)))
    (slime-eval-async `(swank:exception-filter-disable ,id)))
  (call-interactively 'slime-update-exception-filters-buffer))

(defun slime-exception-filter-enable ()
  (interactive)
  (let ((id (get-text-property (point) 'exception-filter-id)))
    (slime-eval-async `(swank:exception-filter-enable ,id)))
  (call-interactively 'slime-update-exception-filters-buffer))

(defun slime-save-exception-filters ()
  "Save current exception filters"
  (interactive)
  (slime-eval-async `(swank:save-exception-filters)
    (lambda (_)
      (message "Exception filters saved")))
  nil)

(def-slime-selector-method ?f
  "SLIME Filter exceptions buffer"
  (slime-list-exception-filters)
  slime-exception-filters-buffer-name)

;;; Threads
(slime-define-keys slime-thread-control-mode-map
  ("r" 'slime-resume-vm-threads))

(defun slime-resume-vm-threads ()
  "Resume a suspended vm"
  (interactive)
  (call-interactively 'slime-resume-vm)
  (call-interactively 'slime-update-threads-buffer))

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
  (slime-eval-async `(swank:javadoc-url ,symbol-name)
    (lambda (url)
      (if url
          (browse-url url)
        (error "No javadoc url for %S" url)))))

;;; Initialization
(defcustom slime-ritz-connected-hook nil
  "List of functions to call when SLIME connects to clojure."
  :type 'hook
  :group 'slime-lisp)

(defcustom slime-ritz-repl-mode-hook nil
  "List of functions to call when a SLIME clojure repl starts."
  :type 'hook
  :group 'slime-lisp)

(defun slime-connection-is-clojure-p ()
  (compare-strings "clojure" 0 7 (slime-connection-name) 0 7))

(defun slime-ritz-connected ()
  (slime-ritz-bind-keys)
  (when (slime-connection-is-clojure-p)
    (run-hooks 'slime-ritz-connected-hook)))

(defun slime-ritz-repl-connected ()
  (when (slime-connection-is-clojure-p)
    (run-hooks 'slime-ritz-repl-mode-hook)))

(defun slime-ritz-init ()
  "Initialise slime-ritz.  Creates clojure specific slime hooks."
  (add-hook 'slime-connected-hook slime-ritz-connected)
  (add-hook 'slime-repl-mode-hook slime-ritz-repl-connected))

(add-hook 'slime-ritz-connected-hook 'slime-clojure-connection-setup)
(add-hook 'slime-ritz-repl-mode-hook 'slime-clojure-repl-setup)

(defun slime-clojure-connection-setup ()
  (slime-ritz-bind-keys))

(defun slime-clojure-repl-setup ()
  (slime-ritz-bind-repl-keys))

(defun slime-ritz-bind-keys ()
  (define-key slime-mode-map "\C-c\C-x\C-b" 'slime-line-breakpoint)
  (define-key slime-mode-map (kbd "C-c b") 'slime-javadoc))

(defun slime-ritz-bind-repl-keys ()
  (define-key slime-repl-mode-map (kbd "C-c b") 'slime-javadoc))

;;;###autoload
(add-hook 'slime-load-hook
          (lambda ()
            (require 'slime-ritz)
            (slime-ritz-init)))

(provide 'slime-ritz)
;;; slime-ritz.el ends here
