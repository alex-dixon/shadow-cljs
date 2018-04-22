(ns shadow.cljs.devtools.api
  (:refer-clojure :exclude (compile test))
  (:require
    [clojure.core.async :as async :refer (go <! >! >!! <!! alt! alt!!)]
    [clojure.java.io :as io]
    [clojure.tools.logging :as log]
    [clojure.pprint :refer (pprint)]
    [clojure.java.browse :refer (browse-url)]
    [clojure.set :as set]
    [shadow.runtime.services :as rt]
    [shadow.build :as build]
    [shadow.build.api :as build-api]
    [shadow.build.node :as node]
    [shadow.build.npm :as npm]
    [shadow.build.classpath :as cp]
    [shadow.build.babel :as babel]
    [shadow.cljs.devtools.server.worker :as worker]
    [shadow.cljs.devtools.server.util :as util]
    [shadow.cljs.devtools.server.common :as common]
    [shadow.cljs.devtools.config :as config]
    [shadow.cljs.devtools.errors :as e]
    [shadow.cljs.devtools.server.supervisor :as super]
    [shadow.cljs.devtools.server.repl-impl :as repl-impl]
    [shadow.cljs.devtools.server.runtime :as runtime]
    [shadow.build.output :as output]
    [shadow.build.log :as build-log]
    [shadow.core-ext :as core-ext]
    [shadow.cljs.devtools.release-snapshot :as snapshot]))

;; nREPL support

(def ^:dynamic *nrepl-cljs* nil)
(def ^:dynamic *nrepl-clj-ns* nil)
(def ^:dynamic *nrepl-active* false)
(def ^:dynamic *nrepl-quit-signal* nil)

(defn get-worker
  [id]
  {:pre [(keyword? id)]}
  (let [{:keys [out supervisor] :as app}
        (runtime/get-instance!)]
    (super/get-worker supervisor id)
    ))

(defn make-runtime []
  (let [config (config/load-cljs-edn!)]

    (log/debug "starting runtime instance")

    (-> {::started (System/currentTimeMillis)
         :config config}
        (rt/init common/app-config)
        (rt/start-all)
        )))

