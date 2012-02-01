(ns ritz.commands.contrib.swank-media
  "Contrib for display of images in slime"
  (:require
   [clojure.java.io :as io]
   [clojure.data.codec.base64 :as base64]
   [ritz.commands.emacs :as emacs]
   [ritz.connection :as connection]
   [ritz.logging :as logging]
   [ritz.swank.core :as core]
   [ritz.swank.messages :as messages]
   [ritz.swank.utils :as utils]
   [ritz.swank.commands :as commands])
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
