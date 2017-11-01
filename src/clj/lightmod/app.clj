(ns lightmod.app
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [nightcode.state :refer [pref-state runtime-state]]
            [nightcode.editors :as e]
            [nightcode.shortcuts :as shortcuts]
            [nightcode.utils :as u]
            [cljs.build.api :refer [build]]
            [hawk.core :as hawk]
            [lightmod.reload :as lr]
            [cljs.analyzer :as ana]
            [lightmod.repl :as lrepl]
            [eval-soup.core :refer [with-security]]
            [eval-soup.clojail :refer [thunk-timeout]]
            [clojure.tools.namespace.find :as find]
            [clojure.tools.namespace.file :as file]
            [clojure.tools.namespace.track :as track])
  (:import [javafx.application Platform]
           [javafx.scene.control Button ContentDisplay Label]
           [javafx.scene.image ImageView]
           [javafx.event EventHandler]
           [javafx.fxml FXMLLoader]
           [netscape.javascript JSObject]
           [nightcode.utils Bridge]))

(defn get-files-in-dep-order [dir]
  (let [tracker (->> (file-seq (io/file dir))
                     (filter #(file/file-with-extension? % (:extensions find/clj)))
                     (file/add-files (track/tracker)))
        ns->file (-> tracker
                     :clojure.tools.namespace.file/filemap
                     set/map-invert)]
     (keep ns->file (:clojure.tools.namespace.track/load tracker))))

(defn dir-pane [f]
  (let [pane (FXMLLoader/load (io/resource "dir.fxml"))]
    (shortcuts/add-tooltips! pane [:#up :#new_file :#open_in_file_browser :#close])
    (doseq [file (.listFiles f)
            :when (and  (-> file .getName (.startsWith ".") not)
                        (-> file .getName (not= "main.js")))]
      (-> (.lookup pane "#filegrid")
          .getContent
          .getChildren
          (.add (doto (if-let [icon (u/get-icon-path file)]
                        (Button. "" (doto (Label. (.getName file)
                                            (doto (ImageView. icon)
                                              (.setFitWidth 90)
                                              (.setPreserveRatio true)))
                                      (.setContentDisplay ContentDisplay/TOP)))
                        (Button. (.getName file)))
                  (.setPrefWidth 150)
                  (.setPrefHeight 150)
                  (.setOnAction (reify EventHandler
                                  (handle [this event]
                                    (swap! pref-state assoc :selection (.getCanonicalPath file)))))))))
    pane))

(defn get-project-dir
  ([] (some-> @pref-state :selection io/file get-project-dir))
  ([file]
   (loop [f file]
     (when-let [parent (.getParentFile f)]
       (if (= parent (:projects-dir @runtime-state))
         f
         (recur parent))))))

(defn eval-cljs-code [path dir code]
  (when-let [pane (get-in @runtime-state [:projects dir :pane])]
    (when-let [app (.lookup pane "#app")]
      (some-> (.getEngine app)
              (.executeScript "lightmod.init")
              (.call "eval_code" (into-array [path code])))
      nil)))

(defn set-selection-listener! [scene]
  (add-watch pref-state :selection-changed
    (fn [_ _ _ {:keys [selection]}]
      (when selection
        (let [file (io/file selection)]
          (when-let [project-dir (get-project-dir file)]
            (when-let [tab (->> (.lookup scene "#projects")
                                .getTabs
                                (filter #(= (.getText %) (.getName project-dir)))
                                first)]
              (when-let [pane (or (when (.isDirectory file)
                                    (dir-pane file))
                                  (get-in @runtime-state [:editor-panes selection])
                                  (when-let [new-editor (e/editor-pane pref-state runtime-state file
                                                          (case (-> file .getName u/get-extension)
                                                            ("clj" "cljc") e/eval-code
                                                            "cljs" (partial eval-cljs-code selection (.getCanonicalPath project-dir))
                                                            nil))]
                                    (swap! runtime-state update :editor-panes assoc selection new-editor)
                                    new-editor))]
                (let [content (.getContent tab)
                      editors (-> content
                                  (.lookup "#project")
                                  .getItems
                                  (.get 1)
                                  (.lookup "#editors"))]
                  (shortcuts/hide-tooltips! content)
                  (doto (.getChildren editors)
                    (.clear)
                    (.add pane))
                  (.setDisable (.lookup editors "#up") (= selection (.getCanonicalPath project-dir)))
                  (.setDisable (.lookup editors "#close") (.isDirectory file))
                  (Platform/runLater
                    (fn []
                      (some-> (.lookup pane "#webview") .requestFocus))))))))))))

(defn copy-from-resources! [from to]
  (let [dest (io/file to ".out" from)]
    (when-not (.exists dest)
      (.mkdirs (.getParentFile dest))
      (spit dest (slurp (io/resource from))))
    (str (-> to io/file .getName) "/.out/" from)))

(defn sanitize-name [s]
  (-> s
      str/lower-case
      (str/replace #"[ _]" "-")
      (str/replace #"[^a-z0-9\-]" "")))

(defn path->ns [path leaf-name]
  (-> path io/file .getName sanitize-name (str "." leaf-name)))

(defn send-message! [project-pane dir msg]
  (lr/send-message! dir msg)
  (Platform/runLater
    (fn []
      (when-let [obj (some-> project-pane
                             (.lookup "#app")
                             .getEngine
                             (.executeScript "lightmod.loading"))]
        (when (instance? JSObject obj)
          (.call obj "show_error" (into-array [(pr-str msg)])))))))

(defn compile-clj!
  ([project-pane dir]
   (compile-clj! project-pane dir nil))
  ([project-pane dir file-path]
   (try
     (when-not (.exists (io/file dir "server.clj"))
       (throw (Exception. "You must have a server.clj file.")))
     (with-security
       (if file-path
         (load-file file-path)
         (doseq [f (get-files-in-dep-order dir)]
           (load-file (.getCanonicalPath f)))))
     (send-message! project-pane dir {:type :visual-clj})
     true
     (catch Exception e
       (.printStackTrace e)
       (send-message! project-pane dir
         {:type :visual-clj
          :exception {:message (.getMessage e)}})
       false))))

(defn compile-cljs! [project-pane dir]
  (let [cljs-dir (io/file dir ".out" (-> dir io/file .getName))
        warnings (atom [])
        on-warning (fn [warning-type env extra]
                     (when-not (#{:infer-warning} warning-type)
                       (swap! warnings conj
                         (merge {:message (ana/error-message warning-type extra)
                                 :ns (-> env :ns :name)
                                 :type warning-type
                                 :file (str ana/*cljs-file*)}
                           (select-keys env [:line :column])))))]
    (try
      (when (.exists cljs-dir)
        (u/delete-children-recursively! cljs-dir))
      (catch Exception _))
    (try
      (when-not (.exists (io/file dir "client.cljs"))
        (throw (Exception. "You must have a client.cljs file.")))
      (build dir
        {:output-to (.getCanonicalPath (io/file dir "main.js"))
         :output-dir (.getCanonicalPath (io/file dir ".out"))
         :main (path->ns dir "client")
         :asset-path ".out"
         :preloads '[lightmod.init]
         :foreign-libs (mapv #(update % :file copy-from-resources! dir)
                         [{:file "js/p5.js"
                           :provides ["p5.core"]}
                          {:file "js/p5.tiledmap.js"
                           :provides ["p5.tiled-map"]
                           :requires ["p5.core"]}
                          {:file "cljsjs/react/development/react.inc.js"
                           :provides ["react" "cljsjs.react"]
                           :file-min ".out/cljsjs/react/production/react.min.inc.js"
                           :global-exports '{react React}}
                          {:file "cljsjs/create-react-class/development/create-react-class.inc.js"
                           :provides ["cljsjs.create-react-class" "create-react-class"]
                           :requires ["react"]
                           :file-min "cljsjs/create-react-class/production/create-react-class.min.inc.js"
                           :global-exports '{create-react-class createReactClass}}
                          {:file "cljsjs/react-dom/development/react-dom.inc.js"
                           :provides ["react-dom" "cljsjs.react.dom"]
                           :requires ["react"]
                           :file-min "cljsjs/react-dom/production/react-dom.min.inc.js"
                           :global-exports '{react-dom ReactDOM}}])
         :externs (mapv #(copy-from-resources! % dir)
                    ["cljsjs/react/common/react.ext.js"
                     "cljsjs/create-react-class/common/create-react-class.ext.js"
                     "cljsjs/react-dom/common/react-dom.ext.js"])
         :warning-handlers [on-warning]})
      (send-message! project-pane dir {:type :visual
                                       :warnings @warnings})
      (empty? @warnings)
      (catch Exception e
        (.printStackTrace e)
        (send-message! project-pane dir
          {:type :visual
           :warnings @warnings
           :exception (merge
                        {:message (.getMessage e)}
                        (select-keys (ex-data e) [:line :column]))})
        false))))

(defn run-main! [project-pane dir]
  (try
    (let [-main (resolve (symbol (path->ns dir "server") "-main"))]
      (when (nil? -main)
        (throw (Exception. "Can't find a -main function in your server.clj file.")))
      (let [server (thunk-timeout #(with-security (-main)) 5000)]
        (when-not (instance? org.eclipse.jetty.server.Server server)
          (throw (Exception. "The -main function in server.clj must call run-jetty as its last step.")))
        server))
    (catch Exception e
      (.printStackTrace e)
      (send-message! project-pane dir
        {:type :visual-clj
         :exception {:message (.getMessage e)}})
      nil)))

(defn init-console! [webview repl? on-load on-enter]
  (doto webview
    (.setVisible true)
    (.setContextMenuEnabled false))
  (let [engine (.getEngine webview)
        bridge (reify Bridge
                 (onload [this]
                   (try
                     (doto (.getEngine webview)
                       (.executeScript (if repl? "initConsole(true)" "initConsole(false)"))
                       (.executeScript (case (:theme @pref-state)
                                         :dark "changeTheme(true)"
                                         :light "changeTheme(false)"))
                       (.executeScript (format "setTextSize(%s)" (:text-size @pref-state))))
                     (on-load)
                     (catch Exception e (.printStackTrace e))))
                 (onautosave [this])
                 (onchange [this])
                 (onenter [this text]
                   (on-enter text))
                 (oneval [this code]))]
    (.setOnStatusChanged engine
      (reify EventHandler
        (handle [this event]
          (-> engine
              (.executeScript "window")
              (.setMember "java" bridge)))))
    (.load engine (str "http://localhost:"
                    (:web-port @runtime-state)
                    "/paren-soup.html"))
    bridge))

(defn init-client-repl! [project inner-pane dir]
  (let [start-ns (symbol (path->ns dir "client"))
        on-recv (fn [text]
                  (Platform/runLater
                    (fn []
                      (eval-cljs-code nil dir (pr-str [text])))))]
    (assoc project
      :client-repl-bridge
      (-> inner-pane
          (.lookup "#client_repl_webview")
          (init-console!
            true
            #(on-recv (pr-str (list 'ns start-ns)))
            on-recv)))))

(defn init-server-repl! [{:keys [server-repl-pipes] :as project} inner-pane dir]
  (when-let [{:keys [in-pipe out-pipe]} server-repl-pipes]
    (doto out-pipe (.write "lightmod.repl/exit\n") (.flush))
    (.close in-pipe))
  (let [webview (.lookup inner-pane "#server_repl_webview")
        pipes (lrepl/create-pipes)
        start-ns (symbol (path->ns dir "server"))
        on-recv (fn [text]
                  (Platform/runLater
                    (fn []
                      (-> (.getEngine webview)
                          (.executeScript "window")
                          (.call "append" (into-array [text]))))))]
    (assoc project
      :server-repl-bridge
      (init-console! webview true
        #(on-recv (str start-ns "=> "))
        (fn [text]
          (doto (:out-pipe pipes)
            (.write text)
            (.flush))))
      :server-repl-pipes
      (lrepl/start-repl-thread! pipes start-ns on-recv))))

(defn append! [webview s]
  (when (seq s)
    (-> webview
        .getEngine
        (.executeScript "window")
        (.call "append" (into-array [s])))))

(defn redirect-stdio! [logs-atom]
  (let [stdout-pipes (lrepl/create-pipes)
        stderr-pipes (lrepl/create-pipes)]
    (intern 'clojure.core '*out* (:out stdout-pipes))
    (System/setErr (-> (:out stderr-pipes)
                       org.apache.commons.io.output.WriterOutputStream.
                       java.io.PrintStream.))
    (lrepl/pipe-into-console! (:in-pipe stdout-pipes)
      (fn [s]
        (binding [*out* (java.io.OutputStreamWriter. System/out)]
          (println s))
        (swap! logs-atom str s)))
    (lrepl/pipe-into-console! (:in-pipe stderr-pipes)
      (fn [s]
        (binding [*out* (java.io.OutputStreamWriter. System/out)]
          (println s))
        (swap! logs-atom str (str s \newline))))
    {:stdout stdout-pipes
     :stderr stderr-pipes}))

(defn init-server-logs! [inner-pane dir]
  (swap! runtime-state update-in [:projects dir]
    (fn [project]
      (let [logs (or (:server-logs-atom project)
                     (atom ""))
            pipes (redirect-stdio! logs)]
        (assoc project
          :server-logs-atom logs
          :server-logs-pipes pipes))))
  (swap! runtime-state update-in [:projects dir]
    (fn [{:keys [server-logs-atom] :as project}]
      (let [webview (.lookup inner-pane "#server_logs_webview")
            bridge (init-console! webview false
                     (fn []
                       (append! webview @server-logs-atom)
                       (add-watch server-logs-atom :append
                         (fn [_ _ old-log new-log]
                           (Platform/runLater
                             #(append! webview (subs new-log (count old-log)))))))
                     (fn []))]
        (assoc project
          :server-logs-bridge bridge))))
  (swap! runtime-state assoc-in [:editor-panes (.getCanonicalPath (io/file dir "*server-logs*"))]
    (.lookup inner-pane "#server_logs_webview")))

(defn init-reload-server! [dir]
  (let [reload-stop-fn (lr/start-reload-server! dir)
        reload-port (-> reload-stop-fn meta :local-port)]
    (spit (io/file dir ".out" "lightmod.edn") (pr-str {:reload-port reload-port}))
    (swap! runtime-state update-in [:projects dir] assoc
      :reload-stop-fn reload-stop-fn
      :clients #{})))

(defn stop-server! [dir]
  (let [{:keys [server reload-stop-fn reload-file-watcher
                server-logs-atom server-logs-pipes]}
        (get-in @runtime-state [:projects dir])]
    (when server (.stop server))
    (when reload-stop-fn (reload-stop-fn))
    (when reload-file-watcher (hawk/stop! reload-file-watcher))
    (when server-logs-atom (remove-watch server-logs-atom :append))
    (when-let [{:keys [stdout stderr]} server-logs-pipes]
      (doto stdout
        (-> :in-pipe .close)
        (-> :out .close))
      (doto stderr
        (-> :in-pipe .close)
        (-> :out .close)))))

(definterface AppBridge
  (onload [])
  (onevalcomplete [path results ns-name]))

(defn start-server! [project-pane dir]
  (when (and (compile-clj! project-pane dir)
             (compile-cljs! project-pane dir))
    (when-let [server (run-main! project-pane dir)]
      (let [port (-> server .getConnectors (aget 0) .getLocalPort)
            url (str "http://localhost:" port "/"
                  (-> dir io/file .getName)
                  "/index.html")
            out-dir (.getCanonicalPath (io/file dir ".out"))
            client-repl-started? (atom false)
            bridge (reify AppBridge
                     (onload [this]
                       (let [inner-pane (-> project-pane (.lookup "#project") .getItems (.get 1))]
                         (swap! runtime-state update-in [:projects dir]
                           init-client-repl! inner-pane dir)
                         (swap! runtime-state update-in [:projects dir]
                           init-server-repl! inner-pane dir)
                         (swap! runtime-state assoc-in [:editor-panes (.getCanonicalPath (io/file dir "*client-repl*"))]
                           (.lookup inner-pane "#client_repl_webview"))
                         (swap! runtime-state assoc-in [:editor-panes (.getCanonicalPath (io/file dir "*server-repl*"))]
                           (.lookup inner-pane "#server_repl_webview"))))
                     (onevalcomplete [this path results ns-name]
                       (if-not path
                         (let [inner-pane (-> project-pane (.lookup "#project") .getItems (.get 1))
                               result (-> results edn/read-string first)
                               result (cond
                                        (vector? result)
                                        (str "Error: " (first result))
                                        @client-repl-started?
                                        result
                                        :else
                                        (do
                                          (reset! client-repl-started? true)
                                          nil))
                               result (when (seq result)
                                        (str result "\n"))
                               result (str result ns-name "=> ")]
                           (-> inner-pane
                               (.lookup "#client_repl_webview")
                               .getEngine
                               (.executeScript "window")
                               (.call "append" (into-array [result]))))
                         (when-let [editor (get-in @runtime-state [:editor-panes path])]
                           (-> editor
                               (.lookup "#webview")
                               .getEngine
                               (.executeScript "window")
                               (.call "setInstaRepl" (into-array [results])))))))]
        (Platform/runLater
          (fn []
            (let [app (.lookup project-pane "#app")]
              (.setContextMenuEnabled app false)
              (.setOnStatusChanged (.getEngine app)
                (reify EventHandler
                  (handle [this event]
                    ; set the bridge
                    (-> (.getEngine app)
                        (.executeScript "window")
                        (.setMember "java" bridge)))))
              (-> app .getEngine (.load url)))))
        (swap! runtime-state update-in [:projects dir] merge
          {:url url
           :server server
           :app-bridge bridge
           :editor-file-watcher
           (or (get-in @runtime-state [:projects dir :editor-file-watcher])
               (e/create-file-watcher dir runtime-state))
           :reload-file-watcher
           (hawk/watch! [{:paths [dir]
                          :handler (fn [ctx {:keys [kind file]}]
                                     (when (and (= kind :modify)
                                                (some #(-> file .getName (.endsWith %)) [".clj" ".cljc"]))
                                       (compile-clj! project-pane dir (.getCanonicalPath file)))
                                     (cond
                                       (and (some #(-> file .getName (.endsWith %)) [".cljs" ".cljc"])
                                            (not (u/parent-path? out-dir (.getCanonicalPath file))))
                                       (compile-cljs! project-pane dir)
                                       (u/parent-path? out-dir (.getCanonicalPath file))
                                       (lr/reload-file! dir file))
                                     ctx)}])})))))

(defn stop-app! [project-pane dir]
  (stop-server! dir)
  (doto (.lookup project-pane "#app")
    (-> .getEngine (.loadContent "<html><body></body></html>"))))

(defn start-app! [project-pane dir]
  (stop-app! project-pane dir)
  (swap! runtime-state assoc-in [:projects dir :pane] project-pane)
  (init-reload-server! dir)
  (-> project-pane (.lookup "#project") .getItems (.get 1) (init-server-logs! dir))
  (let [app (.lookup project-pane "#app")]
    (.setOnStatusChanged (.getEngine app)
      (reify EventHandler
        (handle [this event]
          (-> (.getEngine app)
              (.executeScript "window")
              (.setMember "java"
                (reify AppBridge
                  (onload [this]
                    (.start (Thread. #(start-server! project-pane dir))))
                  (onevalcomplete [this path results ns-name])))))))
    (-> app .getEngine (.load (str "http://localhost:"
                                (:web-port @runtime-state)
                                "/loading.html")))))