(defmacro with-runtime [& body]
  ;; not using binding since there should only ever be one runtime instance per JVM
  `(let [body-fn#
         (fn []
           ~@body)]
     (if (runtime/get-instance)
       (body-fn#)

       ;; start/stop instance when not running in server context
       (let [runtime# (make-runtime)]
         (try
           (runtime/set-instance! runtime#)
           (body-fn#)
           (finally
             (runtime/reset-instance!)
             (rt/stop-all runtime#)))))))

(defn get-build-config [build-id]
  (config/get-build build-id))

(defn get-runtime! []
  (runtime/get-instance!))

(defn get-or-start-worker [build-config opts]
  (let [{:keys [autobuild]}
        opts

        {:keys [out supervisor] :as app}
        (runtime/get-instance!)]

    (if-let [worker (super/get-worker supervisor (:build-id build-config))]
      worker
      (super/start-worker supervisor build-config)
      )))

(defn watch
  "starts a dev worker process for a given :build-id
  opts defaults to {:autobuild true}"
  ([build-id]
   (watch build-id {}))
  ([build-id {:keys [autobuild verbose] :as opts}]
   (let [out
         (util/stdout-dump verbose)

         build-config
         (if (map? build-id)
           build-id
           (config/get-build! build-id))]

     (-> (get-or-start-worker build-config opts)
         (worker/watch out true)
         (cond->
           (not (false? autobuild))
           (-> (worker/start-autobuild)
               (worker/sync!))))

     :watching
     )))

(defn active-builds []
  (let [{:keys [supervisor]}
        (runtime/get-instance!)]
    (super/active-builds supervisor)))

(defn compiler-env [build-id]
  (let [{:keys [supervisor]}
        (runtime/get-instance!)]
    (when-let [worker (super/get-worker supervisor build-id)]
      (-> worker
          :state-ref
          (deref)
          :build-state
          :compiler-env))))

(comment
  (watch :browser)
  (stop-worker :browser))

(defn stop-worker [build-id]
  (let [{:keys [supervisor] :as app}
        (runtime/get-instance!)]
    (super/stop-worker supervisor build-id)
    :stopped))

(defn get-config []
  (or (when-let [inst (runtime/get-instance)]
        (:config inst))
      (config/load-cljs-edn)))

(defn get-build-config [id]
  {:pre [(keyword? id)]}
  (config/get-build! id))

(defn build-finish [{::build/keys [build-info] :as state} config]
  (util/print-build-complete build-info config)
  (let [rt (::runtime state)]
    (when (::once rt)
      (let [{:keys [babel npm]} rt]
        (babel/stop babel)
        (npm/stop npm))))
  state)

(defn compile* [build-config opts]
  (util/print-build-start build-config)
  (-> (util/new-build build-config :dev opts)
      (build/configure :dev build-config)
      (build/compile)
      (build/flush)
      (build-finish build-config)))

(defn compile!
  "do not use at the REPL, will return big pile of build state, will blow up REPL by printing too much"
  [build opts]
  (with-runtime
    (let [build-config (config/get-build! build)]
      (compile* build-config opts))))

(defn compile
  ([build]
   (compile build {}))
  ([build opts]
   (try
     (compile! build opts)
     :done
     (catch Exception e
       (e/user-friendly-error e)))))

(defn once
  "deprecated: use compile"
  ([build]
   (compile build {}))
  ([build opts]
   (compile build opts)))

(defn release*
  [build-config {:keys [debug source-maps pseudo-names] :as opts}]
  (util/print-build-start build-config)
  (-> (util/new-build build-config :release opts)
      (build/configure :release build-config)
      (cond->
        (or debug source-maps)
        (build-api/enable-source-maps)

        (or debug pseudo-names)
        (build-api/with-compiler-options
          {:pretty-print true
           :pseudo-names true}))
      (build/compile)
      (build/optimize)
      (build/flush)
      (build-finish build-config)))

(defn release
  ([build]
   (release build {}))
  ([build opts]
   (try
     (with-runtime
       (let [build-config (config/get-build! build)]
         (release* build-config opts)))
     :done
     (catch Exception e
       (e/user-friendly-error e)))))

(defn check* [{:keys [id] :as build-config} opts]
  ;; FIXME: pretend release mode so targets don't need to account for extra mode
  ;; in most cases we want exactly :release but not sure that is true for everything?
  (-> (util/new-build build-config :release opts)
      (build/configure :release build-config)
      ;; using another dir because of source maps
      ;; not sure :release builds want to enable source maps by default
      ;; so running check on the release dir would cause a recompile which is annoying
      ;; but check errors are really useless without source maps
      (as-> X
        (-> X
            (assoc :cache-dir (io/file (:cache-dir X) "check"))
            ;; always override :output-dir since check output should never be used
            ;; only generates output for source maps anyways
            (assoc :output-dir (io/file (:cache-dir X) "check-out"))))
      (build-api/enable-source-maps)
      (update-in [:compiler-options :closure-warnings] merge {:check-types :warning})
      (build/compile)
      (build/check)
      (build-finish build-config))
  :done)

(defn check
  ([build]
   (check build {}))
  ([build opts]
   (try
     (with-runtime
       (let [build-config (config/get-build! build)]
         (check* build-config opts)))
     (catch Exception e
       (e/user-friendly-error e)))))

(defn repl-client-connected? [worker]
  (not (empty? (-> worker :state-ref deref :eval-clients))))

(defn nrepl-select [id]
  (let [worker (get-worker id)]
    (cond
      (nil? worker)
      [:no-worker id]

      (not (repl-client-connected? worker))
      [:no-client id "Make sure your JS environment has loaded your compiled ClojureScript code."]

      :else
      (do (set! *nrepl-cljs* id)

          ;; doing this to make cider prompt not show "user" as prompt after calling this
          (set! *nrepl-clj-ns* *ns*)
          (set! *ns* (create-ns 'cljs.user))

          (log/warn ::nrepl-select-output-loop *nrepl-quit-signal*)

          ;; nrepl doesn't have a clear way to signal the end of a session
          ;; so this keeps running even is the nrepl is disconnected
          ;; I hope the print just fails and kills the loop?
          (when-let [quit *nrepl-quit-signal*]
            (let [chan (async/chan 100)]

              (go (try
                    (loop []
                      (alt! :priority true
                        chan
                        ([msg]
                          (when (some? msg)
                            (case (:type msg)
                              :repl/out
                              (do (println (:text msg))
                                  (flush))

                              :repl/err
                              (binding [*out* *err*]
                                (println (:text msg))
                                (flush))

                              :ignored)
                            (recur)
                            ))

                        quit
                        ([_] :quit)))
                    (log/debug ::nrepl-print-loop-end)
                    (catch Exception e
                      (log/debug e ::nrepl-print-loop-ex)
                      (async/close! chan))))

              (worker/watch worker chan true)))

          ;; need to set the var immediately since some cider middleware needs it to
          ;; switch REPL type to cljs. can't get to the nrepl session from here.
          (when-let [pvar (find-var 'cemerick.piggieback/*cljs-compiler-env*)]
            (.set pvar
              (reify
                clojure.lang.IDeref
                (deref [_]
                  (when-let [worker (get-worker id)]
                    (-> worker :state-ref deref :build-state :compiler-env))))))

          ;; Cursive uses this to switch repl type to cljs
          (println "To quit, type: :cljs/quit")
          [:selected id]))))

(defn repl
  ([build-id]
   (repl build-id {}))
  ([build-id {:keys [stop-on-eof] :as opts}]
   (if *nrepl-active*
     (nrepl-select build-id)
     (let [{:keys [supervisor] :as app}
           (runtime/get-instance!)

           worker
           (super/get-worker supervisor build-id)]
       (if-not worker
         :no-worker
         (do (repl-impl/stdin-takeover! worker app)
             (when stop-on-eof
               (super/stop-worker supervisor build-id))))))))

;; FIXME: should maybe allow multiple instances
(defn node-repl
  ([]
   (node-repl {}))
  ([opts]
   (let [{:keys [supervisor] :as app}
         (runtime/get-instance!)

         worker
         (or (super/get-worker supervisor :node-repl)
             (repl-impl/node-repl* app opts))]

     (repl :node-repl)
     )))

;; FIXME: should maybe allow multiple instances
(defn start-browser-repl* [{:keys [config supervisor] :as app}]
  (let [cfg
        {:build-id :browser-repl
         :target :browser
         :output-dir (str (:cache-root config) "/builds/browser-repl/js")
         :asset-path "/cache/browser-repl/js"
         :modules
         {:repl {:entries '[shadow.cljs.devtools.client.browser-repl]}}
         :devtools
         {:autoload false
          :async-require true}}]

    (super/start-worker supervisor cfg)))

(defn browser-repl
  ([]
   (browser-repl {}))
  ([{:keys [verbose open] :as opts}]
   (let [{:keys [supervisor config http] :as app}
         (runtime/get-instance!)

         worker
         (or (super/get-worker supervisor :browser-repl)
             (let [worker
                   (start-browser-repl* app)

                   out-chan
                   (-> (async/sliding-buffer 10)
                       (async/chan))]

               (go (loop []
                     (when-some [msg (<! out-chan)]
                       (try
                         (util/print-worker-out msg verbose)
                         (catch Exception e
                           (prn [:print-worker-out-error e])))
                       (recur)
                       )))

               (worker/watch worker out-chan)
               (worker/compile! worker)

               (browse-url (str "http" (when (:ssl http) "s") "://localhost:" (:port http) "/browser-repl-js"))
               worker))]

     (repl :browser-repl)
     )))

(defn dev*
  [build-config {:keys [autobuild verbose] :as opts}]
  (let [config
        (config/load-cljs-edn)

        {:keys [supervisor] :as app}
        (runtime/get-instance!)

        out
        (util/stdout-dump verbose)

        worker
        (-> (get-or-start-worker build-config opts)
            (worker/watch out false)
            (cond->
              (not (false? autobuild))
              (-> (worker/start-autobuild)
                  (worker/sync!))))]

    ;; for normal REPL loops we wait for the CLJS loop to end
    (when-not *nrepl-active*
      (repl-impl/stdin-takeover! worker app)
      (super/stop-worker supervisor (:build-id build-config)))

    :done))

(defn dev
  ([build]
   (dev build {}))
  ([build opts]
   (try
     (let [build-config (config/get-build! build)]
       (dev* build-config opts))
     (catch Exception e
       (e/user-friendly-error e)))))

(defn help []
  (-> (slurp (io/resource "shadow/txt/repl-help.txt"))
      (println)))

(defn find-resources-using-ns [ns]
  (let [{:keys [classpath]} (get-runtime!)]
    (->> (cp/find-resources-using-ns classpath ns)
         (map :ns)
         (into #{}))))

(defn test []
  (println "TBD"))

(defn- clean-dir [dir]
  (when (.exists dir)
    (doseq [file (.listFiles dir)]
      (.delete file)
      )))

(defn release-snapshot
  [build-id {:keys [tag] :or {tag "latest"} :as opts}]
  {:pre [(keyword? build-id)
         (string? tag)
         (seq tag)]}
  (with-runtime
    (let [{:keys [cache-root]}
          (get-config)

          output-dir
          (doto (io/file cache-root "release-snapshots" (name build-id) tag)
            (clean-dir))

          build-id-alias
          (keyword (str (name build-id) "-release-snapshot"))

          build-config
          (-> (get-build-config build-id)
              (assoc
                :build-id build-id-alias
                ;; not required, the files are never going to be used
                :module-hash-names false))

          state
          (-> (util/new-build build-config :release {})
              (build/configure :release build-config)
              (build-api/enable-source-maps)
              (build-api/with-build-options
                {:output-dir (io/file output-dir)})
              (build/compile)
              (build/optimize)
              (build/flush))

          bundle-info
          (output/generate-bundle-info state)]

      (spit
        (io/file output-dir "bundle-info.edn")
        (core-ext/safe-pr-str bundle-info))

      (snapshot/print-bundle-info-table bundle-info))

    :done
    ))

(comment
  (release-snapshot :browser {})

  (defn node-execute! [node-args file]
    (let [script-args
          ["node"]

          pb
          (doto (ProcessBuilder. script-args)
            (.directory nil))]


      ;; not using this because we only get output once it is done
      ;; I prefer to see progress
      ;; (prn (apply shell/sh script-args))

      (let [node-proc (.start pb)]

        (.start (Thread. (bound-fn [] (util/pipe node-proc (.getInputStream node-proc) *out*))))
        (.start (Thread. (bound-fn [] (util/pipe node-proc (.getErrorStream node-proc) *err*))))

        (let [out (.getOutputStream node-proc)]
          (io/copy (io/file file) out)
          (.close out))

        ;; FIXME: what if this doesn't terminate?
        (let [exit-code (.waitFor node-proc)]
          exit-code))))


  (defn test-all []
    (-> (build/configure :dev '{:build-id :shadow-build-api/test
                                :target :node-script
                                :main shadow.test-runner/main
                                :output-to "target/shadow-test-runner.js"
                                :hashbang false})
        (node/make-test-runner)
        (build/compile)
        (build/flush))

    (node-execute! [] "target/shadow-test-runner.js")
    ::test-all)

  (defn test-affected
    [source-names]
    {:pre [(seq source-names)
           (not (string? source-names))
           (every? string? source-names)]}
    (-> (test-setup)
        (node/execute-affected-tests! source-names))
    ::test-affected))


