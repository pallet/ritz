(require 'slime)

(defun slime-line-breakpoint ()
  "Set a breakpoint at the current line"
  (interactive)
  (slime-eval-with-transcript
   `(swank:line-breakpoint
     ,(slime-current-package) ,(buffer-name) ,(line-number-at-pos))))
