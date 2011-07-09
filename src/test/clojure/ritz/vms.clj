(ns ritz.vms
  "VMs for running ritz"
  (:require
   [pallet.core :as core]
   [pallet.crate.automated-admin-user :as automated-admin-user]
   [pallet.crate.git :as git]
   [pallet.crate.java :as java]
   [pallet.action.directory :as directory]
   [pallet.action.exec-script :as exec-script]
   [pallet.action.package :as package]
   [pallet.action.remote-file :as remote-file]
   [pallet.phase :as phase]
   [pallet.script.lib :as lib]
   [pallet.session :as session]))


(def base-spec
  (core/server-spec
   :phases {:bootstrap automated-admin-user/automated-admin-user}))

(defn lein
  "Install latest stable lein"
  [session]
  (let [admin-user (session/admin-user session)]
    (->
     session
     (directory/directory
      "bin" :owner (:username admin-user) :mode "755")
     (remote-file/remote-file
      "bin/lein"
      :owner (:username admin-user) :mode "755"
      :url "https://github.com/technomancy/leiningen/raw/stable/bin/lein"
      :no-versioning true)
     (exec-script/exec-checked-script
      "Upgrade lein"
      (sudo -u ~(:username admin-user)
            (~lib/heredoc-in ("bin/lein" upgrade) "Y\n" {}))))))

(def clojure-dev
  (core/server-spec
   :phases {:configure (phase/phase-fn
                        (git/git)
                        (java/java :openjdk :jdk)
                        (package/package "tmux")
                        (package/package "emacs")
                        (lein))}))

(def ritz-dev
  (core/server-spec
   :phases {:configure
            (fn [session]
              (->
               session
               (exec-script/exec-checked-script
                "Clone ritz"
                (if-not (directory? rtiz)
                  (sudo -u ~(:username (session/admin-user session))
                        git clone "git://github.com/pallet/ritz")))))}))

(def ritz-dev-ubuntu
  (core/group-spec
   "ritz"
   :extends [base-spec clojure-dev ritz-dev]
   :node-spec (core/node-spec
               :image {:os-family :ubuntu :os-version-matches "10.10"})))
