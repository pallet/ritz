(ns ritz.swank.commands.contrib.swank-media
  "Contrib for display of images in slime"
  (:require
   [clojure.data.codec.base64 :as base64]
   [clojure.java.io :as io]
   [ritz.logging :as logging]
   [ritz.repl-utils.utils :as utils]
   [ritz.swank.commands :as commands]
   [ritz.swank.commands.emacs :as emacs]
   [ritz.swank.connection :as connection]
   [ritz.swank.core :as core]
   [ritz.swank.messages :as messages])
  (:import
   java.util.WeakHashMap))

(extend-type java.awt.image.BufferedImage
  core/ReplResult
  (write-result [value connection options]
    (logging/trace "Writing BufferedImage to repl")

    (let [image-bytes
          (let [os (java.io.ByteArrayOutputStream.)]
            (javax.imageio.ImageIO/write value "jpeg" os)
            (.toByteArray os))]
      (emacs/eval-in-emacs
       connection
       `(~'slime-media-insert-image
         (~'create-image
          (~'base64-decode-string
           ~(String. (base64/encode image-bytes) "US-ASCII"))
          '~'jpeg ~'t)
         " "
         ~'t)))))
